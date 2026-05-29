package com.example.androidhost

import android.view.Surface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.androidhost.ui.components.AppLauncher
import com.example.androidhost.ui.components.Taskbar
import com.example.androidhost.ui.components.WindowChrome
import com.example.androidhost.vm.ConnectionViewModel
import com.example.androidhost.vm.DisplayViewModel
import com.example.androidhost.vm.ShellViewModel
import kotlinx.coroutines.delay

@Composable
fun DesktopShell(
    viewModel: ConnectionViewModel = viewModel(),
    displayViewModel: DisplayViewModel = viewModel(),
    shellViewModel: ShellViewModel = viewModel()
) {
    val isReady by viewModel.isTetheringReady.collectAsState()
    val surface by displayViewModel.virtualDisplaySurface.collectAsState()
    
    DesktopShellContent(
        isTetheringReady = isReady,
        surface = surface,
        shellViewModel = shellViewModel
    )
}

@Composable
fun DesktopShellContent(
    isTetheringReady: Boolean,
    surface: Surface? = null,
    shellViewModel: ShellViewModel? = null
) {
    var quicState by remember { mutableStateOf(0) }
    var framesSent by remember { mutableStateOf(0) }
    var showLauncher by remember { mutableStateOf(false) }

    val windows by shellViewModel?.windows?.collectAsState(initial = emptyList()) ?: remember { mutableStateOf(emptyList()) }

    LaunchedEffect(Unit) {
        while (true) {
            val handleStarted = com.example.androidhost.network.FrameSender.isConnected
            val hasData = com.example.androidhost.service.DesktopAccessibilityService.hasReceivedData
            
            quicState = if (hasData) 2 else if (handleStarted) 1 else 0
            framesSent = com.example.androidhost.network.FrameSender.framesSent.get()
            delay(1000)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (surface != null) {
            AndroidView(
                factory = { context ->
                    android.view.SurfaceView(context)
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Initializing display...",
                    color = Color.White,
                    fontSize = 14.sp
                )
                
                if (com.example.androidhost.BuildConfig.DEBUG) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = {
                        val service = com.example.androidhost.service.DesktopAccessibilityService.instance
                        if (service != null) {
                            service.injectClick(500f, 500f)
                        } else {
                            android.util.Log.e("A11y", "Service not connected")
                        }
                    }) {
                        Text("Inject Test Click")
                    }
                }
            }
        }

        if (quicState > 0 || framesSent > 0) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(8.dp)
            ) {
                Text(text = "Sending frames to 10.214.143.14:55556", color = Color.White, fontSize = 12.sp)
                Text(text = "Frames sent: $framesSent", color = Color.White, fontSize = 12.sp)
            }
        }

        // Windows
        windows.forEach { window ->
            WindowChrome(
                windowState = window,
                onClose = { shellViewModel?.closeWindow(window.id) },
                onMinimize = { shellViewModel?.minimizeWindow(window.id) },
                onMaximize = { shellViewModel?.maximizeWindow(window.id) }
            )
        }

        // Launcher Overlay
        if (showLauncher) {
            AppLauncher(
                onDismiss = { showLauncher = false },
                onAppSelected = { packageName ->
                    shellViewModel?.openApp(packageName)
                }
            )
        }

        // Taskbar at bottom
        Box(modifier = Modifier.align(Alignment.BottomCenter)) {
            Taskbar(
                quicState = quicState,
                onLauncherClick = { showLauncher = !showLauncher }
            )
        }
    }
}

