package com.example.androidhost

import android.view.Surface
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.androidhost.ui.theme.Obsidian
import com.example.androidhost.ui.theme.Platinum
import com.example.androidhost.vm.ConnectionViewModel
import com.example.androidhost.vm.DisplayViewModel

@Composable
fun DesktopShell(
    viewModel: ConnectionViewModel = viewModel(),
    displayViewModel: DisplayViewModel = viewModel()
) {
    val isReady by viewModel.isTetheringReady.collectAsState()
    val surface by displayViewModel.virtualDisplaySurface.collectAsState()
    DesktopShellContent(isTetheringReady = isReady, surface = surface)
}

@Composable
fun DesktopShellContent(isTetheringReady: Boolean, surface: Surface? = null) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Obsidian)
    ) {
        if (surface != null) {
            AndroidView(
                factory = { context ->
                    android.view.SurfaceView(context)
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            androidx.compose.foundation.layout.Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Initializing display...",
                    color = Platinum,
                    fontSize = 14.sp
                )
                
                if (com.example.androidhost.BuildConfig.DEBUG) {
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(16.dp))
                    androidx.compose.material3.Button(onClick = {
                        val service = com.example.androidhost.service.DesktopAccessibilityService.instance
                        if (service != null) {
                            service.injectClick(500f, 500f)
                        } else {
                            android.util.Log.e("A11y", "Service not connected")
                        }
                    }) {
                        Text("Inject Test Click")
                    }
                }
            }
        }

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
    DesktopShellContent(isTetheringReady, null)
}
