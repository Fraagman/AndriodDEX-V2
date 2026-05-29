package com.example.androidhost.network

import android.util.Log
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

import com.example.androidhost.quic.QuicServer

object FrameSender {
    private const val TAG = "FrameSender"
    private var isRunning = false
    val framesSent = AtomicInteger(0)
    
    val isConnected: Boolean
        get() = com.example.androidhost.quic.QuicServer.handle != 0L

    fun start() {
        if (isRunning) return
        isRunning = true
        Log.d(TAG, "Starting FrameSender (via QUIC)")
    }

    fun stop() {
        isRunning = false
    }

    private fun writeVarint(value: Long, out: java.io.ByteArrayOutputStream) {
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
        if (!isRunning) return
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
            
            // Send directly through QuicServer
            QuicServer.sendFrame(protobufBytes)
            val count = framesSent.incrementAndGet()
            Log.d(TAG, "Video frame sent via QUIC: $count")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send frame", e)
        }
    }
}
