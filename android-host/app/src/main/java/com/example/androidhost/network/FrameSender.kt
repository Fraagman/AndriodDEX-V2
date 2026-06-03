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

    fun sendVideoFrame(width: Int, height: Int, data: ByteArray) {
        if (!isRunning) return
        try {
            val protoBaos = java.io.ByteArrayOutputStream(data.size + 100)
            protoBaos.write(8) // Tag 1 (width)
            writeVarint(width.toLong(), protoBaos)
            protoBaos.write(16) // Tag 2 (height)
            writeVarint(height.toLong(), protoBaos)
            protoBaos.write(24) // Tag 3 (timestamp = 0)
            writeVarint(0L, protoBaos)
            protoBaos.write(34) // Tag 4 (rgba_data)
            writeVarint(data.size.toLong(), protoBaos)
            protoBaos.write(data)
            val vfBytes = protoBaos.toByteArray()

            val hfBaos = java.io.ByteArrayOutputStream(vfBytes.size + 10)
            hfBaos.write(10) // HybridFrame.video (tag 1, wire type 2)
            writeVarint(vfBytes.size.toLong(), hfBaos)
            hfBaos.write(vfBytes)

            val hfBytes = hfBaos.toByteArray()
            val finalBuffer = ByteBuffer.allocate(1 + hfBytes.size)
            finalBuffer.put(0x01.toByte())
            finalBuffer.put(hfBytes)

            QuicServer.sendFrame(finalBuffer.array())
            val count = framesSent.incrementAndGet()
            Log.d(TAG, "Video frame sent via QUIC: $count")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send frame", e)
        }
    }

    fun sendTileUpdate(x: Int, y: Int, width: Int, height: Int, zstdData: ByteArray) {
        if (!isRunning) return
        try {
            val protoBaos = java.io.ByteArrayOutputStream(zstdData.size + 100)
            protoBaos.write(8) // Tag 1 (x)
            writeVarint(x.toLong(), protoBaos)
            protoBaos.write(16) // Tag 2 (y)
            writeVarint(y.toLong(), protoBaos)
            protoBaos.write(24) // Tag 3 (width)
            writeVarint(width.toLong(), protoBaos)
            protoBaos.write(32) // Tag 4 (height)
            writeVarint(height.toLong(), protoBaos)
            protoBaos.write(42) // Tag 5 (zstd_data)
            writeVarint(zstdData.size.toLong(), protoBaos)
            protoBaos.write(zstdData)
            val tuBytes = protoBaos.toByteArray()

            val hfBaos = java.io.ByteArrayOutputStream(tuBytes.size + 10)
            hfBaos.write(18) // HybridFrame.tile (tag 2, wire type 2)
            writeVarint(tuBytes.size.toLong(), hfBaos)
            hfBaos.write(tuBytes)

            val hfBytes = hfBaos.toByteArray()
            val finalBuffer = ByteBuffer.allocate(1 + hfBytes.size)
            finalBuffer.put(0x01.toByte())
            finalBuffer.put(hfBytes)

            QuicServer.sendFrame(finalBuffer.array())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send tile", e)
        }
    }

    fun sendCursorUpdate(x: Int, y: Int, width: Int, height: Int, bitmap: ByteArray) {
        if (!isRunning) return
        try {
            val protoBaos = java.io.ByteArrayOutputStream(bitmap.size + 100)
            protoBaos.write(8) // Tag 1 (x)
            writeVarint(x.toLong(), protoBaos)
            protoBaos.write(16) // Tag 2 (y)
            writeVarint(y.toLong(), protoBaos)
            protoBaos.write(26) // Tag 3 (bitmap, wire type 2)
            writeVarint(bitmap.size.toLong(), protoBaos)
            protoBaos.write(bitmap)
            protoBaos.write(32) // Tag 4 (width)
            writeVarint(width.toLong(), protoBaos)
            protoBaos.write(40) // Tag 5 (height)
            writeVarint(height.toLong(), protoBaos)
            val cuBytes = protoBaos.toByteArray()

            val hfBaos = java.io.ByteArrayOutputStream(cuBytes.size + 10)
            hfBaos.write(26) // HybridFrame.cursor (tag 3, wire type 2)
            writeVarint(cuBytes.size.toLong(), hfBaos)
            hfBaos.write(cuBytes)

            val hfBytes = hfBaos.toByteArray()
            val finalBuffer = ByteBuffer.allocate(1 + hfBytes.size)
            finalBuffer.put(0x01.toByte())
            finalBuffer.put(hfBytes)

            QuicServer.sendFrame(finalBuffer.array())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send cursor", e)
        }
    }
}
