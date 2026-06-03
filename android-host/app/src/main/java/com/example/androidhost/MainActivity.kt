package com.example.androidhost

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.androidhost.service.TetheringService
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Continue regardless of permission, we just won't show notifications or capture audio if denied
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
        enableEdgeToEdge()
        setContent {
            val currentScreen = remember { 
                mutableStateOf(Screen.PIN) 
            }

            val ctx = LocalContext.current
            LaunchedEffect(currentScreen.value) {
                if (currentScreen.value == Screen.DESKTOP) {
                    val intent = Intent(ctx, com.example.androidhost.service.DisplayService::class.java)
                    ContextCompat.startForegroundService(ctx, intent)
                }
            }

            when (currentScreen.value) {
                Screen.PIN -> com.example.androidhost.screens.PinEntryScreen(
                    onPinSuccess = { currentScreen.value = Screen.DESKTOP }
                )
                Screen.LOCK -> com.example.androidhost.screens.BiometricLockScreen(
                    onUnlockSuccess = { currentScreen.value = Screen.DESKTOP }
                )
                Screen.DESKTOP -> ControlPanel(
                    onLockSession = { 
                        ctx.stopService(Intent(ctx, com.example.androidhost.service.DisplayService::class.java))
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

        startService(Intent(this, TetheringService::class.java))
        
        com.example.androidhost.network.LocalInputServer.start()
        com.example.androidhost.quic.QuicServer.startServer(4433, filesDir.absolutePath)
    }
}

/**
 * Control Panel shown on the phone's physical screen.
 * The phone screen is NOT a mirror of the desktop — it's a control surface.
 * The real desktop is rendered on the VirtualDisplay (captured by ImageReader and streamed to the PC).
 */
@Composable
fun ControlPanel(
    onLockSession: () -> Unit = {},
    onRequestAudioCapture: (Boolean) -> Unit = {}
) {
    var quicState by remember { mutableStateOf(0) }
    var framesSent by remember { mutableStateOf(0) }
    var wasConnected by remember { mutableStateOf(false) }
    val isAudioCapturing by com.example.androidhost.service.AudioCaptureService.isServiceRunning.collectAsState()
    val ctx = LocalContext.current

    LaunchedEffect(Unit) {
        while (true) {
            quicState = com.example.androidhost.quic.QuicServer.getConnectionState()
            framesSent = com.example.androidhost.network.FrameSender.framesSent.get()
            
            if (quicState == 2) {
                wasConnected = true
            } else if (wasConnected && quicState != 2) {
                ctx.stopService(Intent(ctx, com.example.androidhost.service.DisplayService::class.java))
                wasConnected = false
            }
            
            delay(1000)
        }
    }

    val statusText = when (quicState) {
        0 -> "Idle"
        1 -> "Pairing..."
        2 -> "Connected"
        3 -> "Disconnected"
        else -> "Unknown"
    }
    val statusColor = when (quicState) {
        2 -> Color(0xFF4CAF50)
        1 -> Color(0xFFFFC107)
        3 -> Color(0xFFF44336)
        else -> Color.Gray
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D1117))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            Spacer(modifier = Modifier.height(48.dp))

            Text(
                text = "AndroidDex",
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Control Panel",
                color = Color(0xFF8B949E),
                fontSize = 14.sp
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Status Card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF161B22), shape = RoundedCornerShape(12.dp))
                    .padding(20.dp)
            ) {
                Text(text = "Connection Status", color = Color(0xFF8B949E), fontSize = 12.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .background(statusColor, shape = RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(text = statusText, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                if (framesSent > 0) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = "Frames sent: $framesSent", color = Color(0xFF8B949E), fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Controls Card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF161B22), shape = RoundedCornerShape(12.dp))
                    .padding(20.dp)
            ) {
                Text(text = "Controls", color = Color(0xFF8B949E), fontSize = 12.sp)
                Spacer(modifier = Modifier.height(16.dp))

                // Lock Session
                Button(
                    onClick = onLockSession,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF21262D))
                ) {
                    Text("Lock Session", color = Color.White)
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Audio Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "System Audio Capture",
                        color = Color.White,
                        fontSize = 14.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = isAudioCapturing,
                        onCheckedChange = { onRequestAudioCapture(it) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Touchpad placeholder
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color(0xFF161B22), shape = RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Touchpad Area",
                    color = Color(0xFF484F58),
                    fontSize = 14.sp
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

enum class Screen {
    PIN, LOCK, DESKTOP
}
