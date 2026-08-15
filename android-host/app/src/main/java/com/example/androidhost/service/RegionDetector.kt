package com.example.androidhost.service

import android.util.Log
import com.example.androidhost.network.FrameSender

/**
 * Dirty-tile detector for the VirtualDisplay capture loop.
 *
 * The captured surface is split into a grid of [TILE_SIZE]x[TILE_SIZE] tiles. Each
 * tile's pixels are hashed with FNV-1a 64. A tile whose hash differs from the previous
 * frame's hash is considered dirty, compressed with zstd, and pushed to the PC via
 * [FrameSender.sendTileUpdate] as a raw-RGBA TileUpdate — the exact payload
 * `zc-video`'s tile_compositor expects (`zstd::stream::copy_decode` into a
 * `width * height * 4` RGBA buffer).
 *
 * Two cases deliberately send zero tiles and let [DisplayService] fall back to a full
 * keyframe instead:
 *  - the first frame after a resolution change, where there is no baseline to diff against;
 *  - a frame where more than [MAX_TILES_PER_FRAME] tiles changed, where a single
 *    full-frame send is cheaper than hundreds of individually compressed tiles.
 *
 * Not thread safe. [processFrame] is called only from DisplayService's capture
 * HandlerThread, which is single-threaded.
 */
class RegionDetector {

    companion object {
        private const val TAG = "RegionDetector"

        /** Edge length of one tile in pixels. */
        const val TILE_SIZE = 64

        /** Bytes per pixel in the RGBA_8888 capture buffer. */
        private const val BYTES_PER_PIXEL = 4

        /**
         * Above this many dirty tiles, fall back to a keyframe. 96 tiles of 64x64 is
         * roughly 12% of a 1920x1080 grid (30 x 17 = 510 tiles).
         */
        private const val MAX_TILES_PER_FRAME = 96

        /**
         * Fraction of the grid that must change in one frame for the content to be
         * classified as video-like rather than as ordinary UI updates.
         */
        private const val VIDEO_TILE_RATIO = 0.35f

        private const val FNV_OFFSET_BASIS = -0x340d631b7bdddcdbL // 14695981039346656037
        private const val FNV_PRIME = 0x100000001b3L
    }

    /** Number of tiles emitted during the most recent [processFrame] call. */
    var tilesSentLastFrame: Int = 0
        private set

    /** True when the most recent frame changed enough of the grid to look like video. */
    var videoDetectedLastFrame: Boolean = false
        private set

    private var tileCols = 0
    private var tileRows = 0
    private var frameWidth = 0
    private var frameHeight = 0

    /** Per-tile FNV-1a hash of the previous frame, indexed row-major. */
    private var previousHashes = LongArray(0)

    /** True once [previousHashes] holds a full baseline frame. */
    private var hasBaseline = false

    /** Scratch buffer holding one tile's pixels contiguously before compression. */
    private var tileBuffer = ByteArray(0)

    /**
     * Hashes every tile of [rgba], sends the ones that changed since the previous
     * frame, and updates [tilesSentLastFrame] / [videoDetectedLastFrame].
     *
     * @param width  frame width in pixels
     * @param height frame height in pixels
     * @param rgba   tightly packed RGBA_8888 pixels, `width * height * 4` bytes
     */
    fun processFrame(width: Int, height: Int, rgba: ByteArray) {
        tilesSentLastFrame = 0
        videoDetectedLastFrame = false

        val expected = width * height * BYTES_PER_PIXEL
        if (width <= 0 || height <= 0 || rgba.size < expected) {
            Log.w(TAG, "Ignoring malformed frame: ${width}x$height, ${rgba.size} bytes (need $expected)")
            return
        }

        if (width != frameWidth || height != frameHeight) {
            resizeGrid(width, height)
        }

        val totalTiles = tileCols * tileRows
        if (totalTiles == 0) return

        // Pass 1: hash every tile and record which ones changed.
        val dirty = IntArray(totalTiles)
        var dirtyCount = 0

        for (row in 0 until tileRows) {
            val tileY = row * TILE_SIZE
            val tileH = minOf(TILE_SIZE, height - tileY)
            for (col in 0 until tileCols) {
                val tileX = col * TILE_SIZE
                val tileW = minOf(TILE_SIZE, width - tileX)
                val hash = hashTile(rgba, width, tileX, tileY, tileW, tileH)
                val index = row * tileCols + col
                if (!hasBaseline || previousHashes[index] != hash) {
                    dirty[dirtyCount++] = index
                }
                previousHashes[index] = hash
            }
        }

        // First frame after a resize has no baseline; every tile counts as "changed",
        // which is not evidence of video. DisplayService sends a keyframe for it.
        if (!hasBaseline) {
            hasBaseline = true
            return
        }

        videoDetectedLastFrame = dirtyCount.toFloat() / totalTiles >= VIDEO_TILE_RATIO

        // Too much changed for tiles to be worthwhile — let the keyframe path handle it.
        if (dirtyCount == 0 || dirtyCount > MAX_TILES_PER_FRAME) return

        // Pass 2: compress and send only the dirty tiles.
        var sent = 0
        for (i in 0 until dirtyCount) {
            val index = dirty[i]
            val row = index / tileCols
            val col = index % tileCols
            val tileX = col * TILE_SIZE
            val tileY = row * TILE_SIZE
            val tileW = minOf(TILE_SIZE, width - tileX)
            val tileH = minOf(TILE_SIZE, height - tileY)
            if (sendTile(rgba, width, tileX, tileY, tileW, tileH)) sent++
        }
        tilesSentLastFrame = sent
    }

    /**
     * Drops the baseline so the next frame is treated as a fresh start. Call after the
     * VirtualDisplay is recreated, since the PC's tile cache is stale at that point.
     */
    fun reset() {
        hasBaseline = false
    }

    private fun resizeGrid(width: Int, height: Int) {
        frameWidth = width
        frameHeight = height
        tileCols = (width + TILE_SIZE - 1) / TILE_SIZE
        tileRows = (height + TILE_SIZE - 1) / TILE_SIZE
        previousHashes = LongArray(tileCols * tileRows)
        tileBuffer = ByteArray(TILE_SIZE * TILE_SIZE * BYTES_PER_PIXEL)
        hasBaseline = false
        Log.d(TAG, "Tile grid: ${tileCols}x$tileRows for ${width}x$height frame")
    }

    /**
     * FNV-1a 64 over the tile's pixel rows. The buffer is row-major over the whole
     * frame, so each tile row is a separate slice `tileW * 4` bytes long.
     */
    private fun hashTile(
        rgba: ByteArray,
        frameWidth: Int,
        tileX: Int,
        tileY: Int,
        tileW: Int,
        tileH: Int
    ): Long {
        var hash = FNV_OFFSET_BASIS
        val rowBytes = tileW * BYTES_PER_PIXEL
        for (y in 0 until tileH) {
            var offset = ((tileY + y) * frameWidth + tileX) * BYTES_PER_PIXEL
            val end = offset + rowBytes
            while (offset < end) {
                hash = (hash xor (rgba[offset].toLong() and 0xFF)) * FNV_PRIME
                offset++
            }
        }
        return hash
    }

    /**
     * Copies one tile into a contiguous buffer, zstd-compresses it, and hands it to
     * [FrameSender]. Returns true when the tile was handed off.
     */
    private fun sendTile(
        rgba: ByteArray,
        frameWidth: Int,
        tileX: Int,
        tileY: Int,
        tileW: Int,
        tileH: Int
    ): Boolean {
        val rowBytes = tileW * BYTES_PER_PIXEL
        val tileBytes = rowBytes * tileH
        for (y in 0 until tileH) {
            val src = ((tileY + y) * frameWidth + tileX) * BYTES_PER_PIXEL
            System.arraycopy(rgba, src, tileBuffer, y * rowBytes, rowBytes)
        }

        return try {
            // Send exact-length copy when the tile is smaller than the full-size scratch buffer (right/bottom edges).
            val payload = if (tileBytes == tileBuffer.size) tileBuffer else tileBuffer.copyOf(tileBytes)
            FrameSender.sendTileUpdate(tileX, tileY, tileW, tileH, payload)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send tile at $tileX,$tileY", e)
            false
        }
    }
}
