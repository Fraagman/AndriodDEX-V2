package com.example.androidhost.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class EncoderStatsTest {

    @Test
    fun testUnclosedWindowReturnsNull() {
        val stats = EncoderStats()
        // First frame establishes start time at t = 1000ms
        val snap1 = stats.record(sizeBytes = 10000, isKeyframe = true, nowMs = 1000L)
        assertNull("First frame must not close window", snap1)

        // Second frame at t = 1500ms (elapsed 500ms < 1000ms)
        val snap2 = stats.record(sizeBytes = 8000, isKeyframe = false, nowMs = 1500L)
        assertNull("Frame within window (< 1000ms) must return null", snap2)
        assertEquals(0, stats.latest.value.fps)
    }

    @Test
    fun testClosedWindowReportsRecordedFramesAndKeyframes() {
        val stats = EncoderStats()
        // Window start at t = 1000ms
        stats.record(sizeBytes = 25000, isKeyframe = true, nowMs = 1000L)
        stats.record(sizeBytes = 10000, isKeyframe = false, nowMs = 1300L)
        stats.record(sizeBytes = 10000, isKeyframe = false, nowMs = 1600L)
        stats.record(sizeBytes = 30000, isKeyframe = true, nowMs = 1900L)

        // 5th frame closes the window at t = 2000ms (elapsed 1000ms >= 1000ms)
        val snap = stats.record(sizeBytes = 15000, isKeyframe = false, nowMs = 2000L)
        assertNotNull("Frame at >= 1000ms elapsed must close window and return Snapshot", snap)

        snap!!
        assertEquals(5, snap.fps)
        assertEquals(2, snap.keyframes)
        assertEquals(5L, snap.totalFrames)
        // total bytes = 25000 + 10000 + 10000 + 30000 + 15000 = 90000 bytes
        // kbps = (90000 * 8 * 1000) / (1000 * 1000) = 720 kbps
        assertEquals(720, snap.kilobitsPerSecond)
        assertEquals(snap, stats.latest.value)
    }

    @Test
    fun testResetClearsState() {
        val stats = EncoderStats()
        stats.record(sizeBytes = 50000, isKeyframe = true, nowMs = 1000L)
        stats.record(sizeBytes = 50000, isKeyframe = false, nowMs = 2000L)

        assertEquals(2, stats.latest.value.fps)
        assertEquals(2L, stats.latest.value.totalFrames)

        stats.reset()

        assertEquals(0, stats.latest.value.fps)
        assertEquals(0, stats.latest.value.keyframes)
        assertEquals(0, stats.latest.value.kilobitsPerSecond)
        assertEquals(0L, stats.latest.value.totalFrames)

        // Next frame after reset starts a new window
        val snap = stats.record(sizeBytes = 10000, isKeyframe = true, nowMs = 5000L)
        assertNull(snap)
    }
}
