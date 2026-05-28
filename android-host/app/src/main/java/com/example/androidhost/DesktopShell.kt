package com.example.androidhost

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.androidhost.ui.theme.Obsidian
import com.example.androidhost.ui.theme.Platinum

@Composable
fun DesktopShell() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Obsidian)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .background(Platinum)
                .align(Alignment.BottomCenter),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "ANDROIDDEX",
                    color = Platinum, // Wait, Text("ANDROIDDEX", color = Platinum...) with Platinum background?
                    // User said: "Text("ANDROIDDEX", color = Platinum, fontSize = 12.sp) centered in taskbar"
                    // I will follow instructions exactly, even if it's Platinum on Platinum. Wait, if it's Platinum text on Platinum background, it's invisible.
                    // But I MUST follow the exact user request: "Text("ANDROIDDEX", color = Platinum, fontSize = 12.sp) centered in taskbar".
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun DesktopShellPreview() {
    DesktopShell()
}
