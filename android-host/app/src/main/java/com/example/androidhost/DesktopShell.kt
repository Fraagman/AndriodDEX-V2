package com.example.androidhost

import android.view.Surface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.androidhost.service.DisplayService
import com.example.androidhost.ui.components.AppRegistry
import com.example.androidhost.vm.ConnectionViewModel
import com.example.androidhost.vm.DisplayViewModel
import com.example.androidhost.vm.ShellHolder
import com.example.androidhost.vm.ShellViewModel

@Composable
fun DesktopShell(
    viewModel: ConnectionViewModel = viewModel(),
    displayViewModel: DisplayViewModel = viewModel(),
    shellViewModel: ShellViewModel = ShellHolder.shellViewModel,
    onLockSession: () -> Unit = {},
    onRequestAudioCapture: (Boolean) -> Unit = {}
) {
    val isReady by viewModel.isTetheringReady.collectAsState()
    val surface by displayViewModel.virtualDisplaySurface.collectAsState()

    DesktopShellContent(
        isTetheringReady = isReady,
        surface = surface,
        shellViewModel = shellViewModel,
        onLockSession = onLockSession,
        onRequestAudioCapture = onRequestAudioCapture
    )
}

@Composable
fun DesktopShellContent(
    isTetheringReady: Boolean,
    surface: Surface? = null,
    shellViewModel: ShellViewModel? = null,
    onLockSession: () -> Unit = {},
    onRequestAudioCapture: (Boolean) -> Unit = {}
) {
    val windows by shellViewModel?.windows?.collectAsState(initial = emptyList<com.example.androidhost.vm.WindowState>())
        ?: remember { mutableStateOf(emptyList<com.example.androidhost.vm.WindowState>()) }

    // The taskbar lives on the receiver (its egui shell owns Start, app launching,
    // clock, status and navigation). The one thing it cannot do is keep THIS
    // composition drawing: the encoder only emits frames when something draws,
    // and a joining client's keyframe request can only be answered once new pixels
    // exist. This 1 Hz tick keeps the shell alive on a static desktop — the same
    // job the removed taskbar clock happened to do.
    val frameTick by DisplayService.frameTick.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Windows 10 Hero Wallpaper background (draws a 1 px per-second keepalive)
        Windows10Wallpaper(keepAliveTick = frameTick)

        // Windows. List order is the z-order — the view model raises a window by
        // moving it to the end of the list.
        windows.forEach { window ->
            val appConfig = AppRegistry.apps[window.packageName]
            if (appConfig != null) {
                appConfig.content(
                    window,
                    { shellViewModel?.closeWindow(window.id) },
                    { shellViewModel?.minimizeWindow(window.id) },
                    { shellViewModel?.maximizeWindow(window.id) }
                )
            }
        }
    }
}

/**
 * Draws the desktop wallpaper.
 *
 * [keepAliveTick] alternates one pixel's alpha every call: the H.264 encoder only
 * produces output when its input changes, this device's encoder ignores
 * KEY_REPEAT_PREVIOUS_FRAME_AFTER, and a fully static desktop would otherwise starve
 * the stream — a client that joins (or one whose decoder just lost a reference)
 * would wait forever for an IDR. One imperceptible pixel per second is the smallest
 * change that keeps frames flowing.
 */
@Composable
fun Windows10Wallpaper(modifier: Modifier = Modifier, keepAliveTick: Long = 0) {
    androidx.compose.foundation.Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        // 0. Encoder keepalive: one pixel, alpha toggling 1/2 per tick. Invisible
        // against the near-black backdrop, but it is a real pixel change, so the
        // compositor hands the encoder a fresh buffer every tick.
        drawRect(
            color = Color(0x01000000 + (keepAliveTick % 2).toInt()),
            topLeft = androidx.compose.ui.geometry.Offset(0f, 0f),
            size = androidx.compose.ui.geometry.Size(1f, 1f)
        )

        // 1. Deep space navy background gradient
        drawRect(
            brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                colors = listOf(
                    Color(0xFF020712),
                    Color(0xFF001128),
                    Color(0xFF001A3A),
                    Color(0xFF010814)
                )
            )
        )

        // 2. Windows 10 Hero radial luminous bloom centered on right quadrant
        val heroCenter = androidx.compose.ui.geometry.Offset(w * 0.65f, h * 0.48f)
        drawCircle(
            brush = androidx.compose.ui.graphics.Brush.radialGradient(
                colors = listOf(
                    Color(0x880078D7),
                    Color(0x44005A9E),
                    Color(0x18002050),
                    Color.Transparent
                ),
                center = heroCenter,
                radius = w * 0.45f
            ),
            center = heroCenter,
            radius = w * 0.45f
        )

        // 3. Volumetric angled light rays
        val rayPath = androidx.compose.ui.graphics.Path().apply {
            moveTo(heroCenter.x - 300f, 0f)
            lineTo(heroCenter.x + 400f, 0f)
            lineTo(heroCenter.x + 600f, h)
            lineTo(heroCenter.x - 100f, h)
            close()
        }
        drawPath(
            path = rayPath,
            brush = androidx.compose.ui.graphics.Brush.linearGradient(
                colors = listOf(
                    Color(0x2200C8FF),
                    Color(0x0C0078D7),
                    Color.Transparent
                ),
                start = androidx.compose.ui.geometry.Offset(heroCenter.x, 0f),
                end = androidx.compose.ui.geometry.Offset(heroCenter.x + 200f, h)
            )
        )

        // 4. Iconic four-quadrant angled Windows 10 logo
        val logoSize = 130f
        val gap = 9f
        val half = logoSize * 0.5f
        val skewX = -18f

        val quadrants = listOf(
            listOf(
                androidx.compose.ui.geometry.Offset(heroCenter.x - half + skewX * 0.7f, heroCenter.y - half),
                androidx.compose.ui.geometry.Offset(heroCenter.x - gap * 0.5f + skewX * 0.2f, heroCenter.y - half + 6f),
                androidx.compose.ui.geometry.Offset(heroCenter.x - gap * 0.5f, heroCenter.y - gap * 0.5f),
                androidx.compose.ui.geometry.Offset(heroCenter.x - half, heroCenter.y - gap * 0.5f - 4f)
            ),
            listOf(
                androidx.compose.ui.geometry.Offset(heroCenter.x + gap * 0.5f + skewX * 0.2f, heroCenter.y - half + 6f),
                androidx.compose.ui.geometry.Offset(heroCenter.x + half - skewX * 0.4f, heroCenter.y - half + 14f),
                androidx.compose.ui.geometry.Offset(heroCenter.x + half, heroCenter.y - gap * 0.5f + 3f),
                androidx.compose.ui.geometry.Offset(heroCenter.x + gap * 0.5f, heroCenter.y - gap * 0.5f)
            ),
            listOf(
                androidx.compose.ui.geometry.Offset(heroCenter.x - half, heroCenter.y + gap * 0.5f - 4f),
                androidx.compose.ui.geometry.Offset(heroCenter.x - gap * 0.5f, heroCenter.y + gap * 0.5f),
                androidx.compose.ui.geometry.Offset(heroCenter.x - gap * 0.5f - skewX * 0.2f, heroCenter.y + half - 6f),
                androidx.compose.ui.geometry.Offset(heroCenter.x - half - skewX * 0.7f, heroCenter.y + half)
            ),
            listOf(
                androidx.compose.ui.geometry.Offset(heroCenter.x + gap * 0.5f, heroCenter.y + gap * 0.5f),
                androidx.compose.ui.geometry.Offset(heroCenter.x + half, heroCenter.y + gap * 0.5f + 3f),
                androidx.compose.ui.geometry.Offset(heroCenter.x + half + skewX * 0.4f, heroCenter.y + half - 14f),
                androidx.compose.ui.geometry.Offset(heroCenter.x + gap * 0.5f - skewX * 0.2f, heroCenter.y + half - 6f)
            )
        )

        for (quad in quadrants) {
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(quad[0].x, quad[0].y)
                lineTo(quad[1].x, quad[1].y)
                lineTo(quad[2].x, quad[2].y)
                lineTo(quad[3].x, quad[3].y)
                close()
            }
            drawPath(
                path = path,
                brush = androidx.compose.ui.graphics.Brush.linearGradient(
                    colors = listOf(
                        Color(0xEE00D2FF),
                        Color(0xBB0078D7)
                    ),
                    start = quad[0],
                    end = quad[2]
                )
            )
        }
    }
}
