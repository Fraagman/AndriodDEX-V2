package com.example.androidhost.ui.linux

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.androidhost.ui.components.WindowChrome
import com.example.androidhost.vm.WindowState
import com.example.androidhost.input.LocalInputDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Compose-native terminal.
 *
 * Compose receives raw `KeyEvent`s through `View.dispatchKeyEvent` and needs no
 * `InputConnection`, which is what makes this work where the previous
 * `AndroidView(EditText)` did not — an `EditText` on an untrusted virtual display never
 * gets an `InputConnection` and silently swallows every keystroke.
 *
 * **Scope reduced to line-buffered command/response (task 31e).**
 * Running an interactive `sh` and printing its prompt from the read loop is possible but
 * fragile: it requires either a PTY (which needs `su`) or hand-rolled prompt-boundary
 * detection over a pipe (there is no protocol boundary the reader can trust). The
 * previous implementation used a 100 ms `postDelayed` timer to guess when a command was
 * done and printed the next prompt then, which is why prompts interleaved with output.
 *
 * The correct alternative is to make each command its own short-lived process:
 *   1. user types a command and presses Enter,
 *   2. we launch `sh -c "<command>"` with the current working directory,
 *   3. we drain its stdout+stderr fully, and only then print the next prompt.
 *
 * There is no interleaving because we never write the prompt until the process has
 * exited. `cd` is handled in-process because a subshell's `cd` would not persist.
 */
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
        TerminalSurface()
    }
}

@Composable
private fun TerminalSurface() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val rootDir = remember { context.filesDir }
    var currentDir by remember { mutableStateOf(rootDir) }
    val scrollback: SnapshotStateList<String> = remember { mutableStateListOf() }
    var currentInput by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val textOwner = remember { Any() }

    LaunchedEffect(Unit) {
        scrollback.add("AndroidDex terminal — sandboxed to ${rootDir.absolutePath}")
        scrollback.add("Type a command and press Enter. `cd`, `pwd`, `ls` are supported via sh -c.")
        focusRequester.requestFocus()
    }

    LaunchedEffect(scrollback.size, currentInput) {
        scrollState.scrollTo(scrollState.maxValue)
    }

    fun runCommand(cmd: String) {
        if (busy) return
        val trimmed = cmd.trim()
        scrollback.add("${prompt(currentDir)} $cmd")
        if (trimmed.isEmpty()) return
        // Handle `cd` in-process so it persists across commands. Sandbox to filesDir.
        if (trimmed == "cd" || trimmed.startsWith("cd ")) {
            val target = trimmed.removePrefix("cd").trim().ifEmpty { rootDir.absolutePath }
            val resolved = File(currentDir, target).canonicalFile
            if (!resolved.absolutePath.startsWith(rootDir.absolutePath)) {
                scrollback.add("cd: $target: outside sandbox (${rootDir.absolutePath})")
            } else if (!resolved.exists() || !resolved.isDirectory) {
                scrollback.add("cd: $target: No such file or directory")
            } else {
                currentDir = resolved
            }
            return
        }
        busy = true
        scope.launch {
            val output = withContext(Dispatchers.IO) {
                runCatching {
                    val proc = ProcessBuilder("sh", "-c", trimmed)
                        .directory(currentDir)
                        .redirectErrorStream(true)
                        .start()
                    proc.outputStream.close()
                    val text = proc.inputStream.bufferedReader().readText()
                    proc.waitFor()
                    text
                }.getOrElse { e -> "sh: ${e.message ?: e.javaClass.simpleName}\n" }
            }
            output.trimEnd('\n').split('\n').forEach { scrollback.add(it) }
            busy = false
        }
    }

    SelectionContainer {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(12.dp)
                .verticalScroll(scrollState)
                .focusRequester(focusRequester)
                .focusable()
                .onFocusChanged { state ->
                    LocalInputDispatcher.registerTextInsert(textOwner, if (state.isFocused) { text ->
                        // Only add printable characters, same as the onKeyEvent logic below
                        for (c in text) {
                            val cp = c.code
                            if (cp in 0x20..0x10FFFF) {
                                currentInput += String(Character.toChars(cp))
                            }
                        }
                    } else null)
                }
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (event.key) {
                        androidx.compose.ui.input.key.Key.Enter,
                        androidx.compose.ui.input.key.Key.NumPadEnter -> {
                            val cmd = currentInput
                            currentInput = ""
                            runCommand(cmd)
                            true
                        }
                        androidx.compose.ui.input.key.Key.Backspace -> {
                            if (currentInput.isNotEmpty()) {
                                currentInput = currentInput.dropLast(1)
                            }
                            true
                        }
                        else -> {
                            val cp = event.utf16CodePoint
                            if (cp in 0x20..0x10FFFF) {
                                currentInput += String(Character.toChars(cp))
                                true
                            } else {
                                false
                            }
                        }
                    }
                }
        ) {
            scrollback.forEach { line ->
                Text(
                    text = line,
                    color = Color(0xFF33FF33),
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            // Current input line, only when not running a command.
            if (!busy) {
                Text(
                    text = "${prompt(currentDir)} ${currentInput}_",
                    color = Color(0xFF33FF33),
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                )
            } else {
                Text(
                    text = "…",
                    color = Color(0xFFAAAAAA),
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }

}

private fun prompt(dir: File): String = "${dir.absolutePath} $"
