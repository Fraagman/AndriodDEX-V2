package com.example.androidhost.service

import android.util.Log
import com.androiddex.protocol.InputEvent
import com.example.androidhost.input.LocalInputDispatcher
import com.example.androidhost.quic.QuicServer
import java.io.OutputStream

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

    /** Dedicated vsock stream for the AVF Linux VM, when one is attached. */
    @Volatile
    var vmOutputStream: OutputStream? = null

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
                    } else {
                        Thread.sleep(1)
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

    fun stopPolling() {
        isPolling = false
        inputServerThread?.interrupt()
        inputServerThread = null
    }

    private fun handleInputEvent(data: ByteArray, length: Int) {
        // If a VM is running, pipe the raw bytes straight to its vsock and skip Android.
        vmOutputStream?.let { stream ->
            try {
                val lenBytes = java.nio.ByteBuffer.allocate(4)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    .putInt(length)
                    .array()
                stream.write(lenBytes)
                stream.write(data, 0, length)
                stream.flush()
                return
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write to VM vsock, falling back to Android", e)
                vmOutputStream = null
            }
        }

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
                LocalInputDispatcher.onKey(k.keycode, k.pressed, k.modifiers)
            }
            InputEvent.EventCase.SCROLL -> {
                val s = event.scroll
                LocalInputDispatcher.onScroll(s.x, s.y, s.vScroll, s.hScroll)
            }
            InputEvent.EventCase.REQUEST_KEYFRAME -> {
                Log.i(TAG, "Keyframe requested by receiver")
                DisplayService.requestKeyframe()
            }
            InputEvent.EventCase.EVENT_NOT_SET, null -> Unit
        }
    }
}
