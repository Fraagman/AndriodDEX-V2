package com.example.androidhost

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import com.example.androidhost.service.TetheringService
import com.example.androidhost.service.AudioCaptureService
import com.example.androidhost.security.SecurityBridge
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

class MainActivity : FragmentActivity() {
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Continue regardless of permission. Consequence of a denial: with
        // POST_NOTIFICATIONS denied, the foreground-service notifications are
        // silently suppressed (the services still run, but invisibly); with
        // RECORD_AUDIO denied, the System Audio toggle simply fails at capture
        // time. Streaming and input never need either permission.
    }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val intent = Intent(this, com.example.androidhost.service.AudioCaptureService::class.java).apply {
                putExtra("RESULT_CODE", result.resultCode)
                putExtra("DATA", result.data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } else {
            com.example.androidhost.service.AudioCaptureService.isServiceRunning.value = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AudioCaptureService.tryRestoreMutedVolume(this)
        enableEdgeToEdge()
        setContent {
            // rememberSaveable: configuration changes and process recreation must not
            // reset the screen to PAIRING mid-session.
            val currentScreen = rememberSaveable {
                mutableStateOf(if (SecurityBridge.isPaired()) Screen.DESKTOP else Screen.PAIRING)
            }

            val ctx = LocalContext.current
            LaunchedEffect(currentScreen.value) {
                if (currentScreen.value == Screen.DESKTOP) {
                    val intent = Intent(ctx, com.example.androidhost.service.DisplayService::class.java)
                    ContextCompat.startForegroundService(ctx, intent)
                }
            }

            when (currentScreen.value) {
                Screen.PAIRING -> com.example.androidhost.screens.PairingConfirmationScreen(
                    onPairingSuccess = { currentScreen.value = Screen.DESKTOP }
                )
                Screen.LOCK -> com.example.androidhost.screens.BiometricLockScreen(
                    onUnlockSuccess = {
                        currentScreen.value = Screen.DESKTOP
                    }
                )
                Screen.DESKTOP -> ControlPanel(
                    onLockSession = {
                        // Never lock without a working unlock method: the lock screen
                        // would be a dead end.
                        val bm = androidx.biometric.BiometricManager.from(ctx)
                        val canAuth = bm.canAuthenticate(
                            androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                                androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
                        )
                        if (canAuth != androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS) {
                            android.widget.Toast.makeText(
                                ctx,
                                "Cannot lock: no enrolled fingerprint or device credential",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                            return@ControlPanel
                        }
                        // Locking stops the stream (DisplayService) AND the audio
                        // capture — a locked device must not keep streaming to a PC.
                        ctx.stopService(Intent(ctx, com.example.androidhost.service.DisplayService::class.java))
                        ctx.stopService(Intent(ctx, com.example.androidhost.service.AudioCaptureService::class.java))
                        com.example.androidhost.service.AudioCaptureService.isServiceRunning.value = false
                        currentScreen.value = Screen.LOCK
                    },
                    onRequestAudioCapture = { enabled ->
                        if (enabled) {
                            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                            mediaProjectionLauncher.launch(manager.createScreenCaptureIntent())
                        } else {
                            val intent = Intent(this, com.example.androidhost.service.AudioCaptureService::class.java)
                            stopService(intent)
                            com.example.androidhost.service.AudioCaptureService.isServiceRunning.value = false
                        }
                    }
                )
            }
        }

        val permissionsToRequest = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        }
        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }

        try {
            startService(Intent(this, TetheringService::class.java))
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        try {
            com.example.androidhost.service.InputManager.startPolling(filesDir.absolutePath)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (com.example.androidhost.security.SecurityBridge.isPaired()) {
            try {
                ContextCompat.startForegroundService(this, Intent(this, com.example.androidhost.service.DisplayService::class.java))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
/**
 * Control Panel shown on the phone's physical screen.
 * The phone screen is NOT a mirror of the desktop — it's a control surface.
 * The real desktop is rendered on the VirtualDisplay, which feeds the H.264 encoder directly.
 */
@Composable
fun ControlPanel(
    onLockSession: () -> Unit = {},
    onRequestAudioCapture: (Boolean) -> Unit = {}
) {
    var quicState by remember { mutableStateOf(0) }
    var framesSent by remember { mutableStateOf(0) }
    var encoderFps by remember { mutableStateOf(0) }
    var encoderKbps by remember { mutableStateOf(0) }
    var droppedVideo by remember { mutableLongStateOf(0) }
    val isAudioCapturing by com.example.androidhost.service.AudioCaptureService.isServiceRunning.collectAsState()
    val ctx = LocalContext.current

    LaunchedEffect(Unit) {
        while (true) {
            quicState = com.example.androidhost.quic.QuicServer.getConnectionState()
            framesSent = com.example.androidhost.network.FrameSender.framesSent.get()
            val stats = com.example.androidhost.service.DisplayService.encoderStats.latest.value
            encoderFps = stats.fps
            encoderKbps = stats.kilobitsPerSecond
            droppedVideo = com.example.androidhost.quic.QuicServer.getDroppedVideoFrames()
            // NOTE: the service itself pauses its pipeline when the client leaves
            // (DisplayService's client watch) and resumes when it returns. Stopping
            // the whole service from here would kill that watch, and the receiver
            // could never reconnect without relaunching the app.
            delay(1000)
        }
    }

    val statusText = when {
        com.example.androidhost.quic.QuicServer.serverStartFailed -> "Server failed to start"
        quicState == 0 -> "Idle"
        quicState == 1 -> "Pairing"
        quicState == 2 -> "Connected"
        quicState == 3 -> "Disconnected"
        else -> "Unknown"
    }
    val statusColor = when {
        com.example.androidhost.quic.QuicServer.serverStartFailed -> Color(0xFFFF5252)
        quicState == 2 -> Color(0xFF3DDC84)
        quicState == 1 -> Color(0xFFFFB300)
        quicState == 3 -> Color(0xFFFF5252)
        else -> Color(0xFF8B949E)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0F14))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(Color(0xFF1C6DFF), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("A", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text("AndroidDex", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("Desktop control surface", color = Color(0xFF8B949E), fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Connection status card
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF151B23), RoundedCornerShape(16.dp))
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(statusColor, RoundedCornerShape(5.dp))
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(statusText, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    when {
                        com.example.androidhost.quic.QuicServer.serverStartFailed ->
                            "Port 4433 busy or storage unusable — reinstall or reboot the device"
                        quicState == 2 -> "Streaming desktop to receiver"
                        else -> "Waiting for a receiver to connect"
                    },
                    color = Color(0xFF8B949E), fontSize = 12.sp
                )
            }
            if (framesSent > 0) {
                Column(horizontalAlignment = Alignment.End) {
                    Text("$framesSent", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("frames", color = Color(0xFF8B949E), fontSize = 11.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Stream telemetry
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatTile("Encoder", "$encoderFps fps", Modifier.weight(1f))
            StatTile("Bitrate", "$encoderKbps kbps", Modifier.weight(1f))
            StatTile("Dropped", "$droppedVideo", Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Session actions
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF151B23), RoundedCornerShape(16.dp))
                .padding(16.dp)
        ) {
            Text("Session", color = Color(0xFF8B949E), fontSize = 12.sp)
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Lock Session", color = Color.White, fontSize = 15.sp)
                    Text("Stops streaming and locks this device", color = Color(0xFF8B949E), fontSize = 11.sp)
                }
                Button(
                    onClick = onLockSession,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1C6DFF)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Lock")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = Color(0xFF222933))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("System Audio", color = Color.White, fontSize = 15.sp)
                    Text(
                        if (isAudioCapturing) "Captured and streamed to the receiver" else "Not captured",
                        color = Color(0xFF8B949E), fontSize = 11.sp
                    )
                    if (isAudioCapturing) {
                        Text(
                            "Phone playback is muted while streaming; DRM apps block capture by OS design.",
                            color = Color(0xFF6B7280), fontSize = 10.sp
                        )
                    }
                }
                Switch(
                    checked = isAudioCapturing,
                    onCheckedChange = { onRequestAudioCapture(it) }
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(Color(0xFF151B23), RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Text(label, color = Color(0xFF8B949E), fontSize = 11.sp)
        Spacer(modifier = Modifier.height(4.dp))
        Text(value, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

enum class Screen {
    PAIRING, LOCK, DESKTOP
}
