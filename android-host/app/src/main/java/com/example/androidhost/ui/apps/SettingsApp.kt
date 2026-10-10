package com.example.androidhost.ui.apps

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.androidhost.quic.QuicServer
import com.example.androidhost.security.SecurityBridge
import com.example.androidhost.service.DisplayService
import com.example.androidhost.ui.components.WindowChrome
import com.example.androidhost.vm.WindowState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import android.view.KeyEvent
import com.example.androidhost.input.LocalInputDispatcher
import kotlinx.coroutines.delay

@Composable
fun SettingsApp(
    windowState: WindowState,
    onClose: () -> Unit,
    onMinimize: () -> Unit,
    onMaximize: () -> Unit
) {
    WindowChrome(
        windowState = windowState,
        onClose = onClose,
        onMinimize = onMinimize,
        onMaximize = onMaximize
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF1E1E1E))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "System Settings",
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 24.dp)
                )

                DisplaySection()
                Spacer(modifier = Modifier.height(24.dp))
                
                InputSection()
                Spacer(modifier = Modifier.height(24.dp))
                
                ConnectionSection()
                Spacer(modifier = Modifier.height(24.dp))
                
                StatsSection()
            }
        }
    }
}

@Composable
private fun DisplaySection() {
    // Keyed to the live pipeline values so a change applied elsewhere (another
    // window, the QUIC side) is reflected here instead of showing stale numbers.
    var widthText by remember(DisplayService.CAPTURE_WIDTH) { mutableStateOf(DisplayService.CAPTURE_WIDTH.toString()) }
    val widthOwner = remember { Any() }
    var heightText by remember(DisplayService.CAPTURE_HEIGHT) { mutableStateOf(DisplayService.CAPTURE_HEIGHT.toString()) }
    val heightOwner = remember { Any() }
    var bitrateText by remember(DisplayService.BIT_RATE) { mutableStateOf((DisplayService.BIT_RATE / 1000).toString()) }
    val bitrateOwner = remember { Any() }
    var resolutionError by remember { mutableStateOf<String?>(null) }
    var bitrateError by remember { mutableStateOf<String?>(null) }

    SectionCard(title = "Display & Encoding") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = widthText,
                onValueChange = { widthText = it },
                label = { Text("Width", color = Color.Gray) },
                colors = TextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent
                ),
                modifier = Modifier.weight(1f)
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.changes.any { it.pressed }) {
                                    LocalInputDispatcher.registerComposeTarget(
                                        widthOwner,
                                        onText = { text -> widthText += text },
                                        onKey = { keyCode, pressed ->
                                            // Consume only Backspace; other keys fall
                                            // through to the view tree (caret movement).
                                            if (pressed && keyCode == KeyEvent.KEYCODE_DEL && widthText.isNotEmpty()) {
                                                widthText = widthText.dropLast(1)
                                                true
                                            } else {
                                                false
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
            )
            Text(" x ", color = Color.White, modifier = Modifier.padding(horizontal = 8.dp))
            OutlinedTextField(
                value = heightText,
                onValueChange = { heightText = it },
                label = { Text("Height", color = Color.Gray) },
                colors = TextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent
                ),
                modifier = Modifier.weight(1f)
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.changes.any { it.pressed }) {
                                    LocalInputDispatcher.registerComposeTarget(
                                        heightOwner,
                                        onText = { text -> heightText += text },
                                        onKey = { keyCode, pressed ->
                                            if (pressed && keyCode == KeyEvent.KEYCODE_DEL && heightText.isNotEmpty()) {
                                                heightText = heightText.dropLast(1)
                                                true
                                            } else {
                                                false
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
            )
            Spacer(modifier = Modifier.width(16.dp))
            Button(onClick = {
                val w = widthText.toIntOrNull()
                val h = heightText.toIntOrNull()
                when {
                    w == null || h == null ->
                        resolutionError = "Width and height must be numbers."
                    w !in 16..3840 || h !in 16..3840 ->
                        resolutionError = "Each dimension must be between 16 and 3840."
                    w % 2 != 0 || h % 2 != 0 ->
                        resolutionError = "H.264 needs even dimensions; ${w}x$h is rejected as-is."
                    else -> {
                        resolutionError = null
                        DisplayService.updateResolution(w, h)
                    }
                }
            }) {
                Text("Apply Resolution")
            }
        }

        resolutionError?.let {
            Text(it, color = Color(0xFFFF6B6B), fontSize = 12.sp)
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = bitrateText,
                onValueChange = { bitrateText = it },
                label = { Text("Bitrate (kbps)", color = Color.Gray) },
                colors = TextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent
                ),
                modifier = Modifier.weight(1f)
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.changes.any { it.pressed }) {
                                    LocalInputDispatcher.registerComposeTarget(
                                        bitrateOwner,
                                        onText = { text -> bitrateText += text },
                                        onKey = { keyCode, pressed ->
                                            if (pressed && keyCode == KeyEvent.KEYCODE_DEL && bitrateText.isNotEmpty()) {
                                                bitrateText = bitrateText.dropLast(1)
                                                true
                                            } else {
                                                false
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
            )
            Spacer(modifier = Modifier.width(16.dp))
            Button(onClick = {
                val kbps = bitrateText.toIntOrNull()
                when {
                    kbps == null -> bitrateError = "Bitrate must be a number."
                    kbps !in 100..100_000 ->
                        bitrateError = "Bitrate must be between 100 and 100000 kbps."
                    else -> {
                        bitrateError = null
                        DisplayService.updateBitrate(kbps)
                    }
                }
            }) {
                Text("Apply Bitrate")
            }
        }

        bitrateError?.let {
            Text(it, color = Color(0xFFFF6B6B), fontSize = 12.sp)
        }
    }
}

@Composable
private fun InputSection() {
    val context = LocalContext.current
    // Read once, then re-checked periodically: the accessibility service can be
    // enabled or disabled from system Settings while this window stays open.
    var isImeEnabled by remember { mutableStateOf(checkImeEnabled(context)) }
    var isA11yEnabled by remember { mutableStateOf(checkA11yEnabled(context)) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(10_000)
            isImeEnabled = checkImeEnabled(context)
            isA11yEnabled = checkA11yEnabled(context)
        }
    }

    SectionCard(title = "Input & Accessibility") {
        SettingRow(
            label = "AndroidDEX IME",
            status = if (isImeEnabled) "Enabled" else "Disabled",
            statusColor = if (isImeEnabled) Color.Green else Color.Red,
            actionText = "Open Settings",
            onAction = {
                val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        )
        Spacer(modifier = Modifier.height(8.dp))
        SettingRow(
            label = "Desktop Accessibility Service",
            status = if (isA11yEnabled) "Enabled" else "Disabled",
            statusColor = if (isA11yEnabled) Color.Green else Color.Red,
            actionText = "Open Settings",
            onAction = {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        )
    }
}

private fun checkImeEnabled(context: Context): Boolean = try {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
    imm?.enabledInputMethodList?.any { it.packageName == context.packageName } == true
} catch (e: Exception) {
    false
}

private fun checkA11yEnabled(context: Context): Boolean = try {
    com.example.androidhost.service.DesktopAccessibilityService.isEnabled(context)
} catch (e: Exception) {
    false
}

@Composable
private fun ConnectionSection() {
    var state by remember { mutableIntStateOf(QuicServer.getConnectionState()) }
    var isPaired by remember { mutableStateOf(SecurityBridge.isPaired()) }

    LaunchedEffect(Unit) {
        while (true) {
            state = QuicServer.getConnectionState()
            isPaired = SecurityBridge.isPaired()
            delay(1000)
        }
    }

    val stateText = when (state) {
        0 -> "Idle"
        1 -> "Pairing"
        2 -> "Authenticated"
        3 -> "Disconnected"
        else -> "Unknown"
    }

    SectionCard(title = "Connection") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("State: $stateText", color = Color.White, modifier = Modifier.weight(1f))
            if (isPaired) {
                Button(onClick = {
                    SecurityBridge.forgetPairing()
                    isPaired = SecurityBridge.isPaired()
                }) {
                    Text("Unpair")
                }
            } else {
                Text("Not Paired", color = Color.Gray)
            }
        }
    }
}

@Composable
private fun StatsSection() {
    var fps by remember { mutableStateOf(0) }
    var kbps by remember { mutableStateOf(0) }
    var droppedVideo by remember { mutableLongStateOf(0) }
    var droppedAudio by remember { mutableLongStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            val stats = DisplayService.encoderStats.latest.value
            fps = stats.fps
            kbps = stats.kilobitsPerSecond
            droppedVideo = QuicServer.getDroppedVideoFrames()
            droppedAudio = QuicServer.getDroppedAudioFrames()
            delay(1000)
        }
    }

    SectionCard(title = "Live Stats") {
        Column {
            Text("Encoder: $fps fps, $kbps kbps", color = Color.White)
            Spacer(modifier = Modifier.height(4.dp))
            Text("Dropped Frames: Video=$droppedVideo, Audio=$droppedAudio", color = Color.White)
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                color = Color.LightGray,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            content()
        }
    }
}

@Composable
private fun SettingRow(label: String, status: String, statusColor: Color, actionText: String, onAction: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = Color.White)
            Text(status, color = statusColor, fontSize = 12.sp)
        }
        Button(onClick = onAction) {
            Text(actionText)
        }
    }
}
