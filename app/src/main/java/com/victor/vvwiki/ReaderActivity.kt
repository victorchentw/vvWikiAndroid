package com.victor.vvwiki

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ActionMode
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Locale

class ReaderActivity : Activity() {
    private data class Location(val repo: String, val path: String, val fragment: String = "")
    private data class Selection(val text: String, val prefix: String, val suffix: String)

    private lateinit var repository: WikiRepository
    private lateinit var gitSync: GitSync
    private lateinit var webView: WebView
    private lateinit var metadata: TextView
    private lateinit var backButton: Button
    private lateinit var forwardButton: Button
    private var current = Location("vvdoc", "wiki/README.md")
    private var source = ""
    private var textZoom = 100
    private var lineHint: Int? = null
    private var findHint: String? = null
    private var commentIdHint: String? = null
    private val history = mutableListOf<Location>()
    private var historyIndex = -1
    private val scrollPositions = mutableMapOf<String, Int>()
    private var pendingPosition: WikiRepository.ReaderPosition? = null
    private var restoringPosition = false
    private var searchDialog: AlertDialog? = null
    private var searchInput: EditText? = null
    private var searchStatus: TextView? = null
    private val scrollHandler = Handler(Looper.getMainLooper())
    private val saveScrollRunnable = Runnable { persistCurrentScroll() }
    private val processTargets = mutableMapOf<Int, ComponentName>()
    private var pendingSpeechForPermission: String? = null

    private val bg = Color.rgb(11, 15, 20)
    private val surface = Color.rgb(20, 28, 38)
    private val card = Color.rgb(25, 35, 47)
    private val border = Color.rgb(47, 62, 79)
    private val textColor = Color.rgb(239, 244, 249)
    private val muted = Color.rgb(157, 171, 185)
    private val accent = Color.rgb(137, 194, 255)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        repository = WikiRepository(this)
        gitSync = GitSync(this, repository)

        val repo = savedInstanceState?.getString(STATE_REPO)
            ?: intent.getStringExtra(EXTRA_REPO) ?: "vvdoc"
        val path = savedInstanceState?.getString(STATE_PATH)
            ?: intent.getStringExtra(EXTRA_PATH) ?: "wiki/README.md"
        lineHint = (savedInstanceState?.getInt(STATE_LINE, -1) ?: -1).takeIf { it > 0 }
            ?: intent.getIntExtra(EXTRA_LINE, -1).takeIf { it > 0 }
        findHint = savedInstanceState?.getString(STATE_FIND)
            ?: intent.getStringExtra(EXTRA_FIND)
        commentIdHint = savedInstanceState?.getString(STATE_COMMENT_ID)
            ?: intent.getStringExtra(EXTRA_COMMENT_ID)
        current = Location(
            repo,
            path,
            savedInstanceState?.getString(STATE_FRAGMENT)
                ?: intent.getStringExtra(EXTRA_FRAGMENT).orEmpty(),
        )
        pendingPosition = if (savedInstanceState?.containsKey(STATE_SCROLL_Y) == true) {
            WikiRepository.ReaderPosition(
                savedInstanceState.getInt(STATE_SCROLL_Y, 0),
                savedInstanceState.getFloat(STATE_SCROLL_FRACTION, 0f),
            )
        } else {
            repository.readerPosition(repo, path)
        }
        history += current
        historyIndex = 0
        setContentView(buildRoot())
        loadLocation(current)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        persistCurrentScroll()
        outState.putString(STATE_REPO, current.repo)
        outState.putString(STATE_PATH, current.path)
        outState.putString(STATE_FRAGMENT, current.fragment)
        outState.putInt(STATE_SCROLL_Y, webView.scrollY)
        outState.putFloat(STATE_SCROLL_FRACTION, currentScrollFraction())
        outState.putInt(STATE_LINE, lineHint ?: -1)
        outState.putString(STATE_FIND, findInputValue())
        outState.putString(STATE_COMMENT_ID, commentIdHint)
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        persistCurrentScroll()
        super.onPause()
    }

    override fun onDestroy() {
        scrollHandler.removeCallbacks(saveScrollRunnable)
        super.onDestroy()
    }

    private fun applySystemUiForOrientation() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val decor = window.decorView
            window.setDecorFitsSystemWindows(false)
            decor.windowInsetsController?.let { controller ->
                if (landscape) {
                    controller.hide(WindowInsets.Type.statusBars())
                    controller.systemBarsBehavior =
                        android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } else {
                    controller.show(WindowInsets.Type.statusBars())
                }
            }
        } else {
            if (landscape) {
                window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                window.decorView.systemUiVisibility =
                    View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                window.decorView.systemUiVisibility = 0
            }
        }
    }

    private fun buildRoot(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            root.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                val portraitTop = if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
                    bars.top
                } else {
                    0
                }
                view.setPadding(view.paddingLeft, portraitTop, view.paddingRight, bars.bottom)
                insets
            }
        }
        val toolbar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(7), dp(8), dp(7))
            background = roundedBackground(surface, 0)
            elevation = dp(3).toFloat()
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
        toolbar.addView(toolbarButton("Notes") { showCommentsDialog() }.apply {
            contentDescription = "Comments and questions"
        }, notesButtonParams())
        toolbar.addView(toolbarButton("Search") { showFindDialog() }.apply {
            contentDescription = "Search in document"
        }, searchButtonParams())
        root.addView(toolbar)

        metadata = TextView(this).apply {
            textSize = 11f
            setTextColor(muted)
            setLineSpacing(0f, 1.1f)
            setPadding(dp(16), dp(9), dp(16), dp(9))
            setBackgroundColor(Color.rgb(15, 21, 29))
        }
        root.addView(metadata, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))

        webView = SelectionWebView(this).apply {
            setBackgroundColor(bg)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = false
            settings.defaultTextEncodingName = "utf-8"
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
            setOnScrollChangeListener { _, scrollY, _, _, _ ->
                if (!restoringPosition) {
                    scrollPositions[locationKey(current)] = scrollY
                    scrollHandler.removeCallbacks(saveScrollRunnable)
                    scrollHandler.postDelayed(saveScrollRunnable, 250)
                }
            }
        }
        root.addView(webView, LinearLayout.LayoutParams(-1, 0, 1f))
        root.post {
            applySystemUiForOrientation()
            root.requestApplyInsets()
        }
        updateNavigationButtons()
        return root
    }

    private inner class SelectionWebView(context: android.content.Context) : WebView(context) {
        override fun startActionMode(callback: ActionMode.Callback): ActionMode? =
            super.startActionMode(wrapSelectionCallback(callback))

        override fun startActionMode(callback: ActionMode.Callback, type: Int): ActionMode? =
            super.startActionMode(wrapSelectionCallback(callback), type)
    }

    private fun wrapSelectionCallback(original: ActionMode.Callback): ActionMode.Callback =
        object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                addSelectionItems(menu)
                return original.onCreateActionMode(mode, menu)
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
                val prepared = original.onPrepareActionMode(mode, menu)
                addSelectionItems(menu)
                return prepared
            }

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                when {
                    item.groupId == MENU_GROUP_PROCESS_TEXT -> {
                        val target = processTargets[item.itemId] ?: return false
                        selectedText { text ->
                            mode.finish()
                            launchProcessText(target, text)
                        }
                        return true
                    }
                    item.itemId == MENU_ADD_COMMENT -> {
                        selectedSelection { selection ->
                            mode.finish()
                            showCommentEditor("comment", selection)
                        }
                        return true
                    }
                    item.itemId == MENU_ADD_QUESTION -> {
                        selectedSelection { selection ->
                            mode.finish()
                            showCommentEditor("question", selection)
                        }
                        return true
                    }
                    item.itemId == MENU_TTS -> {
                        selectedText { text ->
                            mode.finish()
                            speakSelectedText(text)
                        }
                        return true
                    }
                }
                return original.onActionItemClicked(mode, item)
            }

            override fun onDestroyActionMode(mode: ActionMode) {
                processTargets.clear()
                original.onDestroyActionMode(mode)
            }
        }

    private fun addSelectionItems(menu: Menu) {
        menu.removeGroup(MENU_GROUP_PROCESS_TEXT)
        menu.removeItem(MENU_ADD_COMMENT)
        menu.removeItem(MENU_ADD_QUESTION)
        menu.removeItem(MENU_TTS)
        menu.add(Menu.NONE, MENU_ADD_COMMENT, 0, "Add comment")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(Menu.NONE, MENU_ADD_QUESTION, 1, "Ask question")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(Menu.NONE, MENU_TTS, 2, "🔊 TTS")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        processTargets.clear()
        val processIntent = Intent(Intent.ACTION_PROCESS_TEXT)
            .addCategory(Intent.CATEGORY_DEFAULT)
            .setType("text/plain")
        val activities = packageManager.queryIntentActivities(processIntent, 0)
            .sortedWith(compareBy<android.content.pm.ResolveInfo>(
                { selectionActionPriority(it.loadLabel(packageManager).toString()) },
                { it.loadLabel(packageManager).toString().lowercase(Locale.ROOT) },
            ))
        activities.forEachIndexed { index, info ->
            val activity = info.activityInfo ?: return@forEachIndexed
            val label = info.loadLabel(packageManager).toString()
            val id = MENU_PROCESS_BASE + index
            processTargets[id] = ComponentName(activity.packageName, activity.name)
            val order = when (selectionActionPriority(label)) {
                0 -> 3 + index // Mia Explain
                1 -> 20 + index // Mia Trans
                else -> 100 + index
            }
            menu.add(MENU_GROUP_PROCESS_TEXT, id, order, label)
                .setShowAsAction(if (selectionActionPriority(label) < 2) MenuItem.SHOW_AS_ACTION_IF_ROOM else MenuItem.SHOW_AS_ACTION_NEVER)
        }
    }

    private fun selectionActionPriority(label: String): Int {
        val normalized = label.lowercase(Locale.ROOT)
        return when {
            "mia explain" in normalized -> 0
            "mia trans" in normalized -> 1
            else -> 2
        }
    }

    private fun speakSelectedText(text: String) {
        val value = text.trim()
        if (value.isBlank()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingSpeechForPermission = value
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_TTS_NOTIFICATION)
            return
        }
        startTtsService(value)
    }

    private fun startTtsService(text: String) {
        val intent = TtsService.speakIntent(this, text)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        }.onFailure { toast("Unable to start TTS: ${it.message ?: "unknown error"}") }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_TTS_NOTIFICATION) return
        val text = pendingSpeechForPermission
        pendingSpeechForPermission = null
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && !text.isNullOrBlank()) {
            startTtsService(text)
        } else {
            toast("Allow notifications to use TTS controls")
        }
    }

    private fun selectedText(callback: (String) -> Unit) {
        webView.evaluateJavascript("window.getSelectionPayload();") { raw ->
            parseSelection(raw)?.let { callback(it.text) }
        }
    }

    private fun selectedSelection(callback: (Selection) -> Unit) {
        webView.evaluateJavascript("window.getSelectionPayload();") { raw ->
            parseSelection(raw)?.let(callback)
                ?: toast("Select some rendered text first")
        }
    }

    private fun parseSelection(raw: String): Selection? = runCatching {
        val encoded = JSONTokener(raw).nextValue() as? String ?: return@runCatching null
        val value = JSONObject(encoded)
        val text = value.optString("text").trim()
        if (text.isBlank()) null else Selection(
            text,
            value.optString("prefix"),
            value.optString("suffix"),
        )
    }.getOrNull()

    private fun launchProcessText(component: ComponentName, text: String) {
        if (text.isBlank()) return
        val intent = Intent(Intent.ACTION_PROCESS_TEXT)
            .addCategory(Intent.CATEGORY_DEFAULT)
            .setType("text/plain")
            .setComponent(component)
            .putExtra(Intent.EXTRA_PROCESS_TEXT, text)
            .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
        runCatching { startActivity(intent) }
            .onFailure { toast("No app can process selected text") }
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
        repository.markRecentlyOpened(location.repo, location.path)
        val doc = repository.documents(location.repo).firstOrNull { it.path == location.path }
        val line = lineHint?.let { "  ·  opened near line $it" }.orEmpty()
        metadata.text = "${location.repo} / ${location.path}  ·  ${content.toByteArray().size} bytes  ·  local SHA ${doc?.sha256?.take(12) ?: "unknown"}…$line"
        backButton.isEnabled = true
        forwardButton.isEnabled = historyIndex in 0 until history.lastIndex
        webView.loadUrl("file:///android_asset/web/reader.html")
    }

    private fun evaluateRender() {
        if (!::webView.isInitialized) return
        val docs = org.json.JSONArray(repository.allPaths(current.repo)).toString()
        val comments = JSONObject.quote(repository.commentsJson(current.repo, current.path))
        val commentFocusId = commentIdHint?.takeIf { it.isNotBlank() }
        commentIdHint = null
        val js = "window.renderWiki(${JSONObject.quote(source)},${JSONObject.quote(current.repo)}," +
            "${JSONObject.quote(current.path)},$docs);window.applyComments($comments);"
        webView.evaluateJavascript(js, null)
        val fragment = current.fragment
        val stored = pendingPosition ?: scrollPositions[locationKey(current)]
            ?.let { WikiRepository.ReaderPosition(it, 0f) }
            ?: repository.readerPosition(current.repo, current.path)
        pendingPosition = null
        if (fragment.isNotBlank()) {
            webView.postDelayed({ webView.evaluateJavascript("window.scrollToFragment(${JSONObject.quote(fragment)});", null) }, 180)
        } else if (stored != null) {
            val position = stored
            webView.postDelayed({
                restoringPosition = true
                webView.evaluateJavascript("window.restoreReaderPosition(${position.y},${position.fraction});") {
                    restoringPosition = false
                }
            }, 140)
            webView.postDelayed({
                restoringPosition = true
                webView.evaluateJavascript("window.restoreReaderPosition(${position.y},${position.fraction});") {
                    restoringPosition = false
                }
            }, 500)
        }
        commentFocusId?.let { id ->
            webView.postDelayed({
                webView.evaluateJavascript("window.focusComment(${JSONObject.quote(id)});", null)
            }, 700)
        }
        findHint?.takeIf { it.isNotBlank() }?.let { term ->
            webView.postDelayed({
                searchInput?.setText(term)
                webView.evaluateJavascript("window.searchText(${JSONObject.quote(term)},1);", null)
            }, 300)
            findHint = null
        }
    }

    private fun navigate(location: Location) {
        if (location.repo !in repository.repositoryIds()) return
        if (repository.readText(location.repo, location.path) == null) {
            toast("Document is not available in the local cache")
            return
        }
        persistCurrentScroll()
        if (historyIndex < history.lastIndex) history.subList(historyIndex + 1, history.size).clear()
        history += location
        historyIndex = history.lastIndex
        lineHint = null
        pendingPosition = repository.readerPosition(location.repo, location.path)
        loadLocation(location)
    }

    private fun goBack() {
        if (historyIndex <= 0) {
            persistCurrentScroll()
            finish()
            return
        }
        persistCurrentScroll()
        historyIndex--
        pendingPosition = scrollPositions[locationKey(history[historyIndex])]
            ?.let { WikiRepository.ReaderPosition(it, 0f) }
        loadLocation(history[historyIndex])
        updateNavigationButtons()
    }

    private fun goForward() {
        if (historyIndex >= history.lastIndex) return
        persistCurrentScroll()
        historyIndex++
        pendingPosition = scrollPositions[locationKey(history[historyIndex])]
            ?.let { WikiRepository.ReaderPosition(it, 0f) }
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
        searchDialog?.let { if (it.isShowing) { searchInput?.requestFocus(); return } }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(4), dp(18), 0)
        }
        val input = EditText(this).apply {
            hint = "Text to find"
            setSingleLine(true)
            setTextColor(textColor)
            setHintTextColor(muted)
        }
        searchInput = input
        layout.addView(input, LinearLayout.LayoutParams(-1, dp(48)))
        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val previous = Button(this).apply { text = "Previous"; setTextColor(textColor) }
        val next = Button(this).apply { text = "Next"; setTextColor(textColor) }
        searchStatus = TextView(this).apply { setTextColor(muted); textSize = 12f }
        actions.addView(previous, LinearLayout.LayoutParams(0, dp(44), 1f))
        actions.addView(next, LinearLayout.LayoutParams(0, dp(44), 1f))
        actions.addView(searchStatus, LinearLayout.LayoutParams(dp(58), dp(44)))
        layout.addView(actions)
        val dialog = AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("Find in document")
            .setView(layout)
            .setNegativeButton("Close", null)
            .create()
        fun runSearch(direction: Int) {
            val term = input.text.toString()
            if (term.isBlank()) {
                webView.evaluateJavascript("window.clearSearch();", null)
                searchStatus?.text = ""
                return
            }
            webView.evaluateJavascript("window.searchText(${JSONObject.quote(term)},$direction);") { raw ->
                val result = runCatching { JSONObject(JSONTokener(raw).nextValue() as String) }.getOrNull()
                searchStatus?.text = if (result == null) "" else "${result.optInt("index")}/${result.optInt("count")}"
            }
        }
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { runSearch(1) }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        previous.setOnClickListener { runSearch(-1) }
        next.setOnClickListener { runSearch(1) }
        dialog.setOnDismissListener { searchDialog = null }
        searchDialog = dialog
        dialog.show()
        input.requestFocus()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        findHint?.takeIf { it.isNotBlank() }?.let { input.setText(it) }
    }

    private fun showCommentEditor(type: String, selection: Selection) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), 0, dp(18), 0)
        }
        layout.addView(TextView(this).apply {
            text = "“${selection.text}”"
            setTextColor(accent)
            textSize = 14f
            setPadding(0, dp(4), 0, dp(10))
        })
        val input = EditText(this).apply {
            hint = if (type == "question") "Question for the AI…" else "Comment…"
            setTextColor(textColor)
            setHintTextColor(muted)
            minLines = 3
            gravity = Gravity.TOP
        }
        layout.addView(input, LinearLayout.LayoutParams(-1, dp(100)))
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle(if (type == "question") "Ask question" else "Add comment")
            .setView(layout)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val body = input.text.toString().trim()
                if (body.isBlank()) {
                    toast("Comment cannot be empty")
                } else {
                    repository.addComment(current.repo, current.path, type, selection.text, selection.prefix, selection.suffix, body)
                    applyCurrentComments()
                    toast("${if (type == "question") "Question" else "Comment"} saved locally")
                }
            }
            .show()
    }

    private fun showCommentsDialog(commentId: String? = null) {
        val comments = repository.comments(current.repo, current.path)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), 0, dp(18), 0)
        }
        if (comments.isEmpty()) {
            layout.addView(TextView(this).apply {
                text = "No comments or questions in this document. Long-press rendered text to add one."
                setTextColor(muted)
                setPadding(0, dp(8), 0, dp(8))
            })
        } else {
            comments.forEach { comment ->
                val row = TextView(this).apply {
                    text = "${if (comment.type == "question") "Question" else "Comment"}\n“${comment.quote}”\n${comment.body}"
                    setTextColor(textColor)
                    textSize = 14f
                    setPadding(dp(10), dp(9), dp(10), dp(9))
                    setBackgroundColor(surface)
                    setOnClickListener {
                        webView.evaluateJavascript("window.searchText(${JSONObject.quote(comment.quote)},1);", null)
                    }
                }
                layout.addView(row, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(8)
                })
            }
        }
        val scroll = ScrollView(this).apply { addView(layout) }
        val title = if (commentId.isNullOrBlank()) "Comments & questions" else "Comment"
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle(title)
            .setView(scroll)
            .setNeutralButton("Export Git") { _, _ -> exportCommentsToGit() }
            .setPositiveButton("Close", null)
            .show()
    }

    private fun applyCurrentComments() {
        if (!::webView.isInitialized) return
        webView.evaluateJavascript("window.applyComments(${JSONObject.quote(repository.commentsJson(current.repo, current.path))});", null)
    }

    private fun exportCommentsToGit() {
        if (!gitSync.hasConfiguredKey()) {
            toast("No SSH key configured for comment export")
            return
        }
        toast("Uploading comments branch…")
        Thread {
            val results = gitSync.exportCommentsBranch()
            runOnUiThread {
                val ok = results.count { it.success }
                toast("Comments branch: $ok/${results.size} repositories uploaded")
            }
        }.start()
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

    private fun persistCurrentScroll() {
        if (!::webView.isInitialized || source.isBlank()) return
        val y = webView.scrollY
        val fraction = currentScrollFraction()
        scrollPositions[locationKey(current)] = y
        repository.saveReaderPosition(current.repo, current.path, y, fraction)
    }

    private fun currentScrollFraction(): Float {
        if (!::webView.isInitialized) return 0f
        val total = webView.contentHeight * webView.scale
        val max = total - webView.height
        return if (max > 0f) (webView.scrollY / max).coerceIn(0f, 1f) else 0f
    }

    private fun findInputValue(): String? = searchInput?.text?.toString()?.takeIf { it.isNotBlank() }
    private fun locationKey(location: Location): String = "${location.repo}/${location.path}#${location.fragment}"
    private fun toolbarButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 13f
        setAllCaps(false)
        setTextColor(textColor)
        background = roundedBackground(card, 12, border)
        minWidth = 0
        minHeight = 0
        setPadding(dp(3), 0, dp(3), 0)
        setOnClickListener { action() }
    }
    private fun buttonParams() = LinearLayout.LayoutParams(dp(48), dp(52))
    private fun toolbarActionParams() = LinearLayout.LayoutParams(dp(45), dp(52))
    private fun notesButtonParams() = LinearLayout.LayoutParams(dp(62), dp(52))
    private fun searchButtonParams() = LinearLayout.LayoutParams(dp(72), dp(52))
    private fun roundedBackground(fill: Int, radiusDp: Int, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = dp(radiusDp).toFloat()
            stroke?.let { setStroke(dp(1), it) }
        }
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
        fun openComment(id: String) = runOnUiThread { showCommentsDialog(id) }

        @JavascriptInterface
        fun rememberScroll(y: Int, fraction: Float) = runOnUiThread {
            if (!restoringPosition) {
                scrollPositions[locationKey(current)] = y
                repository.saveReaderPosition(current.repo, current.path, y, fraction)
            }
        }

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
        const val EXTRA_COMMENT_ID = "commentId"
        const val EXTRA_FRAGMENT = "fragment"
        private const val STATE_REPO = "reader.repo"
        private const val STATE_PATH = "reader.path"
        private const val STATE_FRAGMENT = "reader.fragment"
        private const val STATE_SCROLL_Y = "reader.scrollY"
        private const val STATE_SCROLL_FRACTION = "reader.scrollFraction"
        private const val STATE_LINE = "reader.line"
        private const val STATE_FIND = "reader.find"
        private const val STATE_COMMENT_ID = "reader.commentId"
        private const val MENU_GROUP_PROCESS_TEXT = 7100
        private const val MENU_ADD_COMMENT = 7101
        private const val MENU_ADD_QUESTION = 7102
        private const val MENU_TTS = 7103
        private const val MENU_PROCESS_BASE = 7200
        private const val REQUEST_TTS_NOTIFICATION = 7300
    }
}
