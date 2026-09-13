package com.victor.vvwiki

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
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
    private lateinit var content: FrameLayout
    private lateinit var status: TextView
    private var librarySyncInfo: TextView? = null
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

    private enum class Screen { LIBRARY, SEARCH, SETTINGS }

    private val bg = Color.rgb(16, 17, 24)
    private val surface = Color.rgb(27, 29, 37)
    private val textColor = Color.rgb(230, 231, 237)
    private val muted = Color.rgb(167, 171, 184)
    private val accent = Color.rgb(126, 180, 255)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        applySystemUiForOrientation()
        repository = WikiRepository(this)
        gitSync = GitSync(this, repository)
        setContentView(buildRoot())
        showLibrary()
        Handler(Looper.getMainLooper()).postDelayed({ startStartupSync() }, 700)
    }

    override fun onResume() {
        super.onResume()
        if (::repository.isInitialized && ::content.isInitialized && currentScreen == Screen.LIBRARY) {
            showLibrary()
        }
    }

    private fun applySystemUiForOrientation() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
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
        val toolbar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(14), dp(14), dp(10))
        }
        val title = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textColor)
        }
        toolbar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        status = TextView(this).apply {
            textSize = 12f
            setTextColor(muted)
            gravity = Gravity.END
        }
        toolbar.addView(status, LinearLayout.LayoutParams(dp(170), ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(toolbar)

        val nav = LinearLayout(this).apply {
            setPadding(dp(12), 0, dp(12), dp(8))
            setBackgroundColor(surface)
        }
        nav.addView(navButton("Library") { showLibrary() }, weightParams())
        nav.addView(navButton("Search") { showSearch() }, weightParams())
        nav.addView(navButton("Settings") { showSettings() }, weightParams())
        root.addView(nav)

        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        root.post {
            applySystemUiForOrientation()
            root.requestApplyInsets()
        }
        return root
    }

    private fun navButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 13f
        setTextColor(textColor)
        setBackgroundColor(Color.TRANSPARENT)
        setOnClickListener { action() }
    }

    private fun showLibrary() {
        currentScreen = Screen.LIBRARY
        searchResultsContainer = null
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), 0)
        }
        page.addView(heading("Offline Library"))
        page.addView(label("Browse and read synced or imported Markdown without Gemini or network access."))
        librarySyncInfo = label(syncSummary())
        page.addView(librarySyncInfo)

        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val spinner = repoSpinner { selectedRepo = it; renderLibraryList(listContainer) }
        controls.addView(spinner, LinearLayout.LayoutParams(0, dp(48), 1f))
        syncButton = button("Sync") { startSync() }
        controls.addView(syncButton, LinearLayout.LayoutParams(dp(82), dp(48)))
        rescanButton = button("Rescan") { refreshAndNotify() }
        controls.addView(rescanButton, LinearLayout.LayoutParams(dp(92), dp(48)))
        loadingIndicator = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = View.GONE
            contentDescription = "Loading"
        }
        controls.addView(loadingIndicator, LinearLayout.LayoutParams(dp(36), dp(36)))
        page.addView(controls)

        val filter = EditText(this).apply {
            hint = "Filter by path…"
            setHintTextColor(muted)
            setTextColor(textColor)
            setSingleLine(true)
            setPadding(dp(12), 0, dp(12), 0)
            setBackgroundColor(surface)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    libraryFilter = s?.toString().orEmpty()
                    renderLibraryList(listContainer)
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        page.addView(filter, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(6) })

        val scroll = ScrollView(this)
        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(24))
        }
        scroll.addView(listContainer)
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        content.removeAllViews()
        content.addView(page)
        renderLibraryList(listContainer)
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
        librarySyncInfo?.text = syncSummary()

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
            container.addView(label("No documents match this filter."))
            updateStatus("0 documents")
            return
        }
        docs.forEach { addDocumentRow(container, it) }
        updateStatus("${docs.size} documents")
    }

    private fun addDocumentRow(container: LinearLayout, document: WikiRepository.Document) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(10), dp(10))
            setBackgroundColor(surface)
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
        titleRow.addView(button(if (pinned) "Unpin" else "Pin") {
            repository.togglePinned(document.repo, document.path)
            renderLibraryList(container)
        }, LinearLayout.LayoutParams(dp(68), dp(42)))
        row.addView(titleRow)
        row.addView(TextView(this).apply {
            text = "${formatBytes(document.bytes)}  ·  ${formatTime(document.modified)}  ·  SHA-256 ${document.sha256.take(10)}…"
            textSize = 12f
            setTextColor(muted)
        })
        container.addView(row, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8)
        })
    }

    private fun sectionLabel(value: String) = TextView(this).apply {
        text = value
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(muted)
        setPadding(dp(4), dp(10), 0, dp(6))
    }

    private fun showSearch() {
        currentScreen = Screen.SEARCH
        searchResultsContainer = null
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), 0)
        }
        page.addView(heading("Offline Search"))
        page.addView(label("Search local Markdown and text. Results never leave this device."))
        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val query = EditText(this).apply {
            hint = "Search full text…"
            setHintTextColor(muted)
            setTextColor(textColor)
            setSingleLine(true)
            setBackgroundColor(surface)
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
        controls.addView(query, LinearLayout.LayoutParams(0, dp(48), 1f))
        val spinner = repoSpinner { selectedRepo = it; renderSearchResults() }
        controls.addView(spinner, LinearLayout.LayoutParams(dp(118), dp(48)))
        page.addView(controls)
        val scroll = ScrollView(this)
        searchResultsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(24))
        }
        scroll.addView(searchResultsContainer)
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        content.removeAllViews()
        content.addView(page)
        renderSearchResults()
        query.requestFocus()
    }

    private fun renderSearchResults() {
        val container = searchResultsContainer ?: return
        container.removeAllViews()
        val query = searchQuery.trim()
        if (query.isBlank()) {
            container.addView(label("Type a keyword to search headings, paths, and full document text."))
            updateStatus("Ready")
            return
        }
        val results = repository.search(query, if (selectedRepo == "All") null else selectedRepo)
        if (results.isEmpty()) {
            container.addView(label("No result for “$query”."))
            updateStatus("0 results")
            return
        }
        results.forEach { result ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                setBackgroundColor(surface)
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
            container.addView(row, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        }
        updateStatus("${results.size} results")
    }

    private fun showSettings() {
        currentScreen = Screen.SETTINGS
        searchResultsContainer = null
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(24))
        }
        page.addView(heading("Settings & Import"))
        page.addView(label("The APK is read-only for wiki content. Import a local wiki folder with the Android file picker; only allowlisted Markdown/text/images are copied."))
        page.addView(button("Import into vvdoc") { launchImport("vvdoc") }, fullButtonParams())
        page.addView(button("Import into radoc") { launchImport("radoc") }, fullButtonParams())
        page.addView(button("Import SSH private key") { launchSshKeyImport() }, fullButtonParams())
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
                .setPositiveButton("Clear") { _, _ -> repository.resetLocalCache(); showLibrary(); toast("Local cache cleared") }
                .show()
        }, fullButtonParams())
        val keyState = if (gitSync.hasConfiguredKey()) {
            "SSH key configured for sync."
        } else {
            "No SSH key configured; import one above or build the personal APK with VVWIKI_SSH_KEY_PATH."
        }
        page.addView(label("\nSync\n• vvdoc: GitHub victorchentw/vvdoc @ vv_note (Markdown only)\n• radoc: GitLab victor.chen.tw/ra_doc @ main\n• No Markdown is bundled; sync/import is required before documents appear.\n• $keyState\n\nSecurity\n• WebView network loads and arbitrary HTML/scripts are blocked.\n• Imported key/certificate/credential-looking paths are skipped.\n• Reader is offline and dark-only; rendered text can be copied, annotated, and exported as a temporary comments branch."))
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
            toast("Imported $count allowlisted files into $pendingImportRepo")
            showLibrary()
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
                toast("Local index rescanned")
                if (currentScreen == Screen.LIBRARY) showLibrary() else updateLoadingUi()
            }
        }.start()
    }

    private fun startStartupSync() {
        if (startupSyncAttempted) return
        startupSyncAttempted = true
        if (gitSync.hasConfiguredKey()) startSync()
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
                val ok = results.count { it.success }
                val failed = results.size - ok
                toast("Git sync: $ok updated, $failed failed")
                if (currentScreen == Screen.LIBRARY) showLibrary() else updateStatus(syncSummary())
            }
        }.start()
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

    private fun openReader(repo: String, path: String, line: Int? = null, find: String? = null) {
        startActivity(Intent(this, ReaderActivity::class.java).apply {
            putExtra(ReaderActivity.EXTRA_REPO, repo)
            putExtra(ReaderActivity.EXTRA_PATH, path)
            if (line != null) putExtra(ReaderActivity.EXTRA_LINE, line)
            if (!find.isNullOrBlank()) putExtra(ReaderActivity.EXTRA_FIND, find)
        })
    }

    private fun repoSpinner(onChange: (String) -> Unit): Spinner = Spinner(this).apply {
        val values = arrayOf("All", "vvdoc", "radoc")
        adapter = android.widget.ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, values)
        setSelection(values.indexOf(selectedRepo).coerceAtLeast(0))
        onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) { onChange(values[position]) }
        }
    }

    private fun heading(value: String) = TextView(this).apply {
        text = value
        textSize = 24f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(textColor)
        setPadding(0, 0, 0, dp(4))
    }

    private fun label(value: String) = TextView(this).apply {
        text = value
        textSize = 14f
        setTextColor(muted)
        setPadding(0, dp(2), 0, dp(8))
    }

    private fun button(value: String, action: () -> Unit) = Button(this).apply {
        text = value
        textSize = 13f
        setTextColor(textColor)
        setBackgroundColor(surface)
        setOnClickListener { action() }
    }

    private fun weightParams() = LinearLayout.LayoutParams(0, dp(44), 1f)
    private fun fullButtonParams() = LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(8) }
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
