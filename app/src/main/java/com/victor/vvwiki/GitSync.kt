package com.victor.vvwiki

import android.content.Context
import android.net.Uri
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.ObjectIdRef
import org.eclipse.jgit.lib.NullProgressMonitor
import org.eclipse.jgit.lib.Ref
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.FetchConnection
import org.eclipse.jgit.transport.FilterSpec
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.SshSessionFactory
import org.eclipse.jgit.transport.Transport
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.transport.ssh.jsch.JschConfigSessionFactory
import org.eclipse.jgit.transport.ssh.jsch.OpenSshConfig
import org.eclipse.jgit.treewalk.TreeWalk
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** Read-only SSH Git sync for the two wiki repositories in plan.md. */
class GitSync(
    private val context: Context,
    private val wikiRepository: WikiRepository,
) {
    data class RemoteRepository(
        val id: String,
        val remote: String,
        val branch: String,
    )

    data class Result(
        val repository: String,
        val success: Boolean,
        val commit: String? = null,
        val files: Int = 0,
        val message: String,
    )

    private data class GitFile(val path: String, val objectId: ObjectId)

    private val running = AtomicBoolean(false)
    private val metadataRoot = File(context.filesDir, "git-metadata")
    private val blobRoot = File(context.filesDir, "git-blobs")
    private val credentialRoot = File(context.filesDir, "git-credentials")

    private val remotes = listOf(
        RemoteRepository("vvdoc", "git@github.com:victorchentw/vvdoc.git", "vv_note"),
        RemoteRepository("radoc", "git@gitlab.com:victor.chen.tw/ra_doc.git", "main"),
    )

    fun repositories(): List<RemoteRepository> = remotes

    fun hasConfiguredKey(): Boolean = ensureCredentials() != null

    fun importPrivateKey(uri: Uri): Boolean {
        credentialRoot.mkdirs()
        val temporary = File(credentialRoot, "id_rsa.importing")
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(temporary).use { output -> input.copyTo(output) }
            } ?: error("Could not read the selected file")
            val bytes = temporary.readBytes()
            require(bytes.size in 64..2 * 1024 * 1024) { "The selected file is not a private SSH key" }
            val header = bytes.toString(Charsets.UTF_8)
            require(header.contains("PRIVATE KEY")) { "The selected file is not a private SSH key" }
            val target = File(credentialRoot, "id_rsa")
            temporary.copyTo(target, overwrite = true)
            target.setReadable(false, false)
            target.setReadable(true, true)
            true
        }.getOrElse {
            temporary.delete()
            false
        }.also { temporary.delete() }
    }

    fun syncAll(progress: (String) -> Unit = {}): List<Result> {
        if (!running.compareAndSet(false, true)) {
            return remotes.map { Result(it.id, false, message = "A sync is already running") }
        }
        return try {
            val credentials = ensureCredentials()
            if (credentials == null) {
                remotes.map {
                    Result(it.id, false, message = "No SSH key. Import one in Settings or build with VVWIKI_SSH_KEY_PATH.")
                }
            } else {
                installSshFactory(credentials.first, credentials.second)
                remotes.map { remote ->
                    progress("Syncing ${remote.id}…")
                    runCatching { syncOne(remote) }
                        .getOrElse { error ->
                            wikiRepository.recordSyncFailure(remote.id, error.message ?: "Sync failed")
                            Result(remote.id, false, message = error.message ?: "Sync failed")
                        }
                }
            }
        } finally {
            running.set(false)
        }
    }

    private fun syncOne(remote: RemoteRepository): Result {
        metadataRoot.mkdirs()
        blobRoot.mkdirs()
        // Remove the pre-selective-sync implementation's potentially huge full clone.
        File(context.filesDir, "git-checkouts/${remote.id}").deleteRecursively()
        val metadataDirectory = File(metadataRoot, "${remote.id}.git")
        val blobDirectory = File(blobRoot, "${remote.id}.git")
        val metadata = openOrCreateBare(metadataDirectory)
        return try {
            val commit = fetchMetadata(metadata, remote)
            val previous = wikiRepository.syncStatus(remote.id)
            if (previous.commit == commit.name) {
                return Result(remote.id, true, commit.name, previous.files, "Already current")
            }
            val files = readWikiFiles(metadata, commit, remote.id)
            val blobRepository = openOrCreateBlobStore(blobDirectory, metadata)
            try {
                fetchMissingBlobs(blobRepository, remote, files)
                val staging = File(context.filesDir, ".git-materialized-${remote.id}-${System.nanoTime()}")
                try {
                    materialize(blobRepository, files, staging)
                    val count = wikiRepository.replaceFromCheckout(remote.id, staging, commit.name)
                    Result(remote.id, true, commit.name, count, "Updated")
                } finally {
                    staging.deleteRecursively()
                }
            } finally {
                blobRepository.close()
            }
        } finally {
            metadata.close()
        }
    }

    private fun fetchMetadata(repository: Repository, remote: RemoteRepository): ObjectId {
        val source = "refs/heads/${remote.branch}"
        val destination = "refs/remotes/origin/${remote.branch}"
        Transport.open(repository, URIish(remote.remote)).use { transport ->
            // Metadata only: tree objects and commits, never the whole repository's blobs.
            transport.setFilterSpec(FilterSpec.fromFilterLine("blob:none"))
            transport.setDepth(1)
            transport.setTimeout(60)
            transport.fetch(NullProgressMonitor.INSTANCE, listOf(RefSpec("+$source:$destination")))
        }
        return repository.resolve(destination) ?: error("Remote ${remote.id} returned no $source")
    }

    private fun readWikiFiles(repository: Repository, commit: ObjectId, repoId: String): List<GitFile> {
        val result = ArrayList<GitFile>()
        RevWalk(repository).use { walk ->
            val parsed = walk.parseCommit(commit)
            TreeWalk(repository).use { tree ->
                tree.addTree(parsed.tree)
                tree.isRecursive = true
                while (tree.next()) {
                    val path = tree.pathString
                    if (!path.startsWith("wiki/")) continue
                    val mode = tree.getFileMode(0)
                    if (mode.getObjectType() != Constants.OBJ_BLOB) continue
                    if (!wikiRepository.isAllowedSyncPath(repoId, path)) continue
                    result += GitFile(path, tree.getObjectId(0))
                }
            }
        }
        return result
    }

    /**
     * Fetch only the selected wiki blobs. A separate empty-ref repository is
     * used so JGit sends the same blob wants as native Git's partial clone,
     * without downloading unrelated mail/tool/credential blobs.
     */
    private fun fetchMissingBlobs(repository: Repository, remote: RemoteRepository, files: List<GitFile>) {
        val missing = files.filterNot { repository.objectDatabase.has(it.objectId) }
        if (missing.isEmpty()) return
        val wants = missing.map { file ->
            ObjectIdRef.Unpeeled(
                Ref.Storage.NETWORK,
                "refs/vvwiki/${file.objectId.name}",
                file.objectId,
            )
        }
        Transport.open(repository, URIish(remote.remote)).use { transport ->
            transport.setFilterSpec(FilterSpec.fromFilterLine("blob:none"))
            transport.setTimeout(60)
            transport.openFetch().use { connection: FetchConnection ->
                connection.fetch(NullProgressMonitor.INSTANCE, wants, emptySet())
            }
        }
        val stillMissing = missing.firstOrNull { !repository.objectDatabase.has(it.objectId) }
        require(stillMissing == null) { "Git server did not return wiki blob ${stillMissing?.objectId?.name}" }
    }

    private fun materialize(repository: Repository, files: List<GitFile>, destination: File) {
        destination.deleteRecursively()
        destination.mkdirs()
        files.forEach { file ->
            val loader = repository.open(file.objectId)
            if (loader.type != Constants.OBJ_BLOB) return@forEach
            if (loader.size > wikiRepository.syncMaxBytes(file.path)) return@forEach
            val output = File(destination, file.path)
            output.parentFile?.mkdirs()
            loader.openStream().use { input ->
                FileOutputStream(output).use { outputStream -> input.copyTo(outputStream, 16 * 1024) }
            }
        }
    }

    private fun openOrCreateBare(directory: File): Repository {
        return if (File(directory, "config").isFile) {
            FileRepositoryBuilder().setGitDir(directory).build()
        } else {
            directory.parentFile?.mkdirs()
            Git.init().setBare(true).setDirectory(directory).call().repository
        }
    }

    private fun openOrCreateBlobStore(directory: File, metadata: Repository): Repository {
        var repository = openOrCreateBare(directory)
        val alternates = File(directory, "objects/info/alternates")
        if (!alternates.isFile) {
            alternates.parentFile?.mkdirs()
            alternates.writeText(metadata.directory.absolutePath + "\n")
            repository.close()
            repository = FileRepositoryBuilder().setGitDir(directory).build()
        }
        return repository
    }

    private fun installSshFactory(key: File, knownHosts: File) {
        SshSessionFactory.setInstance(AppSshSessionFactory(key, knownHosts))
    }

    /** Resolve the personal build asset or an imported key; never log its contents. */
    private fun ensureCredentials(): Pair<File, File>? {
        credentialRoot.mkdirs()
        val key = File(credentialRoot, "id_rsa")
        if (!key.isFile) {
            runCatching {
                context.assets.open("credentials/id_rsa").use { input ->
                    FileOutputStream(key).use { output -> input.copyTo(output) }
                }
                key.setReadable(false, false)
                key.setReadable(true, true)
            }.onFailure { key.delete() }
        }
        if (!key.isFile) return null

        val knownHosts = File(credentialRoot, "known_hosts")
        if (!knownHosts.isFile) {
            runCatching {
                context.assets.open("credentials/known_hosts").use { input ->
                    FileOutputStream(knownHosts).use { output -> input.copyTo(output) }
                }
                knownHosts.setReadable(false, false)
                knownHosts.setReadable(true, true)
            }.onFailure { knownHosts.delete() }
        }
        if (!knownHosts.isFile) return null
        return key to knownHosts
    }

    private class AppSshSessionFactory(
        private val privateKey: File,
        private val knownHosts: File,
    ) : JschConfigSessionFactory() {
        override fun configureJSch(jsch: JSch) {
            jsch.removeAllIdentity()
            jsch.setKnownHosts(knownHosts.absolutePath)
            jsch.addIdentity(privateKey.absolutePath)
        }

        override fun configure(host: OpenSshConfig.Host, session: Session) {
            session.setConfig("StrictHostKeyChecking", "yes")
            session.setConfig("PreferredAuthentications", "publickey")
            session.timeout = 60_000
        }
    }
}
