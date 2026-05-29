package com.example.androidhost

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.example.androidhost.service.TetheringService

class MainActivity : FragmentActivity() {
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        // Continue regardless of permission, we just won't show notifications if denied
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
                    onLockSession = { currentScreen.value = Screen.LOCK }
                )
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        startService(Intent(this, TetheringService::class.java))
        
        com.example.androidhost.network.LocalInputServer.start()
    }
}

enum class Screen {
    PIN, LOCK, DESKTOP
}
