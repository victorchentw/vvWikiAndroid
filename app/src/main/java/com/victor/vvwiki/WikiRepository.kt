package com.victor.vvwiki

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

/**
 * Small, deterministic offline wiki store.
 *
 * The filesystem is the source of truth for this first runnable APK. It is
 * intentionally allowlisted: imported content can be browsed and rendered,
 * but keys, certificates, binaries and secret-looking paths never enter the
 * reader/index.
 */
class WikiRepository(private val context: Context) {
    data class Document(
        val repo: String,
        val path: String,
        val bytes: Long,
        val modified: Long,
        val sha256: String,
    )

    data class SearchResult(
        val document: Document,
        val heading: String,
        val line: Int,
        val snippet: String,
    )

    data class SyncStatus(
        val commit: String?,
        val syncedAt: Long?,
        val files: Int,
        val error: String?,
    )

    private val root = File(context.filesDir, "wiki-cache")
    private val prefs = context.getSharedPreferences("wiki-store", Context.MODE_PRIVATE)
    private val repos = listOf("vvdoc", "radoc")
    private val textExtensions = setOf("md", "markdown", "txt")
    private val imageExtensions = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "avif")
    private val maxTextBytes = 20L * 1024L * 1024L
    private val maxImageBytes = 50L * 1024L * 1024L

    init {
        root.mkdirs()
        migrateLegacySeedCache()
    }

    fun repositoryIds(): List<String> = repos

    fun resetLocalCache() {
        root.deleteRecursively()
        root.mkdirs()
        val editor = prefs.edit().remove("seed-installed")
        repos.forEach { repo ->
            editor.remove("sync-$repo-commit")
                .remove("sync-$repo-time")
                .remove("sync-$repo-files")
                .remove("sync-$repo-error")
        }
        editor.apply()
    }

    fun syncStatus(repo: String): SyncStatus {
        require(repo in repos) { "Unknown repository" }
        return SyncStatus(
            commit = prefs.getString("sync-$repo-commit", null),
            syncedAt = prefs.getLong("sync-$repo-time", 0L).takeIf { it > 0L },
            files = prefs.getInt("sync-$repo-files", 0),
            error = prefs.getString("sync-$repo-error", null),
        )
    }

    /**
     * Replace one repo's readable working tree from a checked-out Git tree.
     * The .git directory and every disallowed path stay outside wiki-cache.
     */
    fun replaceFromCheckout(repo: String, checkout: File, commit: String): Int {
        require(repo in repos) { "Unknown repository" }
        require(checkout.isDirectory) { "Checkout does not exist" }
        val checkoutBase = checkout.canonicalFile
        val staging = File(root, ".sync-$repo-${System.nanoTime()}")
        staging.mkdirs()
        var copied = 0
        try {
            checkout.walkTopDown()
                .filter { it.isFile }
                .forEach { source ->
                    val path = relativePath(checkout, source) ?: return@forEach
                    val canonical = source.canonicalFile
                    if (!canonical.path.startsWith(checkoutBase.path + File.separator)) return@forEach
                    if (!isAllowedSyncPath(repo, path)) return@forEach
                    val limit = if (isImage(path)) maxImageBytes else maxTextBytes
                    if (source.length() > limit) return@forEach
                    val destination = File(staging, path)
                    destination.parentFile?.mkdirs()
                    FileInputStream(source).use { input ->
                        FileOutputStream(destination).use { output -> input.copyTo(output, 16 * 1024) }
                    }
                    copied++
                }

            val target = File(root, repo)
            target.deleteRecursively()
            if (!staging.renameTo(target)) {
                staging.copyRecursively(target, overwrite = true)
                staging.deleteRecursively()
            }
            prefs.edit()
                .putString("sync-$repo-commit", commit)
                .putLong("sync-$repo-time", System.currentTimeMillis())
                .putInt("sync-$repo-files", copied)
                .remove("sync-$repo-error")
                .apply()
            return copied
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
    }

    fun recordSyncFailure(repo: String, message: String) {
        if (repo !in repos) return
        prefs.edit().putString("sync-$repo-error", message.take(500)).apply()
    }

    fun documents(repo: String? = null): List<Document> {
        val selected = if (repo == null || repo == "All") repos else listOf(repo)
        return selected.flatMap { id ->
            val base = File(root, id)
            if (!base.isDirectory) emptyList() else base.walkTopDown()
                .filter { it.isFile && isReadableText(it.name) && relativePath(base, it) != null }
                .mapNotNull { file ->
                    val path = relativePath(base, file) ?: return@mapNotNull null
                    if (!isAllowedPath(path) || file.length() > maxTextBytes) return@mapNotNull null
                    Document(id, path, file.length(), file.lastModified(), sha256(file))
                }
                .toList()
        }.sortedWith(compareBy({ it.repo }, { it.path.lowercase(Locale.ROOT) }))
    }

    /** All paths are passed to the WebView resolver so local image embeds work. */
    fun allPaths(repo: String): List<String> {
        val base = File(root, repo)
        if (!base.isDirectory) return emptyList()
        return base.walkTopDown()
            .filter { it.isFile }
            .mapNotNull { relativePath(base, it) }
            .filter { isAllowedPath(it) && (isReadableText(it) || isImage(it)) }
            .sorted()
            .toList()
    }

    /** GitHub vvdoc only needs Markdown; radoc keeps the normal wiki allowlist. */
    internal fun isAllowedSyncPath(repo: String, path: String): Boolean {
        if (!isAllowedPath(path)) return false
        return repo != "vvdoc" || path.substringAfterLast('.', "").equals("md", ignoreCase = true)
    }

    internal fun syncMaxBytes(path: String): Long = if (isImage(path)) maxImageBytes else maxTextBytes

    fun readText(repo: String, path: String): String? {
        val file = safeFile(repo, path) ?: return null
        if (!file.isFile || !isReadableText(file.name) || file.length() > maxTextBytes) return null
        return runCatching { file.readText(StandardCharsets.UTF_8) }.getOrNull()
    }

    fun resource(repo: String, path: String): Pair<String, InputStream>? {
        val file = safeFile(repo, path) ?: return null
        if (!file.isFile || !isAllowedPath(path) || (!isReadableText(path) && !isImage(path))) return null
        val mime = when {
            isImage(path) && path.endsWith(".svg", true) -> "image/svg+xml"
            isImage(path) -> "image/${path.substringAfterLast('.').lowercase(Locale.ROOT).replace("jpg", "jpeg")}"
            else -> "text/plain"
        }
        return runCatching { mime to FileInputStream(file) }.getOrNull()
    }

    fun resolveLink(repo: String, currentPath: String, target: String): String? {
        val parsed = target.trim().substringBefore("|")
        val pathPart = parsed.substringBefore("#").trim().replace('\\', '/')
        if (pathPart.isBlank() || pathPart.startsWith("/") || pathPart.split('/').contains("..")) return null
        val paths = allPaths(repo).toSet()
        val candidates = linkedSetOf<String>()
        val clean = pathPart.removePrefix("./")
        val base = currentPath.substringBeforeLast('/', "")
        if (target.trim().startsWith("./") && base.isNotBlank()) candidates += "$base/$clean"
        if (base.isNotBlank() && !clean.contains('/')) candidates += "$base/$clean"
        candidates += clean
        if (!clean.substringAfterLast('/').contains('.')) {
            candidates += candidates.toList().map { "$it.md" }
        }
        candidates.firstOrNull { it in paths }?.let { return it }
        val stem = clean.substringAfterLast('/').removeSuffix(".md")
        val matches = paths.filter {
            val name = it.substringAfterLast('/').substringBeforeLast('.', it.substringAfterLast('/'))
            name == stem
        }
        return matches.singleOrNull()
    }

    fun search(query: String, repo: String? = null): List<SearchResult> {
        val needle = query.trim().lowercase(Locale.ROOT)
        if (needle.isBlank()) return emptyList()
        return documents(repo).mapNotNull { document ->
            val text = readText(document.repo, document.path) ?: return@mapNotNull null
            val lower = text.lowercase(Locale.ROOT)
            val index = lower.indexOf(needle)
            if (index < 0) return@mapNotNull null
            val line = text.substring(0, index).count { it == '\n' } + 1
            val lines = text.split('\n')
            val sourceLine = lines.getOrNull(line - 1)?.trim().orEmpty()
            val heading = lines.take(line).lastOrNull { it.trimStart().startsWith("#") }?.trim().orEmpty()
            val start = maxOf(0, index - 80)
            val end = minOf(text.length, index + needle.length + 140)
            SearchResult(document, heading, line, text.substring(start, end).replace('\n', ' ').trim())
        }.sortedWith(compareBy({ it.document.repo }, { it.document.path }, { it.line }))
    }

    fun importTree(treeUri: Uri, repo: String): Int {
        require(repo in repos) { "Unknown repository" }
        val resolver = context.contentResolver
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val count = copyChildren(resolver, treeUri, rootId, File(root, repo), "", repo)
        return count
    }

    private fun copyChildren(
        resolver: android.content.ContentResolver,
        treeUri: Uri,
        parentDocumentId: String,
        destination: File,
        relativeParent: String,
        repo: String,
    ): Int {
        var copied = 0
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            while (cursor.moveToNext()) {
                val id = cursor.getString(idIndex)
                val name = cursor.getString(nameIndex) ?: continue
                if (name == "." || name == ".." || name.contains('/') || name.contains('\\')) continue
                val relative = if (relativeParent.isBlank()) name else "$relativeParent/$name"
                val mime = cursor.getString(mimeIndex).orEmpty()
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    if (isSafeDirectory(relative)) {
                        copied += copyChildren(resolver, treeUri, id, File(destination, name), relative, repo)
                    }
                    continue
                }
                if (!isAllowedPath(relative)) continue
                val size = if (cursor.isNull(sizeIndex)) 0L else cursor.getLong(sizeIndex)
                val limit = if (isImage(relative)) maxImageBytes else maxTextBytes
                if (size > limit) continue
                val output = safeFile(repo, relative) ?: continue
                output.parentFile?.mkdirs()
                val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                resolver.openInputStream(documentUri)?.use { input ->
                    FileOutputStream(output).use { outputStream ->
                        input.copyTo(outputStream, 16 * 1024)
                    }
                }
                copied++
            }
        }
        return copied
    }

    /** Remove fixture content left by versions before 0.1.2; new APKs contain no Markdown seed. */
    private fun migrateLegacySeedCache() {
        if (!prefs.getBoolean("seed-installed", false)) return
        repos.forEach { repo ->
            if (prefs.getString("sync-$repo-commit", null).isNullOrBlank()) {
                File(root, repo).deleteRecursively()
            }
        }
        prefs.edit().remove("seed-installed").apply()
    }

    private fun safeFile(repo: String, path: String): File? {
        if (repo !in repos || !isAllowedPath(path)) return null
        val base = File(root, repo).canonicalFile
        val candidate = File(base, path).canonicalFile
        if (candidate.path != base.path && !candidate.path.startsWith(base.path + File.separator)) return null
        return candidate
    }

    private fun isSafeDirectory(path: String): Boolean = path.split('/').none { segment ->
        segment.isBlank() || segment == "." || segment == ".." || segment.startsWith(".") || isSecretName(segment)
    }

    private fun isAllowedPath(path: String): Boolean {
        val normalized = path.replace('\\', '/')
        if (normalized.startsWith('/') || normalized.split('/').any { it == ".." || it.isBlank() || it.startsWith(".") }) return false
        if (normalized.split('/').any(::isSecretName)) return false
        val extension = normalized.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return extension in textExtensions || extension in imageExtensions
    }

    private fun isReadableText(pathOrName: String): Boolean = pathOrName.substringAfterLast('.', "").lowercase(Locale.ROOT) in textExtensions
    private fun isImage(pathOrName: String): Boolean = pathOrName.substringAfterLast('.', "").lowercase(Locale.ROOT) in imageExtensions

    private fun isSecretName(value: String): Boolean {
        val n = value.lowercase(Locale.ROOT)
        return n == ".git" || n == "key" || n == "keys" || n == "secret" || n == "secrets" ||
            n.contains("credential") || n.contains("password") || n.contains("passwd") ||
            n.contains("token") || n.contains("private") || n.endsWith(".pem") || n.endsWith(".jks") ||
            n.endsWith(".keystore") || n.endsWith(".p12") || n.endsWith(".pfx") || n.endsWith(".key")
    }

    private fun relativePath(base: File, file: File): String? = runCatching {
        base.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/')
    }.getOrNull()

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
