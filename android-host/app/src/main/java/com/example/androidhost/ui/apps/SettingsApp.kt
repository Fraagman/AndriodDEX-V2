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
    var widthText by remember { mutableStateOf(DisplayService.CAPTURE_WIDTH.toString()) }
    var heightText by remember { mutableStateOf(DisplayService.CAPTURE_HEIGHT.toString()) }
    var bitrateText by remember { mutableStateOf((DisplayService.BIT_RATE / 1000).toString()) }

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
            )
            Spacer(modifier = Modifier.width(16.dp))
            Button(onClick = {
                val w = widthText.toIntOrNull()
                val h = heightText.toIntOrNull()
                if (w != null && h != null && w > 0 && h > 0) {
                    DisplayService.updateResolution(w, h)
                }
            }) {
                Text("Apply Resolution")
            }
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
            )
            Spacer(modifier = Modifier.width(16.dp))
            Button(onClick = {
                val kbps = bitrateText.toIntOrNull()
                if (kbps != null && kbps > 0) {
                    DisplayService.updateBitrate(kbps)
                }
            }) {
                Text("Apply Bitrate")
            }
        }
    }
}

@Composable
private fun InputSection() {
    val context = LocalContext.current
    val isImeEnabled = remember { 
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_INPUT_METHODS)?.contains("com.example.androidhost") == true 
    }
    val isA11yEnabled = remember { 
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)?.contains("com.example.androidhost") == true 
    }

    SectionCard(title = "Input & Accessibility") {
        SettingRow(
            label = "AndroidDEX IME",
            status = if (isImeEnabled) "Enabled" else "Disabled",
            statusColor = if (isImeEnabled) Color.Green else Color.Red,
            actionText = "Open Settings",
            onAction = { context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        )
        Spacer(modifier = Modifier.height(8.dp))
        SettingRow(
            label = "Desktop Accessibility Service",
            status = if (isA11yEnabled) "Enabled" else "Disabled",
            statusColor = if (isA11yEnabled) Color.Green else Color.Red,
            actionText = "Open Settings",
            onAction = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        )
    }
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
