package com.example.androidhost.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import com.example.androidhost.quic.QuicServer
import com.example.androidhost.video.EncoderStats
import com.example.androidhost.video.ScreenEncoder
import java.nio.ByteBuffer

/**
 * Owns the VirtualDisplay the desktop shell is rendered into, and the hardware H.264
 * encoder that consumes it.
 *
 * The display's surface *is* the encoder's input surface, so composited frames go from
 * SurfaceFlinger straight into the encoder without ever being read back into application
 * memory. There is no capture loop, no ImageReader and no per-frame buffer: the encoder
 * produces output whenever the shell draws, driven by MediaCodec's async callback.
 */
class DisplayService : Service() {

    companion object {
        private const val TAG = "DisplayService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "display_service_channel"

        /**
         * Dimensions of the VirtualDisplay the desktop is rendered into. Public because
         * LocalInputDispatcher scales incoming PC coordinates into this space — there
         * must be exactly one definition of the desktop's resolution.
         */
        const val CAPTURE_WIDTH = 1920
        const val CAPTURE_HEIGHT = 1080
        private const val CAPTURE_DPI = 320

        /**
         * How often the QUIC connection state is sampled so a freshly paired client can
         * be handed a keyframe. QuicServer exposes no pairing callback, only a polled
         * state, so this is the available mechanism. It is not a per-frame path.
         */
        private const val CLIENT_WATCH_INTERVAL_MS = 500L

        /** QuicServer connection state meaning "PIN verified, ready for frames". */
        private const val QUIC_STATE_AUTHENTICATED = 2

        /**
         * Sustained encoder throughput, updated once per second. Held here rather than
         * per-instance so the desktop shell can display it without binding to the
         * service, which it cannot do from inside the Presentation.
         */
        val encoderStats = EncoderStats()

        var instance: DisplayService? = null
            private set

        fun requestKeyframe() {
            instance?.screenEncoder?.requestKeyframe()
        }
    }

    private val binder = LocalBinder()
    private var virtualDisplay: VirtualDisplay? = null
    private var screenEncoder: ScreenEncoder? = null
    private var desktopPresentation: DesktopPresentation? = null

    /**
     * The encoder's input surface. Exposed for DisplayViewModel, which surfaces it to
     * the UI layer.
     */
    var surface: Surface? = null
        private set

    /** Watches for a client completing pairing so it can be sent a keyframe. */
    private val clientWatchHandler = Handler(Looper.getMainLooper())
    private var lastQuicState = -1

    inner class LocalBinder : Binder() {
        fun getService(): DisplayService = this@DisplayService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        instance = this
        startEncodingPipeline()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundWithNotification()
        startEncodingPipeline()
        return START_STICKY
    }

    /**
     * Brings up the encoder, then the VirtualDisplay that feeds it, then the Presentation
     * that draws into the display. Order matters: the encoder's input surface must exist
     * before the display can be created against it.
     */
    private fun startEncodingPipeline() {
        if (virtualDisplay != null) return

        val encoder = ScreenEncoder(CAPTURE_WIDTH, CAPTURE_HEIGHT, encoderListener)
        try {
            encoder.prepare()
        } catch (e: Exception) {
            Log.e(TAG, "No usable H.264 encoder on this device; display not started", e)
            return
        }

        screenEncoder = encoder
        surface = encoder.inputSurface

        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        // PRESENTATION so a Presentation can target this display; OWN_CONTENT_ONLY so it
        // shows only our shell; PUBLIC so the Presentation is allowed to attach.
        val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC

        val display = displayManager.createVirtualDisplay(
            "AndroidDex", CAPTURE_WIDTH, CAPTURE_HEIGHT, CAPTURE_DPI, surface, flags
        )
        if (display == null) {
            Log.e(TAG, "createVirtualDisplay returned null; tearing encoder back down")
            encoder.release()
            screenEncoder = null
            surface = null
            return
        }
        virtualDisplay = display

        encoderStats.reset()
        encoder.start()
        com.example.androidhost.network.FrameSender.start()
        launchDesktopPresentation()
        startClientWatch()
    }

    /**
     * Shows the Compose desktop on the VirtualDisplay. Because the display renders into
     * the encoder's input surface, everything this Presentation draws is encoded.
     */
    private fun launchDesktopPresentation() {
        val display = virtualDisplay?.display ?: return
        try {
            desktopPresentation = DesktopPresentation(this, display).apply {
                show()
                onResume()
            }
            Log.d(TAG, "DesktopPresentation launched on VirtualDisplay")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch DesktopPresentation", e)
        }
    }

    /**
     * Polls the QUIC connection state and asks for a keyframe on each transition into
     * the authenticated state, so a newly paired client gets a decodable picture
     * immediately instead of waiting for the next scheduled IDR.
     */
    private fun startClientWatch() {
        clientWatchHandler.post(object : Runnable {
            override fun run() {
                if (virtualDisplay == null) return

                val state = QuicServer.getConnectionState()
                if (state == QUIC_STATE_AUTHENTICATED && lastQuicState != QUIC_STATE_AUTHENTICATED) {
                    Log.i(TAG, "Client authenticated — requesting keyframe")
                    screenEncoder?.requestKeyframe()
                }
                lastQuicState = state

                clientWatchHandler.postDelayed(this, CLIENT_WATCH_INTERVAL_MS)
            }
        })
    }

    /**
     * Puts each encoded access unit on the wire and measures sustained throughput.
     *
     * Runs on MediaCodec's callback thread. The NAL is serialized straight out of the
     * codec's own buffer, so the captured pixels are never copied into a frame-sized
     * application buffer at any point in the pipeline.
     */
    private val encoderListener = object : ScreenEncoder.Listener {
        override fun onEncodedFrame(csd: ByteArray?, nal: ByteBuffer, isKeyframe: Boolean, ptsUs: Long) {
            val size = nal.remaining()
            com.example.androidhost.network.FrameSender.sendEncodedFrame(
                nal, csd, isKeyframe, ptsUs, CAPTURE_WIDTH, CAPTURE_HEIGHT
            )

            val closed = encoderStats.record(size, isKeyframe) ?: return
            Log.i(
                TAG,
                "encode ${closed.fps} fps, ${closed.kilobitsPerSecond} kbps, " +
                    "${closed.keyframes} keyframes, ${closed.totalFrames} total"
            )
        }

        override fun onEncoderError(cause: Exception) {
            Log.e(TAG, "Encoder failed; stopping capture pipeline", cause)
            // This runs on MediaCodec's callback thread. Tearing down from here would
            // dismiss the Presentation off the main thread and stop the codec from
            // inside its own callback, so hop to the main looper first.
            clientWatchHandler.post { stopEncodingPipeline() }
        }
    }

    /**
     * Use startForeground() instead of notificationManager.notify() so Android O+
     * does not kill this service, which would release the VirtualDisplay and encoder.
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

    /**
     * Tears down in the reverse order of construction: stop drawing, stop the frame
     * producer, then release the consumer that owns the surface.
     */
    private fun stopEncodingPipeline() {
        clientWatchHandler.removeCallbacksAndMessages(null)

        desktopPresentation?.dismiss()
        desktopPresentation = null

        virtualDisplay?.release()
        virtualDisplay = null

        com.example.androidhost.network.FrameSender.stop()

        // ScreenEncoder.release() releases the input surface it created, so this must
        // not be released separately.
        screenEncoder?.release()
        screenEncoder = null
        surface = null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopEncodingPipeline()
    }
}
