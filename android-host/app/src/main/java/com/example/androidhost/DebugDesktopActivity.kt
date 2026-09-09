package com.example.androidhost

import android.content.Intent
import android.os.Bundle
import android.view.Display
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * **Debug affordance.** Hosts [DesktopShellContent] directly as a normal Activity on
 * `Display.DEFAULT_DISPLAY` (the physical screen), instead of as a `Presentation` on the
 * VirtualDisplay owned by `DisplayService`.
 *
 * Purpose: `adb shell input …` and `adb exec-out screencap -p` can drive and inspect
 * Activities on the default display, but the standard `input`/`screencap` paths are not
 * routed to a `Presentation` on a virtual display owned by another process — this was
 * measured this session (see progress entry 032). Hosting the desktop UI on display 0
 * makes end-to-end UI-interaction tests (T35/T36/T37) executable from a shell.
 *
 * **This activity does not exercise the [com.example.androidhost.input.LocalInputDispatcher]
 * → WebView-bridge / Compose-native paths that T31 fixed** — those paths run when input
 * arrives over QUIC from the PC receiver and lands on the untrusted VirtualDisplay. On
 * display 0 the platform IME is trusted and works normally, so a WebView editor here
 * accepts characters through the standard Android IME regardless of the bridge. This
 * activity is therefore useful for verifying the *desktop UI's own behaviour* (windows,
 * launcher, buttons, dialogs, dragging, non-text interactions) but does not by itself
 * prove BUG-14's fix on the intended surface.
 *
 * Not exported via a MAIN/LAUNCHER intent-filter — invoked only by
 * `adb shell am start -n com.androiddex.host/com.example.androidhost.DebugDesktopActivity`.
 */
class DebugDesktopActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Start DisplayService for parity with the real flow.
        val intent = Intent(this, com.example.androidhost.service.DisplayService::class.java)
        try {
            ContextCompat.startForegroundService(this, intent)
        } catch (_: Throwable) { /* harmless if denied */ }

        setContent {
            // Force a dark background: the app theme is Material Light and DesktopShell's
            // outer Box uses `background(Color.Transparent)`, assuming a dark host
            // (the Presentation on the VirtualDisplay). On display 0 without that host
            // the theme's white shows through and makes the launcher's white app-icon
            // squares and the desktop backdrop invisible.
            Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1B1B1F))) {
                DesktopShellContent(
                    isTetheringReady = true,
                    surface = null,
                    shellViewModel = com.example.androidhost.vm.ShellHolder.shellViewModel,
                    displayId = Display.DEFAULT_DISPLAY
                )
            }
        }
    }
}
