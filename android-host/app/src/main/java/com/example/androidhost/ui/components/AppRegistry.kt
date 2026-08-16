package com.example.androidhost.ui.components

import android.graphics.drawable.Drawable
import androidx.compose.runtime.Composable
import com.example.androidhost.ui.linux.CodeServerWindow
import com.example.androidhost.ui.linux.TerminalWindow
import com.example.androidhost.ui.apps.FilesApp
import com.example.androidhost.ui.apps.SettingsApp
import com.example.androidhost.ui.apps.BrowserApp
import com.example.androidhost.vm.WindowState

data class AppConfig(
    val packageName: String,
    val name: String,
    val icon: Drawable?,
    val content: @Composable (
        windowState: WindowState,
        onClose: () -> Unit,
        onMinimize: () -> Unit,
        onMaximize: () -> Unit
    ) -> Unit
)

object AppRegistry {
    val apps: Map<String, AppConfig> = listOf(
        AppConfig(
            packageName = "com.androiddex.codeserver",
            name = "VS Code",
            icon = null,
            content = { state, close, minimize, maximize ->
                CodeServerWindow(state, close, minimize, maximize)
            }
        ),
        AppConfig(
            packageName = "com.androiddex.terminal",
            name = "Terminal",
            icon = null,
            content = { state, close, minimize, maximize ->
                TerminalWindow(state, close, minimize, maximize)
            }
        ),
        AppConfig(
            packageName = "com.androiddex.files",
            name = "Files",
            icon = null,
            content = { state, close, minimize, maximize ->
                FilesApp(state, close, minimize, maximize)
            }
        ),
        AppConfig(
            packageName = "com.androiddex.settings",
            name = "Settings",
            icon = null,
            content = { state, close, minimize, maximize ->
                SettingsApp(state, close, minimize, maximize)
            }
        ),
        AppConfig(
            packageName = "com.androiddex.browser",
            name = "Browser",
            icon = null,
            content = { state, close, minimize, maximize ->
                BrowserApp(state, close, minimize, maximize)
            }
        )
    ).associateBy { it.packageName }
}
