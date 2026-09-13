package com.victor.vvwiki

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class ReaderActivity : Activity() {
    private data class Location(val repo: String, val path: String, val fragment: String = "")

    private lateinit var repository: WikiRepository
    private lateinit var webView: WebView
    private lateinit var metadata: TextView
    private lateinit var backButton: Button
    private lateinit var forwardButton: Button
    private var current = Location("vvdoc", "wiki/README.md")
    private var source = ""
    private var textZoom = 100
    private var lineHint: Int? = null
    private var findHint: String? = null
    private val history = mutableListOf<Location>()
    private var historyIndex = -1
    private val scrollPositions = mutableMapOf<String, Int>()

    private val bg = Color.rgb(16, 17, 24)
    private val surface = Color.rgb(27, 29, 37)
    private val textColor = Color.rgb(230, 231, 237)
    private val muted = Color.rgb(167, 171, 184)
    private val accent = Color.rgb(126, 180, 255)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        repository = WikiRepository(this)
        val repo = intent.getStringExtra(EXTRA_REPO) ?: "vvdoc"
        val path = intent.getStringExtra(EXTRA_PATH) ?: "wiki/README.md"
        lineHint = intent.getIntExtra(EXTRA_LINE, -1).takeIf { it > 0 }
        findHint = intent.getStringExtra(EXTRA_FIND)
        current = Location(repo, path, intent.getStringExtra(EXTRA_FRAGMENT).orEmpty())
        history += current
        historyIndex = 0
        setContentView(buildRoot())
        loadLocation(current)
    }

    private fun buildRoot(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        val toolbar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(4))
            setBackgroundColor(surface)
        }
        backButton = toolbarButton("‹") { goBack() }.apply {
            contentDescription = "Back"
            textSize = 26f
        }
        forwardButton = toolbarButton("›") { goForward() }.apply {
            contentDescription = "Forward"
            textSize = 26f
        }
        toolbar.addView(backButton, buttonParams())
        toolbar.addView(forwardButton, buttonParams())
        val title = TextView(this).apply {
            text = "Reader"
            textSize = 17f
            setTextColor(textColor)
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        toolbar.addView(title, LinearLayout.LayoutParams(0, dp(52), 1f))
        toolbar.addView(toolbarButton("−") { adjustZoom(-10) }.apply {
            contentDescription = "Zoom out"
            textSize = 22f
        }, toolbarActionParams())
        toolbar.addView(toolbarButton("+") { adjustZoom(10) }.apply {
            contentDescription = "Zoom in"
            textSize = 22f
        }, toolbarActionParams())
        toolbar.addView(toolbarButton("Search") { showFindDialog() }.apply {
            contentDescription = "Search in document"
        }, searchButtonParams())
        root.addView(toolbar)

        metadata = TextView(this).apply {
            textSize = 11f
            setTextColor(muted)
            setPadding(dp(14), dp(7), dp(14), dp(7))
            setBackgroundColor(Color.rgb(20, 22, 30))
        }
        root.addView(metadata, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))

        webView = WebView(this).apply {
            setBackgroundColor(bg)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            // The only file URL loaded is the APK-owned reader.html and its bundled assets.
            // Network loads stay disabled; note content cannot grant file access.
            settings.allowFileAccess = true
            settings.allowContentAccess = false
            settings.blockNetworkLoads = true
            settings.allowFileAccessFromFileURLs = false
            settings.allowUniversalAccessFromFileURLs = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.textZoom = textZoom
            webViewClient = WikiWebViewClient()
            addJavascriptInterface(JsBridge(), "Android")
        }
        root.addView(webView, LinearLayout.LayoutParams(-1, 0, 1f))
        updateNavigationButtons()
        return root
    }

    private fun loadLocation(location: Location) {
        val content = repository.readText(location.repo, location.path)
        if (content == null) {
            toast("Cannot read ${location.repo}/${location.path}")
            finish()
            return
        }
        current = location
        source = content
        val doc = repository.documents(location.repo).firstOrNull { it.path == location.path }
        val line = lineHint?.let { "  ·  opened near line $it" }.orEmpty()
        metadata.text = "${location.repo} / ${location.path}  ·  ${content.toByteArray().size} bytes  ·  local SHA ${doc?.sha256?.take(12) ?: "unknown"}…$line"
        backButton.isEnabled = true
        forwardButton.isEnabled = historyIndex in 0 until history.lastIndex
        webView.loadUrl("file:///android_asset/web/reader.html")
    }

    private fun evaluateRender() {
        if (!::webView.isInitialized) return
        val docs = JSONArray(repository.allPaths(current.repo)).toString()
        val js = "window.renderWiki(${JSONObject.quote(source)},${JSONObject.quote(current.repo)}," +
            "${JSONObject.quote(current.path)},$docs);"
        webView.evaluateJavascript(js, null)
        val fragment = current.fragment
        if (fragment.isNotBlank()) {
            webView.postDelayed({ webView.evaluateJavascript("window.scrollToFragment(${JSONObject.quote(fragment)});", null) }, 180)
        } else {
            val y = scrollPositions[locationKey(current)] ?: 0
            webView.postDelayed({ webView.scrollTo(0, y) }, 120)
        }
        findHint?.takeIf { it.isNotBlank() }?.let { term ->
            webView.postDelayed({ webView.evaluateJavascript("window.findText(${JSONObject.quote(term)});", null) }, 250)
            findHint = null
        }
    }

    private fun navigate(location: Location) {
        if (location.repo !in repository.repositoryIds()) return
        if (repository.readText(location.repo, location.path) == null) {
            toast("Document is not available in the local cache")
            return
        }
        if (historyIndex >= 0) scrollPositions[locationKey(current)] = webView.scrollY
        if (historyIndex < history.lastIndex) history.subList(historyIndex + 1, history.size).clear()
        history += location
        historyIndex = history.lastIndex
        lineHint = null
        loadLocation(location)
    }

    private fun goBack() {
        if (historyIndex <= 0) {
            finish()
            return
        }
        scrollPositions[locationKey(current)] = webView.scrollY
        historyIndex--
        loadLocation(history[historyIndex])
        updateNavigationButtons()
    }

    private fun goForward() {
        if (historyIndex >= history.lastIndex) return
        scrollPositions[locationKey(current)] = webView.scrollY
        historyIndex++
        loadLocation(history[historyIndex])
        updateNavigationButtons()
    }

    override fun onBackPressed() {
        if (historyIndex > 0) goBack() else super.onBackPressed()
    }

    private fun updateNavigationButtons() {
        if (!::backButton.isInitialized) return
        backButton.isEnabled = true
        forwardButton.isEnabled = historyIndex in 0 until history.lastIndex
        backButton.alpha = 1f
        forwardButton.alpha = if (forwardButton.isEnabled) 1f else .45f
    }

    private fun adjustZoom(delta: Int) {
        textZoom = (textZoom + delta).coerceIn(80, 180)
        if (::webView.isInitialized) webView.settings.textZoom = textZoom
    }

    private fun showFindDialog() {
        val input = EditText(this).apply {
            hint = "Text to find"
            setSingleLine(true)
            setPadding(dp(10), 0, dp(10), 0)
        }
        AlertDialog.Builder(this)
            .setTitle("Find in document")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Find") { _, _ ->
                val term = input.text.toString()
                if (term.isNotBlank()) webView.evaluateJavascript("window.findText(${JSONObject.quote(term)});", null)
            }
            .show()
    }

    private fun handleWikiHref(href: String) {
        val uri = runCatching { Uri.parse(href) }.getOrNull() ?: return
        val repo = uri.host?.let(Uri::decode) ?: return
        val path = uri.pathSegments.joinToString("/") { Uri.decode(it) }
        val fragment = uri.fragment?.let(Uri::decode).orEmpty()
        if (path.isNotBlank()) navigate(Location(repo, path, fragment))
    }

    private fun handleMarkdownHref(href: String) {
        if (href.startsWith("#")) {
            webView.evaluateJavascript("window.scrollToFragment(${JSONObject.quote(href.removePrefix("#"))});", null)
            return
        }
        if (href.startsWith("http://", true) || href.startsWith("https://", true)) {
            openExternal(href)
            return
        }
        val target = href.substringBefore('#')
        val fragment = href.substringAfter('#', "")
        val path = repository.resolveLink(current.repo, current.path, target) ?: run {
            toast("Unresolved local link: $target")
            return
        }
        navigate(Location(current.repo, path, fragment))
    }

    private fun openExternal(url: String) {
        if (!(url.startsWith("https://", true) || url.startsWith("http://", true) || url.startsWith("mailto:", true))) return
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { toast("No app can open this link") }
    }

    private fun locationKey(location: Location): String = "${location.repo}/${location.path}#${location.fragment}"
    private fun toolbarButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 14f
        setTextColor(textColor)
        setBackgroundColor(Color.TRANSPARENT)
        minWidth = 0
        minHeight = 0
        setPadding(dp(3), 0, dp(3), 0)
        setOnClickListener { action() }
    }
    private fun buttonParams() = LinearLayout.LayoutParams(dp(56), dp(52))
    private fun toolbarActionParams() = LinearLayout.LayoutParams(dp(52), dp(52))
    private fun searchButtonParams() = LinearLayout.LayoutParams(dp(78), dp(52))
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_SHORT).show()

    private inner class WikiWebViewClient : WebViewClient() {
        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            evaluateRender()
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val url = request?.url?.toString().orEmpty()
            when {
                url.startsWith("wiki://") -> handleWikiHref(url)
                url.startsWith("http://", true) || url.startsWith("https://", true) || url.startsWith("mailto:", true) -> openExternal(url)
                else -> handleMarkdownHref(url)
            }
            return true
        }

        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            val uri = request?.url ?: return null
            if (uri.scheme != "wiki-resource") return super.shouldInterceptRequest(view, request)
            val repo = uri.host?.let(Uri::decode) ?: return null
            val path = uri.pathSegments.joinToString("/") { Uri.decode(it) }
            val resource = repository.resource(repo, path) ?: return null
            val encoding = if (resource.first.startsWith("text/")) "utf-8" else null
            return WebResourceResponse(resource.first, encoding, resource.second)
        }
    }

    inner class JsBridge {
        @JavascriptInterface
        fun openWikiLink(href: String) = runOnUiThread { handleWikiHref(href) }

        @JavascriptInterface
        fun openMarkdownLink(href: String) = runOnUiThread { handleMarkdownHref(href) }

        @JavascriptInterface
        fun openExternal(url: String) = runOnUiThread { this@ReaderActivity.openExternal(url) }

        @JavascriptInterface
        fun rememberFragment(fragment: String) = Unit

        @JavascriptInterface
        fun reportRenderError(error: String) = runOnUiThread {
            if (error.isNotBlank()) Toast.makeText(this@ReaderActivity, "Renderer fallback used", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val EXTRA_REPO = "repo"
        const val EXTRA_PATH = "path"
        const val EXTRA_LINE = "line"
        const val EXTRA_FIND = "find"
        const val EXTRA_FRAGMENT = "fragment"
    }
}
