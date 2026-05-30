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
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.androidhost.MainActivity
import com.example.androidhost.quic.QuicServer
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioCaptureService : Service() {

    companion object {
        private const val TAG = "AudioCaptureService"
        private const val CHANNEL_ID = "audio_capture_channel"
        private const val NOTIFICATION_ID = 2

        // Flow to communicate service status to Compose UI
        val isServiceRunning = MutableStateFlow(false)
    }

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    @Volatile private var isCapturing = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand triggered")
        
        val resultCode = intent?.getIntExtra("RESULT_CODE", -1) ?: -1
        val data = intent?.getParcelableExtra<Intent>("DATA")

        if (resultCode != -1 && data != null) {
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

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)
        
        if (mediaProjection == null) {
            Log.e(TAG, "Failed to retrieve MediaProjection token")
            stopSelf()
            return
        }

        // Configure system audio capture
        val config = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
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

                    val now = System.currentTimeMillis()

                    // Periodically log captured audio properties (every 100ms)
                    if (now - lastLogTime >= 100) {
                        lastLogTime = now
                        Log.d("Audio", "Audio bytes read: ${pcmBytes.size}")
                        Log.d("Audio", "First 16 bytes: ${pcmBytes.take(16).joinToString("") { "%02x".format(it) }}")
                    }

                    // Manually serialize AudioPacket protobuf message
                    val audioPacketBytes = encodeAudioPacket(pcmBytes, now)

                    // Prepend a 4-byte length prefix (big-endian) before the protobuf packet
                    val finalBuffer = ByteBuffer.allocate(4 + audioPacketBytes.size)
                    finalBuffer.order(ByteOrder.BIG_ENDIAN)
                    finalBuffer.putInt(audioPacketBytes.size)
                    finalBuffer.put(audioPacketBytes)

                    val dataToSend = finalBuffer.array()

                    // Send over QUIC via JNI sendFrame (utilizes the same UDP channel)
                    QuicServer.sendFrame(dataToSend)
                } else if (readResult < 0) {
                    Log.e(TAG, "AudioRecord read error: $readResult")
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
        captureThread?.interrupt()
        captureThread = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            // Ignore
        }
        audioRecord = null

        try {
            mediaProjection?.stop()
        } catch (e: Exception) {
            // Ignore
        }
        mediaProjection = null
    }

    private fun writeVarint(value: Long, out: ByteArrayOutputStream) {
        var v = value
        while (true) {
            if ((v and 0xFFFFFFFFFFFFFF80u.toLong()) == 0L) {
                out.write(v.toInt())
                return
            }
            out.write((v.toInt() and 0x7F) or 0x80)
            v = v ushr 7
        }
    }

    private fun encodeAudioPacket(pcmData: ByteArray, timestamp: Long): ByteArray {
        val protoBaos = ByteArrayOutputStream(pcmData.size + 32)
        
        // Tag 1 (pcm_data): field number 1, wire type 2 -> (1 << 3) | 2 = 10
        protoBaos.write(10)
        writeVarint(pcmData.size.toLong(), protoBaos)
        protoBaos.write(pcmData)

        // Tag 2 (timestamp): field number 2, wire type 0 -> (2 << 3) | 0 = 16
        protoBaos.write(16)
        writeVarint(timestamp, protoBaos)

        return protoBaos.toByteArray()
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
