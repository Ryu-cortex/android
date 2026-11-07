package com.ryucortex.onehourlock

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * QR コードで読み取ったページだけを表示するブラウザ。
 * URL バーは無く、読み取ったサイト（と最初のリダイレクト先）以外には移動できない。
 *
 * [strictIntent] で開いた場合は、指定した URL より下（同じスキーム・ホストで、パスが前方一致）
 * のページだけを表示し、それ以外へ移動しようとした時点で読み込みを止める。
 */
class WebTabActivity : Activity() {

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_STRICT_PREFIX = "strict_prefix"

        /** 短縮 URL や JS リダイレクトを許すため、読み込み開始からこの時間はタップ以外の遷移を許可 */
        private const val INITIAL_REDIRECT_WINDOW_MS = 10_000L

        fun intent(context: Context, url: String): Intent =
            Intent(context, WebTabActivity::class.java).putExtra(EXTRA_URL, url)

        /** [prefix] 配下のページだけを見られるタブとして開く */
        fun strictIntent(context: Context, prefix: String): Intent =
            intent(context, prefix).putExtra(EXTRA_STRICT_PREFIX, prefix)
    }

    private lateinit var webView: WebView
    private lateinit var header: TextView
    private val allowedHosts = mutableSetOf<String>()
    private var loadStartedAt = 0L
    private var strictPrefix: Uri? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL)
        val host = url?.let { Uri.parse(it).host }
        if (url == null || host == null) {
            finish()
            return
        }
        allowedHosts += baseHost(host)
        strictPrefix = intent.getStringExtra(EXTRA_STRICT_PREFIX)?.let { Uri.parse(it) }

        val dp = resources.displayMetrics.density
        header = TextView(this).apply {
            text = host
            textSize = 14f
            setSingleLine()
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#FF101418"))
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt())
        }
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = RestrictedClient()
            webChromeClient = object : WebChromeClient() {
                override fun onReceivedTitle(view: WebView, title: String?) {
                    if (!title.isNullOrBlank()) header.text = title
                }
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header)
            addView(webView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        })

        loadStartedAt = SystemClock.elapsedRealtime()
        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            webView.loadUrl(url)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }

    private inner class RestrictedClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            val scheme = uri.scheme?.lowercase()
            val host = uri.host
            strictPrefix?.let { prefix ->
                // ページ内に埋め込まれた iframe は対象外。画面全体の移動だけを止める
                if (!request.isForMainFrame || isUnderPrefix(uri, prefix)) return false
                blockedOutsidePrefix()
                return true
            }
            // tel: や intent: などで他のアプリに飛ぶのは禁止
            if ((scheme != "http" && scheme != "https") || host == null) return true
            if (isAllowedHost(host)) return false
            val initialLoad = SystemClock.elapsedRealtime() - loadStartedAt < INITIAL_REDIRECT_WINDOW_MS
            if (request.isRedirect || (initialLoad && !request.hasGesture())) {
                allowedHosts += baseHost(host)
        strictPrefix = intent.getStringExtra(EXTRA_STRICT_PREFIX)?.let { Uri.parse(it) }
                return false
            }
            Toast.makeText(this@WebTabActivity, "読み取ったサイトの外には移動できません", Toast.LENGTH_SHORT).show()
            return true
        }

        // フォーム送信など shouldOverrideUrlLoading を通らない移動も、読み込み開始の時点で止める
        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            val prefix = strictPrefix
            if (prefix != null && url != null && !isUnderPrefix(Uri.parse(url), prefix)) {
                view.stopLoading()
                if (view.canGoBack()) view.goBack() else view.loadUrl(prefix.toString())
                blockedOutsidePrefix()
                return
            }
            super.onPageStarted(view, url, favicon)
        }
    }

    private fun isUnderPrefix(uri: Uri, prefix: Uri): Boolean =
        uri.scheme.equals(prefix.scheme, ignoreCase = true) &&
            uri.host.equals(prefix.host, ignoreCase = true) &&
            (uri.port == prefix.port) &&
            (uri.path ?: "/").ifEmpty { "/" }.startsWith((prefix.path ?: "/").ifEmpty { "/" })

    private fun blockedOutsidePrefix() {
        Toast.makeText(this, "${strictPrefix} の外には移動できません", Toast.LENGTH_SHORT).show()
    }

    private fun isAllowedHost(host: String): Boolean {
        val h = baseHost(host)
        return allowedHosts.any { a -> h == a || h.endsWith(".$a") || a.endsWith(".$h") }
    }

    private fun baseHost(host: String): String = host.lowercase().removePrefix("www.")
}
