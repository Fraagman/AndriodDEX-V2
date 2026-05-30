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
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.example.androidhost.service.TetheringService

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
            val currentScreen = androidx.compose.runtime.remember { 
                androidx.compose.runtime.mutableStateOf(Screen.PIN) 
            }

            when (currentScreen.value) {
                Screen.PIN -> com.example.androidhost.screens.PinEntryScreen(
                    onPinSuccess = { currentScreen.value = Screen.DESKTOP }
                )
                Screen.LOCK -> com.example.androidhost.screens.BiometricLockScreen(
                    onUnlockSuccess = { currentScreen.value = Screen.DESKTOP }
                )
                Screen.DESKTOP -> DesktopShell(
                    onLockSession = { currentScreen.value = Screen.LOCK },
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

enum class Screen {
    PIN, LOCK, DESKTOP
}
