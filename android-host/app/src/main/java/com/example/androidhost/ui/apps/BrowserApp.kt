package com.example.androidhost.ui.apps

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.KeyEvent
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.androidhost.input.LocalInputDispatcher
import com.example.androidhost.input.WebViewInputBridge
import com.example.androidhost.input.buildInsertTextScript
import com.example.androidhost.ui.components.WindowChrome
import com.example.androidhost.vm.WindowState

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserApp(
    windowState: WindowState,
    onClose: () -> Unit,
    onMinimize: () -> Unit,
    onMaximize: () -> Unit
) {
    var urlInput by remember { mutableStateOf("https://www.google.com") }
    val textOwner = remember { Any() }
    val bridgeOwner = remember { Any() }
    var currentUrl by remember { mutableStateOf("https://www.google.com") }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    // True while the user is actively editing the URL field: onPageStarted must
    // not clobber the field mid-edit when a redirect or navigation fires.
    var urlBarEditing by remember { mutableStateOf(false) }

    WindowChrome(
        windowState = windowState,
        onClose = onClose,
        onMinimize = onMinimize,
        onMaximize = onMaximize
    ) {
        Column(modifier = Modifier.fillMaxSize().background(Color(0xFF1E1E1E))) {
            // Toolbar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF2A2A2A))
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { webView?.goBack() }, enabled = canGoBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = if (canGoBack) Color.White else Color.Gray)
                }
                IconButton(onClick = { webView?.goForward() }, enabled = canGoForward) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Forward", tint = if (canGoForward) Color.White else Color.Gray)
                }
                IconButton(onClick = { webView?.reload() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Reload", tint = Color.White)
                }
                
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    if (event.changes.any { it.pressed }) {
                                        urlBarEditing = true
                                        LocalInputDispatcher.registerComposeTarget(
                                        textOwner,
                                        onText = { text -> urlInput += text },
                                        onKey = { keyCode, pressed ->
                                            // Consume only what this field handles;
                                            // unconsumed keys fall through to the view
                                            // tree so Compose can move the caret etc.
                                            if (pressed) {
                                                if (keyCode == KeyEvent.KEYCODE_DEL) {
                                                    if (urlInput.isNotEmpty()) urlInput = urlInput.dropLast(1)
                                                    true
                                                } else if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                                                    urlBarEditing = false
                                                    val target = normalizeUrl(urlInput)
                                                    currentUrl = target
                                                    webView?.loadUrl(target)
                                                    true
                                                } else {
                                                    false
                                                }
                                            } else {
                                                false
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedContainerColor = Color(0xFF1E1E1E),
                        unfocusedContainerColor = Color(0xFF1E1E1E),
                        cursorColor = Color.White
                    ),
                    placeholder = { Text("Search or type URL", color = Color.Gray) }
                )
                
                Button(onClick = {
                    urlBarEditing = false
                    val target = normalizeUrl(urlInput)
                    currentUrl = target
                    webView?.loadUrl(target)
                }) {
                    Text("Go")
                }
            }
            
            if (isLoading) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF4CAF50),
                    trackColor = Color(0xFF2A2A2A)
                )
            } else {
                Spacer(modifier = Modifier.height(4.dp).fillMaxWidth().background(Color(0xFF2A2A2A)))
            }

            // Web Content
            AndroidView(
                modifier = Modifier.weight(1f),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        settings.cacheMode = WebSettings.LOAD_DEFAULT
                        
                        isFocusable = true
                        isFocusableInTouchMode = true
                        
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                isLoading = true
                                // Don't clobber the user's in-progress edit: a
                                // redirect mid-typing used to overwrite the field.
                                if (!urlBarEditing) urlInput = url ?: ""
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                isLoading = false
                                canGoBack = view?.canGoBack() == true
                                canGoForward = view?.canGoForward() == true
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?
                            ) {
                                super.onReceivedError(view, request, error)
                                if (request?.isForMainFrame == true) {
                                    // Escape the interpolations: a hostile URL or
                                    // error string must not inject markup into the
                                    // error page. Entities are assembled from parts
                                    // so no literal "&...;" appears in source.
                                    val esc = { s: String ->
                                        val sb = StringBuilder()
                                        for (ch in s) when (ch) {
                                            '&' -> sb.append('&').append("amp;")
                                            '<' -> sb.append('&').append("lt;")
                                            '>' -> sb.append('&').append("gt;")
                                            '"' -> sb.append('&').append("quot;")
                                            '\'' -> sb.append('&').append("#39;")
                                            else -> sb.append(ch)
                                        }
                                        sb.toString()
                                    }
                                    val urlText = request.url?.toString() ?: ""
                                    val errText = error?.description?.toString() ?: "unknown"
                                    view?.loadDataWithBaseURL(
                                        null,
                                        "<html><body style='background-color:#1E1E1E; color:#FFFFFF; font-family:sans-serif; padding:2rem; text-align:center;'>" +
                                        "<h2>Failed to load page</h2>" +
                                        "<p>Could not connect to <b>${esc(urlText)}</b>.</p>" +
                                        "<p style='color:#AAAAAA;'>Error: ${esc(errText)}</p>" +
                                        "</body></html>",
                                        "text/html",
                                        "UTF-8",
                                        null
                                    )
                                }
                            }
                        }
                        
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                progress = newProgress / 100f
                            }

                            // Page console messages go to logcat: without this the
                            // browser is a black box when a page misbehaves.
                            override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                                android.util.Log.i(
                                    "BrowserConsole",
                                    "[${consoleMessage?.lineNumber()}] ${consoleMessage?.message()}"
                                )
                                return true
                            }
                        }
                        
                        // Route text entry from the PC-side keyboard into the DOM. The
                        // platform IME cannot serve this virtual display (see the class
                        // comment on LocalInputDispatcher), so `LocalInputDispatcher` calls back
                        // into the WebView while it is focused and injects text via
                        // `evaluateJavascript` targeting `document.activeElement`.
                        // Everything else (control keys, shortcuts) arrives as real
                        // KeyEvents dispatched straight into the WebView.
                        val bridge = object : WebViewInputBridge {
                            override fun insertText(text: CharSequence) {
                                evaluateJavascript(buildInsertTextScript(text), null)
                            }
                        }
                        // Register immediately (not just on touch): the dispatcher routes
                        // pointer events straight into this WebView when the cursor is
                        // over it, so the first click needs the bridge armed already.
                        // Eligibility: only while this browser window is the shell's
                        // top window — a WebView covered by another window must not
                        // swallow clicks aimed at the window above it.
                        val shell = com.example.androidhost.vm.ShellHolder.shellViewModel
                        val isTopWindow: () -> Boolean = {
                            // Top of the shell's z-order (list order) must be this very
                            // browser window; any window above it disqualifies the page
                            // from taking pointer/key events.
                            shell.windows.value.lastOrNull { !it.isMinimized }?.id == windowState.id
                        }
                        LocalInputDispatcher.registerWebViewBridge(bridgeOwner, bridge, this, isTopWindow)
                        setOnTouchListener { _, event ->
                            if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                                LocalInputDispatcher.registerWebViewBridge(bridgeOwner, bridge, this, isTopWindow)
                            }
                            false
                        }

                        webView = this
                        loadUrl(currentUrl)
                    }
                },
                onRelease = { wv ->
                    // Window closed: drop the input-routing slot and tear the
                    // WebView down — otherwise its process-side objects (and the
                    // stale bridge) outlive the window.
                    LocalInputDispatcher.registerWebViewBridge(bridgeOwner, null, wv)
                    wv.destroy()
                    if (webView === wv) webView = null
                }
            )
        }
    }
}

/**
 * Keeps scheme-less input (a hostname or search terms) on the https navigation
 * path without mangling URLs that already carry a scheme — data:, about:,
 * file: and friends must reach [android.webkit.WebView.loadUrl] untouched.
 * Input that is not a domain (no dot, or has spaces) becomes a web search.
 */
private fun normalizeUrl(input: String): String {
    val trimmed = input.trim()
    if (Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(trimmed)) return trimmed
    return if (trimmed.contains('.') && !trimmed.contains(' ')) {
        "https://$trimmed"
    } else {
        "https://www.google.com/search?q=" + java.net.URLEncoder.encode(trimmed, "UTF-8")
    }
}
