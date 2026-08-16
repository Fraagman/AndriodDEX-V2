package com.example.androidhost.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.input.KeyboardType
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
 * Pairing screen.
 *
 * The PIN is generated and displayed by the PC; this screen only collects it. The check
 * itself happens in the native server, which derives a pre-shared key from the digits and
 * then requires the PC to prove it derived the same one. Nothing here can grant access on
 * its own — entering the wrong PIN leaves the connection unauthenticated no matter what
 * this screen does.
 */
@Composable
fun PinEntryScreen(
    onPinSuccess: () -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }
    var awaitingPin by remember { mutableStateOf(false) }
    var alreadyPaired by remember { mutableStateOf(false) }
    var verifying by remember { mutableStateOf(false) }
    val shakeOffset = remember { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    // Platinum text
    val platinumColor = Color(0xFFE5E4E2)
    val electricBlue = Color(0xFF7DF9FF)

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    // Follow the native server: it tells us when a PC is mid-pairing and waiting for the
    // user, and when a session has authenticated so this screen can step out of the way.
    LaunchedEffect(Unit) {
        while (true) {
            awaitingPin = SecurityBridge.isAwaitingPin()
            alreadyPaired = SecurityBridge.isPaired()
            if (QuicServer.getConnectionState() == STATE_AUTHENTICATED) {
                onPinSuccess()
                return@LaunchedEffect
            }
            delay(300)
        }
    }

    val statusText = when {
        verifying -> "Checking with your PC…"
        awaitingPin -> "Enter the PIN shown on your PC"
        alreadyPaired -> "Waiting for your paired PC to connect…"
        else -> "Waiting for a PC to connect…"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayer {
                translationX = shakeOffset.value
            }
        ) {
            Text(
                text = statusText,
                color = platinumColor,
                fontSize = 18.sp,
                modifier = Modifier.padding(bottom = 32.dp)
            )

            // Hidden text field for system keyboard
            BasicTextField(
                value = pin,
                onValueChange = {
                    if (it.length <= 6 && it.all { char -> char.isDigit() }) {
                        pin = it
                        isError = false
                    }
                },
                modifier = Modifier
                    .size(1.dp)
                    .focusRequester(focusRequester),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 32.dp)
            ) {
                for (i in 0 until 6) {
                    Box(
                        modifier = Modifier
                            .size(48.dp, 64.dp)
                            .border(
                                1.dp,
                                when {
                                    isError -> Color.Red
                                    awaitingPin -> Color.White
                                    else -> Color.DarkGray
                                },
                                RoundedCornerShape(4.dp)
                            )
                            .background(Color.Transparent),
                        contentAlignment = Alignment.Center
                    ) {
                        if (i < pin.length) {
                            Box(
                                modifier = Modifier
                                    .size(16.dp)
                                    .background(electricBlue, CircleShape)
                            )
                        }
                    }
                }
            }

            if (isError) {
                Text(
                    text = "Incorrect PIN",
                    color = Color.Red,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            } else {
                Spacer(modifier = Modifier.height(34.dp)) // To keep layout stable
            }

            Button(
                onClick = {
                    val submitted = pin
                    verifying = true
                    coroutineScope.launch {
                        // verifyPin blocks until the PC's auth token arrives, so it must
                        // not run on the main thread.
                        val accepted = withContext(Dispatchers.IO) {
                            SecurityBridge.verifyPin(submitted)
                        }
                        verifying = false
                        if (accepted) {
                            android.util.Log.d("PinEntryScreen", "Pairing accepted")
                            onPinSuccess()
                        } else {
                            android.util.Log.d("PinEntryScreen", "Pairing rejected")
                            isError = true
                            pin = ""
                            shakeOffset.animateTo(
                                targetValue = 0f,
                                animationSpec = keyframes {
                                    durationMillis = 200
                                    -8f at 50
                                    8f at 100
                                    -8f at 150
                                    0f at 200
                                }
                            )
                        }
                    }
                },
                enabled = pin.length == 6 && awaitingPin && !verifying,
                colors = ButtonDefaults.buttonColors(
                    containerColor = electricBlue,
                    contentColor = Color.Black,
                    disabledContainerColor = Color.DarkGray
                )
            ) {
                Text(if (verifying) "Checking…" else "Verify")
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Opens the phone's own control panel. It grants a PC nothing: an unpaired PC
            // still has to complete the PIN flow, and a paired one authenticates on its
            // own without this screen.
            TextButton(
                onClick = { onPinSuccess() }
            ) {
                Text("Open control panel", color = platinumColor)
            }

            if (alreadyPaired) {
                TextButton(
                    onClick = {
                        SecurityBridge.forgetPairing()
                        alreadyPaired = false
                    }
                ) {
                    Text("Forget paired PC", color = Color(0xFF8B949E))
                }
            }
        }
    }
}
