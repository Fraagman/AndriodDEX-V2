package com.example.androidhost.input

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import com.example.androidhost.service.AndroidDexIME
import com.example.androidhost.service.DisplayService
import java.lang.ref.WeakReference

/**
 * Dispatches input received from the PC directly into the Compose view tree hosted by
 * `DesktopPresentation` on the VirtualDisplay.
 *
 * The desktop being streamed is our own Compose hierarchy in our own process, so there
 * is nothing to inject *into the system* — we can hand `MotionEvent`s and `KeyEvent`s
 * straight to the root view. That needs no permission at all, which is why this replaces
 * the old privileged `IInputManager.injectInputEvent` path and its ADB-pairing setup.
 *
 * Everything is dispatched on the main thread. The QUIC input thread calls into here
 * freely; each entry point marshals onto [mainHandler] before touching the view.
 */
object LocalInputDispatcher {

    private const val TAG = "LocalInputDispatcher"

    /**
     * Coordinate space the receiver sends in. The Windows side already normalises
     * window-local pixels into this space before transmitting — see `VIRTUAL_WIDTH` /
     * `VIRTUAL_HEIGHT` in `rust-receiver/zc-input/src/lib.rs`. It is a property of the
     * wire protocol, not of our display, so it is tracked separately from
     * [DisplayService.CAPTURE_WIDTH].
     */
    private const val WIRE_WIDTH = 1920f
    private const val WIRE_HEIGHT = 1080f

    /** Button bits used by the receiver (`WindowEvent::MouseInput` in zc-core). */
    private const val WIRE_BUTTON_LEFT = 1
    private const val WIRE_BUTTON_RIGHT = 2
    private const val WIRE_BUTTON_MIDDLE = 4

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Weak so a dismissed Presentation can be collected even if [detach] is missed.
     * All access is confined to the main thread.
     */
    private var targetRef: WeakReference<View>? = null

    // ---- Pointer gesture state (main thread only) ----

    /** downTime of the in-flight gesture; every MOVE/UP must reuse it. */
    private var gestureDownTime = 0L
    private var lastWireButtons = 0
    private var lastX = 0f
    private var lastY = 0f

    // ---- Keyboard state (main thread only) ----

    /** Currently held modifiers, as an Android metaState bitmask. */
    private var metaState = 0

    /** Latched CapsLock / NumLock bits, toggled on each press. */
    private var lockState = 0

    /** downTime per Android keycode, so KeyEvent UP pairs with its DOWN. */
    private val keyDownTimes = HashMap<Int, Long>()

    /**
     * Points the dispatcher at the ComposeView inside `DesktopPresentation`.
     * Safe to call repeatedly; the most recent view wins.
     */
    fun attach(view: View) {
        mainHandler.post {
            targetRef = WeakReference(view)
            resetState()
            Log.d(TAG, "Attached to ${view.javaClass.simpleName}")
        }
    }

    /** Releases the target view. Events received while detached are dropped. */
    fun detach() {
        mainHandler.post {
            cancelActiveGesture()
            targetRef = null
            resetState()
            Log.d(TAG, "Detached")
        }
    }

    // ---------------------------------------------------------------------
    // Pointer
    // ---------------------------------------------------------------------

    /**
     * Handles one mouse sample from the PC.
     *
     * The receiver reports level-triggered state (absolute position plus the current
     * button mask), so press/release edges are derived here by diffing against the
     * previous sample.
     *
     * @param wireX  X in the [WIRE_WIDTH] coordinate space
     * @param wireY  Y in the [WIRE_HEIGHT] coordinate space
     * @param buttons bitmask of [WIRE_BUTTON_LEFT] / [WIRE_BUTTON_RIGHT] / [WIRE_BUTTON_MIDDLE]
     */
    fun onMouse(wireX: Int, wireY: Int, buttons: Int) {
        mainHandler.post { handleMouse(wireX, wireY, buttons) }
    }

    private fun handleMouse(wireX: Int, wireY: Int, buttons: Int) {
        val view = targetRef?.get() ?: return

        val x = scaleX(wireX)
        val y = scaleY(wireY)
        val previous = lastWireButtons
        val changed = previous xor buttons
        val androidButtons = toAndroidButtonState(buttons)
        val moved = x != lastX || y != lastY

        val wasDown = previous != 0
        val isDown = buttons != 0

        // Android's order for a non-primary click is
        //   ACTION_DOWN -> ACTION_BUTTON_PRESS -> ACTION_BUTTON_RELEASE -> ACTION_UP,
        // so releases are emitted before the pointer action and presses after it.
        if (changed and WIRE_BUTTON_RIGHT != 0 && buttons and WIRE_BUTTON_RIGHT == 0) {
            dispatchButtonAction(view, x, y, false, androidButtons, MotionEvent.BUTTON_SECONDARY)
        }
        if (changed and WIRE_BUTTON_MIDDLE != 0 && buttons and WIRE_BUTTON_MIDDLE == 0) {
            dispatchButtonAction(view, x, y, false, androidButtons, MotionEvent.BUTTON_TERTIARY)
        }

        when {
            !wasDown && isDown -> {
                gestureDownTime = SystemClock.uptimeMillis()
                dispatchPointer(view, MotionEvent.ACTION_DOWN, x, y, androidButtons)
            }
            wasDown && !isDown -> {
                dispatchPointer(view, MotionEvent.ACTION_UP, x, y, 0)
                gestureDownTime = 0L
            }
            isDown -> {
                // Emit a MOVE for position changes and for button changes mid-gesture,
                // so Compose sees the updated buttonState without a new DOWN.
                if (moved || changed != 0) {
                    dispatchPointer(view, MotionEvent.ACTION_MOVE, x, y, androidButtons)
                }
            }
            moved -> {
                // No button held: this is hover. Must go through the generic-motion
                // path with SOURCE_MOUSE for hover and cursor states to work.
                dispatchHover(view, x, y)
            }
        }

        if (changed and WIRE_BUTTON_RIGHT != 0 && buttons and WIRE_BUTTON_RIGHT != 0) {
            dispatchButtonAction(view, x, y, true, androidButtons, MotionEvent.BUTTON_SECONDARY)
        }
        if (changed and WIRE_BUTTON_MIDDLE != 0 && buttons and WIRE_BUTTON_MIDDLE != 0) {
            dispatchButtonAction(view, x, y, true, androidButtons, MotionEvent.BUTTON_TERTIARY)
        }

        lastWireButtons = buttons
        lastX = x
        lastY = y
    }

    /**
     * Dispatches a scroll wheel event as `ACTION_SCROLL` carrying `AXIS_VSCROLL` and
     * `AXIS_HSCROLL`, which is what Compose's scrollable modifiers consume.
     *
     * @param vScroll vertical detents; positive scrolls content up (away from the user)
     * @param hScroll horizontal detents; positive scrolls content right
     */
    fun onScroll(wireX: Int, wireY: Int, vScroll: Float, hScroll: Float) {
        mainHandler.post {
            val view = targetRef?.get() ?: return@post
            val x = scaleX(wireX)
            val y = scaleY(wireY)
            val now = SystemClock.uptimeMillis()

            val coords = MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
                setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll)
                setAxisValue(MotionEvent.AXIS_HSCROLL, hScroll)
            }
            val event = MotionEvent.obtain(
                now, now, MotionEvent.ACTION_SCROLL,
                1, arrayOf(mouseProperties()), arrayOf(coords),
                metaState or lockState, toAndroidButtonState(lastWireButtons),
                1.0f, 1.0f,
                0, 0, InputDevice.SOURCE_MOUSE, 0
            )
            try {
                view.dispatchGenericMotionEvent(event)
            } finally {
                event.recycle()
            }
            lastX = x
            lastY = y
        }
    }

    private fun dispatchPointer(view: View, action: Int, x: Float, y: Float, buttonState: Int) {
        val now = SystemClock.uptimeMillis()
        // ACTION_DOWN establishes downTime; MOVE and UP must reuse it or Compose treats
        // them as unrelated events and the gesture never registers as one interaction.
        val downTime = if (gestureDownTime != 0L) gestureDownTime else now

        val coords = MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = if (action == MotionEvent.ACTION_UP) 0f else 1f
            size = 1f
        }
        val event = MotionEvent.obtain(
            downTime, now, action,
            1, arrayOf(mouseProperties()), arrayOf(coords),
            metaState or lockState, buttonState,
            1.0f, 1.0f,
            0, 0, InputDevice.SOURCE_MOUSE, 0
        )
        try {
            view.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    private fun dispatchHover(view: View, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            size = 1f
        }
        val event = MotionEvent.obtain(
            now, now, MotionEvent.ACTION_HOVER_MOVE,
            1, arrayOf(mouseProperties()), arrayOf(coords),
            metaState or lockState, 0,
            1.0f, 1.0f,
            0, 0, InputDevice.SOURCE_MOUSE, 0
        )
        try {
            view.dispatchGenericMotionEvent(event)
        } finally {
            event.recycle()
        }
    }

    /**
     * Emits `ACTION_BUTTON_PRESS` / `ACTION_BUTTON_RELEASE` for the secondary and
     * tertiary buttons.
     *
     * Note: `MotionEvent.obtain` exposes no way to set `actionButton`, so
     * [actionButton] is carried in `buttonState` instead — which is the field Compose
     * reads to populate `PointerButtons.isSecondaryPressed` / `isTertiaryPressed`.
     */
    private fun dispatchButtonAction(
        view: View,
        x: Float,
        y: Float,
        pressed: Boolean,
        buttonState: Int,
        actionButton: Int
    ) {
        val now = SystemClock.uptimeMillis()
        val downTime = if (gestureDownTime != 0L) gestureDownTime else now
        val action = if (pressed) MotionEvent.ACTION_BUTTON_PRESS else MotionEvent.ACTION_BUTTON_RELEASE

        val coords = MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            size = 1f
        }
        val event = MotionEvent.obtain(
            downTime, now, action,
            1, arrayOf(mouseProperties()), arrayOf(coords),
            metaState or lockState,
            // Guarantee the bit for the button this event is about matches the action,
            // independent of how the caller computed buttonState.
            if (pressed) buttonState or actionButton else buttonState and actionButton.inv(),
            1.0f, 1.0f,
            0, 0, InputDevice.SOURCE_MOUSE, 0
        )
        try {
            view.dispatchGenericMotionEvent(event)
        } finally {
            event.recycle()
        }
    }

    private fun mouseProperties() = MotionEvent.PointerProperties().apply {
        id = 0
        toolType = MotionEvent.TOOL_TYPE_MOUSE
    }

    private fun toAndroidButtonState(wireButtons: Int): Int {
        var state = 0
        if (wireButtons and WIRE_BUTTON_LEFT != 0) state = state or MotionEvent.BUTTON_PRIMARY
        if (wireButtons and WIRE_BUTTON_RIGHT != 0) state = state or MotionEvent.BUTTON_SECONDARY
        if (wireButtons and WIRE_BUTTON_MIDDLE != 0) state = state or MotionEvent.BUTTON_TERTIARY
        return state
    }

    private fun scaleX(wireX: Int): Float =
        (wireX.coerceIn(0, WIRE_WIDTH.toInt() - 1) / WIRE_WIDTH) * DisplayService.CAPTURE_WIDTH

    private fun scaleY(wireY: Int): Float =
        (wireY.coerceIn(0, WIRE_HEIGHT.toInt() - 1) / WIRE_HEIGHT) * DisplayService.CAPTURE_HEIGHT

    /** Sends ACTION_CANCEL if a gesture is mid-flight, so Compose does not hang on it. */
    private fun cancelActiveGesture() {
        val view = targetRef?.get() ?: return
        if (gestureDownTime == 0L) return
        dispatchPointer(view, MotionEvent.ACTION_CANCEL, lastX, lastY, 0)
        gestureDownTime = 0L
    }

    private fun resetState() {
        gestureDownTime = 0L
        lastWireButtons = 0
        metaState = 0
        lockState = 0
        keyDownTimes.clear()
    }

    // ---------------------------------------------------------------------
    // Keyboard
    // ---------------------------------------------------------------------

    /**
     * Handles one key event from the PC.
     *
     * [winitKeyCode] is the receiver's raw `winit::keyboard::KeyCode` ordinal; see
     * [WinitKeyMap]. The receiver hardcodes `modifiers: 0` on the wire, so shift/ctrl/alt
     * state is reconstructed here from the modifier key presses themselves.
     *
     * When our IME is the active keyboard and has a live `InputConnection`, the event is
     * handed to it so text lands in the focused editor. Otherwise it goes straight into
     * the view tree.
     */
    fun onKey(winitKeyCode: Int, pressed: Boolean) {
        mainHandler.post { handleKey(winitKeyCode, pressed) }
    }

    private fun handleKey(winitKeyCode: Int, pressed: Boolean) {
        updateMetaState(winitKeyCode, pressed)

        val keyCode = WinitKeyMap.toAndroidKeyCode(winitKeyCode)
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            Log.d(TAG, "No Android keycode for winit code $winitKeyCode")
            return
        }

        val effectiveMeta = metaState or lockState
        val now = SystemClock.uptimeMillis()
        val downTime: Long
        if (pressed) {
            downTime = now
            keyDownTimes[keyCode] = now
        } else {
            downTime = keyDownTimes.remove(keyCode) ?: now
        }

        if (AndroidDexIME.dispatchFromHost(keyCode, pressed, effectiveMeta, downTime, now)) return

        val view = targetRef?.get() ?: return
        val event = KeyEvent(
            downTime, now,
            if (pressed) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP,
            keyCode, 0, effectiveMeta,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
            InputDevice.SOURCE_KEYBOARD
        )
        view.dispatchKeyEvent(event)
    }

    /**
     * Commits a literal string, bypassing keycode translation. Used for characters the
     * PC resolves itself (dead keys, IME composition, clipboard paste).
     */
    fun onText(text: CharSequence) {
        if (text.isEmpty()) return
        mainHandler.post { AndroidDexIME.commitTextFromHost(text) }
    }

    private fun updateMetaState(winitKeyCode: Int, pressed: Boolean) {
        // CapsLock / NumLock latch on press and persist until pressed again.
        if (!pressed) {
            when (winitKeyCode) {
                WinitKeyMap.CAPS_LOCK, WinitKeyMap.NUM_LOCK -> return
            }
        } else {
            when (winitKeyCode) {
                WinitKeyMap.CAPS_LOCK -> {
                    lockState = lockState xor KeyEvent.META_CAPS_LOCK_ON
                    return
                }
                WinitKeyMap.NUM_LOCK -> {
                    lockState = lockState xor KeyEvent.META_NUM_LOCK_ON
                    return
                }
            }
        }

        val bits = when (winitKeyCode) {
            WinitKeyMap.SHIFT_LEFT -> KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
            WinitKeyMap.SHIFT_RIGHT -> KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_RIGHT_ON
            WinitKeyMap.CONTROL_LEFT -> KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
            WinitKeyMap.CONTROL_RIGHT -> KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_RIGHT_ON
            WinitKeyMap.ALT_LEFT -> KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
            WinitKeyMap.ALT_RIGHT -> KeyEvent.META_ALT_ON or KeyEvent.META_ALT_RIGHT_ON
            WinitKeyMap.SUPER_LEFT, WinitKeyMap.META -> KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
            WinitKeyMap.SUPER_RIGHT -> KeyEvent.META_META_ON or KeyEvent.META_META_RIGHT_ON
            else -> return
        }

        metaState = if (pressed) metaState or bits else metaState and bits.inv()
    }
}
