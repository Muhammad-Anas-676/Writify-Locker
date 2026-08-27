package com.anas.applocker

import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

/**
 * Decoy screen. When the FAKE pin is entered, this is what opens —
 * it just loads Writify (the notes app) from bundled assets, exactly
 * like the standalone Writify app would, so there is nothing here to
 * suggest a vault exists.
 */
class WritifyActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_writify)

        val webView = findViewById<WebView>(R.id.writifyWebView)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webViewClient = WebViewClient()
        webView.loadUrl("file:///android_asset/writify/index.html")
    }

    override fun onBackPressed() {
        val webView = findViewById<WebView>(R.id.writifyWebView)
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
