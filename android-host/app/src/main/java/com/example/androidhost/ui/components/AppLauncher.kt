package com.example.androidhost.ui.components

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap

data class AppInfo(
    val packageName: String,
    val name: String,
    val icon: Drawable?
)

@Composable
fun AppLauncher(
    onDismiss: () -> Unit,
    onAppSelected: (String) -> Unit,
    displayId: Int = android.view.Display.DEFAULT_DISPLAY
) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }

    LaunchedEffect(Unit) {
        apps = loadTargetApps(context)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.8f))
            .clickable { onDismiss() }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = "AndroidDEX runs its own workspace rather than mirroring phone apps.",
                color = Color.White,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 32.dp, start = 16.dp, end = 16.dp)
            )

            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 80.dp),
                contentPadding = PaddingValues(32.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(apps) { app ->
                    AppIconCell(app = app, onClick = {
                        onAppSelected(app.packageName)
                        onDismiss()
                    })
                }
            }
        }
    }
}

@Composable
fun AppIconCell(app: AppInfo, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .padding(8.dp)
            .clickable { onClick() }
    ) {
        if (app.icon != null) {
            val bitmap = app.icon.toBitmap(config = android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
            Image(
                bitmap = bitmap,
                contentDescription = app.name,
                modifier = Modifier.size(48.dp)
            )
        } else {
            // Fallback icon for missing apps
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(Color.White, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = app.name.take(1).uppercase(),
                    color = Color.Black,
                    fontSize = 20.sp
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = app.name,
            color = Color.White,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

private fun loadTargetApps(context: Context): List<AppInfo> {
    return listOf(
        AppInfo("com.androiddex.codeserver", "VS Code", null),
        AppInfo("com.androiddex.terminal", "Terminal", null),
        AppInfo("com.androiddex.files", "Files", null),
        AppInfo("com.androiddex.settings", "Settings", null)
    )
}
