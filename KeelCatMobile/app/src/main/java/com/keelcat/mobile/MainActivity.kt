package com.keelcat.mobile

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.keelcat.mobile.server.KeelCatServer

class MainActivity : AppCompatActivity() {

    private lateinit var server: KeelCatServer
    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        // Start the on-device server: it serves the exact KeelCat web UI plus a
        // native /api implementation (GitHub + changelog parsing on the phone).
        server = KeelCatServer(applicationContext)
        server.start()

        // Dark system-bar icons off (app uses a dark theme by default).
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        webView = findViewById(R.id.webview)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true          // localStorage (theme/skin)
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            // App-like viewport: honor width=device-width, fit to screen width.
            useWideViewPort = true
            loadWithOverviewMode = true
            // Kill pinch-zoom so components never shift around.
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            // Ignore the system font-size setting so the layout stays stable.
            textZoom = 100
            // Allow target="_blank" / window.open so we can catch & externalize them.
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
        }
        webView.isHorizontalScrollBarEnabled = false
        webView.overScrollMode = View.OVER_SCROLL_NEVER
        webView.setBackgroundColor(0xFF0B0B0F.toInt())
        webView.webViewClient = object : WebViewClient() {
            // Keep the app UI (served from 127.0.0.1) inside the WebView; send
            // everything external (GitHub PRs, mailto, etc.) to the system browser.
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url ?: return false
                val internal = isInternal(url)
                Log.i(TAG, "override url=$url internal=$internal")
                return if (internal) {
                    false
                } else {
                    openExternally(url)
                    true
                }
            }
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                Log.e(TAG, "load error ${request?.url}: ${error?.errorCode} ${error?.description}")
            }
            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                Log.e(TAG, "http error ${request?.url}: ${errorResponse?.statusCode}")
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(cm: ConsoleMessage): Boolean {
                Log.i(TAG, "console ${cm.messageLevel()} ${cm.message()} @${cm.sourceId()}:${cm.lineNumber()}")
                return true
            }
            // target="_blank" / window.open: capture the popup's URL with a throwaway
            // WebView and hand it to the system browser instead of opening in-app.
            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean {
                val href = view?.hitTestResult?.extra
                if (href != null && !isInternal(Uri.parse(href))) {
                    openExternally(Uri.parse(href))
                    return false
                }
                val temp = WebView(this@MainActivity)
                temp.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView?, req: WebResourceRequest?): Boolean {
                        req?.url?.let { if (!isInternal(it)) openExternally(it) }
                        temp.destroy()
                        return true
                    }
                }
                val transport = resultMsg?.obj as? WebView.WebViewTransport
                transport?.webView = temp
                resultMsg?.sendToTarget()
                return true
            }
        }
        WebView.setWebContentsDebuggingEnabled(true)
        webView.loadUrl("http://127.0.0.1:${server.listeningPort}/")
    }

    private fun isInternal(uri: Uri): Boolean {
        val host = uri.host ?: return true            // relative/opaque -> in-app
        return host == "127.0.0.1" || host == "localhost"
    }

    private fun openExternally(uri: Uri) {
        Log.i(TAG, "openExternally $uri")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.e(TAG, "no handler for $uri: ${e.message}")
        }
    }

    override fun onDestroy() {
        if (this::server.isInitialized) server.stop()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "KeelCatWV"
    }
}
