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
        }
    }

    // Both are optional capabilities. When off, the shell hides the buttons they power
    // rather than blocking or nagging — desktop control works either way.
    val context = androidx.compose.ui.platform.LocalContext.current
    val a11yEnabled by com.example.androidhost.service.DesktopAccessibilityService.isConnected.collectAsState()
    var imeSelected by remember {
        mutableStateOf(com.example.androidhost.service.AndroidDexIME.isSelectedIme(context))
    }

    LaunchedEffect(Unit) {
        // Both settings are toggled in system UI on the phone, out of band from this
        // Presentation, so poll rather than wait for a lifecycle event we never get.
        while (true) {
            com.example.androidhost.service.DesktopAccessibilityService.refresh(context)
            imeSelected = com.example.androidhost.service.AndroidDexIME.isSelectedIme(context)
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
            .background(Color.Transparent)
    ) {
        // The desktop content ALWAYS renders the real desktop:
        // 1. Background (dark wallpaper color, already set on the Box)
        // 2. Windows layer
        // 3. Launcher overlay (when toggled)
        // 4. Taskbar at the bottom



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



        // Taskbar at bottom
        Box(modifier = Modifier.align(Alignment.BottomCenter)) {
            Taskbar(
                quicState = quicState,
                isAudioCapturing = isAudioCapturing,
                computeState = computeState,
                onLauncherClick = { showLauncher = !showLauncher },
                onSettingsClick = { shellViewModel?.openApp("com.androiddex.settings") },
                showNavButtons = a11yEnabled,
                onBackClick = { com.example.androidhost.service.DesktopAccessibilityService.performBack() },
                onHomeClick = { com.example.androidhost.service.DesktopAccessibilityService.performHome() },
                onRecentsClick = { com.example.androidhost.service.DesktopAccessibilityService.performRecents() },
                showKeyboardPrompt = !imeSelected,
                onKeyboardPromptClick = {
                    com.example.androidhost.service.AndroidDexIME.showImePicker(context)
                }
            )
        }
    }
}
