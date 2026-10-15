package srv.scrtvwr.media.remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Bytes of a download received so far; [totalBytes] is -1 when the server did not say. */
data class DownloadProgress(val downloadedBytes: Long, val totalBytes: Long) {
    val fraction: Float?
        get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else null
}

/**
 * Local copies of archives stored on HTTP servers. `ZipFile` and junrar need random access, and
 * servers such as `python -m http.server` do not answer Range requests, so an archive is
 * downloaded whole before it is read.
 *
 * Copies live in `cacheDir/remote_archives`, named by the SHA-256 of the archive URL plus its
 * extension, so archive type detection by file name works on the copy too. The cache is trimmed
 * least recently used first. Callers keep addressing archives and their entries by URL; the copy
 * is never a path that ends up in history, bookmarks or the viewer.
 */
object RemoteArchiveCache {
    private const val MAX_ARCHIVE_BYTES = 512L * 1024L * 1024L
    private const val MAX_CACHE_BYTES = 1024L * 1024L * 1024L
    private const val DIRECTORY_NAME = "remote_archives"
    private const val PARTIAL_SUFFIX = ".part"
    private const val PROGRESS_INTERVAL_MILLIS = 200L
    private const val MAX_EXTENSION_LENGTH = 8

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // One download at a time: a second request for the same archive waits and then finds the copy.
    private val downloadLock = Mutex()
    @Volatile private var directory: File? = null

    fun initialize(cacheDirectory: File) {
        val cache = File(cacheDirectory, DIRECTORY_NAME)
        directory = cache
        scope.launch {
            downloadLock.withLock {
                // Leftovers of downloads the process died during.
                cache.listFiles()?.filter { it.name.endsWith(PARTIAL_SUFFIX) }?.forEach(File::delete)
                trim(cache)
            }
        }
    }

    /** The finished copy of [archiveUrl], marked as just used, or null when it is not cached. */
    fun cachedFile(archiveUrl: String): File? {
        val file = File(directory ?: return null, fileName(archiveUrl))
        if (!file.isFile) return null
        file.setLastModified(System.currentTimeMillis())
        return file
    }

    /**
     * Local copy of [archiveUrl], downloading it first when it is not cached. While a download
     * runs, [onProgress] is called when it starts, then at most every 200 ms. Cancelling the
     * caller cancels the download and leaves no partial file behind.
     */
    @Throws(IOException::class)
    suspend fun file(archiveUrl: String, onProgress: (DownloadProgress) -> Unit = {}): File {
        cachedFile(archiveUrl)?.let { return it }
        return downloadLock.withLock {
            cachedFile(archiveUrl) ?: download(archiveUrl, onProgress)
        }
    }

    private suspend fun download(archiveUrl: String, onProgress: (DownloadProgress) -> Unit): File {
        val cache = directory ?: throw IOException("Archive cache is not ready")
        if (!cache.isDirectory && !cache.mkdirs()) throw IOException("Unable to create the archive cache")
        val target = File(cache, fileName(archiveUrl))
        val temporary = File.createTempFile("download-", PARTIAL_SUFFIX, cache)
        try {
            var reportedAtMillis = 0L
            HttpDirectoryClient.download(archiveUrl, temporary, MAX_ARCHIVE_BYTES) { downloadedBytes, totalBytes ->
                val now = System.currentTimeMillis()
                if (now - reportedAtMillis >= PROGRESS_INTERVAL_MILLIS || downloadedBytes == totalBytes) {
                    reportedAtMillis = now
                    onProgress(DownloadProgress(downloadedBytes, totalBytes))
                }
            }
            if (!temporary.renameTo(target)) throw IOException("Unable to store the downloaded archive")
        } finally {
            temporary.delete()
        }
        target.setLastModified(System.currentTimeMillis())
        trim(cache)
        return target
    }

    private fun trim(cache: File) {
        val files = cache.listFiles()?.filter { it.isFile && !it.name.endsWith(PARTIAL_SUFFIX) } ?: return
        filesToEvict(files, MAX_CACHE_BYTES).forEach(File::delete)
    }

    /** Cache file name for [archiveUrl]: the URL's SHA-256 in hex, then its lowercase extension. */
    fun fileName(archiveUrl: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(archiveUrl.toByteArray(Charsets.UTF_8))
        val hash = digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
        val extension = RemotePaths.extension(archiveUrl)
            .filter { it in 'a'..'z' || it in '0'..'9' }
            .take(MAX_EXTENSION_LENGTH)
        return if (extension.isEmpty()) hash else "$hash.$extension"
    }

    /** The least recently used of [files] to delete so the rest fit in [maxBytes]. */
    internal fun filesToEvict(files: List<File>, maxBytes: Long): List<File> {
        var retainedBytes = 0L
        return files
            .map { file -> file to file.lastModified() }
            .sortedByDescending { (_, lastModified) -> lastModified }
            .filter { (file, _) ->
                retainedBytes += file.length()
                retainedBytes > maxBytes
            }
            .map { (file, _) -> file }
    }
}
