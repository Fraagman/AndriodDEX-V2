package com.example.androidhost.service

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager

/**
 * Soft keyboard that lets key events arriving from the PC reach whatever editor
 * currently has focus on the VirtualDisplay.
 *
 * `LocalInputDispatcher` already delivers key events into our own Compose view tree, so
 * this IME is only needed to bridge the gap when Android has routed text entry through
 * an `InputConnection` — i.e. when a Compose `TextField` is focused and the platform has
 * started an input session. When no session is active, [dispatchFromHost] declines the
 * event and the dispatcher falls back to `View.dispatchKeyEvent`.
 *
 * Enabling and selecting a keyboard lives in Settings > Languages & input. It needs no
 * developer options and no ADB.
 */
class AndroidDexIME : InputMethodService() {

    companion object {
        private const val TAG = "AndroidDexIME"

        /** Set while the IME is bound. Read only from the main thread. */
        @Volatile
        private var instance: AndroidDexIME? = null

        /**
         * True when the user has selected AndroidDex as their active keyboard.
         *
         * Reads `Settings.Secure.DEFAULT_INPUT_METHOD`, which holds a flattened
         * ComponentName such as `com.example.androidhost/.service.AndroidDexIME`.
         */
        fun isSelectedIme(context: Context): Boolean {
            val current = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.DEFAULT_INPUT_METHOD
            ) ?: return false
            return current.startsWith("${context.packageName}/")
        }

        /** Opens the system keyboard picker so the user can switch to AndroidDex. */
        fun showImePicker(context: Context) {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }

        /**
         * Offers a key event to the active input session.
         *
         * @return true when the event was consumed via an `InputConnection`. false means
         *         no session is active and the caller should dispatch the event itself.
         */
        fun dispatchFromHost(
            keyCode: Int,
            pressed: Boolean,
            metaState: Int,
            downTime: Long,
            eventTime: Long
        ): Boolean {
            Log.d(TAG, "AndroidDexIME dispatchFromHost: keyCode=$keyCode, pressed=$pressed")
            val ime = instance ?: return false
            if (!ime.hasLiveEditor()) return false
            val connection = ime.currentInputConnection ?: return false

            val event = KeyEvent(
                downTime, eventTime,
                if (pressed) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP,
                keyCode, 0, metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
                InputDevice.SOURCE_KEYBOARD
            )
            connection.sendKeyEvent(event)
            return true
        }

        /**
         * Commits a literal string into the focused editor.
         *
         * @return true when the text was committed.
         */
        fun commitTextFromHost(text: CharSequence): Boolean {
            val ime = instance ?: return false
            if (!ime.hasLiveEditor()) return false
            val connection = ime.currentInputConnection ?: return false
            return connection.commitText(text, 1)
        }
    }

    /**
     * True only when a real editor is attached.
     *
     * `currentInputConnection` can be a no-op connection when the IME is bound but no
     * field has focus; committing into that silently swallows the keystroke. An
     * `inputType` of `TYPE_NULL` is the platform's signal for "not a real text editor".
     */
    private fun hasLiveEditor(): Boolean {
        val editor = currentInputEditorInfo ?: return false
        return editor.inputType != InputType.TYPE_NULL
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "IME created")
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
        Log.d(TAG, "IME destroyed")
    }

    /**
     * No on-screen key layout: every keystroke originates from the PC's physical
     * keyboard. Returning null keeps the IME window out of the streamed desktop, which
     * is what we want — a soft keyboard covering the desktop would be pure obstruction.
     */
    override fun onCreateInputView(): View? = null

    override fun onEvaluateInputViewShown(): Boolean = false

    /**
     * The PC's keyboard is a real hardware keyboard from the user's point of view, so
     * the IME must not claim fullscreen (extract) mode in landscape.
     */
    override fun onEvaluateFullscreenMode(): Boolean = false
}
