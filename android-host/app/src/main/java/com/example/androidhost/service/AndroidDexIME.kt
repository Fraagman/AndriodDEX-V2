package com.example.androidhost.service

import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AndroidDexIME : InputMethodService() {

    companion object {
        private const val TAG = "AndroidDexIME"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "IME Created")
        
        CoroutineScope(Dispatchers.IO).launch {
            InputManager.keyboardEvents.collect { event ->
                handleKeyboardEvent(event)
            }
        }
    }

    private fun handleKeyboardEvent(event: InputManager.ParsedKeyboardEvent) {
        val action = if (event.pressed) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
        // The Windows keycode is a raw hardware code. For a production IME, a full mapping table is needed.
        // For MVP, we pass it directly to Android's InputConnection.
        val inputConnection = currentInputConnection
        if (inputConnection != null) {
            val keyEvent = KeyEvent(event.timestamp, event.timestamp, action, event.keycode, 0, event.modifiers)
            inputConnection.sendKeyEvent(keyEvent)
            Log.d(TAG, "Injected KeyEvent: keycode=${event.keycode}, action=$action")
        } else {
            Log.w(TAG, "No active InputConnection to inject keystroke")
        }
    }
}
