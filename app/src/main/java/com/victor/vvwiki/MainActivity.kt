package com.victor.vvwiki

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.text.DateFormat
import java.util.Date

class MainActivity : Activity() {
    private lateinit var repository: WikiRepository
    private lateinit var gitSync: GitSync
    private lateinit var rootView: View
    private lateinit var content: FrameLayout
    private lateinit var status: TextView
    private var syncButton: Button? = null
    private var rescanButton: Button? = null
    private var loadingIndicator: ProgressBar? = null
    private var syncInProgress = false
    private var rescanInProgress = false
    private var startupSyncAttempted = false
    private var currentScreen = Screen.LIBRARY
    private var selectedRepo = "All"
    private var pendingImportRepo = "vvdoc"
    private var searchResultsContainer: LinearLayout? = null
    private var searchQuery = ""
    private var searchCacheKey: String? = null
    private var searchCacheResults: List<WikiRepository.SearchResult> = emptyList()
    private var commentsCache: List<WikiRepository.Comment>? = null
    private var libraryPage: View? = null
    private var libraryNeedsRefresh = true
    private var libraryStatus = "Ready"

    private enum class Screen { LIBRARY, SEARCH, COMMENTS, SETTINGS }

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
        rootView = buildRoot()
        setContentView(rootView)
        showLibrary()
        if (repository.autoSyncOnLaunch()) {
            Handler(Looper.getMainLooper()).postDelayed({ startStartupSync() }, 700)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applySystemUiForOrientation()
        if (::rootView.isInitialized) rootView.requestApplyInsets()
        if (::content.isInitialized) {
            when (currentScreen) {
                Screen.LIBRARY -> {
                    // Rebuild only the lightweight shell; retain the existing document rows.
                    libraryPage = null
                    showLibrary()
                }
                Screen.SEARCH -> showSearch(focusQuery = false)
                Screen.COMMENTS -> showComments()
                Screen.SETTINGS -> Unit
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Do not rebuild/rehash the whole Library when returning from Reader.
        // The previous implementation made the back gesture wait on every cached file.
        if (::repository.isInitialized && ::content.isInitialized) {
            when (currentScreen) {
                Screen.LIBRARY -> if (libraryNeedsRefresh) showLibrary(forceRefresh = true) else updateStatus(libraryStatus)
                Screen.COMMENTS -> showComments(forceRefresh = true)
                else -> Unit
            }
        }
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
        } else if (landscape) {
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            window.decorView.systemUiVisibility = 0
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
                val top = if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) bars.top else 0
                view.setPadding(view.paddingLeft, top, view.paddingRight, bars.bottom)
                insets
            }
        }
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val toolbar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(if (landscape) 14 else 20), dp(if (landscape) 5 else 13), dp(if (landscape) 12 else 16), dp(if (landscape) 5 else 11))
            background = roundedBackground(surface, 0)
            elevation = dp(3).toFloat()
        }
        val titleBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val title = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = if (landscape) 19f else 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textColor)
        }
        titleBlock.addView(title)
        toolbar.addView(titleBlock, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        status = TextView(this).apply {
            textSize = 11f
            setTextColor(muted)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = roundedBackground(card, 12, border)
        }
        toolbar.addView(status, LinearLayout.LayoutParams(if (landscape) dp(118) else dp(142), if (landscape) dp(30) else dp(34)))
        root.addView(toolbar)

        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))

        val nav = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = roundedBackground(surface, 0)
            elevation = dp(4).toFloat()
        }
        nav.addView(navButton("▦", "Library") { showLibrary() }, weightParams())
        nav.addView(navButton("⌕", "Search") { showSearch() }, weightParams())
        nav.addView(navButton("💬", "Comments") { showComments(forceRefresh = true) }, weightParams())
        nav.addView(navButton("⚙", "Settings") { showSettings() }, weightParams())
        root.addView(nav)
        root.post {
            applySystemUiForOrientation()
            root.requestApplyInsets()
        }
        return root
    }

    private fun navButton(icon: String, label: String, action: () -> Unit): Button = Button(this).apply {
        val value = SpannableString("$icon\n$label")
        value.setSpan(RelativeSizeSpan(1.75f), 0, icon.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text = value
        textSize = 12f
        setAllCaps(false)
        setTextColor(textColor)
        gravity = Gravity.CENTER
        minHeight = 0
        minWidth = 0
        includeFontPadding = true
        setPadding(0, 0, 0, 0)
        background = roundedBackground(Color.TRANSPARENT, 14)
        setOnClickListener { action() }
    }

    private fun showLibrary(forceRefresh: Boolean = false) {
        currentScreen = Screen.LIBRARY
        searchResultsContainer = null
        libraryPage?.let { cached ->
            content.removeAllViews()
            content.addView(cached)
            if (forceRefresh || libraryNeedsRefresh) {
                renderLibraryList(listContainer)
                libraryNeedsRefresh = false
            } else {
                updateStatus(libraryStatus)
            }
            updateLoadingUi()
            return
        }

        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val horizontal = if (landscape) 12 else 20
            val vertical = if (landscape) 7 else 14
            setPadding(dp(horizontal), dp(vertical), dp(horizontal), 0)
        }

        val filter = EditText(this).apply {
            hint = "Filter documents by path…"
            setText(libraryFilter)
            setHintTextColor(muted)
            setTextColor(textColor)
            setSingleLine(true)
            setPadding(dp(12), 0, dp(12), 0)
            background = roundedBackground(card, 13, border)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    libraryFilter = s?.toString().orEmpty()
                    renderLibraryList(listContainer)
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }

        val controls = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(if (landscape) 6 else 10), dp(if (landscape) 2 else 4), dp(if (landscape) 6 else 8), dp(if (landscape) 2 else 4))
            background = roundedBackground(surface, 15)
        }
        val spinner = repoSpinner { repo ->
            if (selectedRepo != repo) {
                selectedRepo = repo
                renderLibraryList(listContainer)
            }
        }
        val controlHeight = if (landscape) 42 else 48
        controls.addView(spinner, LinearLayout.LayoutParams(0, dp(controlHeight), 1f))
        syncButton = button(if (landscape) "↻" else "Sync") { startSync() }.apply {
            contentDescription = "Sync"
        }
        controls.addView(syncButton, LinearLayout.LayoutParams(dp(if (landscape) 44 else 82), dp(42)).apply { leftMargin = dp(5) })
        rescanButton = button(if (landscape) "⟳" else "Rescan") { refreshAndNotify() }.apply {
            contentDescription = "Rescan"
        }
        controls.addView(rescanButton, LinearLayout.LayoutParams(dp(if (landscape) 44 else 92), dp(42)).apply { leftMargin = dp(5) })
        loadingIndicator = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = View.GONE
            contentDescription = "Loading"
        }
        controls.addView(loadingIndicator, LinearLayout.LayoutParams(dp(if (landscape) 26 else 30), dp(30)).apply { leftMargin = dp(5) })
        if (landscape) {
            controls.addView(filter, 1, LinearLayout.LayoutParams(0, dp(controlHeight), 1.15f).apply {
                leftMargin = dp(6)
            })
        }
        page.addView(controls, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(if (landscape) 6 else 8)
        })
        if (!landscape) {
            page.addView(filter, LinearLayout.LayoutParams(-1, dp(46)).apply { bottomMargin = dp(5) })
        }

        val hadExistingList = ::listContainer.isInitialized
        val reusableList = if (hadExistingList) {
            (listContainer.parent as? ViewGroup)?.removeView(listContainer)
            listContainer
        } else {
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 0, 0, dp(28))
            }
        }
        val scroll = ScrollView(this).apply {
            clipToPadding = false
            isFillViewport = true
            setPadding(0, dp(if (landscape) 0 else 4), 0, 0)
        }
        listContainer = reusableList
        scroll.addView(listContainer)
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        libraryPage = page
        content.removeAllViews()
        content.addView(page)
        if (!hadExistingList || libraryNeedsRefresh) renderLibraryList(listContainer)
        libraryNeedsRefresh = false
        updateLoadingUi()
    }

    private var libraryFilter = ""
    private lateinit var listContainer: LinearLayout

    private fun renderLibraryList(container: LinearLayout) {
        if (!::listContainer.isInitialized || container !== listContainer) return
        container.removeAllViews()
        val filter = libraryFilter.trim().lowercase()
        val selected = if (selectedRepo == "All") null else selectedRepo
        val docs = repository.documents(selected)
            .filter { filter.isBlank() || it.path.lowercase().contains(filter) }
        if (filter.isBlank()) {
            val pinned = repository.pinnedDocuments().filter { selected == null || it.repo == selected }
            val recent = repository.recentDocuments().filter { selected == null || it.repo == selected }
            if (pinned.isNotEmpty()) {
                container.addView(sectionLabel("Pinned"))
                pinned.forEach { addDocumentRow(container, it) }
            }
            if (recent.isNotEmpty()) {
                container.addView(sectionLabel("Continue reading"))
                recent.forEach { addDocumentRow(container, it) }
            }
            if (pinned.isNotEmpty() || recent.isNotEmpty()) container.addView(sectionLabel("All documents"))
        }
        if (docs.isEmpty()) {
            container.addView(emptyState("No documents match this filter.", "Sync a repository or import a local folder to get started."))
            libraryStatus = "0 documents"
            updateStatus(libraryStatus)
            return
        }
        docs.forEach { addDocumentRow(container, it) }
        libraryStatus = "${docs.size} documents"
        updateStatus(libraryStatus)
    }

    private fun addDocumentRow(container: LinearLayout, document: WikiRepository.Document) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(13), dp(10), dp(13))
            background = roundedBackground(card, 16, border)
            elevation = dp(1).toFloat()
            isClickable = true
            setOnClickListener { openReader(document.repo, document.path) }
        }
        val titleRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(TextView(this).apply {
            text = "${document.repo} / ${document.path}"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(accent)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val pinned = repository.isPinned(document.repo, document.path)
        titleRow.addView(button(if (pinned) "📌" else "📍") {
            repository.togglePinned(document.repo, document.path)
            renderLibraryList(container)
        }.apply { textSize = 18f }, LinearLayout.LayoutParams(dp(68), dp(42)))
        row.addView(titleRow)
        row.addView(TextView(this).apply {
            text = "${formatBytes(document.bytes)}  ·  ${formatTime(document.modified)}  ·  SHA-256 ${document.sha256.take(10)}…"
            textSize = 12f
            setTextColor(muted)
        })
        container.addView(row, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(10)
        })
    }

    private fun sectionLabel(value: String) = TextView(this).apply {
        text = value.uppercase()
        textSize = 12f
        letterSpacing = 0.08f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(accent)
        setPadding(dp(4), dp(14), 0, dp(8))
    }

    private fun showSearch(focusQuery: Boolean = true) {
        currentScreen = Screen.SEARCH
        searchResultsContainer = null
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val horizontal = if (landscape) 12 else 20
            val vertical = if (landscape) 7 else 14
            setPadding(dp(horizontal), dp(vertical), dp(horizontal), 0)
        }
        if (!landscape) {
            page.addView(heading("Search"))
            page.addView(label("Search local Markdown and text. Results never leave this device."))
        }
        val controls = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(if (landscape) 6 else 10), dp(if (landscape) 2 else 4), dp(if (landscape) 6 else 8), dp(if (landscape) 2 else 4))
            background = roundedBackground(surface, 15)
        }
        val query = EditText(this).apply {
            hint = "Search full text…"
            setText(searchQuery)
            setHintTextColor(muted)
            setTextColor(textColor)
            setSingleLine(true)
            background = roundedBackground(card, 13, border)
            setPadding(dp(12), 0, dp(12), 0)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    searchQuery = s?.toString().orEmpty()
                    renderSearchResults()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        val spinner = repoSpinner { repo ->
            if (selectedRepo != repo) {
                selectedRepo = repo
                renderSearchResults()
            }
        }
        val controlHeight = if (landscape) 42 else 48
        controls.addView(query, LinearLayout.LayoutParams(0, dp(controlHeight), 1f))
        controls.addView(spinner, LinearLayout.LayoutParams(if (landscape) dp(104) else dp(118), dp(controlHeight)).apply { leftMargin = dp(6) })
        page.addView(controls, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(if (landscape) 6 else 8)
        })
        val scroll = ScrollView(this).apply {
            clipToPadding = false
            isFillViewport = true
        }
        searchResultsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(if (landscape) 0 else 4), 0, dp(28))
        }
        scroll.addView(searchResultsContainer)
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        content.removeAllViews()
        content.addView(page)
        renderSearchResults()
        if (focusQuery) query.requestFocus()
    }

    private fun renderSearchResults() {
        val container = searchResultsContainer ?: return
        container.removeAllViews()
        val query = searchQuery.trim()
        if (query.isBlank()) {
            container.addView(emptyState("Search your offline library", "Type a keyword to search headings, paths, and full document text."))
            updateStatus("Ready")
            return
        }
        val cacheKey = "$selectedRepo\u0000$query"
        val results = if (searchCacheKey == cacheKey) {
            searchCacheResults
        } else {
            repository.search(query, if (selectedRepo == "All") null else selectedRepo).also {
                searchCacheKey = cacheKey
                searchCacheResults = it
            }
        }
        if (results.isEmpty()) {
            container.addView(emptyState("No matches", "Nothing matched “$query”. Try a shorter keyword or another repository."))
            updateStatus("0 results")
            return
        }
        results.forEach { result ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(13), dp(16), dp(13))
                background = roundedBackground(card, 16, border)
                elevation = dp(1).toFloat()
                setOnClickListener { openReader(result.document.repo, result.document.path, result.line, searchQuery) }
            }
            row.addView(TextView(this).apply {
                text = "${result.document.repo} / ${result.document.path}:${result.line}"
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(accent)
            })
            if (result.heading.isNotBlank()) row.addView(label(result.heading))
            row.addView(TextView(this).apply {
                text = result.snippet
                textSize = 14f
                setTextColor(textColor)
                maxLines = 3
            })
            container.addView(row, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
        }
        updateStatus("${results.size} results")
    }

    private fun showComments(forceRefresh: Boolean = false) {
        currentScreen = Screen.COMMENTS
        searchResultsContainer = null
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val horizontal = if (landscape) 12 else 20
            val vertical = if (landscape) 7 else 14
            setPadding(dp(horizontal), dp(vertical), dp(horizontal), if (landscape) 0 else dp(20))
        }
        if (!landscape) {
            page.addView(heading("Comments"))
            page.addView(label("Notes and questions are stored locally and never change the Wiki source."))
        }
        val remoteLabel = sectionLabel(if (landscape) "Remote branch" else "Remote comments branch")
        if (landscape) remoteLabel.setPadding(dp(2), dp(2), 0, dp(4))
        page.addView(remoteLabel)
        val remoteActions = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val actionHeight = if (landscape) 42 else 52
        remoteActions.addView(button(if (landscape) "Upload" else "Upload branch") { exportCommentsToGit() }, LinearLayout.LayoutParams(0, dp(actionHeight), 1f).apply { rightMargin = dp(6) })
        remoteActions.addView(button(if (landscape) "Delete" else "Delete remote") {
            AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("Delete remote comments branch?")
                .setMessage("This deletes ${gitSync.commentsBranchName()} from both remotes. Local comments on this device will remain.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete") { _, _ -> deleteCommentsBranch() }
                .show()
        }, LinearLayout.LayoutParams(0, dp(actionHeight), 1f).apply { leftMargin = dp(6) })
        page.addView(remoteActions, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(if (landscape) 4 else 0)
        })
        val savedLabel = sectionLabel("Saved annotations")
        if (landscape) savedLabel.setPadding(dp(2), dp(4), 0, dp(4))
        page.addView(savedLabel)

        val scroll = ScrollView(this).apply {
            clipToPadding = false
            isFillViewport = true
        }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(if (landscape) 0 else 4), 0, dp(28))
        }
        val comments = if (!forceRefresh && commentsCache != null) {
            commentsCache!!
        } else {
            repository.allComments().also { commentsCache = it }
        }
        if (comments.isEmpty()) {
            list.addView(emptyState("No comments yet", "Select text in Reader, then choose Add comment or Ask question."))
        } else {
            comments.forEach { comment -> list.addView(commentRow(comment)) }
        }
        scroll.addView(list)
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = if (landscape) 0 else dp(4) })
        content.removeAllViews()
        content.addView(page)
        updateStatus(if (comments.isEmpty()) "No annotations" else "${comments.size} annotations")
    }

    private fun commentRow(comment: WikiRepository.Comment): View {
        val kind = if (comment.type == "question") "QUESTION" else "NOTE"
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(12), dp(12))
            background = roundedBackground(card, 16, border)
            elevation = dp(1).toFloat()
            isClickable = true
            setOnClickListener { openReader(comment.repo, comment.path, commentId = comment.id) }
        }
        row.addView(TextView(this).apply {
            text = "$kind  ·  ${comment.repo} / ${comment.path}"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (comment.type == "question") Color.rgb(242, 190, 92) else accent)
        })
        row.addView(TextView(this).apply {
            text = "“${comment.quote}”"
            textSize = 15f
            setTextColor(textColor)
            maxLines = 3
            setPadding(0, dp(7), 0, dp(4))
        })
        row.addView(TextView(this).apply {
            text = comment.body
            textSize = 14f
            setTextColor(muted)
            maxLines = 4
        })
        val actions = LinearLayout(this).apply {
            gravity = Gravity.END
            setPadding(0, dp(8), 0, 0)
        }
        actions.addView(button("Edit") { editComment(comment) }, LinearLayout.LayoutParams(dp(80), dp(40)).apply { rightMargin = dp(6) })
        actions.addView(button("Delete") { confirmDeleteComment(comment) }, LinearLayout.LayoutParams(dp(80), dp(40)))
        row.addView(actions)
        return row.apply {
            layoutParams = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            }
        }
    }

    private fun editComment(comment: WikiRepository.Comment) {
        val input = EditText(this).apply {
            setText(comment.body)
            setTextColor(textColor)
            setHintTextColor(muted)
            minLines = 3
            gravity = Gravity.TOP
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBackground(card, 12, border)
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), 0, dp(18), 0)
            addView(input, LinearLayout.LayoutParams(-1, dp(110)))
        }
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle(if (comment.type == "question") "Edit question" else "Edit note")
            .setView(layout)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                if (repository.updateComment(comment.id, input.text.toString())) {
                    toast("Comment updated")
                    showComments(forceRefresh = true)
                } else {
                    toast("Comment cannot be empty")
                }
            }
            .show()
    }

    private fun confirmDeleteComment(comment: WikiRepository.Comment) {
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("Delete this ${if (comment.type == "question") "question" else "note"}?")
            .setMessage("The Wiki source will not be changed.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                if (repository.deleteComment(comment.id)) {
                    toast("Comment deleted")
                    showComments(forceRefresh = true)
                }
            }
            .show()
    }

    private fun showSettings() {
        currentScreen = Screen.SETTINGS
        searchResultsContainer = null
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(28))
        }
        page.addView(heading("Settings"))
        page.addView(label("The APK is read-only for wiki content. Import a local wiki folder with the Android file picker; only allowlisted Markdown/text/images are copied."))
        page.addView(sectionLabel("Import & access"))
        page.addView(button("Import into vvdoc") { launchImport("vvdoc") }, fullButtonParams())
        page.addView(button("Import into radoc") { launchImport("radoc") }, fullButtonParams())
        page.addView(button("Import SSH private key") { launchSshKeyImport() }, fullButtonParams())
        page.addView(sectionLabel("Sync"))
        val autoSync = CheckBox(this).apply {
            text = "Automatically sync + rescan when app opens"
            textSize = 14f
            setTextColor(textColor)
            setPadding(dp(14), 0, dp(10), 0)
            background = roundedBackground(card, 13, border)
            isChecked = repository.autoSyncOnLaunch()
            setOnCheckedChangeListener { _, enabled ->
                repository.setAutoSyncOnLaunch(enabled)
                toast(if (enabled) "Automatic sync enabled" else "Automatic sync disabled")
            }
        }
        page.addView(autoSync, fullButtonParams())
        page.addView(label("Default is off. Manual Sync is available here; Rescan is available in Library."))
        page.addView(button("Sync vvdoc + radoc now") { startSync() }, fullButtonParams())
        page.addView(button("Upload comments to Git branch") { exportCommentsToGit() }, fullButtonParams())
        page.addView(button("Delete remote comments branch") {
            AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("Delete comments branch?")
                .setMessage("This deletes ${gitSync.commentsBranchName()} from both remotes. Exported comments will remain only on this device.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete") { _, _ -> deleteCommentsBranch() }
                .show()
        }, fullButtonParams())
        page.addView(button("Clear local cache") {
            AlertDialog.Builder(this)
                .setTitle("Clear offline cache?")
                .setMessage("Synced and imported files will be removed. No Markdown fixtures are restored.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear") { _, _ ->
                    repository.resetLocalCache()
                    invalidateContentCaches()
                    libraryNeedsRefresh = true
                    showLibrary(forceRefresh = true)
                    toast("Local cache cleared")
                }
                .show()
        }, fullButtonParams())
        val keyState = if (gitSync.hasConfiguredKey()) {
            "SSH key configured for sync."
        } else {
            "No SSH key configured; import one above or build the personal APK with VVWIKI_SSH_KEY_PATH."
        }
        val autoSyncState = if (repository.autoSyncOnLaunch()) "Automatic sync + rescan on app open: enabled." else "Automatic sync + rescan on app open: disabled."
        page.addView(infoCard("Sync status\n• vvdoc: GitHub victorchentw/vvdoc @ vv_note (Markdown only)\n• radoc: GitLab victor.chen.tw/ra_doc @ main\n• No Markdown is bundled; sync/import is required before documents appear.\n• $keyState\n• $autoSyncState\n\nSecurity\n• WebView network loads and arbitrary HTML/scripts are blocked.\n• Imported key/certificate/credential-looking paths are skipped.\n• Reader is offline and dark-only; rendered text can be copied, annotated, and exported as a temporary comments branch."))
        page.addView(button("Open Android app settings") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, UriCompat.packageUri(packageName)))
        }, fullButtonParams())
        val scroll = ScrollView(this).apply { addView(page) }
        content.removeAllViews()
        content.addView(scroll)
        updateStatus("Offline")
    }

    private fun launchImport(repo: String) {
        pendingImportRepo = repo
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, REQUEST_IMPORT)
    }

    private fun launchSshKeyImport() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "application/octet-stream"
            addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, REQUEST_SSH_KEY)
    }

    @Deprecated("Activity result API kept dependency-free for this APK")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_SSH_KEY) {
            if (resultCode != RESULT_OK) return
            val uri = data?.data ?: return
            if (gitSync.importPrivateKey(uri)) {
                toast("SSH private key imported to app-private storage")
                showSettings()
            } else {
                toast("The selected file is not a readable SSH private key")
            }
            return
        }
        if (requestCode != REQUEST_IMPORT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            repository.importTree(uri, pendingImportRepo)
        }.onSuccess { count ->
            invalidateContentCaches()
            toast("Imported $count allowlisted files into $pendingImportRepo")
            libraryNeedsRefresh = true
            showLibrary(forceRefresh = true)
        }.onFailure { error -> toast("Import failed: ${error.message ?: "unknown error"}") }
    }

    private fun refreshAndNotify() {
        if (syncInProgress || rescanInProgress) return
        rescanInProgress = true
        updateLoadingUi()
        updateStatus("Rescanning…")
        Thread {
            repository.documents()
            runOnUiThread {
                rescanInProgress = false
                invalidateContentCaches()
                toast("Local index rescanned")
                if (currentScreen == Screen.LIBRARY) showLibrary(forceRefresh = true) else {
                    libraryNeedsRefresh = true
                    updateLoadingUi()
                }
            }
        }.start()
    }

    private fun startStartupSync() {
        if (startupSyncAttempted || !repository.autoSyncOnLaunch()) return
        startupSyncAttempted = true
        if (gitSync.hasConfiguredKey()) startSync() else refreshAndNotify()
    }

    private fun startSync() {
        if (syncInProgress || rescanInProgress) return
        if (!gitSync.hasConfiguredKey()) {
            toast("No SSH key configured; import one in Settings or build with VVWIKI_SSH_KEY_PATH")
            showSettings()
            return
        }
        syncInProgress = true
        updateLoadingUi()
        updateStatus("Syncing…")
        Thread {
            val results = gitSync.syncAll { message ->
                runOnUiThread { if (syncInProgress) updateStatus(message) }
            }
            runOnUiThread {
                syncInProgress = false
                invalidateContentCaches()
                val ok = results.count { it.success }
                val failed = results.size - ok
                toast("Git sync: $ok updated, $failed failed")
                if (currentScreen == Screen.LIBRARY) showLibrary(forceRefresh = true) else {
                    libraryNeedsRefresh = true
                    updateStatus(syncSummary())
                }
            }
        }.start()
    }

    private fun invalidateContentCaches() {
        searchCacheKey = null
        searchCacheResults = emptyList()
        commentsCache = null
    }

    private fun updateLoadingUi() {
        val busy = syncInProgress || rescanInProgress
        syncButton?.isEnabled = !busy
        rescanButton?.isEnabled = !busy
        loadingIndicator?.visibility = if (busy) View.VISIBLE else View.GONE
    }

    private fun exportCommentsToGit() {
        if (syncInProgress || rescanInProgress) {
            toast("Wait for the current operation to finish")
            return
        }
        if (!gitSync.hasConfiguredKey()) {
            toast("No SSH key configured for comment export")
            return
        }
        toast("Uploading ${gitSync.commentsBranchName()}…")
        Thread {
            val results = gitSync.exportCommentsBranch()
            runOnUiThread {
                val ok = results.count { it.success }
                toast("Comments branch uploaded to $ok/${results.size} repositories")
            }
        }.start()
    }

    private fun deleteCommentsBranch() {
        if (!gitSync.hasConfiguredKey()) {
            toast("No SSH key configured for branch deletion")
            return
        }
        toast("Deleting ${gitSync.commentsBranchName()}…")
        Thread {
            val results = gitSync.deleteCommentsBranch()
            runOnUiThread {
                val ok = results.count { it.success }
                toast("Comments branch deleted from $ok/${results.size} repositories")
            }
        }.start()
    }

    private fun syncSummary(): String = gitSync.repositories().joinToString("  ·  ") { remote ->
        val state = repository.syncStatus(remote.id)
        when {
            state.error != null -> "${remote.id}: error"
            state.commit != null -> "${remote.id}: ${state.commit.take(8)} (${state.files})"
            else -> "${remote.id}: not synced"
        }
    }

    private fun openReader(
        repo: String,
        path: String,
        line: Int? = null,
        find: String? = null,
        commentId: String? = null,
    ) {
        startActivity(Intent(this, ReaderActivity::class.java).apply {
            putExtra(ReaderActivity.EXTRA_REPO, repo)
            putExtra(ReaderActivity.EXTRA_PATH, path)
            if (line != null) putExtra(ReaderActivity.EXTRA_LINE, line)
            if (!find.isNullOrBlank()) putExtra(ReaderActivity.EXTRA_FIND, find)
            if (!commentId.isNullOrBlank()) putExtra(ReaderActivity.EXTRA_COMMENT_ID, commentId)
        })
    }

    private fun repoSpinner(onChange: (String) -> Unit): Spinner = Spinner(this).apply {
        val values = arrayOf("All", "vvdoc", "radoc")
        adapter = android.widget.ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, values)
        setSelection(values.indexOf(selectedRepo).coerceAtLeast(0))
        background = roundedBackground(card, 12, border)
        setPadding(dp(10), 0, dp(4), 0)
        onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) { onChange(values[position]) }
        }
    }

    private fun heading(value: String) = TextView(this).apply {
        text = value
        textSize = 26f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(textColor)
        setPadding(0, 0, 0, dp(5))
    }

    private fun label(value: String) = TextView(this).apply {
        text = value
        textSize = 14f
        setTextColor(muted)
        setLineSpacing(0f, 1.12f)
        setPadding(0, dp(2), 0, dp(9))
    }

    private fun infoCard(value: String) = TextView(this).apply {
        text = value
        textSize = 13f
        setTextColor(muted)
        setLineSpacing(0f, 1.15f)
        setPadding(dp(16), dp(15), dp(16), dp(15))
        background = roundedBackground(card, 16, border)
    }.apply {
        layoutParams = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(16)
            bottomMargin = dp(8)
        }
    }

    private fun emptyState(title: String, message: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(18), dp(18), dp(18))
        background = roundedBackground(card, 16, border)
        addView(TextView(this@MainActivity).apply {
            text = title
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textColor)
        })
        addView(TextView(this@MainActivity).apply {
            text = message
            textSize = 13f
            setTextColor(muted)
            setPadding(0, dp(6), 0, 0)
        })
    }.apply {
        layoutParams = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
            bottomMargin = dp(10)
        }
    }

    private fun roundedBackground(fill: Int, radiusDp: Int, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = dp(radiusDp).toFloat()
            stroke?.let { setStroke(dp(1), it) }
        }

    private fun button(value: String, action: () -> Unit) = Button(this).apply {
        text = value
        textSize = 13f
        setAllCaps(false)
        setTextColor(textColor)
        minHeight = 0
        minWidth = 0
        setPadding(dp(10), 0, dp(10), 0)
        background = roundedBackground(card, 13, border)
        elevation = dp(1).toFloat()
        setOnClickListener { action() }
    }

    private fun weightParams() = LinearLayout.LayoutParams(0, dp(58), 1f)
    private fun fullButtonParams() = LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(8) }
    private fun updateStatus(value: String) { if (::status.isInitialized) status.text = value }
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_SHORT).show()
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun formatBytes(value: Long): String = when {
        value >= 1024 * 1024 -> "%.1f MB".format(value / 1024f / 1024f)
        value >= 1024 -> "%.1f KB".format(value / 1024f)
        else -> "$value B"
    }
    private fun formatTime(value: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(value))

    private object UriCompat {
        fun packageUri(packageName: String): android.net.Uri = android.net.Uri.parse("package:$packageName")
    }

    companion object {
        private const val REQUEST_IMPORT = 42
        private const val REQUEST_SSH_KEY = 43
    }
}
