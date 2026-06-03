package com.example.androidhost.service

import android.util.Log
import com.example.androidhost.network.FrameSender
import com.github.luben.zstd.Zstd
import java.util.zip.CRC32
import kotlin.math.abs

class RegionDetector {
    private val TAG = "RegionDetector"
    private val TILE_SIZE = 64
    private var previousHashes: LongArray? = null
    private var previousData: ByteArray? = null
    private var zstdAvailable = true

    var tilesSentLastFrame = 0
    var videoDetectedLastFrame = false

    fun processFrame(width: Int, height: Int, data: ByteArray) {
        val cols = (width + TILE_SIZE - 1) / TILE_SIZE
        val rows = (height + TILE_SIZE - 1) / TILE_SIZE
        val totalTiles = cols * rows

        if (previousHashes == null || previousHashes!!.size != totalTiles) {
            previousHashes = LongArray(totalTiles)
            previousData = ByteArray(data.size)
        }

        var tilesSent = 0
        var videoDetected = false

        if (!zstdAvailable) {
            tilesSentLastFrame = 0
            videoDetectedLastFrame = false
            return
        }

        val crc32 = CRC32()

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val tileW = if (c == cols - 1 && width % TILE_SIZE != 0) width % TILE_SIZE else TILE_SIZE
                val tileH = if (r == rows - 1 && height % TILE_SIZE != 0) height % TILE_SIZE else TILE_SIZE

                // Extract tile data
                val tileData = ByteArray(tileW * tileH * 4)
                var destIdx = 0
                for (y in 0 until tileH) {
                    val srcY = r * TILE_SIZE + y
                    val srcIdx = (srcY * width + c * TILE_SIZE) * 4
                    System.arraycopy(data, srcIdx, tileData, destIdx, tileW * 4)
                    destIdx += tileW * 4
                }

                crc32.reset()
                crc32.update(tileData)
                val hash = crc32.value

                val tileIndex = r * cols + c
                if (hash != previousHashes!![tileIndex]) {
                    // Changed! Classify by variance
                    var isVideo = false
                    if (previousData != null) {
                        var diffSum = 0L
                        val pixelCount = tileW * tileH
                        
                        // First pass: compute average difference
                        for (y in 0 until tileH) {
                            val srcY = r * TILE_SIZE + y
                            val srcIdx = (srcY * width + c * TILE_SIZE) * 4
                            for (x in 0 until tileW) {
                                val idx = srcIdx + x * 4
                                val currR = data[idx].toInt() and 0xFF
                                val currG = data[idx + 1].toInt() and 0xFF
                                val currB = data[idx + 2].toInt() and 0xFF
                                val prevR = previousData!![idx].toInt() and 0xFF
                                val prevG = previousData!![idx + 1].toInt() and 0xFF
                                val prevB = previousData!![idx + 2].toInt() and 0xFF

                                val lumaCurr = (0.299 * currR + 0.587 * currG + 0.114 * currB).toInt()
                                val lumaPrev = (0.299 * prevR + 0.587 * prevG + 0.114 * prevB).toInt()
                                diffSum += abs(lumaCurr - lumaPrev)
                            }
                        }
                        val avgDiff = diffSum.toDouble() / pixelCount

                        // Second pass: compute variance
                        var varianceSum = 0.0
                        for (y in 0 until tileH) {
                            val srcY = r * TILE_SIZE + y
                            val srcIdx = (srcY * width + c * TILE_SIZE) * 4
                            for (x in 0 until tileW) {
                                val idx = srcIdx + x * 4
                                val currR = data[idx].toInt() and 0xFF
                                val currG = data[idx + 1].toInt() and 0xFF
                                val currB = data[idx + 2].toInt() and 0xFF
                                val prevR = previousData!![idx].toInt() and 0xFF
                                val prevG = previousData!![idx + 1].toInt() and 0xFF
                                val prevB = previousData!![idx + 2].toInt() and 0xFF

                                val lumaCurr = (0.299 * currR + 0.587 * currG + 0.114 * currB).toInt()
                                val lumaPrev = (0.299 * prevR + 0.587 * prevG + 0.114 * prevB).toInt()
                                val diff = abs(lumaCurr - lumaPrev)
                                varianceSum += (diff - avgDiff) * (diff - avgDiff)
                            }
                        }
                        val variance = varianceSum / pixelCount
                        
                        // If variance of difference is high, it's noisy/granular (video)
                        if (variance > 100.0) {
                            isVideo = true
                            videoDetected = true
                        }
                    }

                    // Compress the raw RGBA tile bytes with Zstd
                    try {
                        val compressed = Zstd.compress(tileData, 3) // Level 3 is fast enough
                        
                        if (compressed.size < 4096) {
                            Log.d(TAG, "TileUpdate sent: x=${c * TILE_SIZE}, y=${r * TILE_SIZE}, size=${compressed.size} bytes")
                        } else {
                            Log.d(TAG, "TileUpdate sent: x=${c * TILE_SIZE}, y=${r * TILE_SIZE}, size=${compressed.size} bytes (large)")
                        }

                        // Send TileUpdate
                        FrameSender.sendTileUpdate(c * TILE_SIZE, r * TILE_SIZE, tileW, tileH, compressed)
                        tilesSent++
                    } catch (e: Throwable) {
                        Log.e(TAG, "zstd unavailable, keyframe-only mode", e)
                        zstdAvailable = false
                        tilesSentLastFrame = 0
                        videoDetectedLastFrame = false
                        return
                    }

                    previousHashes!![tileIndex] = hash
                }
            }
        }

        System.arraycopy(data, 0, previousData!!, 0, data.size)
        tilesSentLastFrame = tilesSent
        videoDetectedLastFrame = videoDetected
    }
}
