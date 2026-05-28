package com.example.androidhost.network

import android.util.Log
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

object FrameSender {
    private const val TAG = "FrameSender"
    private var isRunning = false
    private var thread: Thread? = null
    private var socket: Socket? = null
    private var outStream: OutputStream? = null

    val framesSent = AtomicInteger(0)
    var isConnected = false
        private set

    fun start() {
        if (isRunning) return
        isRunning = true
        thread = Thread {
            while (isRunning) {
                try {
                    Log.d(TAG, "Connecting to 192.168.42.1:55556...")
                    socket = Socket("192.168.42.1", 55556)
                    outStream = socket?.getOutputStream()
                    isConnected = true
                    Log.d(TAG, "Connected to 192.168.42.1:55556")
                    
                    // Keep thread alive while connected
                    while (isRunning && socket?.isConnected == true && !socket!!.isClosed) {
                        Thread.sleep(1000)
                    }
                } catch (e: Exception) {
                    isConnected = false
                    Log.e(TAG, "Connection failed, retrying in 5 seconds", e)
                    try {
                        Thread.sleep(5000)
                    } catch (ie: InterruptedException) {
                        break
                    }
                } finally {
                    isConnected = false
                    try { socket?.close() } catch (e: Exception) {}
                    socket = null
                    outStream = null
                }
            }
        }.apply { start() }
    }

    fun stop() {
        isRunning = false
        try { socket?.close() } catch (e: Exception) {}
        thread?.interrupt()
        thread = null
    }

    private fun writeVarint(value: Long, out: OutputStream) {
        var v = value
        while (true) {
            if ((v and 0xFFFFFFFFFFFFFF80u.toLong()) == 0L) {
                out.write(v.toInt())
                return
            }
            out.write((v.toInt() and 0x7F) or 0x80)
            v = v ushr 7
        }
    }

    fun sendFrame(width: Int, height: Int, data: ByteArray) {
        val out = outStream ?: return
        try {
            // Encode VideoFrame protobuf
            // Tag 1 (width)
            val protoBaos = java.io.ByteArrayOutputStream(data.size + 100)
            protoBaos.write(8)
            writeVarint(width.toLong(), protoBaos)
            
            // Tag 2 (height)
            protoBaos.write(16)
            writeVarint(height.toLong(), protoBaos)
            
            // Tag 3 (timestamp = 0)
            protoBaos.write(24)
            writeVarint(0L, protoBaos)
            
            // Tag 4 (rgba_data)
            protoBaos.write(34)
            writeVarint(data.size.toLong(), protoBaos)
            protoBaos.write(data)
            
            val protobufBytes = protoBaos.toByteArray()
            
            // Length prefix (4 bytes big-endian)
            val lengthPrefix = ByteBuffer.allocate(4).putInt(protobufBytes.size).array()
            
            out.write(lengthPrefix)
            out.write(protobufBytes)
            out.flush()
            
            val count = framesSent.incrementAndGet()
            Log.d(TAG, "Frames sent: $count")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send frame", e)
            try { socket?.close() } catch (ex: Exception) {}
        }
    }
}
