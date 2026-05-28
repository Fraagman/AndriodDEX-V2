package com.example.androidhost

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.androidhost.ui.theme.Obsidian
import com.example.androidhost.ui.theme.Platinum
import com.example.androidhost.vm.ConnectionViewModel

@Composable
fun DesktopShell(viewModel: ConnectionViewModel = viewModel()) {
    val isReady by viewModel.isTetheringReady.collectAsState()
    DesktopShellContent(isTetheringReady = isReady)
}

@Composable
fun DesktopShellContent(isTetheringReady: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Obsidian)
    ) {
        Text(
            text = if (isTetheringReady) {
                "USB tethering detected \u2014 waiting for PC"
            } else {
                "Connect USB cable and enable tethering"
            },
            color = Platinum,
            fontSize = 14.sp,
            modifier = Modifier.align(Alignment.Center)
        )

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
                    color = Obsidian,
                    fontSize = 12.sp
                )
            }
        }
    }
}

class TetheringStateProvider : PreviewParameterProvider<Boolean> {
    override val values = sequenceOf(false, true)
}

@Preview(showBackground = true)
@Composable
fun DesktopShellPreview(
    @PreviewParameter(TetheringStateProvider::class) isTetheringReady: Boolean
) {
    DesktopShellContent(isTetheringReady)
}
