package com.example.androidhost.vm

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ConnectionViewModel(application: Application) : AndroidViewModel(application) {

    private val _isTetheringReady = MutableStateFlow(false)
    val isTetheringReady: StateFlow<Boolean> = _isTetheringReady.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.example.androidhost.ACTION_TETHERING_READY") {
                _isTetheringReady.value = true
            }
        }
    }

    init {
        val filter = IntentFilter("com.example.androidhost.ACTION_TETHERING_READY")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            application.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            application.registerReceiver(receiver, filter)
        }
    }

    override fun onCleared() {
        super.onCleared()
        getApplication<Application>().unregisterReceiver(receiver)
    }
}
