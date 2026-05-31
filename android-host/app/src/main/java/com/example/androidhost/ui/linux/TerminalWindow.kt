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
                val scrollView = ScrollView(context).apply {
                    setBackgroundColor(Color.BLACK)
                }
                val editText = android.widget.EditText(context).apply {
                    setBackgroundColor(Color.TRANSPARENT)
                    setTextColor(Color.GREEN)
                    textSize = 14f
                    setText("Welcome to AndroidDex Native Terminal\n$ ")
                    setPadding(16, 16, 16, 16)
                    isFocusable = true
                    isFocusableInTouchMode = true
                    gravity = android.view.Gravity.TOP or android.view.Gravity.START
                }
                scrollView.addView(editText)
                
                try {
                    val process = ProcessBuilder("sh").redirectErrorStream(true).start()
                    val reader = BufferedReader(InputStreamReader(process.inputStream))
                    val writer = process.outputStream.bufferedWriter()
                    
                    thread {
                        while (true) {
                            val line = reader.readLine() ?: break
                            editText.post {
                                editText.append(line + "\n$ ")
                                scrollView.fullScroll(ScrollView.FOCUS_DOWN)
                            }
                        }
                    }

                    editText.setOnKeyListener { _, keyCode, event ->
                        if (event.action == android.view.KeyEvent.ACTION_DOWN && keyCode == android.view.KeyEvent.KEYCODE_ENTER) {
                            val lines = editText.text.toString().split("\n")
                            val lastLine = lines.last().substringAfter("$ ")
                            if (lastLine.isNotEmpty()) {
                                try {
                                    writer.write(lastLine + "\n")
                                    writer.flush()
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            } else {
                                editText.append("\n$ ")
                            }
                            true
                        } else {
                            false
                        }
                    }
                } catch (e: Exception) {
                    editText.append("Failed to launch shell: ${e.message}\n")
                }

                scrollView
            }
        )
    }
}
