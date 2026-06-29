package com.example.androidhost.ui.components

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

enum class KeyboardSetupState {
    IDLE,
    WAITING_FOR_SETTINGS_RETURN,
    READY_TO_PICK
}

@Composable
fun InputSetupPanel() {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var setupState by remember { mutableStateOf(KeyboardSetupState.IDLE) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (setupState == KeyboardSetupState.WAITING_FOR_SETTINGS_RETURN) {
                    setupState = KeyboardSetupState.READY_TO_PICK
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF161B22), shape = RoundedCornerShape(12.dp))
            .padding(20.dp)
    ) {
        Text(text = "Input Setup", color = Color(0xFF8B949E), fontSize = 12.sp)
        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                ctx.startActivity(intent)
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF21262D))
        ) {
            Text("Enable Mouse Clicks (Accessibility)", color = Color.White)
        }

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = {
                val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                ctx.startActivity(intent)
                setupState = KeyboardSetupState.WAITING_FOR_SETTINGS_RETURN
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF21262D))
        ) {
            Text("Step 1: Enable Keyboard in Settings", color = Color.White)
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        
        Button(
            onClick = {
                val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showInputMethodPicker()
                setupState = KeyboardSetupState.IDLE // Reset state after showing picker
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = setupState == KeyboardSetupState.READY_TO_PICK,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF21262D),
                disabledContainerColor = Color(0xFF21262D).copy(alpha = 0.5f),
                disabledContentColor = Color.White.copy(alpha = 0.5f)
            )
        ) {
            Text("Step 2: Select AndroidDex Keyboard", color = Color.White)
        }
    }
}
