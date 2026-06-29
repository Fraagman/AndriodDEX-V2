package com.example.androidhost.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Presentation
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import android.view.Display
import android.view.Surface
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

class DisplayService : Service() {

    companion object {
        private const val TAG = "DisplayService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "display_service_channel"
        private const val CAPTURE_WIDTH = 1920
        private const val CAPTURE_HEIGHT = 1080
        private const val CAPTURE_DPI = 320
        /** Target frame interval in milliseconds (~30 fps) */
        private const val FRAME_INTERVAL_MS = 33L
        /** Delay after presentation.show() before capturing the first frame */
        private const val PRESENTATION_SETTLE_MS = 150L
        /** How often (ms) to force a full keyframe regardless of tile changes */
        private const val KEYFRAME_INTERVAL_MS = 1000L
    }

    private val binder = LocalBinder()
    private var virtualDisplay: VirtualDisplay? = null
    var surface: Surface? = null
        private set

    inner class LocalBinder : Binder() {
        fun getService(): DisplayService = this@DisplayService
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        createVirtualDisplay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundWithNotification()
        createVirtualDisplay()
        return START_STICKY
    }

    private var imageReader: ImageReader? = null
    @Volatile private var isRunning = false
    private var lastKeyframeTime = 0L
    private val regionDetector = RegionDetector()
    private var desktopPresentation: DesktopPresentation? = null

    /** HandlerThread + Handler that drives frame capture at a steady cadence */
    private var captureHandlerThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    /**
     * Set to true after the Presentation has been shown and a settle delay has
     * elapsed. The capture loop skips frames until this is true, guaranteeing
     * the first captured frame contains the rendered desktop, not an empty surface.
     */
    @Volatile private var presentationReady = false

    /** Used to log pixel data of the very first captured frame for debugging */
    @Volatile private var firstFrameLogged = false

    private fun createVirtualDisplay() {
        if (virtualDisplay != null) return
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        // Use PRESENTATION flag so Activities/Presentations can render on this display
        val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION

        // ImageReader with maxImages=4 to allow a buffer and prevent stalls.
        // RGBA_8888 matches the protobuf rgba_data layout expected by the PC receiver.
        imageReader = ImageReader.newInstance(CAPTURE_WIDTH, CAPTURE_HEIGHT, PixelFormat.RGBA_8888, 4)
        surface = imageReader!!.surface

        if (surface != null) {
            virtualDisplay = displayManager.createVirtualDisplay(
                "AndroidDex", CAPTURE_WIDTH, CAPTURE_HEIGHT, CAPTURE_DPI, surface, flags
            )
            if (virtualDisplay != null) {
                com.example.androidhost.network.FrameSender.start()
                launchDesktopPresentation()
                startCaptureLoop()
            }
        }
    }

    /**
     * Launch a Presentation on the VirtualDisplay that hosts the Compose DesktopShellContent
     * wrapped in KeepAliveRedraw so it continuously invalidates the surface.
     * The Presentation renders directly onto the VirtualDisplay's Surface, so the ImageReader
     * captures the real desktop UI instead of a test pattern.
     */
    private fun launchDesktopPresentation() {
        val vd = virtualDisplay ?: return
        val display = vd.display ?: return

        try {
            desktopPresentation = DesktopPresentation(this, display)
            desktopPresentation?.show()
            desktopPresentation?.onResume()
            Log.d(TAG, "DesktopPresentation launched on VirtualDisplay")

            // Wait for the Presentation to render its first frame before we start capturing.
            // This avoids capturing an empty/black surface as the "first frame".
            val mainHandler = Handler(mainLooper)
            mainHandler.postDelayed({
                presentationReady = true
                Log.d(TAG, "Presentation settled — capture enabled after ${PRESENTATION_SETTLE_MS}ms delay")
            }, PRESENTATION_SETTLE_MS)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch DesktopPresentation", e)
            // If presentation fails, still allow capture (will show whatever the surface has)
            presentationReady = true
        }
    }

    /**
     * Drives frame capture from a steady ~30fps HandlerThread loop.
     *
     * On each tick:
     *  1. acquireLatestImage() — returns the most recent rendered frame (or null if
     *     ImageReader has nothing new). KeepAliveRedraw ensures every VSYNC produces
     *     a new frame, so this should almost never return null in steady state.
     *  2. Copy pixel data respecting rowStride (may differ from width*4).
     *  3. Run through RegionDetector for tile diffs.
     *  4. If RegionDetector sent zero tiles (static desktop), send a full keyframe
     *     so the PC always has something to display.
     *  5. Periodically send a full keyframe regardless (every KEYFRAME_INTERVAL_MS).
     *  6. Always close() the Image to free the buffer slot.
     *
     * This replaces the old Thread.sleep()-based loop. HandlerThread + postDelayed
     * gives more precise scheduling without thread-sleep drift.
     */
    private fun startCaptureLoop() {
        isRunning = true
        captureHandlerThread = HandlerThread("DisplayCapture").also { it.start() }
        captureHandler = Handler(captureHandlerThread!!.looper)

        val captureRunnable = object : Runnable {
            override fun run() {
                if (!isRunning) return

                try {
                    captureOneFrame()
                } catch (e: Exception) {
                    Log.e(TAG, "Error in capture loop", e)
                }

                // Schedule next frame
                if (isRunning) {
                    captureHandler?.postDelayed(this, FRAME_INTERVAL_MS)
                }
            }
        }

        // Start the loop
        captureHandler?.postDelayed(captureRunnable, FRAME_INTERVAL_MS)
    }

    /**
     * Captures a single frame from the ImageReader, copies pixel data respecting
     * rowStride, runs region detection, and sends frames via FrameSender.
     */
    private fun captureOneFrame() {
        // Don't capture until presentation has rendered at least once
        if (!presentationReady) return

        val reader = imageReader ?: return
        val image = reader.acquireLatestImage() ?: return

        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride

            val width = CAPTURE_WIDTH
            val height = CAPTURE_HEIGHT
            val data = ByteArray(width * height * 4)

            if (rowStride == width * 4 && pixelStride == 4) {
                // Fast path: no padding, direct copy
                buffer.get(data)
            } else {
                // Slow path: must copy row-by-row to account for rowStride padding.
                // Without this, the image would be garbled or shifted because the
                // ImageReader's internal buffer rows may be wider than width*4.
                for (y in 0 until height) {
                    buffer.position(y * rowStride)
                    buffer.get(data, y * width * 4, width * pixelStride)
                }
            }

            // Log the first frame's pixel data for debugging
            if (!firstFrameLogged) {
                val allZero = data.take(64).all { it == 0.toByte() }
                if (allZero) {
                    Log.w(TAG, "WARNING: First frame pixels are all zero — skipping send to wait for render")
                    return
                }
                firstFrameLogged = true
                val firstBytes = data.take(16).joinToString(" ") { String.format("0x%02X", it) }
                Log.d(TAG, "FIRST FRAME captured: first 16 bytes = [$firstBytes], all-zero(64)=false, rowStride=$rowStride, pixelStride=$pixelStride")
            }

            val currentTime = System.currentTimeMillis()
            val isKeyframeTime = (currentTime - lastKeyframeTime >= KEYFRAME_INTERVAL_MS)

            // Always process through region detector for tile diffs
            regionDetector.processFrame(width, height, data)
            com.example.androidhost.vm.DisplayViewModel.updateRegionStats(
                regionDetector.tilesSentLastFrame, regionDetector.videoDetectedLastFrame
            )

            // Send a full keyframe if:
            // - It's been >= 1 second since the last keyframe, OR
            // - RegionDetector sent zero tiles (static desktop — PC has nothing to display otherwise)
            if (isKeyframeTime || regionDetector.tilesSentLastFrame == 0) {
                com.example.androidhost.network.FrameSender.sendVideoFrame(width, height, data)
                lastKeyframeTime = currentTime
            }
        } finally {
            // ALWAYS close the image to free the buffer slot.
            // Failing to close causes "maxImages (3) has already been acquired" stalls.
            image.close()
        }
    }

    /**
     * Use startForeground() instead of notificationManager.notify() so Android O+
     * does not kill this service, which would release the VirtualDisplay/ImageReader.
     */
    private fun startForegroundWithNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Display Service", NotificationManager.IMPORTANCE_LOW)
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Display active")
            .setContentText("VirtualDisplay is running")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        captureHandler?.removeCallbacksAndMessages(null)
        captureHandlerThread?.quitSafely()
        captureHandler = null
        captureHandlerThread = null
        desktopPresentation?.dismiss()
        desktopPresentation = null
        com.example.androidhost.network.FrameSender.stop()
        virtualDisplay?.release()
        surface?.release()
        imageReader?.close()
    }
}

/**
 * Presentation that hosts the Compose DesktopShellContent on the VirtualDisplay,
 * wrapped in KeepAliveRedraw to force continuous surface invalidation.
 * This is what gets captured by the ImageReader and sent to the PC as video frames.
 *
 * Implements LifecycleOwner, SavedStateRegistryOwner, and ViewModelStoreOwner
 * so Compose can fully function inside this Presentation window.
 */
class DesktopPresentation(
    context: Context,
    display: Display
) : Presentation(context, display), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override val viewModelStore: ViewModelStore
        get() = store

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        savedStateRegistryController.performRestore(savedInstanceState)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        val composeView = ComposeView(context).apply {
            setContent {
                // KeepAliveRedraw wraps the desktop content to force continuous
                // VirtualDisplay surface invalidation. This ensures ImageReader
                // always has a fresh frame to deliver, even when the UI is static.
                com.example.androidhost.KeepAliveRedraw {
                    com.example.androidhost.DesktopShellContent(
                        isTetheringReady = true,
                        surface = null,                                          // VirtualDisplay does NOT embed a surface
                        shellViewModel = com.example.androidhost.vm.ShellHolder.shellViewModel,  // REAL shared view model
                        onLockSession = { /* trigger lock via service */ },
                        onRequestAudioCapture = { /* forward to audio capture flow */ }
                    )
                }
            }
        }

        // Wire up lifecycle, view model store, and saved state for Compose
        composeView.setViewTreeLifecycleOwner(this)
        composeView.setViewTreeViewModelStoreOwner(this)
        composeView.setViewTreeSavedStateRegistryOwner(this)

        setContentView(composeView)
    }

    override fun onStart() {
        super.onStart()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
    }

    fun onResume() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onStop() {
        super.onStop()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    }

    override fun dismiss() {
        super.dismiss()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
    }
}
