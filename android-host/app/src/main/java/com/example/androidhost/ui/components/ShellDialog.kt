package com.example.androidhost.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * An in-composition modal dialog for shell apps.
 *
 * androidx.compose.ui.window.Dialog (and AlertDialog) create a real window, and on
 * Android 15 any window opened from the Presentation's context crashes the app with
 * "Window type mismatch" — the Presentation's window type does not match the dialog's.
 * This composable draws the same modal as plain layout inside the shell, so it is
 * impossible for it to open a window at all.
 */
@Composable
fun ShellDialog(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    body: (@Composable () -> Unit)? = null,
    confirmText: String = "OK",
    onConfirm: () -> Unit,
    dismissText: String = "Cancel"
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp)
                .clickable(enabled = false) { } // swallow taps meant for the scrim
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(title, color = Color.White, fontSize = 18.sp)
                Spacer(modifier = Modifier.height(12.dp))
                body?.invoke()
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text(dismissText, color = Color(0xFF8AB4F8)) }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = onConfirm) { Text(confirmText) }
                }
            }
        }
    }
}
