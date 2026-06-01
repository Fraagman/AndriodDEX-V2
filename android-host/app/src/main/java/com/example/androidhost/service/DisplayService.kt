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
import android.os.IBinder
import android.util.Log
import android.view.Display
import android.view.Surface
import androidx.activity.compose.setContent
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

class DisplayService : Service() {

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

    private var imageReader: ImageReader? = null
    private var isRunning = false
    private var captureThread: Thread? = null
    private var lastKeyframeTime = 0L
    private val regionDetector = RegionDetector()
    private var desktopPresentation: DesktopPresentation? = null

    private fun createVirtualDisplay() {
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val width = 1920
        val height = 1080
        val dpi = 320
        // Use PRESENTATION flag so Activities/Presentations can render on this display
        val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION

        // Using ImageReader to capture the VirtualDisplay framebuffer
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        surface = imageReader!!.surface

        if (surface != null) {
            virtualDisplay = displayManager.createVirtualDisplay("AndroidDex", width, height, dpi, surface, flags)
            if (virtualDisplay != null) {
                com.example.androidhost.network.FrameSender.start()
                launchDesktopPresentation()
                startCaptureThread(width, height)
                showNotification()
            }
        }
    }

    /**
     * Launch a Presentation on the VirtualDisplay that hosts the Compose DesktopShellContent.
     * The Presentation renders directly onto the VirtualDisplay's Surface, so the ImageReader
     * captures the real desktop UI instead of a test pattern.
     */
    private fun launchDesktopPresentation() {
        val vd = virtualDisplay ?: return
        val display = vd.display ?: return

        try {
            desktopPresentation = DesktopPresentation(this, display)
            desktopPresentation?.show()
            Log.d("DisplayService", "DesktopPresentation launched on VirtualDisplay")
        } catch (e: Exception) {
            Log.e("DisplayService", "Failed to launch DesktopPresentation", e)
        }
    }

    /**
     * Capture thread: reads frames from ImageReader (which captures what the Presentation
     * renders on the VirtualDisplay) and sends them via FrameSender. No manual drawing.
     */
    private fun startCaptureThread(width: Int, height: Int) {
        isRunning = true
        captureThread = Thread {
            while (isRunning) {
                try {
                    val image = imageReader?.acquireLatestImage()
                    if (image != null) {
                        val plane = image.planes[0]
                        val buffer = plane.buffer
                        val pixelStride = plane.pixelStride
                        val rowStride = plane.rowStride

                        val data = ByteArray(width * height * 4)
                        if (rowStride == width * 4 && pixelStride == 4) {
                            buffer.get(data)
                        } else {
                            val rowData = ByteArray(rowStride)
                            for (y in 0 until height) {
                                buffer.position(y * rowStride)
                                buffer.get(rowData, 0, Math.min(rowStride, buffer.remaining()))
                                for (x in 0 until width) {
                                    val srcIdx = x * pixelStride
                                    val dstIdx = (y * width + x) * 4
                                    data[dstIdx] = rowData[srcIdx]
                                    data[dstIdx+1] = rowData[srcIdx+1]
                                    data[dstIdx+2] = rowData[srcIdx+2]
                                    data[dstIdx+3] = rowData[srcIdx+3]
                                }
                            }
                        }
                        
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastKeyframeTime >= 1000) {
                            com.example.androidhost.network.FrameSender.sendVideoFrame(width, height, data)
                            lastKeyframeTime = currentTime
                            // Also process so RegionDetector updates its previous frame buffer
                            regionDetector.processFrame(width, height, data)
                        } else {
                            regionDetector.processFrame(width, height, data)
                            com.example.androidhost.vm.DisplayViewModel.updateRegionStats(regionDetector.tilesSentLastFrame, regionDetector.videoDetectedLastFrame)
                        }
                        
                        image.close()
                    }
                } catch (e: Exception) {
                    Log.e("DisplayService", "Error in capture thread", e)
                    if (e is InterruptedException) {
                        break
                    }
                }
                try {
                    Thread.sleep(33) // ~30 FPS
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
        captureThread?.start()
    }

    private fun showNotification() {
        val channelId = "display_service_channel"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Display Service", NotificationManager.IMPORTANCE_LOW)
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Display active")
            .setContentText("VirtualDisplay is running")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()

        notificationManager.notify(1001, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        captureThread?.interrupt()
        desktopPresentation?.dismiss()
        desktopPresentation = null
        com.example.androidhost.network.FrameSender.stop()
        virtualDisplay?.release()
        surface?.release()
        imageReader?.close()
    }
}

/**
 * Presentation that hosts the Compose DesktopShellContent on the VirtualDisplay.
 * This is what gets captured by the ImageReader and sent to the PC as video frames.
 */
class DesktopPresentation(
    context: Context,
    display: Display
) : Presentation(context, display), LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        savedStateRegistryController.performRestore(savedInstanceState)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        val composeView = ComposeView(context).apply {
            setContent {
                com.example.androidhost.DesktopShellContent(
                    isTetheringReady = true,
                    surface = null,
                    shellViewModel = null,
                    onLockSession = {},
                    onRequestAudioCapture = {}
                )
            }
        }

        // Wire up lifecycle and saved state for Compose
        composeView.setViewTreeLifecycleOwner(this)
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
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.dismiss()
    }
}
