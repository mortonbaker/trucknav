package com.morton.trucknav.media

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp

// YouTube inside the cockpit: the mobile site in a WebView so it sits next to
// the map and keeps playing while you navigate. Sign in once and your own
// history and subscriptions are there. Live and Shorts are hidden by a small
// stylesheet injected on every page. Cookies persist across launches.
private const val UA = "Mozilla/5.0 (Linux; Android 14; SM-T220) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
private const val HIDE_CSS = """
  ytm-pivot-bar-item-renderer:has([aria-label*="Shorts"]), ytm-pivot-bar-item-renderer:has([aria-label*="Live"]),
  ytm-reel-shelf-renderer, ytm-rich-section-renderer:has(ytm-reel-shelf-renderer),
  a[href^="/shorts"], ytm-chip-cloud-chip-renderer:has([aria-label*="Live"]),
  ytm-video-with-context-renderer:has([aria-label*="LIVE"]), ytm-badge[aria-label*="LIVE"] { display: none !important; }
"""

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubePane(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val web = remember {
        WebView(ctx).apply {
            settings.apply {
                javaScriptEnabled = true; domStorageEnabled = true; databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                userAgentString = UA
                useWideViewPort = true; loadWithOverviewMode = true
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false
                override fun onPageFinished(view: WebView, url: String?) {
                    view.evaluateJavascript("(function(){var s=document.getElementById('trucknav-css');if(!s){s=document.createElement('style');s.id='trucknav-css';document.head.appendChild(s);}s.textContent=`$HIDE_CSS`;})();", null)
                    CookieManager.getInstance().flush()
                }
            }
            loadUrl("https://m.youtube.com/feed/history")
        }
    }
    DisposableEffect(Unit) { onDispose { web.onPause() } }

    Card(modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (web.canGoBack()) web.goBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                IconButton(onClick = { web.loadUrl("https://m.youtube.com/") }) { Icon(Icons.Filled.Home, "YouTube home") }
                IconButton(onClick = { web.loadUrl("https://m.youtube.com/feed/subscriptions") }) { Icon(Icons.Filled.Subscriptions, "Subscriptions") }
                IconButton(onClick = { web.loadUrl("https://m.youtube.com/feed/history") }) { Icon(Icons.Filled.History, "History") }
                Text("YouTube", color = Color(0xFF9aa4b2))
            }
            AndroidView(factory = { web }, modifier = Modifier.fillMaxWidth().weight(1f))
        }
    }
}
