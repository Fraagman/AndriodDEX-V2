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
import com.example.androidhost.service.DisplayService
import java.lang.ref.WeakReference

/**
 * Callback for WebView surfaces (BrowserApp, CodeServerWindow) that want text
 * events routed through the DOM instead of the Android IME.
 *
 * The platform IME cannot serve the untrusted VirtualDisplay, so printable text
 * resolved by the PC (TextEvent — layout-independent, dead keys, clipboard
 * paste) is injected into the page's focused editable element via
 * `evaluateJavascript`. Everything else arrives as real [KeyEvent]s and is
 * dispatched straight into the WebView, where Chromium performs the default
 * action (caret movement, backward delete, form submit, page scroll) and raises
 * trusted keydown/keyup pairs to page JavaScript.
 */
interface WebViewInputBridge {
    /** Insert `text` into the currently focused editable element. */
    fun insertText(text: CharSequence)
}

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

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

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

    /**
     * Latched CapsLock / NumLock bits, toggled on each press. Modifier state is
     * NOT tracked here: the receiver reports the live modifier bitmask with every
     * event, which is authoritative even when a release is lost on the wire.
     */
    private var lockState = 0

    /** downTime per winit keycode, so KeyEvent UP pairs with its DOWN. Keyed by
     *  the wire code, not the Android keycode: several wire codes map to one
     *  Android keycode (Backslash/IntlBackslash, NumpadMultiply/NumpadStar) and
     *  would otherwise share one downTime. */
    private val keyDownTimes = HashMap<Int, Long>()

    /** Key auto-repeat (the receiver suppresses OS repeats for keyboard-routed
     *  keys; they are regenerated here with proper Android `repeatCount`). */
    private val repeatDelayMs = 400L
    private val repeatPeriodMs = 50L
    private var repeatWinitKey = -1
    private var repeatRunnable: Runnable? = null
    private var repeatCounter = 0

    /**
     * When set, WebView-focused surfaces receive text and control keys through the DOM
     * (via `evaluateJavascript`) instead of via `View.dispatchKeyEvent` or the platform
     * IME. See [WebViewInputBridge] for why the
     * IME route cannot work on our untrusted VirtualDisplay.
     */
    sealed class InputTarget {
        class WebViewBridge(
            val bridge: WebViewInputBridge,
            val view: android.webkit.WebView,
            /** False when this WebView's window is not on top — it must not receive
             *  events a window drawn over it should get. */
            val eligible: (() -> Boolean)? = null
        ) : InputTarget() {
            /** Attached, laid out, and (when known) the top window of the shell. */
            fun isLive(): Boolean =
                view.isAttachedToWindow && view.width > 0 && (eligible?.invoke() != false)
        }
        class ComposeTarget(val onText: (String) -> Unit, val onKey: (Int, Boolean) -> Boolean) : InputTarget()
    }

    private var currentTargetOwner: Any? = null
    private var currentTarget: InputTarget? = null

    private fun setTarget(owner: Any, target: InputTarget?) {
        mainHandler.post {
            if (target != null) {
                currentTargetOwner = owner
                currentTarget = target
            } else if (currentTargetOwner === owner) {
                currentTargetOwner = null
                currentTarget = null
            }
        }
    }

    /**
     * Registers (or clears) a [WebViewInputBridge]. Call with the bridge on WebView focus
     * gain and with `null` on focus loss. Safe to call from any thread; the swap runs on
     * the main thread. Idempotent.
     *
     * Also arms the pointer slot below, so mouse events over the WebView's bounds are
     * dispatched straight into it (see [resolveWebViewTarget]). [eligible] should report
     * whether the owning shell window is currently on top; while it is covered, events
     * pass through to the shell so the covering window receives them.
     */
    fun registerWebViewBridge(
        owner: Any,
        bridge: WebViewInputBridge?,
        view: android.webkit.WebView,
        eligible: (() -> Boolean)? = null
    ) {
        setTarget(owner, bridge?.let { InputTarget.WebViewBridge(it, view, eligible) })
        mainHandler.post {
            webviewPointerTarget = bridge?.let { InputTarget.WebViewBridge(it, view, eligible) }
        }
    }

    /**
     * The WebView that PC pointer events are routed into directly. Pointer events
     * dispatched into the root ComposeView must cross Compose's gesture system to
     * reach an AndroidView, and ancestor gesture handlers (window raise-on-press,
     * scrollable surfaces) consume or delay them, which left web pages dead to the
     * mouse. When the cursor is inside the registered WebView's bounds (or a drag
     * that started there is in flight), events bypass Compose entirely.
     */
    private var webviewPointerTarget: InputTarget.WebViewBridge? = null
    private var webviewGesture = false

    /** The WebView under the cursor, if the pointer slot is armed, the view is still
     *  attached to a window, its window is on top, and [x, y] is inside its bounds.
     *  A destroyed, detached or covered WebView must never intercept events — its
     *  stale bounds would swallow presses meant for the shell or a covering window. */
    private fun resolveWebViewTarget(x: Float, y: Float): android.webkit.WebView? {
        val target = webviewPointerTarget ?: return null
        val wv = target.view
        if (!target.isLive()) return null
        val loc = IntArray(2)
        wv.getLocationInWindow(loc)
        val inside = x >= loc[0] && x < loc[0] + wv.width && y >= loc[1] && y < loc[1] + wv.height
        return if (inside) wv else null
    }

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
     * @param modifiers live wire modifier bitmask from the receiver; folded into
     *        the dispatched MotionEvents so Ctrl-click / Shift-click reach apps
     */
    fun onMouse(wireX: Int, wireY: Int, buttons: Int, modifiers: Int = 0) {
        mainHandler.post { handleMouse(wireX, wireY, buttons, modifiers) }
    }

    private fun handleMouse(wireX: Int, wireY: Int, buttons: Int, wireModifiers: Int) {
        val view = targetRef?.get() ?: return

        val x = scaleX(wireX)
        val y = scaleY(wireY)
        val previous = lastWireButtons
        val changed = previous xor buttons
        val androidButtons = toAndroidButtonState(buttons)
        val moved = x != lastX || y != lastY
        val meta = wireModifiersToAndroidMeta(wireModifiers) or lockState

        val wasDown = previous != 0
        val isDown = buttons != 0

        // Android's order for a non-primary click is
        //   ACTION_DOWN -> ACTION_BUTTON_PRESS -> ACTION_BUTTON_RELEASE -> ACTION_UP,
        // so releases are emitted before the pointer action and presses after it.
        if (changed and WIRE_BUTTON_RIGHT != 0 && buttons and WIRE_BUTTON_RIGHT == 0) {
            dispatchButtonAction(view, x, y, false, androidButtons, MotionEvent.BUTTON_SECONDARY, meta)
        }
        if (changed and WIRE_BUTTON_MIDDLE != 0 && buttons and WIRE_BUTTON_MIDDLE == 0) {
            dispatchButtonAction(view, x, y, false, androidButtons, MotionEvent.BUTTON_TERTIARY, meta)
        }

        when {
            !wasDown && isDown -> {
                gestureDownTime = SystemClock.uptimeMillis()
                dispatchPointer(view, MotionEvent.ACTION_DOWN, x, y, androidButtons, meta)
            }
            wasDown && !isDown -> {
                dispatchPointer(view, MotionEvent.ACTION_UP, x, y, 0, meta)
                gestureDownTime = 0L
            }
            isDown -> {
                // Emit a MOVE for position changes and for button changes mid-gesture,
                // so Compose sees the updated buttonState without a new DOWN.
                if (moved || changed != 0) {
                    dispatchPointer(view, MotionEvent.ACTION_MOVE, x, y, androidButtons, meta)
                }
            }
            moved -> {
                // No button held: this is hover. Must go through the generic-motion
                // path with SOURCE_MOUSE for hover and cursor states to work.
                dispatchHover(view, x, y, meta)
            }
        }

        if (changed and WIRE_BUTTON_RIGHT != 0 && buttons and WIRE_BUTTON_RIGHT != 0) {
            dispatchButtonAction(view, x, y, true, androidButtons, MotionEvent.BUTTON_SECONDARY, meta)
        }
        if (changed and WIRE_BUTTON_MIDDLE != 0 && buttons and WIRE_BUTTON_MIDDLE != 0) {
            dispatchButtonAction(view, x, y, true, androidButtons, MotionEvent.BUTTON_TERTIARY, meta)
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
    fun onScroll(wireX: Int, wireY: Int, vScroll: Float, hScroll: Float, modifiers: Int = 0) {
        mainHandler.post {
            val view = targetRef?.get() ?: return@post
            val x = scaleX(wireX)
            val y = scaleY(wireY)
            val now = SystemClock.uptimeMillis()

            var targetView: View = view
            var eventX = x
            var eventY = y
            resolveWebViewTarget(x, y)?.let { wv ->
                val loc = IntArray(2)
                wv.getLocationInWindow(loc)
                targetView = wv
                eventX = x - loc[0]
                eventY = y - loc[1]
            }

            val coords = MotionEvent.PointerCoords().apply {
                this.x = eventX
                this.y = eventY
                setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll)
                setAxisValue(MotionEvent.AXIS_HSCROLL, hScroll)
            }
            val event = MotionEvent.obtain(
                now, now, MotionEvent.ACTION_SCROLL,
                1, arrayOf(mouseProperties()), arrayOf(coords),
                wireModifiersToAndroidMeta(modifiers) or lockState, toAndroidButtonState(lastWireButtons),
                1.0f, 1.0f,
                0, 0, InputDevice.SOURCE_MOUSE, 0
            )
            try {
                targetView.dispatchGenericMotionEvent(event)
            } finally {
                event.recycle()
            }
            lastX = x
            lastY = y
        }
    }

    private fun dispatchPointer(view: View, action: Int, x: Float, y: Float, buttonState: Int, meta: Int) {
        val now = SystemClock.uptimeMillis()
        // ACTION_DOWN establishes downTime; MOVE and UP must reuse it or Compose treats
        // them as unrelated events and the gesture never registers as one interaction.
        val downTime = if (gestureDownTime != 0L) gestureDownTime else now

        // Direct WebView routing: a drag that started inside the page keeps going to
        // the page even when the cursor leaves its bounds.
        var targetView: View = view
        var eventX = x
        var eventY = y
        val wv = resolveWebViewTarget(x, y)
        if (wv != null) {
            webviewGesture = true
        }
        val active = if (webviewGesture) (wv ?: webviewPointerTarget?.takeIf { it.isLive() }?.view) else null
        if (active != null) {
            val loc = IntArray(2)
            active.getLocationInWindow(loc)
            targetView = active
            eventX = x - loc[0]
            eventY = y - loc[1]
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            webviewGesture = false
        }

        val coords = MotionEvent.PointerCoords().apply {
            this.x = eventX
            this.y = eventY
            pressure = if (action == MotionEvent.ACTION_UP) 0f else 1f
            size = 1f
        }
        val event = MotionEvent.obtain(
            downTime, now, action,
            1, arrayOf(mouseProperties()), arrayOf(coords),
            meta, buttonState,
            1.0f, 1.0f,
            0, 0, InputDevice.SOURCE_MOUSE, 0
        )
        try {
            targetView.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    private fun dispatchHover(view: View, x: Float, y: Float, meta: Int) {
        val now = SystemClock.uptimeMillis()
        val wv = resolveWebViewTarget(x, y)
        var targetView: View = view
        var eventX = x
        var eventY = y
        if (wv != null) {
            val loc = IntArray(2)
            wv.getLocationInWindow(loc)
            targetView = wv
            eventX = x - loc[0]
            eventY = y - loc[1]
        }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = eventX
            this.y = eventY
            size = 1f
        }
        val event = MotionEvent.obtain(
            now, now, MotionEvent.ACTION_HOVER_MOVE,
            1, arrayOf(mouseProperties()), arrayOf(coords),
            meta, 0,
            1.0f, 1.0f,
            0, 0, InputDevice.SOURCE_MOUSE, 0
        )
        try {
            targetView.dispatchGenericMotionEvent(event)
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
        actionButton: Int,
        meta: Int
    ) {
        val now = SystemClock.uptimeMillis()
        val downTime = if (gestureDownTime != 0L) gestureDownTime else now
        val action = if (pressed) MotionEvent.ACTION_BUTTON_PRESS else MotionEvent.ACTION_BUTTON_RELEASE

        var targetView: View = view
        var eventX = x
        var eventY = y
        resolveWebViewTarget(x, y)?.let { wv ->
            val loc = IntArray(2)
            wv.getLocationInWindow(loc)
            targetView = wv
            eventX = x - loc[0]
            eventY = y - loc[1]
        }

        val coords = MotionEvent.PointerCoords().apply {
            this.x = eventX
            this.y = eventY
            size = 1f
        }
        val event = MotionEvent.obtain(
            downTime, now, action,
            1, arrayOf(mouseProperties()), arrayOf(coords),
            meta,
            // Guarantee the bit for the button this event is about matches the action,
            // independent of how the caller computed buttonState.
            if (pressed) buttonState or actionButton else buttonState and actionButton.inv(),
            1.0f, 1.0f,
            0, 0, InputDevice.SOURCE_MOUSE, 0
        )
        try {
            targetView.dispatchGenericMotionEvent(event)
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

    internal fun scaleCoordinate(wireVal: Int, wireMax: Float, targetDimension: Int): Float {
        return (wireVal.coerceIn(0, wireMax.toInt() - 1) / wireMax) * targetDimension
    }

    internal fun scaleX(wireX: Int, targetWidth: Int = DisplayService.CAPTURE_WIDTH): Float =
        scaleCoordinate(wireX, WIRE_WIDTH, targetWidth)

    internal fun scaleY(wireY: Int, targetHeight: Int = DisplayService.CAPTURE_HEIGHT): Float =
        scaleCoordinate(wireY, WIRE_HEIGHT, targetHeight)

    /** Sends ACTION_CANCEL if a gesture is mid-flight, so Compose does not hang on it. */
    private fun cancelActiveGesture() {
        val view = targetRef?.get() ?: return
        if (gestureDownTime == 0L) return
        dispatchPointer(view, MotionEvent.ACTION_CANCEL, lastX, lastY, 0, lockState)
        gestureDownTime = 0L
    }

    private fun resetState() {
        gestureDownTime = 0L
        lastWireButtons = 0
        lockState = 0
        keyDownTimes.clear()
        webviewGesture = false
        lastX = 0f
        lastY = 0f
        cancelRepeat()
        if (targetRef == null || targetRef?.get() == null) {
            webviewPointerTarget = null
        }
    }

    // ---------------------------------------------------------------------
    // Keyboard
    // ---------------------------------------------------------------------

    /**
     * Handles one key event from the PC.
     *
     * [winitKeyCode] is the receiver's raw `winit::keyboard::KeyCode` ordinal; see
     * [WinitKeyMap]. Modifier state is NOT reconstructed from key history: the
     * receiver reports the live bitmask with every event and that is authoritative
     * (a lost release can no longer stick Shift on). Only CapsLock/NumLock latch
     * locally, because the wire carries no lock state.
     *
     * @param wireModifiers modifier bitmask from the receiver.
     * @param repeatCount 0 for the initial press; >0 for synthesised auto-repeat.
     */
    fun onKey(winitKeyCode: Int, pressed: Boolean, wireModifiers: Int = 0) {
        mainHandler.post { handleKey(winitKeyCode, pressed, wireModifiers, 0) }
    }

    private fun handleKey(winitKeyCode: Int, pressed: Boolean, wireModifiers: Int, repeatCount: Int) {
        if (repeatCount == 0) {
            if (pressed) {
                if (winitKeyCode == WinitKeyMap.CAPS_LOCK) {
                    lockState = lockState xor KeyEvent.META_CAPS_LOCK_ON
                } else if (winitKeyCode == WinitKeyMap.NUM_LOCK) {
                    lockState = lockState xor KeyEvent.META_NUM_LOCK_ON
                }
            } else {
                cancelRepeat(winitKeyCode)
            }
        }

        val keyCode = WinitKeyMap.toAndroidKeyCode(winitKeyCode)
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN) return

        val effectiveMeta = wireModifiersToAndroidMeta(wireModifiers) or lockState
        val now = SystemClock.uptimeMillis()
        val downTime: Long
        if (pressed && repeatCount == 0) {
            downTime = now
            keyDownTimes[winitKeyCode] = now
            if (!isModifierOrLockKey(winitKeyCode)) scheduleRepeat(winitKeyCode, wireModifiers)
        } else if (pressed) {
            downTime = keyDownTimes[winitKeyCode] ?: now
        } else {
            downTime = keyDownTimes.remove(winitKeyCode) ?: now
        }

        val event = KeyEvent(
            downTime, now,
            if (pressed) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP,
            keyCode, repeatCount, effectiveMeta,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
            InputDevice.SOURCE_KEYBOARD
        )

        val target = currentTarget
        if (target is InputTarget.WebViewBridge && target.isLive()) {
            // Real Android KeyEvents straight into the WebView: Chromium performs
            // the default action (caret movement, backward delete, form submit,
            // page scroll, find-bar and shortcut handling) and raises trusted
            // keydown/keyup pairs to page JS. DOM injection stays reserved for
            // TextEvent strings, which a bare KeyEvent cannot carry.
            target.view.dispatchKeyEvent(event)
            return
        }

        var consumed = false
        if (target is InputTarget.ComposeTarget) {
            val controlName = controlKeyName(keyCode)
            if (controlName != null) {
                consumed = target.onKey(keyCode, pressed)
            }
            if (!consumed && pressed && effectiveMeta and (KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON or KeyEvent.META_META_ON) == 0) {
                val unicode = event.unicodeChar
                if (unicode != 0) {
                    target.onText(String(Character.toChars(unicode)))
                    consumed = true
                }
            }
        }

        if (!consumed) {
            // No bridge target, or the target declined the key: hand it to the
            // view tree so Compose's own key handling (caret movement in focused
            // text fields, scrollables, shortcuts) still sees it.
            val view = targetRef?.get() ?: return
            view.dispatchKeyEvent(event)
        }
    }

    /** True for keys that must never auto-repeat (modifiers, CapsLock, NumLock). */
    private fun isModifierOrLockKey(winitKeyCode: Int): Boolean = when (winitKeyCode) {
        WinitKeyMap.CAPS_LOCK, WinitKeyMap.NUM_LOCK -> true
        else -> WinitKeyMap.isModifier(winitKeyCode)
    }

    /**
     * Starts the auto-repeat loop for a fresh key press. Repeats are synthesised
     * here because the receiver suppresses OS repeats for keyboard-routed keys —
     * one repeat authority, deterministic over the wire. A new press takes over
     * the slot (Windows behaviour: only the newest key repeats).
     */
    private fun scheduleRepeat(winitKeyCode: Int, wireModifiers: Int) {
        cancelRepeat()
        repeatWinitKey = winitKeyCode
        repeatCounter = 0
        val runnable = object : Runnable {
            override fun run() {
                if (repeatWinitKey != winitKeyCode) return
                repeatCounter += 1
                handleKey(winitKeyCode, true, wireModifiers, repeatCounter)
                mainHandler.postDelayed(this, repeatPeriodMs)
            }
        }
        repeatRunnable = runnable
        mainHandler.postDelayed(runnable, repeatDelayMs)
    }

    private fun cancelRepeat(winitKeyCode: Int) {
        if (repeatWinitKey == winitKeyCode) cancelRepeat()
    }

    private fun cancelRepeat() {
        repeatRunnable?.let { mainHandler.removeCallbacks(it) }
        repeatRunnable = null
        repeatWinitKey = -1
        repeatCounter = 0
    }

    /**
     * Maps Android keycodes for the control keys to the set the shell's text
     * fields are asked to handle through [InputTarget.ComposeTarget.onKey] before
     * anything falls through to the view tree. Returns null for other keys, which
     * are then either injected as text (printable) or dispatched raw (modifier
     * combos, function keys).
     */
    private fun controlKeyName(keyCode: Int): String? = when (keyCode) {
        KeyEvent.KEYCODE_DEL -> "Backspace"
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "Enter"
        KeyEvent.KEYCODE_TAB -> "Tab"
        KeyEvent.KEYCODE_ESCAPE -> "Escape"
        KeyEvent.KEYCODE_DPAD_LEFT -> "ArrowLeft"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "ArrowRight"
        KeyEvent.KEYCODE_DPAD_UP -> "ArrowUp"
        KeyEvent.KEYCODE_DPAD_DOWN -> "ArrowDown"
        else -> null
    }

    fun registerComposeTarget(owner: Any, onText: ((String) -> Unit)?, onKey: ((Int, Boolean) -> Boolean)?) {
        setTarget(owner, if (onText != null && onKey != null) InputTarget.ComposeTarget(onText, onKey) else null)
    }

    /**
     * Commits a literal string, bypassing keycode translation. Used for characters the
     * PC resolves itself (dead keys, IME composition, clipboard paste).
     *
     * Routes through the WebView bridge when one is attached, or the Compose injection
     * channel, or the Terminal. A drop is logged rather than silently swallowed so a
     * missing `registerComposeTarget` call is visible during support/debugging.
     */
    fun onText(text: CharSequence) {
        if (text.isEmpty()) return
        mainHandler.post {
            val target = currentTarget
            if (target is InputTarget.WebViewBridge && target.isLive()) {
                target.bridge.insertText(text)
                return@post
            } else if (target is InputTarget.ComposeTarget) {
                target.onText(text.toString())
            } else {
                Log.w(TAG, "Dropping text (${text.length} chars): no input target registered")
            }
        }
    }

    /**
     * Converts the wire modifier bitmask (matching `input.proto` Modifier enum values)
     * into Android [KeyEvent] `META_*` flags.
     */
    internal fun wireModifiersToAndroidMeta(wireModifiers: Int): Int {
        var meta = 0
        if (wireModifiers and 1 != 0) meta = meta or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        if (wireModifiers and 2 != 0) meta = meta or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (wireModifiers and 4 != 0) meta = meta or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        if (wireModifiers and 8 != 0) meta = meta or KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
        return meta
    }
}

/**
 * Quotes a string for safe embedding inside a JavaScript string literal in an
 * `evaluateJavascript` payload.
 *
 * The output is a **fully-quoted** JSON string, produced by escaping every character that
 * can alter script parsing:
 *
 *  - `"` and `\` are escaped so the closing quote cannot be reached early and the escape
 *    character cannot introduce a spurious escape sequence,
 *  - `\n`, `\r`, `\t`, `\b`, `\f` are escaped so raw control characters cannot terminate
 *    the string literal or the surrounding line comment,
 *  - all other control characters (U+0000..U+001F) are `\uXXXX`-escaped for the same
 *    reason,
 *  - U+2028 (LINE SEPARATOR) and U+2029 (PARAGRAPH SEPARATOR) are `\uXXXX`-escaped
 *    because they terminate JS string literals even though JSON allows them raw, and
 *  - a literal `</` is written as `<\/` so the payload cannot terminate an enclosing
 *    `<script>` element if the JavaScript is ever serialised into HTML.
 *
 * `org.json.JSONObject.quote` covers the JSON rules but is stubbed in Android unit tests
 * (`RuntimeException("Stub!")`), so this pure-Kotlin implementation is used instead. See
 * task 31a — "use `org.json.JSONObject.quote()` or an equivalent, not manual
 * quote-doubling. A quote or backslash typed by the user must not be able to alter the
 * script."
 */
internal fun escapeForJsStringLiteral(text: CharSequence): String {
    val out = StringBuilder(text.length + 2)
    out.append('"')
    var i = 0
    while (i < text.length) {
        val c = text[i]
        val code = c.code
        when {
            c == '"' -> out.append("\\\"")
            c == '\\' -> out.append("\\\\")
            c == '\n' -> out.append("\\n")
            c == '\r' -> out.append("\\r")
            c == '\t' -> out.append("\\t")
            code == 0x08 -> out.append("\\b")
            code == 0x0C -> out.append("\\f")
            c == '<' && i + 1 < text.length && text[i + 1] == '/' -> {
                // Break "</" so the payload cannot close a surrounding <script>.
                out.append("<\\/")
                i++
            }
            code == 0x2028 -> out.append("\\u2028")
            code == 0x2029 -> out.append("\\u2029")
            code < 0x20 -> out.append("\\u").append("%04x".format(code))
            else -> out.append(c)
        }
        i++
    }
    out.append('"')
    return out.toString()
}

/**
 * Builds the `evaluateJavascript` payload for inserting `text` at the current DOM
 * caret via `document.execCommand('insertText', ...)`.
 *
 * The IIFE checks that `document.activeElement` is an editable element (INPUT, TEXTAREA,
 * or contentEditable) and no-ops otherwise, so page-level shortcuts keep working when
 * focus is on a non-editable element (task 31c). `execCommand('insertText')` is chosen
 * over assigning to `.value` because it fires `beforeinput`/`input`/`change` events and
 * preserves undo history (task 31a).
 */
internal fun buildInsertTextScript(text: CharSequence): String {
    return "(function(t){var el=document.activeElement;if(!el)return;var tag=el.tagName;" +
        "var editable=tag==='INPUT'||tag==='TEXTAREA'||el.isContentEditable;" +
        "if(!editable)return;" +
        "try{document.execCommand('insertText',false,t);}catch(e){}" +
        "})(${escapeForJsStringLiteral(text)});"
}
