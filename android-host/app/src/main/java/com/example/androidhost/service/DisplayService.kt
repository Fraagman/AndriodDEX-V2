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
import kotlinx.coroutines.flow.MutableStateFlow

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
         *
         * Volatile: written from whatever thread applies a settings change, read from
         * the MediaCodec callback thread when frames are framed for the wire.
         */
        @Volatile
        var CAPTURE_WIDTH = 1920
        @Volatile
        var CAPTURE_HEIGHT = 1080
        @Volatile
        var BIT_RATE = 12_000_000 // 12 Mbps default

        /**
         * 160 dpi → density 1.0, so 1 dp = 1 px on the virtual display. The shell's
         * geometry (window bounds, the 48 px taskbar, maximized = full height minus
         * the taskbar) is defined in screen pixels; at 320 dpi every dp value was
         * doubled and windows rendered twice as large as the display.
         */
        private const val CAPTURE_DPI = 160

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

        /**
         * Bumped once per second while the pipeline runs. The shell collects this so
         * Compose recomposes at 1 Hz even when nothing on the desktop changes: the
         * encoder only emits frames when new pixels arrive, and a joining client's
         * keyframe request can only be answered once they do. This replaces the taskbar
         * clock, whose per-second recomposition silently did this job. (The encoder
         * ignores KEY_REPEAT_PREVIOUS_FRAME_AFTER — measured on c2.qti.avc.encoder.)
         */
        val frameTick = MutableStateFlow(0L)

        var instance: DisplayService? = null
            private set

        fun requestKeyframe() {
            instance?.screenEncoder?.requestKeyframe()
        }

        fun updateResolution(width: Int, height: Int) {
            // Defensive validation: the Settings UI enforces this too, but the
            // encoder dies on odd/oversized dimensions and must never see them.
            val safeW = width.coerceIn(16, 3840) and 0xFE
            val safeH = height.coerceIn(16, 3840) and 0xFE
            if (CAPTURE_WIDTH == safeW && CAPTURE_HEIGHT == safeH) return
            CAPTURE_WIDTH = safeW
            CAPTURE_HEIGHT = safeH
            instance?.let { service ->
                // Dynamically reconfigure pipeline to apply new resolution without destroying DesktopPresentation
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    service.reconfigureResolution()
                }
            }
        }

        fun updateBitrate(kbps: Int) {
            // Overflow guard: kbps * 1000 overflows Int above ~2.1 Gbps, and a
            // negative bitrate makes MediaCodec.configure throw.
            val safe = kbps.coerceIn(100, 100_000)
            val bps = safe * 1000
            if (BIT_RATE == bps) return
            BIT_RATE = bps
            instance?.screenEncoder?.setBitrate(bps)
        }
    }

    private val binder = LocalBinder()
    private var virtualDisplay: VirtualDisplay? = null
    private var screenEncoder: ScreenEncoder? = null
    private var desktopPresentation: DesktopPresentation? = null

    /**
     * The encoder's input surface. Retained for diagnostics; the shell reads
     * pipeline state through this class's companion instead of binding.
     */
    var surface: Surface? = null
        private set

    /** Watches for a client completing pairing so it can be sent a keyframe. */
    private val clientWatchHandler = Handler(Looper.getMainLooper())
    private var lastQuicState = -1

    private val frameTickHandler = Handler(Looper.getMainLooper())
    private val frameTickRunnable = object : Runnable {
        override fun run() {
            frameTick.value++
            frameTickHandler.postDelayed(this, 1000)
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): DisplayService = this@DisplayService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Foreground status must be established before any pipeline work: if this
        // service is ever created via bind instead of start, it still runs as a
        // proper foreground service and cannot be culled by the system.
        startForegroundWithNotification()
        startEncodingPipeline()
        startClientWatch()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundWithNotification()
        val targetWidth = intent?.getIntExtra("WIDTH", -1) ?: -1
        val targetHeight = intent?.getIntExtra("HEIGHT", -1) ?: -1
        if (targetWidth > 0 && targetHeight > 0) {
            updateResolution(targetWidth, targetHeight)
        } else {
            startEncodingPipeline()
        }
        return START_STICKY
    }

    /**
     * Brings up the encoder, then the VirtualDisplay that feeds it, then the Presentation
     * that draws into the display. Order matters: the encoder's input surface must exist
     * before the display can be created against it.
     */
    private fun startEncodingPipeline() {
        if (virtualDisplay != null) return

        val encoder = ScreenEncoder(CAPTURE_WIDTH, CAPTURE_HEIGHT, BIT_RATE, encoderListener)
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
        frameTickHandler.post(frameTickRunnable)
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
     * Polls the QUIC connection state so the pipeline tracks its client:
     *  - a client arriving on a paused pipeline brings it back up and gets a keyframe,
     *  - the client leaving pauses the pipeline, so no client means no encoder burn.
     *
     * The watch runs for the service's lifetime — it must survive
     * [stopEncodingPipeline] (which is what a pause is) to notice the client
     * returning. `QuicServer` exposes no callback, only a polled state.
     */
    private fun startClientWatch() {
        clientWatchHandler.post(object : Runnable {
            override fun run() {
                val state = QuicServer.getConnectionState()
                if (state == QUIC_STATE_AUTHENTICATED && lastQuicState != QUIC_STATE_AUTHENTICATED) {
                    if (virtualDisplay == null) {
                        Log.i(TAG, "Client authenticated — resuming paused pipeline")
                        startEncodingPipeline()
                    }
                    Log.i(TAG, "Client authenticated — requesting keyframe")
                    screenEncoder?.requestKeyframe(bypassCooldown = true)
                } else if (state != QUIC_STATE_AUTHENTICATED && lastQuicState == QUIC_STATE_AUTHENTICATED && virtualDisplay != null) {
                    Log.i(TAG, "Client gone — pausing pipeline until it returns")
                    stopEncodingPipeline()
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
            if (com.example.androidhost.BuildConfig.DEBUG) {
                Log.i(
                    TAG,
                    "encode ${closed.fps} fps, ${closed.kilobitsPerSecond} kbps, " +
                        "${closed.keyframes} keyframes, ${closed.totalFrames} total"
                )
            }
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

        val contentIntent = android.app.PendingIntent.getActivity(
            this,
            0,
            Intent(this, com.example.androidhost.MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Display active")
            .setContentText("VirtualDisplay is running")
            .setSmallIcon(com.example.androidhost.R.drawable.ic_launcher_foreground)
            .setContentIntent(contentIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * Dynamically switches the hardware encoder to the new resolution and updates
     * the existing VirtualDisplay without dismissing DesktopPresentation.
     *
     * The new encoder is prepared *before* the old one is released: if the codec
     * rejects the new size, the display keeps its live surface and the pipeline
     * keeps streaming at the old resolution. Releasing first would leave the
     * VirtualDisplay rendering into a dead surface — a permanent black screen.
     */
    private fun reconfigureResolution() {
        val display = virtualDisplay
        if (display == null) {
            startEncodingPipeline()
            return
        }

        Log.i(TAG, "Reconfiguring resolution to ${CAPTURE_WIDTH}x${CAPTURE_HEIGHT}")

        // 1. Prepare the new encoder first. On failure the current pipeline is
        //    untouched and keeps streaming at the previous size.
        val encoder = ScreenEncoder(CAPTURE_WIDTH, CAPTURE_HEIGHT, BIT_RATE, encoderListener)
        try {
            encoder.prepare()
        } catch (e: Exception) {
            Log.e(TAG, "Encoder rejected ${CAPTURE_WIDTH}x$CAPTURE_HEIGHT; keeping the live pipeline", e)
            // Roll the companion back so the UI reflects what is actually streaming.
            val mode = display.display.mode
            if (mode != null) {
                CAPTURE_WIDTH = mode.physicalWidth
                CAPTURE_HEIGHT = mode.physicalHeight
            }
            return
        }

        // 2. Swap the display onto the new surface, then release the old encoder:
        //    the display must never point at a released surface.
        val oldEncoder = screenEncoder
        val newSurface = encoder.inputSurface
        display.resize(CAPTURE_WIDTH, CAPTURE_HEIGHT, CAPTURE_DPI)
        display.surface = newSurface
        oldEncoder?.release()

        screenEncoder = encoder
        surface = newSurface

        // 3. Start the new encoder and immediately request an IDR keyframe
        encoderStats.reset()
        encoder.start()
        encoder.requestKeyframe(bypassCooldown = true)
        Log.i(TAG, "Resolution updated successfully to ${CAPTURE_WIDTH}x${CAPTURE_HEIGHT}")
    }

    /**
     * Tears down in the reverse order of construction: stop drawing, stop the frame
     * producer, then release the consumer that owns the surface.
     *
     * The client watch is deliberately NOT stopped here — it is what notices a
     * returning client and restarts the pipeline. It dies with the service.
     */
    private fun stopEncodingPipeline() {
        frameTickHandler.removeCallbacks(frameTickRunnable)

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
        // The poll thread is this service's concern; the QUIC server (process-global)
        // outlives the service and is re-polled by the next start.
        com.example.androidhost.service.InputManager.stopPolling()
        stopEncodingPipeline()
        // The watch is the only thing that outlives the pipeline; it dies here,
        // with the service — and so must the static reference to this context.
        clientWatchHandler.removeCallbacksAndMessages(null)
        if (instance === this) instance = null
    }
}
