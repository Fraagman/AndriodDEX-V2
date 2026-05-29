package com.example.androidhost.ui.components

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
    onAppSelected: (String) -> Unit
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
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 80.dp),
            contentPadding = PaddingValues(32.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(apps) { app ->
                AppIconCell(app = app, onClick = {
                    val intent = context.packageManager.getLaunchIntentForPackage(app.packageName)
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    }
                    onAppSelected(app.packageName)
                    onDismiss()
                })
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
            // Placeholder
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
    val pm = context.packageManager
    
    val targetPackages = listOf(
        "com.android.chrome",
        "com.google.android.documentsui", // Files
        "com.android.settings",
        "com.google.android.calculator",
        "com.google.android.calendar",
        "com.android.camera2", // Camera
        "com.google.android.gm", // Gmail
        "com.google.android.apps.photos" // Photos
    )

    val result = mutableListOf<AppInfo>()

    for (pkg in targetPackages) {
        try {
            val appInfo = pm.getApplicationInfo(pkg, PackageManager.GET_META_DATA)
            val label = pm.getApplicationLabel(appInfo).toString()
            val icon = pm.getApplicationIcon(appInfo)
            result.add(AppInfo(pkg, label, icon))
        } catch (e: PackageManager.NameNotFoundException) {
            // Provide a fallback if not installed on this specific emulator/device
            val fallbackName = pkg.substringAfterLast(".").replaceFirstChar { it.uppercase() }
            result.add(AppInfo(pkg, fallbackName, null))
        }
    }
    return result
}
