package com.example.androidhost.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Minimize
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.androidhost.vm.WindowState

@Composable
fun WindowChrome(
    windowState: WindowState,
    onClose: () -> Unit,
    onMinimize: () -> Unit,
    onMaximize: () -> Unit
) {
    if (windowState.isMinimized) {
        return // Don't draw if minimized
    }

    var offsetX by remember { mutableStateOf(windowState.bounds.left.toFloat()) }
    var offsetY by remember { mutableStateOf(windowState.bounds.top.toFloat()) }

    Box(
        modifier = Modifier
            .offset(x = offsetX.dp, y = offsetY.dp)
            .width(windowState.bounds.width().dp)
            .height(windowState.bounds.height().dp)
            .background(Color.Black)
            .border(1.dp, Color.White)
    ) {
        // Title Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .background(Color(0xFF2A2A2A))
                .border(1.dp, Color.White) // Bottom border implied by enclosing box, but let's add specific if needed
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        offsetX += dragAmount.x
                        offsetY += dragAmount.y
                    }
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = windowState.title.uppercase(),
                color = Color.White,
                fontSize = 11.sp,
                letterSpacing = 1.sp // tracking-wide
            )
            
            Spacer(modifier = Modifier.weight(1f))

            // Minimize
            Box(modifier = Modifier.size(32.dp).clickable { onMinimize() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Minimize, contentDescription = "Minimize", tint = Color.White, modifier = Modifier.size(16.dp))
            }
            // Maximize
            Box(modifier = Modifier.size(32.dp).clickable { onMaximize() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Default.CheckBoxOutlineBlank, contentDescription = "Maximize", tint = Color.White, modifier = Modifier.size(16.dp))
            }
            // Close
            Box(modifier = Modifier.size(32.dp).clickable { onClose() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }

        // Window Content Area
        Box(modifier = Modifier.fillMaxSize().padding(top = 32.dp)) {
            // Future: Render actual window content here
        }
    }
}
