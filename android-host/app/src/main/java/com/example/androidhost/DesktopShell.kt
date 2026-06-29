package com.example.androidhost

import android.view.Surface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
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

/**
 * Forces continuous Compose redraws on every frame by reading withFrameNanos
 * and using drawBehind to invalidate the render layer. This is used ONLY inside
 * the VirtualDisplay Presentation so the ImageReader always has fresh frames.
 *
 * How it works:
 * - LaunchedEffect loops forever calling withFrameNanos, which suspends until
 *   the next choreographer VSYNC and returns the frame time.
 * - Each iteration updates 'tick', which triggers recomposition.
 * - The drawBehind modifier reads 'tick', which invalidates the draw layer,
 *   causing the VirtualDisplay Surface to be re-rendered even when nothing
 *   in the actual content has changed.
 */
@Composable
fun KeepAliveRedraw(content: @Composable () -> Unit) {
    var tick by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        var lastTime = 0L
        while (true) { 
            withFrameNanos { 
                if (it - lastTime >= 33_000_000L) { // ~30fps throttle
                    tick = it
                    lastTime = it
                }
            } 
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .drawBehind {
                // read 'tick' so this layer is invalidated at 30fps
                @Suppress("UNUSED_EXPRESSION")
                tick
            }
    ) { content() }
}

/**
 * Temporary proof-of-life indicator: a small orange rectangle that oscillates
 * horizontally plus a "LIVE Xs" counter. Visible on the VirtualDisplay capture
 * to confirm frames are flowing to the PC receiver.
 * Remove this composable once frame flow is verified stable.
 */
@Composable
fun ProofOfLifeIndicator() {
    var elapsed by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        val start = System.currentTimeMillis()
        while (true) {
            elapsed = System.currentTimeMillis() - start
            delay(16) // ~60fps update
        }
    }
    // Oscillate 0..200dp over a 3-second period
    val period = 3000f
    val phase = (elapsed % period.toLong()) / period
    val offsetX = phase * 200f

    Box(
        modifier = Modifier
            .offset(x = offsetX.dp, y = 40.dp)
            .size(30.dp)
            .background(Color(0xFFFF6B35), RoundedCornerShape(6.dp))
    )
    Text(
        text = "LIVE ${elapsed / 1000}s",
        color = Color.White,
        fontSize = 10.sp,
        modifier = Modifier
            .offset(x = offsetX.dp, y = 74.dp)
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 2.dp)
    )
}

@Composable
fun DesktopShell(
    viewModel: ConnectionViewModel = viewModel(),
    displayViewModel: DisplayViewModel = viewModel(),
    shellViewModel: ShellViewModel = viewModel(),
    onLockSession: () -> Unit = {},
    onRequestAudioCapture: (Boolean) -> Unit = {},
    displayId: Int = android.view.Display.DEFAULT_DISPLAY
) {
    val isReady by viewModel.isTetheringReady.collectAsState()
    val surface by displayViewModel.virtualDisplaySurface.collectAsState()
    
    DesktopShellContent(
        isTetheringReady = isReady,
        surface = surface,
        shellViewModel = shellViewModel,
        onLockSession = onLockSession,
        onRequestAudioCapture = onRequestAudioCapture,
        displayId = displayId
    )
}

@Composable
fun DesktopShellContent(
    isTetheringReady: Boolean,
    surface: Surface? = null,
    shellViewModel: ShellViewModel? = null,
    onLockSession: () -> Unit = {},
    onRequestAudioCapture: (Boolean) -> Unit = {},
    displayId: Int = android.view.Display.DEFAULT_DISPLAY
) {
    var quicState by remember { mutableStateOf(0) }
    var framesSent by remember { mutableStateOf(0) }
    var showLauncher by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    val isAudioCapturing by com.example.androidhost.service.AudioCaptureService.isServiceRunning.collectAsState()
    val vmState by com.example.androidhost.service.VmService.vmState.collectAsState()
    val windows by shellViewModel?.windows?.collectAsState(initial = emptyList()) ?: remember { mutableStateOf(emptyList()) }

    val isNative by com.example.androidhost.service.VmService.isNative.collectAsState()



    LaunchedEffect(Unit) {
        while (true) {
            // Poll real connection state from the Rust QUIC server via JNI
            // 0 = Idle, 1 = Pairing, 2 = Authenticated, 3 = Disconnected
            quicState = com.example.androidhost.quic.QuicServer.getConnectionState()
            framesSent = com.example.androidhost.network.FrameSender.framesSent.get()
            delay(1000)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)  // Transparent so native apps behind it are visible
    ) {
        // The desktop content ALWAYS renders the real desktop:
        // 1. Background (dark wallpaper color, already set on the Box)
        // 2. Windows layer
        // 3. Launcher overlay (when toggled)
        // 4. Taskbar at the bottom

        // Debug overlay — QUIC stats (top-right)
        val regionStats by com.example.androidhost.vm.DisplayViewModel.regionStats.collectAsState()

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
                if (com.example.androidhost.BuildConfig.DEBUG) {
                    Text(text = "Tiles: ${regionStats.first}", color = Color.White, fontSize = 12.sp)
                    Text(text = "Video: ${if (regionStats.second) "Y" else "N"}", color = Color.White, fontSize = 12.sp)
                }
            }
        }

        // Proof-of-life moving indicator (temporary — confirms frames are flowing)
        ProofOfLifeIndicator()

        // Windows
        windows.forEach { window ->
            if (window.packageName == "com.androiddex.codeserver") {
                com.example.androidhost.ui.linux.CodeServerWindow(
                    windowState = window,
                    onClose = { shellViewModel?.closeWindow(window.id) },
                    onMinimize = { shellViewModel?.minimizeWindow(window.id) },
                    onMaximize = { shellViewModel?.maximizeWindow(window.id) }
                )
            } else if (window.packageName == "com.androiddex.terminal") {
                com.example.androidhost.ui.linux.TerminalWindow(
                    windowState = window,
                    onClose = { shellViewModel?.closeWindow(window.id) },
                    onMinimize = { shellViewModel?.minimizeWindow(window.id) },
                    onMaximize = { shellViewModel?.maximizeWindow(window.id) }
                )
            } else {
                WindowChrome(
                    windowState = window,
                    onClose = { shellViewModel?.closeWindow(window.id) },
                    onMinimize = { shellViewModel?.minimizeWindow(window.id) },
                    onMaximize = { shellViewModel?.maximizeWindow(window.id) }
                ) {
                    // Empty for unsupported mock windows
                }
            }
        }

        // Launcher Overlay
        if (showLauncher) {
            AppLauncher(
                onDismiss = { showLauncher = false },
                onAppSelected = { packageName ->
                    shellViewModel?.openApp(packageName)
                },
                displayId = displayId
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
                            text = "Compute Engine",
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
                            }
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
                isNative = isNative,
                onLauncherClick = { showLauncher = !showLauncher },
                onSettingsClick = { showSettings = !showSettings }
            )
        }
    }
}
