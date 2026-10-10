package com.example.androidhost.service

import android.util.Log
import com.androiddex.protocol.InputEvent
import com.example.androidhost.BuildConfig
import com.example.androidhost.input.LocalInputDispatcher
import com.example.androidhost.quic.QuicServer

/**
 * Reads input events off the QUIC transport and hands them to [LocalInputDispatcher].
 *
 * Events are parsed with the protobuf classes generated from
 * `rust-receiver/zc-protocol/proto/input.proto` — the same file the Rust receiver
 * compiles — rather than by walking varints by hand.
 */
object InputManager {
    private const val TAG = "InputManager"

    private var inputServerThread: Thread? = null
    var isPolling = false
        private set

    fun startPolling(dataPath: String) {
        if (isPolling) return
        isPolling = true

        QuicServer.startServer(4433, dataPath)

        inputServerThread = Thread {
            val buffer = ByteArray(1024 * 1024)
            while (!Thread.currentThread().isInterrupted) {
                try {
                    val bytesRead = QuicServer.pollInput(buffer)
                    if (bytesRead > 0) {
                        handleInputEvent(buffer, bytesRead)
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Input polling error", e)
                }
            }
        }.apply {
            name = "InputPollingThread"
            isDaemon = true
            start()
        }
    }

    /** Stops the poll thread. Called when the owning service dies; the QUIC server
     *  itself is process-global and outlives the service. */
    fun stopPolling() {
        isPolling = false
        inputServerThread?.interrupt()
        inputServerThread = null
    }

    private fun handleInputEvent(data: ByteArray, length: Int) {
        val event = try {
            InputEvent.parseFrom(data.copyOf(length))
        } catch (e: Exception) {
            Log.w(TAG, "Dropping malformed InputEvent (${length} bytes)", e)
            return
        }

        when (event.eventCase) {
            InputEvent.EventCase.MOUSE -> {
                val m = event.mouse
                LocalInputDispatcher.onMouse(m.x, m.y, m.buttons, m.modifiers)
            }
            InputEvent.EventCase.KEYBOARD -> {
                val k = event.keyboard
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "InputManager handleInputEvent KEYBOARD: keycode=${k.keycode}, pressed=${k.pressed}")
                }
                LocalInputDispatcher.onKey(k.keycode, k.pressed, k.modifiers)
            }
            InputEvent.EventCase.SCROLL -> {
                val s = event.scroll
                LocalInputDispatcher.onScroll(s.x, s.y, s.vScroll, s.hScroll, s.modifiers)
            }
            InputEvent.EventCase.REQUEST_KEYFRAME -> {
                Log.i(TAG, "Keyframe requested by receiver")
                DisplayService.requestKeyframe()
            }
            InputEvent.EventCase.TEXT -> {
                val t = event.text
                LocalInputDispatcher.onText(t.text)
            }
            InputEvent.EventCase.OPEN_APP -> {
                val pkg = event.openApp.packageName
                Log.i(TAG, "Opening app via QUIC InputEvent: $pkg")
                com.example.androidhost.vm.ShellHolder.shellViewModel.openApp(pkg)
            }
            InputEvent.EventCase.NAV -> {
                // Global navigation requested by the receiver's taskbar. Performed by
                // the optional accessibility service; without it these are no-ops.
                when (event.nav.action) {
                    com.androiddex.protocol.NavAction.NAV_BACK -> DesktopAccessibilityService.performBack()
                    com.androiddex.protocol.NavAction.NAV_HOME -> DesktopAccessibilityService.performHome()
                    com.androiddex.protocol.NavAction.NAV_RECENTS -> DesktopAccessibilityService.performRecents()
                    com.androiddex.protocol.NavAction.UNRECOGNIZED -> Unit
                }
            }
            InputEvent.EventCase.EVENT_NOT_SET, null -> Unit
        }
    }
}
