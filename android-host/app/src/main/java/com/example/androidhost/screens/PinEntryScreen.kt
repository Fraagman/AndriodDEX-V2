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
import kotlinx.coroutines.launch
import com.example.androidhost.security.SecurityBridge

@Composable
fun PinEntryScreen(
    onPinSuccess: () -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }
    val shakeOffset = remember { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    // Platinum text
    val platinumColor = Color(0xFFE5E4E2)
    val electricBlue = Color(0xFF7DF9FF)

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
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
                text = "Enter the PIN shown on your PC",
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
                            .border(1.dp, if (isError) Color.Red else Color.White, RoundedCornerShape(4.dp))
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
                    if (SecurityBridge.verifyPin(pin)) {
                        onPinSuccess()
                    } else {
                        isError = true
                        pin = ""
                        coroutineScope.launch {
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
                enabled = pin.length == 6,
                colors = ButtonDefaults.buttonColors(
                    containerColor = electricBlue,
                    contentColor = Color.Black,
                    disabledContainerColor = Color.DarkGray
                )
            ) {
                Text("Verify")
            }
        }
    }
}
