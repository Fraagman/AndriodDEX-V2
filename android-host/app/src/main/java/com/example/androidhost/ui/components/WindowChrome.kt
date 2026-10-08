package com.example.androidhost.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.androidhost.service.DisplayService
import com.example.androidhost.vm.ShellHolder
import com.example.androidhost.vm.WindowState

/**
 * Title bar and window frame. Positions and sizes come straight from
 * [WindowState.bounds] (screen pixels; the VirtualDisplay runs at density 1.0,
 * so dp here equals px) — dragging writes through to the view model, which is
 * also what makes the position survive minimize/restore and recomposition.
 */
@Composable
fun WindowChrome(
    windowState: WindowState,
    onClose: () -> Unit,
    onMinimize: () -> Unit,
    onMaximize: () -> Unit,
    content: @Composable () -> Unit
) {
    if (windowState.isMinimized) {
        return // Don't draw if minimized
    }

    val shell = ShellHolder.shellViewModel
    val focusedId by shell.focusedId.collectAsState()
    val isFocused = focusedId == windowState.id

    // Drag deltas arrive in physical pixels; bounds are stored in the shell's
    // density-independent unit (1 unit = 1 dp). Convert so the window tracks the
    // pointer 1:1 on every display density.
    val density = LocalDensity.current.density

    val isMaximized = windowState.isMaximized
    val currentOffsetX = if (isMaximized) 0f else windowState.bounds.left.toFloat()
    val currentOffsetY = if (isMaximized) 0f else windowState.bounds.top.toFloat()
    val currentWidth = if (isMaximized) DisplayService.CAPTURE_WIDTH.toFloat() else windowState.bounds.width().toFloat()
    val currentHeight = if (isMaximized) DisplayService.CAPTURE_HEIGHT.toFloat() else windowState.bounds.height().toFloat()

    Box(
        modifier = Modifier
            .offset(x = currentOffsetX.dp, y = currentOffsetY.dp)
            .width(currentWidth.dp)
            .height(currentHeight.dp)
            .background(Color.Black)
            .border(1.dp, Color.White)
            // Raise on any press inside the window, including presses the app
            // content consumes: detectTapGestures observes the down regardless.
            .pointerInput(windowState.id) {
                detectTapGestures(
                    onPress = { shell.raiseWindow(windowState.id) }
                )
            }
    ) {
        // Title Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .background(if (isFocused) Color(0xFF2A2A2A) else Color(0xFF1D1D1D))
                .border(1.dp, Color.White)
                .pointerInput(isMaximized) {
                    if (!isMaximized) {
                        detectDragGestures(
                            onDragStart = { shell.raiseWindow(windowState.id) },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                shell.moveWindow(
                                    windowState.id,
                                    dragAmount.x / density,
                                    dragAmount.y / density
                                )
                            }
                        )
                    }
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = windowState.title.uppercase(),
                color = if (isFocused) Color.White else Color(0xFFAAAAAA),
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
            content()
        }
    }
}
