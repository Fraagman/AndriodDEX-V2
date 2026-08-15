package com.example.androidhost.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.*
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun AnimatedWaveform(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "waveform")
    val heights = listOf(0.3f, 0.6f, 0.9f, 0.5f, 0.7f, 0.4f).mapIndexed { idx, initialHeight ->
        transition.animateFloat(
            initialValue = initialHeight * 0.2f,
            targetValue = initialHeight,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 400 + (idx * 50), easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "bar_$idx"
        )
    }

    androidx.compose.foundation.Canvas(modifier = modifier) {
        val barCount = heights.size
        val gap = 2.dp.toPx()
        val totalGap = gap * (barCount - 1)
        val barWidth = (size.width - totalGap) / barCount
        
        heights.forEachIndexed { index, animState ->
            val barHeight = size.height * animState.value
            val x = index * (barWidth + gap)
            val y = (size.height - barHeight) / 2
            drawRect(
                color = Color.Green,
                topLeft = androidx.compose.ui.geometry.Offset(x, y),
                size = androidx.compose.ui.geometry.Size(barWidth, barHeight)
            )
        }
    }
}

@Composable
fun Taskbar(
    quicState: Int, // 0 = Idle, 1 = Pairing, 2 = Connected, 3 = Disconnected
    isAudioCapturing: Boolean,
    computeState: com.example.androidhost.service.ComputeState,
    onLauncherClick: () -> Unit,
    onSettingsClick: () -> Unit,
    /**
     * Whether the optional accessibility service is enabled. When false the
     * back/home/recents buttons are hidden entirely — no nag, no disabled state.
     */
    showNavButtons: Boolean = false,
    onBackClick: () -> Unit = {},
    onHomeClick: () -> Unit = {},
    onRecentsClick: () -> Unit = {},
    /** Whether to offer the keyboard picker, i.e. our IME is not the selected one. */
    showKeyboardPrompt: Boolean = false,
    onKeyboardPromptClick: () -> Unit = {}
) {
    var currentTime by remember { mutableStateOf(getCurrentTime()) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            currentTime = getCurrentTime()
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(Color(0xFF141414))
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left: Launcher Icon
        Icon(
            imageVector = Icons.Default.Menu,
            contentDescription = "Launcher",
            tint = Color.White,
            modifier = Modifier
                .size(24.dp)
                .clickable { onLauncherClick() }
        )

        // Global navigation. Present only while the optional accessibility service is
        // enabled — the shell is fully usable without these.
        if (showNavButtons) {
            Spacer(modifier = Modifier.width(16.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = Color.White,
                modifier = Modifier
                    .size(22.dp)
                    .clickable { onBackClick() }
            )
            Spacer(modifier = Modifier.width(12.dp))
            Icon(
                imageVector = Icons.Default.Home,
                contentDescription = "Home",
                tint = Color.White,
                modifier = Modifier
                    .size(22.dp)
                    .clickable { onHomeClick() }
            )
            Spacer(modifier = Modifier.width(12.dp))
            Icon(
                imageVector = Icons.Default.Layers,
                contentDescription = "Recents",
                tint = Color.White,
                modifier = Modifier
                    .size(22.dp)
                    .clickable { onRecentsClick() }
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // Center: Ticking Time
        Text(
            text = currentTime,
            color = Color.White,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace
        )

        Spacer(modifier = Modifier.weight(1f))

        // Right: Connection Status & Waveform & Settings
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Offered only until the AndroidDex keyboard is selected. The picker is a
            // system dialog and appears on the phone's own screen, not here.
            if (showKeyboardPrompt) {
                Icon(
                    imageVector = Icons.Default.Keyboard,
                    contentDescription = "Enable AndroidDex keyboard",
                    tint = Color(0xFF8B949E),
                    modifier = Modifier
                        .size(20.dp)
                        .clickable { onKeyboardPromptClick() }
                )
                Spacer(modifier = Modifier.width(12.dp))
            }

            if (isAudioCapturing) {
                Text(
                    text = "LIVE",
                    color = Color.Green,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(end = 4.dp)
                )
                AnimatedWaveform(
                    modifier = Modifier
                        .width(20.dp)
                        .height(14.dp)
                        .padding(end = 8.dp)
                )
            }

            val statusText = when (quicState) {
                2 -> "QUIC: Connected"
                1 -> "QUIC: Pairing"
                3 -> "QUIC: Offline"
                else -> "QUIC: Searching..."
            }
            Text(
                text = statusText,
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier.padding(end = 8.dp)
            )
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        color = when (quicState) {
                            2 -> Color.Green
                            1 -> Color(0xFFFF9800) // Orange
                            3 -> Color.Red
                            else -> Color.Yellow
                        },
                        shape = CircleShape
                    )
            )

            Spacer(modifier = Modifier.width(16.dp))

            // Compute Engine Status
            val computeStatusText = when (computeState) {
                com.example.androidhost.service.ComputeState.RUNNING -> "Compute: Running"
                com.example.androidhost.service.ComputeState.STARTING -> "Compute: Starting"
                else -> "Compute: Ready"
            }

            Text(
                text = computeStatusText,
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier.padding(end = 8.dp)
            )
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        color = when (computeState) {
                            com.example.androidhost.service.ComputeState.RUNNING -> Color.Green
                            com.example.androidhost.service.ComputeState.STARTING -> Color.Yellow
                            else -> Color.Gray
                        },
                        shape = CircleShape
                    )
            )

            Spacer(modifier = Modifier.width(16.dp))

            // Settings Button
            IconButton(
                onClick = onSettingsClick,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = Color.White
                )
            }
        }
    }
}

private fun getCurrentTime(): String {
    val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
    return formatter.format(Date())
}
