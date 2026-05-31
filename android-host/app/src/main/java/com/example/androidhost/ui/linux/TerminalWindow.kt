package com.example.androidhost.ui.linux

import android.content.Context
import android.graphics.Color
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.example.androidhost.ui.components.WindowChrome
import com.example.androidhost.vm.WindowState
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.concurrent.thread

@Composable
fun TerminalWindow(
    windowState: WindowState,
    onClose: () -> Unit,
    onMinimize: () -> Unit,
    onMaximize: () -> Unit
) {
    WindowChrome(
        windowState = windowState,
        onClose = onClose,
        onMinimize = onMinimize,
        onMaximize = onMaximize
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                val scrollView = ScrollView(context)
                val textView = TextView(context).apply {
                    setBackgroundColor(Color.BLACK)
                    setTextColor(Color.GREEN)
                    textSize = 14f
                    text = "Welcome to AndroidDex Native Terminal\n"
                    setPadding(16, 16, 16, 16)
                }
                scrollView.addView(textView)
                
                // Spawn simple bash process (Reduced scope for MVP since full PTY + SurfaceView is too large)
                try {
                    val process = ProcessBuilder("sh").redirectErrorStream(true).start()
                    val reader = BufferedReader(InputStreamReader(process.inputStream))
                    thread {
                        while (true) {
                            val line = reader.readLine() ?: break
                            textView.post {
                                textView.append(line + "\n")
                                scrollView.fullScroll(ScrollView.FOCUS_DOWN)
                            }
                        }
                    }
                    val writer = process.outputStream.bufferedWriter()
                    writer.write("ls -la /data/data/com.example.androidhost/files/termux\n")
                    writer.flush()
                } catch (e: Exception) {
                    textView.append("Failed to launch shell: ${e.message}\n")
                }

                scrollView
            }
        )
    }
}
