package com.example.androidhost.vm

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import com.example.androidhost.service.DisplayService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class DisplayViewModel(application: Application) : AndroidViewModel(application) {

    private val _virtualDisplaySurface = MutableStateFlow<Surface?>(null)
    val virtualDisplaySurface: StateFlow<Surface?> = _virtualDisplaySurface.asStateFlow()

    private var displayService: DisplayService? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as DisplayService.LocalBinder
            displayService = binder.getService()
            _virtualDisplaySurface.value = displayService?.surface
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            displayService = null
            _virtualDisplaySurface.value = null
        }
    }

    init {
        val intent = Intent(application, DisplayService::class.java)
        application.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onCleared() {
        super.onCleared()
        getApplication<Application>().unbindService(connection)
    }
}
