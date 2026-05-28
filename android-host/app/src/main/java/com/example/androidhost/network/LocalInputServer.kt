package com.example.androidhost.network

import android.util.Log
import com.example.androidhost.service.DesktopAccessibilityService
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

object LocalInputServer {
    fun start() {
        thread(name = "LocalInputServerThread") {
            try {
                // Bind to localhost
                val serverSocket = ServerSocket(55555, 0, InetAddress.getByName("127.0.0.1"))
                Log.d("Input", "LocalInputServer listening on port 55555")
                while (true) {
                    val client = serverSocket.accept()
                    Log.d("Input", "Client connected to LocalInputServer")
                    thread(name = "LocalInputClientHandler") {
                        try {
                            val dis = DataInputStream(client.getInputStream())
                            while (true) {
                                val x = dis.readInt()
                                val y = dis.readInt()
                                val buttons = dis.readInt()
                                
                                if (buttons > 0) {
                                    Log.d("Input", "Received click at $x, $y")
                                    DesktopAccessibilityService.instance?.injectClick(x.toFloat(), y.toFloat())
                                }
                            }
                        } catch (e: Exception) {
                            Log.d("Input", "Client disconnected")
                        } finally {
                            client.close()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("Input", "Server error", e)
            }
        }
    }
}
