package com.example.androidhost.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat

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

    private fun createVirtualDisplay() {
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val width = 1920
        val height = 1080
        val dpi = 320
        val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC

        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            format.setInteger(MediaFormat.KEY_BIT_RATE, 6000000)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = codec.createInputSurface()
            codec.start()
            Log.d("DisplayService", "MediaCodec input surface configured")
        } catch (e: Exception) {
            Log.e("DisplayService", "MediaCodec failed to configure, falling back to ImageReader", e)
            val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            surface = imageReader.surface
            Log.d("DisplayService", "ImageReader fallback active")
        }

        if (surface != null) {
            virtualDisplay = displayManager.createVirtualDisplay("AndroidDex", width, height, dpi, surface, flags)
            if (virtualDisplay != null) {
                showNotification()
            }
        }
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
        virtualDisplay?.release()
        surface?.release()
    }
}
