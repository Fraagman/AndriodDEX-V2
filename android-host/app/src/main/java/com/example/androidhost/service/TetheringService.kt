package com.example.androidhost.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import java.net.NetworkInterface

class TetheringService : Service() {

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (action == "android.hardware.usb.action.USB_STATE") {
                val connected = intent.getBooleanExtra("connected", false)
                if (connected) {
                    checkTethering(context)
                }
            } else if (action == "android.net.conn.TETHER_STATE_CHANGED" || action == ConnectivityManager.CONNECTIVITY_ACTION) {
                checkTethering(context)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter().apply {
            addAction("android.hardware.usb.action.USB_STATE")
            addAction("android.net.conn.TETHER_STATE_CHANGED")
            addAction(ConnectivityManager.CONNECTIVITY_ACTION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
        createNotificationChannel()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(receiver)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        checkTethering(this)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun checkTethering(context: Context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val isTethered = isUsbTetheringEnabled(cm)

        if (isTethered) {
            Log.d("Tethering", "USB tethering active")
            val intent = Intent("com.example.androidhost.ACTION_TETHERING_READY")
            sendBroadcast(intent)
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(1)
        } else {
            showTetheringNotification(context)
        }
    }

    private fun isUsbTetheringEnabled(cm: ConnectivityManager): Boolean {
        return try {
            val method = cm.javaClass.getDeclaredMethod("getTetheredIfaces")
            val ifaces = method.invoke(cm) as Array<String>
            ifaces.any { it.matches("usb.*".toRegex()) || it.matches("rndis.*".toRegex()) }
        } catch (e: Exception) {
            try {
                NetworkInterface.getNetworkInterfaces()?.toList()?.any {
                    (it.name.startsWith("rndis") || it.name.startsWith("usb")) && it.isUp
                } ?: false
            } catch (e2: Exception) {
                false
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "tethering_channel",
                "Tethering Service",
                NotificationManager.IMPORTANCE_HIGH
            )
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun showTetheringNotification(context: Context) {
        val settingsIntent = Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            settingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, "tethering_channel")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Tap to enable Desktop Mode")
            .setContentText("USB is connected but tethering is disabled.")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(1, notification)
    }
}
