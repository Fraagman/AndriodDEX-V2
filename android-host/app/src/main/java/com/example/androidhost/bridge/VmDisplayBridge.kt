package com.example.androidhost.bridge

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MVP Phase 10 Bridge for VM Display.
 * Connects to a server running inside the VM via a local TCP socket (or vsock port mapped to localhost).
 * Expects raw RGB565/ARGB8888 frames or JPEG frames depending on the VM-side implementation.
 * For MVP, we will assume the VM agent captures screencap and sends it as JPEG for simplicity over a socket.
 */
class VmDisplayBridge {

    private val isRunning = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var surface: Surface? = null

    fun setSurface(surface: Surface?) {
        this.surface = surface
    }

    suspend fun startListening(port: Int = 8080) {
        isRunning.set(true)
        withContext(Dispatchers.IO) {
            try {
                serverSocket = ServerSocket(port)
                Log.i(TAG, "Bridge listening on port $port")
                
                while (isRunning.get()) {
                    val client = serverSocket?.accept() ?: break
                    Log.i(TAG, "VM Agent connected")
                    handleClient(client)
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.e(TAG, "Error in bridge server socket", e)
                }
            }
        }
    }

    private fun handleClient(client: Socket) {
        try {
            val input: InputStream = client.getInputStream()
            
            // Very simple protocol:
            // 4 bytes: length of frame (N)
            // N bytes: JPEG image data
            val lengthBuffer = ByteArray(4)
            while (isRunning.get()) {
                var read = input.read(lengthBuffer)
                if (read < 4) break
                
                val length = (lengthBuffer[0].toInt() and 0xFF) shl 24 or
                             (lengthBuffer[1].toInt() and 0xFF) shl 16 or
                             (lengthBuffer[2].toInt() and 0xFF) shl 8 or
                             (lengthBuffer[3].toInt() and 0xFF)
                
                if (length <= 0 || length > 10 * 1024 * 1024) { // Max 10MB
                    Log.w(TAG, "Invalid frame length: $length")
                    break
                }
                
                val frameBuffer = ByteArray(length)
                var totalRead = 0
                while (totalRead < length) {
                    val r = input.read(frameBuffer, totalRead, length - totalRead)
                    if (r == -1) break
                    totalRead += r
                }
                
                if (totalRead == length) {
                    val bitmap = BitmapFactory.decodeByteArray(frameBuffer, 0, length)
                    if (bitmap != null) {
                        drawToSurface(bitmap)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling VM client", e)
        } finally {
            try { client.close() } catch (e: Exception) {}
        }
    }

    private fun drawToSurface(bitmap: Bitmap) {
        val s = surface ?: return
        if (!s.isValid) return
        
        var canvas: Canvas? = null
        try {
            canvas = s.lockCanvas(null)
            if (canvas != null) {
                // Scale bitmap to fit the canvas (if needed)
                val srcRect = Rect(0, 0, bitmap.width, bitmap.height)
                val dstRect = Rect(0, 0, canvas.width, canvas.height)
                canvas.drawBitmap(bitmap, srcRect, dstRect, null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error drawing to surface", e)
        } finally {
            if (canvas != null) {
                try {
                    s.unlockCanvasAndPost(canvas)
                } catch (e: Exception) {
                    Log.e(TAG, "Error unlocking canvas", e)
                }
            }
        }
    }

    fun stop() {
        isRunning.set(false)
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing server socket", e)
        }
    }

    companion object {
        private const val TAG = "VmDisplayBridge"
    }
}
