package com.example.androidhost.service

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.provider.Settings
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager

/**
 * Soft keyboard originally intended to bridge PC-side key events into whatever editor
 * has focus on the desktop we stream from the VirtualDisplay.
 *
 * **This route is dead on our display and cannot be revived. It is kept as a bound
 * service only so uninstalling the IME does not leave a dangling manifest reference —
 * removing the `<service>` declaration is a separate decision.**
 *
 * ---
 *
 * ### What was measured (task 30, hardware, entry 027)
 *
 * With this IME enabled and selected as the default (`settings get secure
 * default_input_method` = `com.androiddex.host/com.example.androidhost.service.AndroidDexIME`),
 * focusing a WebView `<input>` on the VirtualDisplay:
 *
 *  - never called `onStartInput` on this service,
 *  - left `currentInputEditorInfo.inputType` at `TYPE_NULL` (0),
 *  - left `hasLiveEditor()` returning `false`,
 *  - left `currentInputConnection` bound to the `MainActivity` on Display 0.
 *
 * `dumpsys input_method` on the same run showed `mCurTokenDisplayId=0` and
 * `mDisplayIdToShowIme=0`. The IME service was bound, but the platform never routed
 * an editor session on our display to it.
 *
 * ### Why it cannot be revived
 *
 * `InputMethodManagerService` only starts editor sessions on **trusted** displays. A
 * VirtualDisplay is trusted only when it carries `VIRTUAL_DISPLAY_FLAG_TRUSTED`, and
 * that flag requires the signature permission `ADD_TRUSTED_DISPLAY`. A Play Store
 * application cannot hold `ADD_TRUSTED_DISPLAY` — it is signature-only, granted only
 * to apps signed with the platform key. Requesting it does not fail politely at
 * runtime; it fails Play review as well.
 *
 * `VIRTUAL_DISPLAY_FLAG_OWN_FOCUS`, which would give our display its own focus lane
 * and therefore its own IME target, also requires `FLAG_TRUSTED`. Same road, same
 * dead end.
 *
 * ### What replaced it (task 31)
 *
 * The dispatcher no longer relies on this service:
 *
 *  - **WebView surfaces** (`BrowserApp`, `CodeServerWindow`) register a
 *    `WebViewInputBridge` with `LocalInputDispatcher` while focused. Text is injected
 *    into the focused DOM node via `evaluateJavascript` calling
 *    `document.execCommand('insertText', ...)`; control keys become synthetic
 *    `KeyboardEvent`s (with `form.requestSubmit()` for Enter inside an INPUT).
 *  - **Terminal** (`TerminalWindow`) is rebuilt as a Compose-native surface that
 *    consumes key events directly via `onKeyEvent`, needing no `InputConnection`.
 *  - **Compose `TextField`s** already worked, because Compose reads `KeyEvent`s from
 *    `View.dispatchKeyEvent` without an `InputConnection` at all.
 *
 * The next person to read this file: do not try to bring the IME path back. It cannot
 * work on an untrusted virtual display. The evidence above is what to check before you
 * decide otherwise.
 */
class AndroidDexIME : InputMethodService() {

    companion object {

        fun isSelectedIme(context: Context): Boolean {
            val current = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.DEFAULT_INPUT_METHOD
            ) ?: return false
            return current.startsWith("${context.packageName}/")
        }

        fun showImePicker(context: Context) {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }

        /**
         * Always returns false on this display; kept because `LocalInputDispatcher`
         * still calls it in case a future trusted-display configuration wires an IME
         * session in. See the class comment.
         */
        @Suppress("UNUSED_PARAMETER")
        fun dispatchFromHost(
            keyCode: Int,
            pressed: Boolean,
            metaState: Int,
            downTime: Long,
            eventTime: Long
        ): Boolean = false

        /** Same status as [dispatchFromHost]: never commits on this display. */
        @Suppress("UNUSED_PARAMETER")
        fun commitTextFromHost(text: CharSequence): Boolean = false
    }

    /**
     * No on-screen key layout: every keystroke originates from the PC's physical
     * keyboard, and this service never receives an input session anyway. Returning
     * null keeps the IME window out of the streamed desktop.
     */
    override fun onCreateInputView(): View? = null

    override fun onEvaluateInputViewShown(): Boolean = false

    override fun onEvaluateFullscreenMode(): Boolean = false

    /** Kept present so an EditorInfo binding, if it ever arrives, doesn't NPE. */
    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
    }
}
