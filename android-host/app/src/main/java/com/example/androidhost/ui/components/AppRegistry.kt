package com.example.androidhost.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.androidhost.ui.linux.TerminalWindow
import com.example.androidhost.ui.apps.FilesApp
import com.example.androidhost.ui.apps.SettingsApp
import com.example.androidhost.ui.apps.BrowserApp
import com.example.androidhost.vm.WindowState

/**
 * The workspace's built-in apps. These are pseudo-packages: each entry is rendered
 * by its own Compose [content] inside a shell window, not by an installed Android
 * package, so icons come from the icon set rather than `PackageManager`.
 */
data class AppConfig(
    val packageName: String,
    val name: String,
    val icon: ImageVector?,
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
            packageName = "com.androiddex.terminal",
            name = "Terminal",
            icon = Icons.Default.Terminal,
            content = { state, close, minimize, maximize ->
                TerminalWindow(state, close, minimize, maximize)
            }
        ),
        AppConfig(
            packageName = "com.androiddex.files",
            name = "Files",
            icon = Icons.Default.Folder,
            content = { state, close, minimize, maximize ->
                FilesApp(state, close, minimize, maximize)
            }
        ),
        AppConfig(
            packageName = "com.androiddex.settings",
            name = "Settings",
            icon = Icons.Default.Settings,
            content = { state, close, minimize, maximize ->
                SettingsApp(state, close, minimize, maximize)
            }
        ),
        AppConfig(
            packageName = "com.androiddex.browser",
            name = "Browser",
            icon = Icons.Default.Public,
            content = { state, close, minimize, maximize ->
                BrowserApp(state, close, minimize, maximize)
            }
        )
    ).associateBy { it.packageName }
}
