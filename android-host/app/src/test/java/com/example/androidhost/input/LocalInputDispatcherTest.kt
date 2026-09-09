package com.example.androidhost.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
class LocalInputDispatcherTest {

    @Test
    fun testExactCornersScaling() {
        // Target 1920x1080
        val targetWidth = 1920
        val targetHeight = 1080

        // Top-left corner: (0, 0)
        assertEquals(0.0f, LocalInputDispatcher.scaleX(0, targetWidth), 0.001f)
        assertEquals(0.0f, LocalInputDispatcher.scaleY(0, targetHeight), 0.001f)

        // Bottom-right corner: (1919, 1079)
        assertEquals(1919.0f, LocalInputDispatcher.scaleX(1919, targetWidth), 0.001f)
        assertEquals(1079.0f, LocalInputDispatcher.scaleY(1079, targetHeight), 0.001f)
    }

    @Test
    fun testMidpointScaling() {
        val targetWidth = 1920
        val targetHeight = 1080

        // Midpoint: (960, 540)
        assertEquals(960.0f, LocalInputDispatcher.scaleX(960, targetWidth), 0.001f)
        assertEquals(540.0f, LocalInputDispatcher.scaleY(540, targetHeight), 0.001f)

        // Midpoint on non-1080p target display (e.g. 1280x720)
        val altWidth = 1280
        val altHeight = 720
        assertEquals(640.0f, LocalInputDispatcher.scaleX(960, altWidth), 0.001f)
        assertEquals(360.0f, LocalInputDispatcher.scaleY(540, altHeight), 0.001f)
    }

    @Test
    fun testOutOfRangeInputClamped() {
        val targetWidth = 1920
        val targetHeight = 1080

        // Below minimum (negative coordinates) must clamp to 0.0f
        assertEquals(0.0f, LocalInputDispatcher.scaleX(-100, targetWidth), 0.001f)
        assertEquals(0.0f, LocalInputDispatcher.scaleY(-50, targetHeight), 0.001f)

        // Above maximum must clamp to (WIRE_MAX - 1)
        assertEquals(1919.0f, LocalInputDispatcher.scaleX(5000, targetWidth), 0.001f)
        assertEquals(1079.0f, LocalInputDispatcher.scaleY(3000, targetHeight), 0.001f)
    }

    @Test
    fun testTextInsertOwnership() {
        val ownerA = Any()
        val ownerB = Any()
        
        var receivedA: String? = null
        var receivedB: String? = null
        
        // Owner A registers
        LocalInputDispatcher.registerTextInsert(ownerA) { receivedA = it }
        ShadowLooper.runUiThreadTasks()
        
        // Owner B registers, overwriting A
        LocalInputDispatcher.registerTextInsert(ownerB) { receivedB = it }
        ShadowLooper.runUiThreadTasks()
        
        // Owner A deregisters (stale blur)
        LocalInputDispatcher.registerTextInsert(ownerA, null)
        ShadowLooper.runUiThreadTasks()
        
        // B's callback must survive. Let's fire some text and check
        LocalInputDispatcher.onText("hello")
        ShadowLooper.runUiThreadTasks()
        
        assertNull("A should not receive text", receivedA)
        assertEquals("B should survive the stale deregistration", "hello", receivedB)
    }
}
