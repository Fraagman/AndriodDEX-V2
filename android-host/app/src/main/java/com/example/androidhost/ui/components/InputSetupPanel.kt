package com.example.androidhost.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.androidhost.service.AndroidDexIME
import com.example.androidhost.service.DesktopAccessibilityService

private val READY_GREEN = Color(0xFF4CAF50)
private val PENDING_GREY = Color(0xFF8B949E)

/**
 * Honest capability status for the three input paths.
 *
 * Desktop control — clicking, dragging, hovering and scrolling the streamed desktop —
 * needs nothing at all: input is dispatched directly into our own Compose view tree.
 * The other two rows are genuine opt-ins that the user can take or leave, and neither
 * blocks anything.
 */
@Composable
fun InputSetupPanel() {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var imeSelected by remember { mutableStateOf(AndroidDexIME.isSelectedIme(ctx)) }
    val a11yEnabled by DesktopAccessibilityService.isConnected.collectAsState()

    // Both settings are changed in system UI, so re-check whenever we come back.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                imeSelected = AndroidDexIME.isSelectedIme(ctx)
                DesktopAccessibilityService.refresh(ctx)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        DesktopAccessibilityService.refresh(ctx)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF161B22), shape = RoundedCornerShape(12.dp))
            .padding(20.dp)
    ) {
        Text(text = "Input", color = PENDING_GREY, fontSize = 12.sp)
        Spacer(modifier = Modifier.height(16.dp))

        CapabilityRow(
            title = "Desktop control",
            detail = "Mouse, drag, hover and keyboard on the streamed desktop.",
            statusText = "Ready — no setup needed",
            ready = true
        )

        Spacer(modifier = Modifier.height(12.dp))

        CapabilityRow(
            title = "Keyboard into text fields",
            detail = "Lets typing reach focused text fields. Enable the AndroidDex keyboard in Languages & input.",
            statusText = if (imeSelected) "Ready" else "Optional — not enabled",
            ready = imeSelected,
            actionLabel = if (imeSelected) null else "Choose keyboard",
            onAction = { AndroidDexIME.showImePicker(ctx) }
        )

        Spacer(modifier = Modifier.height(12.dp))

        CapabilityRow(
            title = "Back / home / recents",
            detail = "Adds the three system navigation buttons to the desktop taskbar.",
            statusText = if (a11yEnabled) "Ready" else "Optional — not enabled",
            ready = a11yEnabled,
            actionLabel = if (a11yEnabled) null else "Open accessibility settings",
            onAction = { DesktopAccessibilityService.openSettings(ctx) }
        )
    }
}

@Composable
private fun CapabilityRow(
    title: String,
    detail: String,
    statusText: String,
    ready: Boolean,
    actionLabel: String? = null,
    onAction: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0D1117), shape = RoundedCornerShape(8.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = if (ready) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
            contentDescription = if (ready) "Ready" else "Not enabled",
            tint = if (ready) READY_GREEN else PENDING_GREY,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(text = title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = statusText,
                color = if (ready) READY_GREEN else PENDING_GREY,
                fontSize = 12.sp,
                fontWeight = if (ready) FontWeight.SemiBold else FontWeight.Normal
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = detail, color = PENDING_GREY, fontSize = 12.sp, lineHeight = 17.sp)
            if (actionLabel != null) {
                TextButton(
                    onClick = onAction,
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Text(text = actionLabel, color = Color(0xFF58A6FF), fontSize = 13.sp)
                }
            }
        }
    }
}
