package com.example.androidhost.ui.components

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
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
import rikka.shizuku.Shizuku

enum class ShizukuState {
    NOT_RUNNING,
    PERMISSION_DENIED,
    GRANTED
}

@Composable
fun InputSetupPanel() {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var shizukuState by remember { mutableStateOf(ShizukuState.NOT_RUNNING) }

    val updateShizukuState = {
        shizukuState = when {
            !Shizuku.pingBinder() -> ShizukuState.NOT_RUNNING
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> ShizukuState.PERMISSION_DENIED
            else -> ShizukuState.GRANTED
        }
    }

    DisposableEffect(lifecycleOwner) {
        val binderReceivedListener = Shizuku.OnBinderReceivedListener { updateShizukuState() }
        val binderDeadListener = Shizuku.OnBinderDeadListener { updateShizukuState() }
        val permissionResultListener = Shizuku.OnRequestPermissionResultListener { _, _ -> updateShizukuState() }

        Shizuku.addBinderReceivedListener(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)

        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                updateShizukuState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        updateShizukuState()

        onDispose {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            Shizuku.removeRequestPermissionResultListener(permissionResultListener)
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF161B22), shape = RoundedCornerShape(12.dp))
            .padding(20.dp)
    ) {
        Text(text = "Input Setup (Shizuku)", color = Color(0xFF8B949E), fontSize = 12.sp)
        Spacer(modifier = Modifier.height(16.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF2D2121), shape = RoundedCornerShape(8.dp))
                .padding(16.dp)
        ) {
            when (shizukuState) {
                ShizukuState.NOT_RUNNING -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Info",
                            tint = Color(0xFFE5C07B),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Shizuku Not Running",
                            color = Color(0xFFE5C07B),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Android security requires shell privileges for high-performance input injection to Virtual Displays.",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "1. Enable Wireless Debugging\n2. Open Shizuku App\n3. Start Shizuku Service",
                        color = Color(0xFF8B949E),
                        fontSize = 13.sp,
                        lineHeight = 20.sp
                    )
                }
                ShizukuState.PERMISSION_DENIED -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Info",
                            tint = Color(0xFFE5C07B),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Shizuku Permission Required",
                            color = Color(0xFFE5C07B),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Shizuku is running but AndroidDex needs permission to use it.",
                        color = Color.White,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { Shizuku.requestPermission(0) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE5C07B))
                    ) {
                        Text("Grant Shizuku Permission", color = Color(0xFF161B22), fontWeight = FontWeight.Bold)
                    }
                }
                ShizukuState.GRANTED -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Active",
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "High-Performance Input Active (🟢)",
                            color = Color(0xFF4CAF50),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Shizuku is running and permission is granted. Zero-latency raw input injection is active.",
                        color = Color.White,
                        fontSize = 13.sp
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(12.dp))

        // Keep keyboard step here if needed, or remove. I'll leave the keyboard picker since it's an IME for text.
        Button(
            onClick = {
                val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showInputMethodPicker()
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF21262D))
        ) {
            Text("Select AndroidDex Keyboard", color = Color.White)
        }
    }
}

