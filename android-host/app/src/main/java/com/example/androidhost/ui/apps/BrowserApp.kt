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
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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
    var currentUrl by remember { mutableStateOf("https://www.google.com") }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }

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
                        .onKeyEvent {
                            if (it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER && it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                                var target = urlInput
                                if (!target.startsWith("http://") && !target.startsWith("https://")) {
                                    target = "https://$target"
                                }
                                currentUrl = target
                                webView?.loadUrl(target)
                                true
                            } else {
                                false
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
                    var target = urlInput
                    if (!target.startsWith("http://") && !target.startsWith("https://")) {
                        target = "https://$target"
                    }
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
                modifier = Modifier.fillMaxSize().weight(1f),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                        settings.cacheMode = WebSettings.LOAD_DEFAULT
                        
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                isLoading = true
                                urlInput = url ?: ""
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
                                    view?.loadDataWithBaseURL(
                                        null,
                                        "<html><body style='background-color:#1E1E1E; color:#FFFFFF; font-family:sans-serif; padding:2rem; text-align:center;'>" +
                                        "<h2>Failed to load page</h2>" +
                                        "<p>Could not connect to <b>${request.url}</b>.</p>" +
                                        "<p style='color:#AAAAAA;'>Error: ${error?.description}</p>" +
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
                        }
                        
                        webView = this
                        loadUrl(currentUrl)
                    }
                }
            )
        }
    }
}
