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

    private var imageReader: ImageReader? = null
    private var isRunning = false
    private var drawThread: Thread? = null

    private fun createVirtualDisplay() {
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val width = 1920
        val height = 1080
        val dpi = 320
        val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC

        // Using ImageReader directly to satisfy the raw RGBA uncompressed requirement over TCP
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        surface = imageReader!!.surface

        if (surface != null) {
            virtualDisplay = displayManager.createVirtualDisplay("AndroidDex", width, height, dpi, surface, flags)
            if (virtualDisplay != null) {
                com.example.androidhost.network.FrameSender.start()
                startDrawThread(width, height)
                showNotification()
            }
        }
    }

    private fun startDrawThread(width: Int, height: Int) {
        val redPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.RED
            style = android.graphics.Paint.Style.FILL
        }
        val bluePaint = android.graphics.Paint().apply {
            color = android.graphics.Color.BLUE
            style = android.graphics.Paint.Style.FILL
        }

        isRunning = true
        drawThread = Thread {
            while (isRunning) {
                try {
                    // Draw test pattern
                    val canvas = try {
                        surface?.lockHardwareCanvas()
                    } catch (e: Exception) {
                        surface?.lockCanvas(null)
                    }
                    if (canvas != null) {
                        canvas.drawRect(0f, 0f, 960f, 1080f, redPaint)
                        canvas.drawRect(960f, 0f, 1920f, 1080f, bluePaint)
                        surface?.unlockCanvasAndPost(canvas)
                    }

                    // Extract frame and send
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
                        com.example.androidhost.network.FrameSender.sendFrame(width, height, data)
                        image.close()
                    }
                } catch (e: Exception) {
                    Log.e("DisplayService", "Error in draw thread", e)
                }
                Thread.sleep(33) // ~30 FPS
            }
        }
        drawThread?.start()
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
        drawThread?.interrupt()
        com.example.androidhost.network.FrameSender.stop()
        virtualDisplay?.release()
        surface?.release()
        imageReader?.close()
    }
}
