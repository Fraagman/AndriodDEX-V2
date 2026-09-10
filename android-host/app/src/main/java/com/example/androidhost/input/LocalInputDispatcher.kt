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
 * Callback for WebView surfaces (BrowserApp, CodeServerWindow) that want key/text events
 * routed through the DOM instead of the Android IME.
 *
 * Registered by the surface when its WebView gains focus and cleared when it loses focus;
 * see [LocalInputDispatcher.registerWebViewBridge]. Callbacks are invoked on the main
 * thread. All three methods must be safe to call for non-editable focus targets — the
 * WebView side is expected to check `document.activeElement` and no-op silently when the
 * focus is not on an editable element (an INPUT, TEXTAREA or contentEditable node), so the
 * page's own key-shortcut handling keeps working (31c).
 */
interface WebViewInputBridge {
    /** Insert `text` into the currently focused editable element. */
    fun insertText(text: CharSequence)

    /**
     * Handle a control key that should not be routed as text.
     *
     * `key` is a DOM-style KeyboardEvent.key value: "Backspace", "Enter", "Tab", "Escape",
     * "ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown". `keyCode` is the legacy DOM
     * KeyboardEvent.keyCode integer. `pressed` is true for keydown, false for keyup.
     */
    fun controlKey(key: String, keyCode: Int, pressed: Boolean)
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

    /** Currently held modifiers, as an Android metaState bitmask. */
    private var metaState = 0

    /** Latched CapsLock / NumLock bits, toggled on each press. */
    private var lockState = 0

    /** downTime per Android keycode, so KeyEvent UP pairs with its DOWN. */
    private val keyDownTimes = HashMap<Int, Long>()

    /**
     * When set, WebView-focused surfaces receive text and control keys through the DOM
     * (via `evaluateJavascript`) instead of via `View.dispatchKeyEvent` or the platform
     * IME. See [WebViewInputBridge] for why the
     * IME route cannot work on our untrusted VirtualDisplay.
     */
    sealed class InputTarget {
        class WebViewBridge(val bridge: WebViewInputBridge, val view: android.webkit.WebView) : InputTarget()
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
     */
    fun registerWebViewBridge(owner: Any, bridge: WebViewInputBridge?, view: android.webkit.WebView) {
        setTarget(owner, bridge?.let { InputTarget.WebViewBridge(it, view) })
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
     */
    fun onMouse(wireX: Int, wireY: Int, buttons: Int, modifiers: Int = 0) {
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
     *
     * @param wireModifiers modifier bitmask from the receiver. When non-zero, this is
     *        used directly instead of the locally-reconstructed state, which can desync
     *        if a modifier release is missed over the network (classic: Shift stuck on).
     */
    fun onKey(winitKeyCode: Int, pressed: Boolean, wireModifiers: Int = 0) {
        mainHandler.post { handleKey(winitKeyCode, pressed, wireModifiers) }
    }

    private fun handleKey(winitKeyCode: Int, pressed: Boolean, wireModifiers: Int = 0) {
        updateMetaState(winitKeyCode, pressed)

        val keyCode = WinitKeyMap.toAndroidKeyCode(winitKeyCode)
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN) return

        // When the receiver sends a non-zero modifier bitmask, trust it over the
        // locally-reconstructed state. A missed key-up over the network leaves the
        // local tracker stuck, but the receiver always has the real modifier state.
        val effectiveMeta = if (wireModifiers != 0) wireModifiersToAndroidMeta(wireModifiers) or lockState else metaState or lockState
        val now = SystemClock.uptimeMillis()
        val downTime: Long
        if (pressed) {
            downTime = now
            keyDownTimes[keyCode] = now
        } else {
            downTime = keyDownTimes.remove(keyCode) ?: now
        }

        // Route through the WebView bridge when a WebView surface has focus. This is the
        // replacement for the dead IME path: our virtual display is
        // untrusted, so `onStartInput` never fires and `InputConnection`-based text entry
        // silently drops. WebViews need text delivered into the DOM instead.
        // the WebView still receives them as Chromium key events for page shortcuts.
        val target = currentTarget
        if (target is InputTarget.WebViewBridge) {
            val bridge = target.bridge
            val controlName = controlKeyName(keyCode)
            if (controlName != null) {
                bridge.controlKey(controlName, controlKeyDomCode(keyCode), pressed)
                return
            }
            // Printable characters: only on key-down, and only when we can resolve one.
            // Modifier-only combos (Ctrl+A etc.) fall through to `dispatchKeyEvent` so
            // the WebView still receives them as Chromium key events for page shortcuts.
            if (pressed && effectiveMeta and (KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON or KeyEvent.META_META_ON) == 0) {
                val unicode = KeyEvent(
                    downTime, now, KeyEvent.ACTION_DOWN, keyCode, 0, effectiveMeta,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD
                ).unicodeChar
                if (unicode != 0) {
                    bridge.insertText(String(Character.toChars(unicode)))
                    return
                }
            }
            // Not a printable char and not a listed control — fall through so page-level
            // shortcuts (Ctrl+F etc.) keep working via View.dispatchKeyEvent.
        } else if (target is InputTarget.ComposeTarget) {
            val controlName = controlKeyName(keyCode)
            if (controlName != null) {
                if (target.onKey(keyCode, pressed)) return
            }
            if (pressed && effectiveMeta and (KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON or KeyEvent.META_META_ON) == 0) {
                val unicode = KeyEvent(
                    downTime, now, KeyEvent.ACTION_DOWN, keyCode, 0, effectiveMeta,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD
                ).unicodeChar
                if (unicode != 0) {
                    target.onText(String(Character.toChars(unicode)))
                    return
                }
            }
        }

        // The IME path is removed.

        if (target == null) {
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
    }

    /**
     * Maps Android keycodes for the control keys named in the task-31 spec to their
     * DOM `KeyboardEvent.key` string. Returns null for keys not covered by 31b, which
     * are then either injected as text (printable) or fall through to `dispatchKeyEvent`
     * (modifier combos, function keys).
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

    /** Legacy DOM keyCode integer for the keys enumerated by [controlKeyName]. */
    private fun controlKeyDomCode(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_DEL -> 8
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> 13
        KeyEvent.KEYCODE_TAB -> 9
        KeyEvent.KEYCODE_ESCAPE -> 27
        KeyEvent.KEYCODE_DPAD_LEFT -> 37
        KeyEvent.KEYCODE_DPAD_UP -> 38
        KeyEvent.KEYCODE_DPAD_RIGHT -> 39
        KeyEvent.KEYCODE_DPAD_DOWN -> 40
        else -> 0
    }

    fun registerComposeTarget(owner: Any, onText: ((String) -> Unit)?, onKey: ((Int, Boolean) -> Boolean)?) {
        setTarget(owner, if (onText != null && onKey != null) InputTarget.ComposeTarget(onText, onKey) else null)
    }

    /**
     * Commits a literal string, bypassing keycode translation. Used for characters the
     * PC resolves itself (dead keys, IME composition, clipboard paste).
     *
     * Routes through the WebView bridge when one is attached, or the Compose injection
     * channel, or the Terminal.
     */
    fun onText(text: CharSequence) {
        if (text.isEmpty()) return
        mainHandler.post {
            val target = currentTarget
            if (target is InputTarget.WebViewBridge) {
                target.bridge.insertText(text)
                return@post
            } else if (target is InputTarget.ComposeTarget) {
                target.onText(text.toString())
            }
        }
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

    /**
     * Converts the wire modifier bitmask (matching `input.proto` Modifier enum values)
     * into Android [KeyEvent] `META_*` flags.
     */
    private fun wireModifiersToAndroidMeta(wireModifiers: Int): Int {
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

/**
 * Builds the `evaluateJavascript` payload for a synthetic KeyboardEvent on the current
 * DOM caret. Backspace is delivered as `execCommand('delete')` per task 31b; Enter,
 * Tab, Escape and the arrow keys as a matching `keydown`/`keyup` pair. When Enter is
 * pressed on an INPUT inside a form we also call `form.requestSubmit()` so form
 * submission — Google search being the specific example the owner will test — actually
 * runs, which synthetic KeyboardEvents alone do not trigger.
 */
internal fun buildControlKeyScript(key: String, keyCode: Int, pressed: Boolean): String {
    val evType = if (pressed) "keydown" else "keyup"
    return "(function(){var el=document.activeElement;if(!el)return;" +
        "var tag=el.tagName;" +
        "var editable=tag==='INPUT'||tag==='TEXTAREA'||el.isContentEditable;" +
        "if(!editable)return;" +
        "var k=${escapeForJsStringLiteral(key)};var kc=$keyCode;" +
        (if (pressed && key == "Backspace") "try{document.execCommand('delete',false,null);}catch(e){}return;" else "") +
        "try{var ev=new KeyboardEvent('$evType',{key:k,code:k,keyCode:kc,which:kc," +
        "bubbles:true,cancelable:true});el.dispatchEvent(ev);" +
        (if (pressed && key == "Enter") "if(tag==='INPUT'&&el.form){if(el.form.requestSubmit){el.form.requestSubmit();}else{el.form.submit();}}" else "") +
        "}catch(e){}" +
        "})();"
}
