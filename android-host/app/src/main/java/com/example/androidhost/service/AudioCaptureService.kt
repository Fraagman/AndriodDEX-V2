package com.example.androidhost.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.androidhost.BuildConfig
import com.example.androidhost.MainActivity
import com.example.androidhost.quic.QuicServer
import com.google.protobuf.ByteString
import kotlinx.coroutines.flow.MutableStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioCaptureService : Service() {

    companion object {
        private const val TAG = "AudioCaptureService"
        private const val CHANNEL_ID = "audio_capture_channel"
        private const val NOTIFICATION_ID = 2
        private const val PREFS_NAME = "audio_capture_prefs"
        private const val KEY_RESTORE_VOLUME = "restore_volume"

        // Flow to communicate service status to Compose UI
        val isServiceRunning = MutableStateFlow(false)

        fun tryRestoreMutedVolume(context: Context) {
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                if (prefs.contains(KEY_RESTORE_VOLUME)) {
                    val volumeToRestore = prefs.getInt(KEY_RESTORE_VOLUME, -1)
                    if (volumeToRestore >= 0) {
                        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                        am?.setStreamVolume(AudioManager.STREAM_MUSIC, volumeToRestore, 0)
                        Log.d(TAG, "Restored leftover muted volume from previous run: $volumeToRestore")
                    }
                    prefs.edit().remove(KEY_RESTORE_VOLUME).apply()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restore muted volume", e)
            }
        }
    }

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    @Volatile private var isCapturing = false

    private var audioManager: AudioManager? = null
    private var originalVolume: Int = -1

    /** MediaProjection lifecycle: the user can stop the projection from the status
     *  bar chip, the lock screen, or by starting another capture — without this
     *  callback nothing ever learns about it and the service captures silence
     *  forever. Runs on [mainHandler]; unregistered in [stopAudioCapture]. */
    private val mainHandler = Handler(Looper.getMainLooper())
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "MediaProjection stopped by the system; stopping capture")
            mainHandler.post {
                stopAudioCapture()
                stopSelf()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        tryRestoreMutedVolume(this)
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand triggered")

        val resultCode = intent?.getIntExtra("RESULT_CODE", -1) ?: -1
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("DATA", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("DATA")
        }

        if (resultCode == android.app.Activity.RESULT_OK && data != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, createNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(NOTIFICATION_ID, createNotification())
            }
            isServiceRunning.value = true
            startAudioCapture(resultCode, data)
        } else {
            Log.e(TAG, "Invalid result code or screen capture intent data. Stopping service.")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForeground(NOTIFICATION_ID, createNotification())
            }
            stopSelf()
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy triggered - stopping capture")
        stopAudioCapture()
        isServiceRunning.value = false
        super.onDestroy()
    }

    private fun startAudioCapture(resultCode: Int, data: Intent) {
        if (isCapturing) return
        isCapturing = true

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_RESTORE_VOLUME)) {
            val currentVol = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: -1
            if (currentVol >= 0) {
                originalVolume = currentVol
                prefs.edit().putInt(KEY_RESTORE_VOLUME, currentVol).apply()
            }
        } else {
            originalVolume = prefs.getInt(KEY_RESTORE_VOLUME, -1)
        }

        if (originalVolume != -1) {
            audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        }

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = projectionManager.getMediaProjection(resultCode, data)

        if (projection == null) {
            Log.e(TAG, "Failed to retrieve MediaProjection token")
            stopSelf()
            return
        }
        mediaProjection = projection

        // Learn when the projection goes away (status-bar chip stop, screen lock,
        // another capture). Must be registered before the AudioRecord is built.
        projection.registerCallback(projectionCallback, mainHandler)

        // Configure system audio capture
        val config = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .build()
        } else {
            Log.e(TAG, "AudioPlaybackCapture requires Android 10 (Q) or higher")
            stopSelf()
            return
        }

        // Configure Stereo 16-bit 48kHz PCM
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(48000)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()

        val minBufferSize = AudioRecord.getMinBufferSize(
            48000,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Invalid minimum buffer size for AudioRecord")
            stopSelf()
            return
        }

        val bufferSize = minBufferSize * 2

        try {
            audioRecord = AudioRecord.Builder()
                .setAudioFormat(format)
                .setAudioPlaybackCaptureConfig(config)
                .setBufferSizeInBytes(bufferSize)
                .build()
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception creating AudioRecord - lack of permission?", e)
            stopSelf()
            return
        } catch (e: Exception) {
            Log.e(TAG, "Error building AudioRecord", e)
            stopSelf()
            return
        }

        // A build() that threw nothing can still leave the record uninitialized on
        // devices that don't support playback capture at this format; the loop
        // would spin reading -1 and exit silently. Surface it instead.
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord is not initialized (state=${audioRecord?.state}); capture unavailable on this device")
            stopSelf()
            return
        }

        captureThread = Thread {
            Log.d(TAG, "Audio capture thread started")
            val shortBuffer = ShortArray(bufferSize / 2)
            audioRecord?.startRecording()

            var lastLogTime = 0L

            while (isCapturing) {
                val record = audioRecord
                if (record == null || record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    try {
                        Thread.sleep(10)
                    } catch (e: InterruptedException) {
                        break
                    }
                    continue
                }

                val readResult = record.read(shortBuffer, 0, shortBuffer.size)
                if (readResult > 0) {
                    // Convert ShortArray to ByteArray
                    val pcmBytes = ByteArray(readResult * 2)
                    val byteBuf = ByteBuffer.allocateDirect(pcmBytes.size).order(ByteOrder.nativeOrder())
                    byteBuf.asShortBuffer().put(shortBuffer, 0, readResult)
                    byteBuf.get(pcmBytes)

                    // Monotonic clock, like the video path's codec ptsUs: wall clock
                    // jumps with NTP/timezone changes and would corrupt any A/V sync.
                    val nowUs = SystemClock.elapsedRealtimeNanos() / 1000

                    if (BuildConfig.DEBUG) {
                        if (nowUs - lastLogTime >= 100_000) {
                            lastLogTime = nowUs
                            Log.d(TAG, "Audio bytes read: ${pcmBytes.size}")
                            Log.d(TAG, "First 16 bytes: ${pcmBytes.take(16).joinToString("") { "%02x".format(it) }}")
                        }
                    }

                    // Serialize with the generated protobuf class (audio.proto is
                    // compiled by the same task as the wire's other messages).
                    val audioPacketBytes = zc_audio.Audio.AudioPacket.newBuilder()
                        .setPcmData(ByteString.copyFrom(pcmBytes))
                        .setTimestamp(nowUs)
                        .build()
                        .toByteArray()

                    val finalBuffer = ByteBuffer.allocate(1 + audioPacketBytes.size)
                    finalBuffer.put(0x02.toByte()) // Audio type
                    finalBuffer.put(audioPacketBytes)

                    val dataToSend = finalBuffer.array()

                    // Send over QUIC via JNI sendAudioFrame (utilizes separate channel)
                    QuicServer.sendAudioFrame(dataToSend)
                } else if (readResult < 0) {
                    Log.e(TAG, "AudioRecord read error: $readResult; stopping capture")
                    // The thread exits but the flow and notification must not stay
                    // "live" — hop to the main looper and tear the service down.
                    mainHandler.post {
                        stopAudioCapture()
                        stopSelf()
                    }
                    break
                }
            }

            try {
                audioRecord?.stop()
            } catch (e: Exception) {
                // Ignore
            }
            Log.d(TAG, "Audio capture thread stopped")
        }.apply {
            name = "AudioCaptureThread"
            isDaemon = true
            start()
        }
    }

    private fun stopAudioCapture() {
        isCapturing = false

        // The capture thread is likely blocked inside AudioRecord.read(), which is
        // not interruptible; stop/release on the same object from under it is
        // use-after-release. Let the read return and the thread exit first.
        captureThread?.interrupt()
        captureThread?.join(2000)
        captureThread = null

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val restoreVol = if (originalVolume != -1) originalVolume else prefs.getInt(KEY_RESTORE_VOLUME, -1)
        if (restoreVol >= 0) {
            audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, restoreVol, 0)
            originalVolume = -1
        }
        prefs.edit().remove(KEY_RESTORE_VOLUME).apply()

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            // Ignore
        }
        audioRecord = null

        try {
            mediaProjection?.unregisterCallback(projectionCallback)
        } catch (e: Exception) {
            // Ignore
        }
        try {
            mediaProjection?.stop()
        } catch (e: Exception) {
            // Ignore
        }
        mediaProjection = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "System Audio Capture",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Running system audio capture service"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AndroidDex Audio")
            .setContentText("Capturing system audio playback...")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
