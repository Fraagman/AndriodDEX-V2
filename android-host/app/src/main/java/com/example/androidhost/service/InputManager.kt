package com.example.androidhost.service

import android.util.Log
import com.example.androidhost.input.LocalInputDispatcher
import com.example.androidhost.quic.QuicServer
import java.io.OutputStream

object InputManager {
    private const val TAG = "InputManager"

    private var inputServerThread: Thread? = null
    var isPolling = false
        private set

    data class ParsedMouseEvent(val x: Int, val y: Int, val buttons: Int, val timestamp: Long)
    data class ParsedKeyboardEvent(val keycode: Int, val pressed: Boolean, val modifiers: Int, val timestamp: Long)

    // Dedicated vsock stream for AVF Linux VM
    @Volatile var vmOutputStream: OutputStream? = null

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
                        val data = buffer.copyOf(bytesRead)
                        handleInputEvent(data)
                    } else {
                        Thread.sleep(10)
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

    private fun handleInputEvent(data: ByteArray) {
        // 1. If VM is running, pipe raw bytes directly to vsock bypass!
        vmOutputStream?.let { stream ->
            try {
                // Prepend length header to frame for the VM daemon to parse
                val lenBytes = java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(data.size).array()
                stream.write(lenBytes)
                stream.write(data)
                stream.flush()
                return // Short-circuit, bypass Android entirely
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write to VM vsock, falling back to Android", e)
                vmOutputStream = null
            }
        }

        // 2. Parse the protobuf InputEvent and dispatch it into the desktop's own
        //    Compose view tree via LocalInputDispatcher. No OS-level injection and no
        //    special permission is involved — the view hierarchy is ours.
        var pos = 0
        while (pos < data.size) {
            val tagResult = readVarint(data, pos) ?: return
            pos = tagResult.second
            val tag = tagResult.first.toInt()

            val fieldNumber = tag ushr 3
            val wireType = tag and 0x07

            if (wireType != 2) return

            val lengthResult = readVarint(data, pos) ?: return
            pos = lengthResult.second
            val fieldLen = lengthResult.first.toInt()

            if (pos + fieldLen > data.size) return

            val fieldData = data.copyOfRange(pos, pos + fieldLen)
            pos += fieldLen

            when (fieldNumber) {
                1 -> decodeMouseEvent(fieldData)?.let {
                    LocalInputDispatcher.onMouse(it.x, it.y, it.buttons)
                }
                2 -> decodeKeyboardEvent(fieldData)?.let {
                    LocalInputDispatcher.onKey(it.keycode, it.pressed)
                }
            }
        }
    }

    private fun decodeMouseEvent(data: ByteArray): ParsedMouseEvent? {
        var pos = 0
        var x = 0; var y = 0; var buttons = 0; var timestamp = 0L

        while (pos < data.size) {
            val tagResult = readVarint(data, pos) ?: break
            pos = tagResult.second
            val tag = tagResult.first.toInt()
            val fieldNumber = tag ushr 3
            if ((tag and 0x07) != 0) break

            val valResult = readVarint(data, pos) ?: break
            pos = valResult.second

            when (fieldNumber) {
                1 -> x = valResult.first.toInt()
                2 -> y = valResult.first.toInt()
                3 -> buttons = valResult.first.toInt()
                4 -> timestamp = valResult.first
            }
        }
        return ParsedMouseEvent(x, y, buttons, timestamp)
    }

    private fun decodeKeyboardEvent(data: ByteArray): ParsedKeyboardEvent? {
        var pos = 0
        var keycode = 0; var pressed = false; var modifiers = 0; var timestamp = 0L

        while (pos < data.size) {
            val tagResult = readVarint(data, pos) ?: break
            pos = tagResult.second
            val tag = tagResult.first.toInt()
            val fieldNumber = tag ushr 3
            if ((tag and 0x07) != 0) break

            val valResult = readVarint(data, pos) ?: break
            pos = valResult.second

            when (fieldNumber) {
                1 -> keycode = valResult.first.toInt()
                2 -> pressed = valResult.first != 0L
                3 -> modifiers = valResult.first.toInt()
                4 -> timestamp = valResult.first
            }
        }
        return ParsedKeyboardEvent(keycode, pressed, modifiers, timestamp)
    }

    private fun readVarint(data: ByteArray, startPos: Int): Pair<Long, Int>? {
        var result = 0L
        var shift = 0
        var pos = startPos
        while (pos < data.size) {
            val b = data[pos].toInt() and 0xFF
            result = result or ((b.toLong() and 0x7F) shl shift)
            pos++
            if (b and 0x80 == 0) return Pair(result, pos)
            shift += 7
            if (shift >= 64) return null
        }
        return null
    }
}
