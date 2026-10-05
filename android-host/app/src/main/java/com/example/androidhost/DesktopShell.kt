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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.androidhost.ui.components.AppLauncher
import com.example.androidhost.ui.components.AppRegistry
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
    shellViewModel: ShellViewModel = com.example.androidhost.vm.ShellHolder.shellViewModel,
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

    val isAudioCapturing by com.example.androidhost.service.AudioCaptureService.isServiceRunning.collectAsState()
    val computeState by com.example.androidhost.service.NativeComputeService.nclState.collectAsState()
    val windows by shellViewModel?.windows?.collectAsState(initial = emptyList()) ?: remember { mutableStateOf(emptyList()) }
    val forceRedraw by com.example.androidhost.service.DisplayService.forceRedraw.collectAsState()
    var burstTick by remember { mutableStateOf(0) }

    LaunchedEffect(forceRedraw) {
        if (forceRedraw > 0) {
            for (i in 0 until 10) {
                burstTick++
                kotlinx.coroutines.delay(16)
            }
            burstTick = 0
        }
    }

    // Both are optional capabilities. When off, the shell hides the buttons they power
    // rather than blocking or nagging — desktop control works either way.
    val context = androidx.compose.ui.platform.LocalContext.current
    val a11yEnabled by com.example.androidhost.service.DesktopAccessibilityService.isConnected.collectAsState()

    LaunchedEffect(Unit) {
        // Both settings are toggled in system UI on the phone, out of band from this
        // Presentation, so poll rather than wait for a lifecycle event we never get.
        while (true) {
            com.example.androidhost.service.DesktopAccessibilityService.refresh(context)
            delay(2000)
        }
    }


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
            .background(Color.Black)
    ) {
        // Windows 10 Hero Wallpaper background
        Windows10Wallpaper()

        // Windows
        windows.forEach { window ->
            val appConfig = AppRegistry.apps[window.packageName]
            if (appConfig != null) {
                appConfig.content(
                    window,
                    { shellViewModel?.closeWindow(window.id) },
                    { shellViewModel?.minimizeWindow(window.id) },
                    { shellViewModel?.maximizeWindow(window.id) }
                )
            }
        }
        if (burstTick > 0) {
            // Guarantee a buffer is queued to the VirtualDisplay by changing layout slightly.
            // Using a burst of 10 frames ensures the MediaCodec pipeline is fully flushed.
            androidx.compose.foundation.layout.Box(Modifier.size((burstTick % 10 + 1).dp).background(Color.Black))
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
    }
}

@Composable
fun Windows10Wallpaper(modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        // 1. Deep space navy background gradient
        drawRect(
            brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                colors = listOf(
                    Color(0xFF020712),
                    Color(0xFF001128),
                    Color(0xFF001A3A),
                    Color(0xFF010814)
                )
            )
        )

        // 2. Windows 10 Hero radial luminous bloom centered on right quadrant
        val heroCenter = androidx.compose.ui.geometry.Offset(w * 0.65f, h * 0.48f)
        drawCircle(
            brush = androidx.compose.ui.graphics.Brush.radialGradient(
                colors = listOf(
                    Color(0x880078D7),
                    Color(0x44005A9E),
                    Color(0x18002050),
                    Color.Transparent
                ),
                center = heroCenter,
                radius = w * 0.45f
            ),
            center = heroCenter,
            radius = w * 0.45f
        )

        // 3. Volumetric angled light rays
        val rayPath = androidx.compose.ui.graphics.Path().apply {
            moveTo(heroCenter.x - 300f, 0f)
            lineTo(heroCenter.x + 400f, 0f)
            lineTo(heroCenter.x + 600f, h)
            lineTo(heroCenter.x - 100f, h)
            close()
        }
        drawPath(
            path = rayPath,
            brush = androidx.compose.ui.graphics.Brush.linearGradient(
                colors = listOf(
                    Color(0x2200C8FF),
                    Color(0x0C0078D7),
                    Color.Transparent
                ),
                start = androidx.compose.ui.geometry.Offset(heroCenter.x, 0f),
                end = androidx.compose.ui.geometry.Offset(heroCenter.x + 200f, h)
            )
        )

        // 4. Iconic four-quadrant angled Windows 10 logo
        val logoSize = 130f
        val gap = 9f
        val half = logoSize * 0.5f
        val skewX = -18f

        val quadrants = listOf(
            listOf(
                androidx.compose.ui.geometry.Offset(heroCenter.x - half + skewX * 0.7f, heroCenter.y - half),
                androidx.compose.ui.geometry.Offset(heroCenter.x - gap * 0.5f + skewX * 0.2f, heroCenter.y - half + 6f),
                androidx.compose.ui.geometry.Offset(heroCenter.x - gap * 0.5f, heroCenter.y - gap * 0.5f),
                androidx.compose.ui.geometry.Offset(heroCenter.x - half, heroCenter.y - gap * 0.5f - 4f)
            ),
            listOf(
                androidx.compose.ui.geometry.Offset(heroCenter.x + gap * 0.5f + skewX * 0.2f, heroCenter.y - half + 6f),
                androidx.compose.ui.geometry.Offset(heroCenter.x + half - skewX * 0.4f, heroCenter.y - half + 14f),
                androidx.compose.ui.geometry.Offset(heroCenter.x + half, heroCenter.y - gap * 0.5f + 3f),
                androidx.compose.ui.geometry.Offset(heroCenter.x + gap * 0.5f, heroCenter.y - gap * 0.5f)
            ),
            listOf(
                androidx.compose.ui.geometry.Offset(heroCenter.x - half, heroCenter.y + gap * 0.5f - 4f),
                androidx.compose.ui.geometry.Offset(heroCenter.x - gap * 0.5f, heroCenter.y + gap * 0.5f),
                androidx.compose.ui.geometry.Offset(heroCenter.x - gap * 0.5f - skewX * 0.2f, heroCenter.y + half - 6f),
                androidx.compose.ui.geometry.Offset(heroCenter.x - half - skewX * 0.7f, heroCenter.y + half)
            ),
            listOf(
                androidx.compose.ui.geometry.Offset(heroCenter.x + gap * 0.5f, heroCenter.y + gap * 0.5f),
                androidx.compose.ui.geometry.Offset(heroCenter.x + half, heroCenter.y + gap * 0.5f + 3f),
                androidx.compose.ui.geometry.Offset(heroCenter.x + half + skewX * 0.4f, heroCenter.y + half - 14f),
                androidx.compose.ui.geometry.Offset(heroCenter.x + gap * 0.5f - skewX * 0.2f, heroCenter.y + half - 6f)
            )
        )

        for (quad in quadrants) {
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(quad[0].x, quad[0].y)
                lineTo(quad[1].x, quad[1].y)
                lineTo(quad[2].x, quad[2].y)
                lineTo(quad[3].x, quad[3].y)
                close()
            }
            drawPath(
                path = path,
                brush = androidx.compose.ui.graphics.Brush.linearGradient(
                    colors = listOf(
                        Color(0xEE00D2FF),
                        Color(0xBB0078D7)
                    ),
                    start = quad[0],
                    end = quad[2]
                )
            )
        }
    }
}
