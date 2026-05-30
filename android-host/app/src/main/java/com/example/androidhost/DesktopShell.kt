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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Switch
import androidx.compose.ui.text.font.FontWeight
import com.example.androidhost.vm.ShellViewModel
import kotlinx.coroutines.delay

@Composable
fun DesktopShell(
    viewModel: ConnectionViewModel = viewModel(),
    displayViewModel: DisplayViewModel = viewModel(),
    shellViewModel: ShellViewModel = viewModel(),
    onLockSession: () -> Unit = {},
    onRequestAudioCapture: (Boolean) -> Unit = {}
) {
    val isReady by viewModel.isTetheringReady.collectAsState()
    val surface by displayViewModel.virtualDisplaySurface.collectAsState()
    
    DesktopShellContent(
        isTetheringReady = isReady,
        surface = surface,
        shellViewModel = shellViewModel,
        onLockSession = onLockSession,
        onRequestAudioCapture = onRequestAudioCapture
    )
}

@Composable
fun DesktopShellContent(
    isTetheringReady: Boolean,
    surface: Surface? = null,
    shellViewModel: ShellViewModel? = null,
    onLockSession: () -> Unit = {},
    onRequestAudioCapture: (Boolean) -> Unit = {}
) {
    var quicState by remember { mutableStateOf(0) }
    var framesSent by remember { mutableStateOf(0) }
    var showLauncher by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    val isAudioCapturing by com.example.androidhost.service.AudioCaptureService.isServiceRunning.collectAsState()
    val vmState by com.example.androidhost.service.VmService.vmState.collectAsState()
    val windows by shellViewModel?.windows?.collectAsState(initial = emptyList()) ?: remember { mutableStateOf(emptyList()) }

    LaunchedEffect(Unit) {
        delay(2000)
        if (!isAudioCapturing) {
            onRequestAudioCapture(true)
        }
    }

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
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = { onLockSession() }) {
                        Text("Lock Session")
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
                Text(text = "Sending frames via QUIC", color = Color.White, fontSize = 12.sp)
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

        // Settings Overlay Dialog
        if (showSettings) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable { showSettings = false }
            ) {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .width(320.dp)
                        .background(Color(0xFF1E1E1E), shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                        .clickable(enabled = false) {}
                        .padding(24.dp)
                ) {
                    Text(
                        text = "System Settings",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 20.dp)
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Enable System Audio",
                            color = Color.White,
                            fontSize = 14.sp,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = isAudioCapturing,
                            onCheckedChange = { onRequestAudioCapture(it) }
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "AVF VM Desktop",
                            color = Color.White,
                            fontSize = 14.sp,
                            modifier = Modifier.weight(1f)
                        )
                        val context = androidx.compose.ui.platform.LocalContext.current
                        Button(
                            onClick = {
                                val intent = android.content.Intent(context, com.example.androidhost.service.VmService::class.java)
                                if (vmState == com.example.androidhost.service.VmState.RUNNING) {
                                    intent.action = "STOP_VM"
                                    context.startService(intent)
                                } else {
                                    intent.action = "START_VM"
                                    context.startService(intent)
                                }
                            },
                            enabled = vmState != com.example.androidhost.service.VmState.UNSUPPORTED
                        ) {
                            Text(if (vmState == com.example.androidhost.service.VmState.RUNNING) "Stop" else "Start")
                        }
                    }
                }
            }
        }

        // Taskbar at bottom
        Box(modifier = Modifier.align(Alignment.BottomCenter)) {
            Taskbar(
                quicState = quicState,
                isAudioCapturing = isAudioCapturing,
                vmState = vmState,
                onLauncherClick = { showLauncher = !showLauncher },
                onSettingsClick = { showSettings = !showSettings }
            )
        }
    }
}

