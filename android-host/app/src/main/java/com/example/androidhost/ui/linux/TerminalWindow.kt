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
                    val rootDir = context.filesDir
                    var currentDir = rootDir
                    
                    val prompt = { "${currentDir.absolutePath} $ " }
                    
                    editText.setText("Welcome to AndroidDex Native Terminal\n${prompt()}")
                    
                    val process = ProcessBuilder("sh").redirectErrorStream(true).directory(currentDir).start()
                    val reader = BufferedReader(InputStreamReader(process.inputStream))
                    val writer = process.outputStream.bufferedWriter()
                    
                    thread {
                        while (true) {
                            val line = reader.readLine() ?: break
                            editText.post {
                                editText.append(line + "\n")
                                // Only append prompt if not immediately followed by another line? No, sh doesn't echo prompt over pipe.
                                // But we handle the prompt manually on command execution.
                                scrollView.fullScroll(ScrollView.FOCUS_DOWN)
                            }
                        }
                    }

                    editText.setOnKeyListener { _, keyCode, event ->
                        if (event.action == android.view.KeyEvent.ACTION_DOWN && keyCode == android.view.KeyEvent.KEYCODE_ENTER) {
                            val lines = editText.text.toString().split("\n")
                            val lastLine = lines.last().substringAfter(prompt())
                            if (lastLine.isNotEmpty()) {
                                if (lastLine.trim().startsWith("cd ")) {
                                    val target = lastLine.trim().substringAfter("cd ").trim()
                                    val targetDir = java.io.File(currentDir, target).canonicalFile
                                    if (targetDir.absolutePath.startsWith(rootDir.absolutePath)) {
                                        if (targetDir.exists() && targetDir.isDirectory) {
                                            currentDir = targetDir
                                            editText.append("\n${prompt()}")
                                        } else {
                                            editText.append("\ncd: $target: No such file or directory\n${prompt()}")
                                        }
                                    } else {
                                        editText.append("\ncd: $target: Permission denied. The terminal is sandboxed to ${rootDir.absolutePath}.\n${prompt()}")
                                    }
                                } else {
                                    try {
                                        writer.write("cd ${currentDir.absolutePath} && $lastLine\n")
                                        writer.flush()
                                        // Wait a tiny bit for output, then append prompt?
                                        // A better way is to append prompt after a small delay, but for simplicity:
                                        editText.postDelayed({ editText.append(prompt()) }, 100)
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }
                                }
                            } else {
                                editText.append("\n${prompt()}")
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
