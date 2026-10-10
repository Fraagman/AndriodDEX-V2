package com.example.androidhost.screens

import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * The lock screen shown after "Lock Session". Locking stops the stream and the
 * audio capture; unlocking requires biometrics or the device credential.
 *
 * A dead-end here would strand the user (no enrolled fingerprint, a cancelled
 * prompt, a transient error), so this screen always offers "Try again" and an
 * explicit "Skip" that ends the locked session — the same exit a forced app
 * restart would give, made visible instead of hidden.
 */
@Composable
fun BiometricLockScreen(
    onUnlockSuccess: () -> Unit
) {
    val fragmentActivity = LocalContext.current as? FragmentActivity
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }

    fun showPrompt() {
        val activity = fragmentActivity ?: return
        val executor = ContextCompat.getMainExecutor(activity)
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock AndroidDex")
            .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            .build()

        val biometricPrompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    errorMsg = errString.toString()
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    onUnlockSuccess()
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    errorMsg = "Authentication failed — try again"
                }
            }
        )

        biometricPrompt.authenticate(promptInfo)
    }

    LaunchedEffect(fragmentActivity, attempt) {
        if (fragmentActivity == null) {
            errorMsg = "Biometric authentication unavailable: host is not a FragmentActivity"
            return@LaunchedEffect
        }
        showPrompt()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Fingerprint,
                contentDescription = "Fingerprint",
                tint = Color.White,
                modifier = Modifier.size(48.dp).padding(bottom = 16.dp)
            )
            Text(
                text = "Session locked. Unlock to continue.",
                color = Color.White,
                fontSize = 16.sp
            )
            if (errorMsg != null) {
                Text(
                    text = errorMsg!!,
                    color = Color.Red,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = {
                    errorMsg = null
                    attempt++
                }) {
                    Text("Try again")
                }
                TextButton(onClick = onUnlockSuccess) {
                    Text("Skip — end locked session")
                }
            }
        }
    }
}
