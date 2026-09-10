package com.example.androidhost.ui.linux

import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.androidhost.input.LocalInputDispatcher
import com.example.androidhost.input.WebViewInputBridge
import com.example.androidhost.input.buildControlKeyScript
import com.example.androidhost.input.buildInsertTextScript
import com.example.androidhost.service.NativeComputeService
import com.example.androidhost.ui.components.WindowChrome
import com.example.androidhost.vm.WindowState

@Composable
fun CodeServerWindow(
    windowState: WindowState,
    onClose: () -> Unit,
    onMinimize: () -> Unit,
    onMaximize: () -> Unit
) {
    val password by NativeComputeService.codeServerPassword.collectAsState()
    val bridgeOwner = remember { Any() }

    WindowChrome(
        windowState = windowState,
        onClose = onClose,
        onMinimize = onMinimize,
        onMaximize = onMaximize
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (password != null) {
                SelectionContainer {
                    Text(
                        text = "Password: $password",
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF252526))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.cacheMode = WebSettings.LOAD_NO_CACHE
                        
                        isFocusable = true
                        isFocusableInTouchMode = true
                        webViewClient = object : WebViewClient() {
                            override fun onReceivedError(
                                view: WebView?,
                                request: android.webkit.WebResourceRequest?,
                                error: android.webkit.WebResourceError?
                            ) {
                                super.onReceivedError(view, request, error)
                                view?.loadDataWithBaseURL(
                                    null,
                                    "<html><body style='background-color:#1E1E1E; color:#FFFFFF; font-family:sans-serif; padding:2rem; text-align:center;'>" +
                                    "<h2>VS Code Server Not Found</h2>" +
                                    "<p>The connection to <b>http://127.0.0.1:18080</b> failed.</p>" +
                                    "<p>Please ensure that native compute / code-server is started on the device.</p>" +
                                    "<br/><p style='color:#AAAAAA; font-size:12px;'>Error: ${error?.description}</p>" +
                                    "</body></html>",
                                    "text/html",
                                    "UTF-8",
                                    null
                                )
                            }
                        }
                        // See BrowserApp for why WebView text entry has to be routed
                        // through the DOM rather than the platform IME.
                        val bridge = object : WebViewInputBridge {
                            override fun insertText(text: CharSequence) {
                                evaluateJavascript(buildInsertTextScript(text), null)
                            }
                            override fun controlKey(key: String, keyCode: Int, pressed: Boolean) {
                                evaluateJavascript(buildControlKeyScript(key, keyCode, pressed), null)
                            }
                        }
                        // Removed focus listener; using touch listener instead.
                        setOnTouchListener { _, event ->
                            if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                                LocalInputDispatcher.registerWebViewBridge(bridgeOwner, bridge, this)
                            }
                            false
                        }

                        loadUrl("http://127.0.0.1:18080")
                    }
                }
            )
        }
    }
}
