package com.example.androidhost.video

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Rolling one-second throughput window for the encoder.
 *
 * [record] is called from the encoder's callback thread once per encoded frame and does
 * no allocation — it only bumps primitive counters. A [Snapshot] is published at most
 * once per second, when the window rolls over, so the measurement itself cannot become
 * the per-frame cost it exists to measure.
 */
class EncoderStats {

    /** Throughput over the window that just closed. */
    data class Snapshot(
        val fps: Int,
        val kilobitsPerSecond: Int,
        val keyframes: Int,
        val totalFrames: Long
    )

    private companion object {
        const val WINDOW_MS = 1000L
    }

    private val _latest = MutableStateFlow(Snapshot(0, 0, 0, 0))

    /** Latest closed window. Updates at most once per second. */
    val latest: StateFlow<Snapshot> = _latest.asStateFlow()

    private var windowStartMs = 0L
    private var windowFrames = 0
    private var windowBytes = 0L
    private var windowKeyframes = 0
    private var totalFrames = 0L

    /**
     * Records one encoded access unit.
     *
     * @return the snapshot if this call closed a window, otherwise null. Callers use the
     *         return value to log without having to track timing themselves.
     */
    fun record(sizeBytes: Int, isKeyframe: Boolean): Snapshot? {
        val now = SystemClock.elapsedRealtime()
        if (windowStartMs == 0L) windowStartMs = now

        windowFrames++
        windowBytes += sizeBytes
        totalFrames++
        if (isKeyframe) windowKeyframes++

        val elapsed = now - windowStartMs
        if (elapsed < WINDOW_MS) return null

        // bytes -> kilobits per second, scaled by the true window length so a window
        // that ran long does not overstate the rate.
        val kbps = ((windowBytes * 8L * 1000L) / (elapsed * 1000L)).toInt()
        val snapshot = Snapshot(
            fps = ((windowFrames * 1000L) / elapsed).toInt(),
            kilobitsPerSecond = kbps,
            keyframes = windowKeyframes,
            totalFrames = totalFrames
        )

        windowStartMs = now
        windowFrames = 0
        windowBytes = 0
        windowKeyframes = 0

        _latest.value = snapshot
        return snapshot
    }

    /** Clears the window. Call when the encoder restarts. */
    fun reset() {
        windowStartMs = 0L
        windowFrames = 0
        windowBytes = 0
        windowKeyframes = 0
        totalFrames = 0
        _latest.value = Snapshot(0, 0, 0, 0)
    }
}
