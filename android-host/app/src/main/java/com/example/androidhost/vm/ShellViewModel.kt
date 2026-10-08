package com.example.androidhost.vm

import android.graphics.Rect
import android.util.Log
import androidx.lifecycle.ViewModel
import com.example.androidhost.service.DisplayService
import com.example.androidhost.ui.components.AppRegistry
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

/**
 * Singleton holder so that both the Presentation (VirtualDisplay) and
 * the Activity/input path share the SAME ShellViewModel instance.
 */
object ShellHolder {
    val shellViewModel = ShellViewModel()
}

class ShellViewModel : ViewModel() {

    companion object {
        private const val TAG = "ShellViewModel"

        /** Hard cap so a buggy or malicious client cannot balloon memory with windows. */
        private const val MAX_WINDOWS = 16

        /**
         * Title-bar slack kept on screen when clamping a dragged window, so a window
         * thrown at any edge can always be grabbed again.
         */
        private const val EDGE_SLACK_PX = 100
    }

    /**
     * Z-order is the list order: last entry renders on top. Bounds are screen
     * pixels — the VirtualDisplay runs at density 1.0 (1 dp = 1 px), matching
     * `WindowChrome`, which lays out in dp.
     */
    private val _windows = MutableStateFlow<List<WindowState>>(emptyList())
    val windows: StateFlow<List<WindowState>> = _windows.asStateFlow()

    private val _focusedId = MutableStateFlow<String?>(null)

    /**
     * The active window: the topmost non-minimized one. Derived from the list on
     * every mutation, so minimizing the focused window hands focus to the window
     * below it without extra bookkeeping.
     */
    val focusedId: StateFlow<String?> = _focusedId.asStateFlow()

    /**
     * Opens a registered app in a new window, or — desktop convention — focuses
     * (and restores, if minimized) the window that already runs it.
     *
     * [packageName] must exist in [AppRegistry]: this is reachable from the QUIC
     * input stream and from exported broadcasts, so unvalidated strings from any
     * sender must not become windows.
     */
    fun openApp(packageName: String) {
        val config = AppRegistry.apps[packageName]
        if (config == null) {
            Log.w(TAG, "openApp: rejecting unknown package '$packageName'")
            return
        }

        val existing = _windows.value.lastOrNull { it.packageName == packageName }
        if (existing != null) {
            if (existing.isMinimized) {
                _windows.value = _windows.value.map { window ->
                    if (window.id == existing.id) window.copy(isMinimized = false) else window
                }
            }
            raiseWindow(existing.id)
            return
        }

        val current = _windows.value
        if (current.size >= MAX_WINDOWS) {
            Log.w(TAG, "openApp: window cap of $MAX_WINDOWS reached, refusing '$packageName'")
            return
        }

        _windows.value = current + WindowState(
            id = UUID.randomUUID().toString(),
            title = config.name,
            packageName = packageName,
            bounds = Rect(100, 100, 900, 700)
        )
        refreshFocus()
        Log.d(TAG, "openApp: ${config.name}, windows: ${_windows.value.size}")
    }

    /** Brings [id] to the top of the z-order (last list position) and focuses it. */
    fun raiseWindow(id: String) {
        val current = _windows.value
        val index = current.indexOfFirst { it.id == id }
        if (index < 0) {
            refreshFocus()
            return
        }
        if (index != current.lastIndex) {
            _windows.value = current.filterIndexed { i, _ -> i != index } + current[index]
        }
        refreshFocus()
    }

    /**
     * Translates [id] by ([dx], [dy]) screen pixels, clamped so at least
     * [EDGE_SLACK_PX] of the title bar always stays inside the desktop.
     */
    fun moveWindow(id: String, dx: Float, dy: Float) {
        _windows.value = _windows.value.map { window ->
            if (window.id != id) {
                window
            } else {
                val width = window.bounds.width()
                // minOf/maxOf keep the range valid even if the user applies a desktop
                // resolution smaller than a window or the slack itself.
                val leftRange = minOf(-(width - EDGE_SLACK_PX), DisplayService.CAPTURE_WIDTH - EDGE_SLACK_PX)..
                        maxOf(-(width - EDGE_SLACK_PX), DisplayService.CAPTURE_WIDTH - EDGE_SLACK_PX)
                val topRange = minOf(0, DisplayService.CAPTURE_HEIGHT - EDGE_SLACK_PX)..
                        maxOf(0, DisplayService.CAPTURE_HEIGHT - EDGE_SLACK_PX)
                val newLeft = (window.bounds.left + dx).toInt().coerceIn(leftRange)
                val newTop = (window.bounds.top + dy).toInt().coerceIn(topRange)
                window.copy(
                    bounds = Rect(
                        newLeft, newTop,
                        newLeft + width, newTop + window.bounds.height()
                    )
                )
            }
        }
    }

    fun closeWindow(id: String) {
        _windows.value = _windows.value.filter { it.id != id }
        refreshFocus()
    }

    fun minimizeWindow(id: String) {
        _windows.value = _windows.value.map {
            if (it.id == id) it.copy(isMinimized = !it.isMinimized) else it
        }
        refreshFocus()
    }

    fun maximizeWindow(id: String) {
        _windows.value = _windows.value.map {
            if (it.id == id) it.copy(isMaximized = !it.isMaximized) else it
        }
    }

    private fun refreshFocus() {
        _focusedId.value = _windows.value.lastOrNull { !it.isMinimized }?.id
    }
}
