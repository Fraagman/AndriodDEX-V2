package com.example.androidhost.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.androidhost.quic.QuicServer
import com.example.androidhost.security.SecurityBridge

/** Mirrors STATE_AUTHENTICATED in the native server. */
private const val STATE_AUTHENTICATED = 2

/**
 * Protocol v2 Pairing Confirmation Screen.
 *
 * Instead of typing a PIN, both devices compute a 6-digit Short Authentication String (SAS)
 * via ECDH and TLS channel binding. The user compares the code on this phone screen with
 * the code on the PC screen.
 *
 * Two actions are provided with equal visual prominence:
 * - "Codes match" (confirms pairing and persists trust data)
 * - "They're different" (rejects pairing, discards ephemeral secrets, stores nothing)
 */
@Composable
fun PairingConfirmationScreen(
    onPairingSuccess: () -> Unit
) {
    var awaitingConfirmation by remember { mutableStateOf(false) }
    var pendingSas by remember { mutableStateOf<String?>(null) }
    var alreadyPaired by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    val platinumColor = Color(0xFFE5E4E2)
    val electricBlue = Color(0xFF7DF9FF)
    val rejectRed = Color(0xFFE53935)
    val confirmGreen = Color(0xFF2E7D32)

    LaunchedEffect(Unit) {
        while (true) {
            awaitingConfirmation = SecurityBridge.isAwaitingConfirmation()
            pendingSas = SecurityBridge.getPendingSas()
            alreadyPaired = SecurityBridge.isPaired()
            if (QuicServer.getConnectionState() == STATE_AUTHENTICATED) {
                onPairingSuccess()
                return@LaunchedEffect
            }
            delay(200)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (awaitingConfirmation && pendingSas != null) {
                val sas = pendingSas ?: ""
                val formattedSas = if (sas.length == 6) {
                    "${sas.substring(0, 3)}  ${sas.substring(3, 6)}"
                } else {
                    sas
                }

                Text(
                    text = "Confirm Pairing Code",
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                Text(
                    text = "Compare the 6-digit code below with the code shown on your PC. Do they match?",
                    color = platinumColor,
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 28.dp)
                )

                // Prominent SAS display card
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .background(Color(0xFF161B22), shape = RoundedCornerShape(12.dp))
                        .border(1.dp, Color(0xFF30363D), shape = RoundedCornerShape(12.dp))
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = formattedSas,
                        color = electricBlue,
                        fontSize = 44.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 4.sp
                    )
                }

                Spacer(modifier = Modifier.height(28.dp))

                if (statusMessage != null) {
                    Text(
                        text = statusMessage ?: "",
                        color = if (isError) Color.Red else platinumColor,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                }

                // Two equally prominent actions: "They're different" and "Codes match"
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Reject button - equally large and visible
                    Button(
                        onClick = {
                            submitting = true
                            statusMessage = "Rejecting pairing…"
                            isError = true
                            coroutineScope.launch {
                                withContext(Dispatchers.IO) {
                                    SecurityBridge.confirmPairing(false)
                                }
                                submitting = false
                                statusMessage = "Pairing rejected. Secrets discarded."
                            }
                        },
                        enabled = !submitting,
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = rejectRed,
                            contentColor = Color.White,
                            disabledContainerColor = Color.DarkGray
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "They're different",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // Confirm button - equally large and visible
                    Button(
                        onClick = {
                            submitting = true
                            statusMessage = "Confirming with PC…"
                            isError = false
                            coroutineScope.launch {
                                val accepted = withContext(Dispatchers.IO) {
                                    SecurityBridge.confirmPairing(true)
                                }
                                submitting = false
                                if (accepted) {
                                    onPairingSuccess()
                                } else {
                                    isError = true
                                    statusMessage = "Pairing failed or timed out."
                                }
                            }
                        },
                        enabled = !submitting,
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = confirmGreen,
                            contentColor = Color.White,
                            disabledContainerColor = Color.DarkGray
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = if (submitting) "Verifying…" else "Codes match",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            } else {
                // Idle or waiting states
                val headerText = when {
                    alreadyPaired -> "Paired PC"
                    else -> "AndroidDEX Pairing"
                }
                val infoText = when {
                    alreadyPaired -> "Waiting for your paired PC to connect…\nEnsure USB tethering is active."
                    else -> "Waiting for a PC to start pairing…\nConnect your phone via USB and enable USB tethering."
                }

                Text(
                    text = headerText,
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                Text(
                    text = infoText,
                    color = platinumColor,
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 32.dp)
                )

                if (statusMessage != null) {
                    Text(
                        text = statusMessage ?: "",
                        color = if (isError) Color.Red else platinumColor,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Open control panel (always available to navigate)
            TextButton(
                onClick = { onPairingSuccess() }
            ) {
                Text("Open control panel", color = platinumColor)
            }

            // Forget paired PC
            if (alreadyPaired) {
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    onClick = {
                        SecurityBridge.forgetPairing()
                        alreadyPaired = false
                        statusMessage = "Paired PC forgotten. Re-pairing required."
                        isError = false
                    }
                ) {
                    Text("Forget paired PC", color = Color(0xFF8B949E))
                }
            }
        }
    }
}
