package com.example.androidhost.ui.linux

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import android.view.KeyEvent
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
    // The jail root is a dedicated subfolder of filesDir — NOT filesDir itself,
    // which holds pairing_v2.psk and tls_identity.bin one level up. Nothing
    // secret is created inside it, and every path-taking program resolves
    // through TerminalSandbox.resolveInRoot.
    val rootDir = remember { TerminalSandbox.jailRoot(context.filesDir) }
    var currentDir by remember { mutableStateOf(rootDir) }
    val scrollback: SnapshotStateList<String> = remember { mutableStateListOf() }
    var currentInput by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val textOwner = remember { Any() }
    var currentProcess: java.lang.Process? = null

    val MAX_OUTPUT_CHARS = 64_000
    val MAX_OUTPUT_LINES = 400
    val WHITESPACE = Regex("\\s+")

    LaunchedEffect(Unit) {
        scrollback.add("AndroidDex terminal — sandboxed to ${rootDir.absolutePath}")
        scrollback.add("Read-only commands only: ${TerminalSandbox.ALLOWED_PROGRAMS.joinToString(" ")}")
        focusRequester.requestFocus()
    }

    LaunchedEffect(scrollback.size, currentInput) {
        scrollState.scrollTo(scrollState.maxValue)
    }

    // Kill a still-running command when the window is disposed: a cancelled
    // scope would otherwise orphan the process (a `sleep 5m` would keep running).
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { currentProcess?.destroy() }
    }

    /**
     * Runs a command inside the sandbox. The terminal is reachable from any paired
     * PC over the network, so it must never be a raw `sh -c` with user text: that
     * would expose the app's private files (the pairing key, the TLS identity) and
     * any binary the app uid can exec.
     *
     * [TerminalSandbox] owns the rules: an allow-list of read-only programs,
     * executed WITHOUT a shell (arguments are literal, so `;`, `|`, `$()` and
     * backticks cannot smuggle commands), and every path-taking program's
     * operands resolved and jailed to [rootDir] (filesDir/home, which holds no
     * secrets).
     */
    fun runCommand(cmd: String) {
        if (busy) return
        val trimmed = cmd.trim()
        scrollback.add("${prompt(currentDir)} $cmd")
        if (trimmed.isEmpty()) return

        // `cd` is handled in-process so it persists across commands. The same
        // canonical check as every path operand.
        if (trimmed == "cd" || trimmed.startsWith("cd ")) {
            val target = trimmed.removePrefix("cd").trim().ifEmpty { rootDir.absolutePath }
            val resolved = TerminalSandbox.resolveInRoot(rootDir, currentDir, target)
            if (resolved == null) {
                scrollback.add("cd: $target: outside sandbox (${rootDir.absolutePath})")
            } else if (!resolved.exists() || !resolved.isDirectory) {
                scrollback.add("cd: $target: No such file or directory")
            } else {
                currentDir = resolved
            }
            return
        }

        val tokens = trimmed.split(WHITESPACE)
        TerminalSandbox.validate(rootDir, currentDir, tokens)?.let { refusal ->
            scrollback.add(refusal)
            return
        }

        busy = true
        currentProcess = null
        scope.launch {
            val output = withContext(Dispatchers.IO) {
                runCatching {
                    val proc = ProcessBuilder(tokens)
                        .directory(currentDir)
                        .redirectErrorStream(true)
                        .start()
                    currentProcess = proc
                    proc.outputStream.close()
                    // Cap the read: a huge file would otherwise balloon memory and
                    // freeze the UI with thousands of lines.
                    val text = proc.inputStream.bufferedReader().use { reader ->
                        val sb = StringBuilder()
                        val buf = CharArray(2048)
                        var read = 0
                        var total = 0
                        while (reader.read(buf).also { read = it } > 0 && total < MAX_OUTPUT_CHARS) {
                            sb.append(buf, 0, read)
                            total += read
                        }
                        if (total >= MAX_OUTPUT_CHARS) sb.append("\n…output truncated at $MAX_OUTPUT_CHARS chars…")
                        sb.toString()
                    }
                    proc.waitFor()
                    currentProcess = null
                    text
                }.getOrElse { e -> "sh: ${e.message ?: e.javaClass.simpleName}\n" }
            }
            output.trimEnd('\n').split('\n').take(MAX_OUTPUT_LINES).forEach { scrollback.add(it) }
            if (output.trimEnd('\n').split('\n').size > MAX_OUTPUT_LINES) {
                scrollback.add("…output truncated at $MAX_OUTPUT_LINES lines…")
            }
            busy = false
        }
    }

    SelectionContainer {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { focusRequester.requestFocus() }
                .padding(12.dp)
                .verticalScroll(scrollState)
                .focusRequester(focusRequester)
                .focusable()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.any { it.pressed }) {
                                LocalInputDispatcher.registerComposeTarget(
                                    textOwner,
                                    onText = { text ->
                                        // Only add printable characters
                                        for (c in text) {
                                            val cp = c.code
                                            if (cp in 0x20..0x10FFFF) {
                                                currentInput += String(Character.toChars(cp))
                                            }
                                        }
                                    },
                                    onKey = { keyCode, pressed ->
                                        if (pressed) {
                                            when (keyCode) {
                                                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                                                    val cmd = currentInput
                                                    currentInput = ""
                                                    runCommand(cmd)
                                                    true
                                                }
                                                KeyEvent.KEYCODE_DEL -> {
                                                    if (currentInput.isNotEmpty()) {
                                                        currentInput = currentInput.dropLast(1)
                                                    }
                                                    true
                                                }
                                                else -> false
                                            }
                                        } else {
                                            false
                                        }
                                    }
                                )
                                focusRequester.requestFocus()
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
