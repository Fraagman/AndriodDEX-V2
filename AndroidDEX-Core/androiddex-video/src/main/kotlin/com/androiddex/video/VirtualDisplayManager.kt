package com.androiddex.video

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.util.DisplayMetrics
import android.view.Surface
import com.androiddex.core.VideoConfig

/**
 * Manages the VirtualDisplay lifecycle, capturing the device screen
 * and routing the raw frames directly to the hardware encoder Surface.
 */
class VirtualDisplayManager(
    private val displayManager: DisplayManager
) {
    private var virtualDisplay: VirtualDisplay? = null

    /**
     * Binds the MediaProjection screen capture to the target Surface provided by the VideoEncoder.
     */
    fun startCapture(
        mediaProjection: MediaProjection,
        targetSurface: Surface,
        config: VideoConfig,
        metrics: DisplayMetrics
    ) {
        if (virtualDisplay != null) {
            stopCapture()
        }

        println("VirtualDisplayManager: Starting capture at ${config.width}x${config.height} @ ${config.fps}fps")

        // Create the VirtualDisplay routing screen contents directly to the MediaCodec Surface
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "AndroidDEX-ScreenCapture",
            config.width,
            config.height,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR or DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
            targetSurface,
            null, // Callback can go here to track display state changes
            null
        )
    }

    /**
     * Stops the screen capture and releases the VirtualDisplay.
     */
    fun stopCapture() {
        virtualDisplay?.release()
        virtualDisplay = null
        println("VirtualDisplayManager: Capture stopped.")
    }
}
