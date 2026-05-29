package com.example.androidhost.vm

import android.graphics.Rect
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class WindowState(
    val id: String,
    val title: String,
    val packageName: String,
    val bounds: Rect,
    val isMinimized: Boolean = false,
    val isMaximized: Boolean = false
)

class ShellViewModel : ViewModel() {

    private val _windows = MutableStateFlow<List<WindowState>>(emptyList())
    val windows: StateFlow<List<WindowState>> = _windows.asStateFlow()

    fun openApp(packageName: String, title: String = packageName) {
        // Don't open if already open
        if (_windows.value.any { it.packageName == packageName }) {
            return
        }

        // Default bounds
        val bounds = Rect(100, 100, 900, 700)
        
        val newWindow = WindowState(
            id = UUID.randomUUID().toString(),
            title = title,
            packageName = packageName,
            bounds = bounds
        )
        
        _windows.value = _windows.value + newWindow
    }

    fun closeWindow(id: String) {
        _windows.value = _windows.value.filter { it.id != id }
    }

    fun minimizeWindow(id: String) {
        _windows.value = _windows.value.map {
            if (it.id == id) it.copy(isMinimized = !it.isMinimized) else it
        }
    }

    fun maximizeWindow(id: String) {
        _windows.value = _windows.value.map {
            if (it.id == id) it.copy(isMaximized = !it.isMaximized) else it
        }
    }
}
