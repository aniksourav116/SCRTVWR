package srv.scrtvwr.media.remote

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset

/**
 * Reads directory listings and files from HTTP storage servers.
 *
 * Listings are cached briefly so a traversal that revisits folders, or a screen that refreshes
 * on resume, does not hit the server each time. Sizes and modification times come from HEAD
 * requests, which are only made when a sort order needs them.
 */
object HttpDirectoryClient {
    internal const val CONNECT_TIMEOUT_MILLIS = 15_000
    internal const val READ_TIMEOUT_MILLIS = 60_000
    private const val LISTING_CACHE_MILLIS = 30_000L
    private const val MAX_LISTING_BYTES = 8L * 1024L * 1024L
    private const val MAX_CACHED_LISTINGS = 256
    private const val MAX_CACHED_METADATA = 8_192
    private const val METADATA_REQUEST_PARALLELISM = 6
    private const val DOWNLOAD_BUFFER_BYTES = 64 * 1024

    private val listings = lruMap<String, CachedListing>(MAX_CACHED_LISTINGS)
    private val metadata = lruMap<String, RemoteFileMetadata>(MAX_CACHED_METADATA)
    private val metadataRequests = Semaphore(METADATA_REQUEST_PARALLELISM)

    /**
     * Entries [directoryUrl] lists, in server order, without sizes or times. Blocks on the network,
     * so call it off the main thread. [refresh] skips the cache.
     */
    @Throws(IOException::class)
    fun list(directoryUrl: String, refresh: Boolean = false): List<RemoteEntry> {
        val url = RemotePaths.directoryUrl(directoryUrl)
        if (!refresh) {
            synchronized(listings) { listings[url] }
                ?.takeIf { System.currentTimeMillis() - it.fetchedAtMillis < LISTING_CACHE_MILLIS }
                ?.let { return it.entries }
        }
        val entries = request(url, method = "GET") { connection ->
            val contentType = connection.contentType.orEmpty()
            if (!contentType.contains("html", ignoreCase = true)) {
                throw IOException("$url is not a directory listing")
            }
            val body = connection.inputStream.use { it.readLimited(MAX_LISTING_BYTES) }
            // Redirects (for example "/folder" to "/folder/") change the base links resolve against.
            HttpDirectoryListingParser.parse(
                html = String(body, contentType.charset()),
                directoryUrl = connection.url.toString(),
            )
        }
        synchronized(listings) { listings[url] = CachedListing(entries, System.currentTimeMillis()) }
        if (refresh) {
            synchronized(metadata) { entries.forEach { metadata.remove(it.url) } }
        }
        return entries
    }

    /** [list] with each file's size and modification time filled in from HEAD requests. */
    suspend fun listWithMetadata(directoryUrl: String, refresh: Boolean = false): List<RemoteEntry> =
        withContext(Dispatchers.IO) {
            list(directoryUrl, refresh)
                .map { entry ->
                    async {
                        if (entry.isDirectory) return@async entry
                        val fileMetadata = metadataRequests.withPermit {
                            ensureActive()
                            fileMetadata(entry.url)
                        } ?: return@async entry
                        entry.copy(
                            sizeBytes = fileMetadata.sizeBytes,
                            lastModifiedMillis = fileMetadata.lastModifiedMillis,
                        )
                    }
                }
                .awaitAll()
        }

    /**
     * Streams [url] into [target] without a file-size cap. [onProgress] receives the
     * bytes written so far and the length the server announced, or -1 when it sent none.
     * Only a fixed-size buffer is kept in memory; available disk space limits the file.
     * Cancelling the calling coroutine closes the connection, so a stalled server cannot keep a
     * cancelled download alive until the read timeout.
     */
    @Throws(IOException::class)
    suspend fun download(
        url: String,
        target: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Unit = withContext(Dispatchers.IO) {
        request<Unit>(url, method = "GET") { connection ->
            // Blocking socket reads ignore thread interrupts; closing the connection ends them.
            val disconnectOnCancel = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    connection.disconnect()
                }
            }
            try {
                val totalBytes = connection.contentLengthLong
                onProgress(0L, totalBytes)
                var downloadedBytes = 0L
                connection.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                        while (true) {
                            ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            downloadedBytes += read
                            output.write(buffer, 0, read)
                            onProgress(downloadedBytes, totalBytes)
                        }
                    }
                }
                if (totalBytes >= 0 && downloadedBytes != totalBytes) throw IOException("Download ended early")
            } catch (error: IOException) {
                // A read failing because cancellation closed the connection is a cancellation.
                ensureActive()
                throw error
            } finally {
                disconnectOnCancel.cancel()
            }
        }
    }

    private fun fileMetadata(url: String): RemoteFileMetadata? {
        synchronized(metadata) { metadata[url] }?.let { return it }
        return runCatching {
            request(url, method = "HEAD") { connection ->
                RemoteFileMetadata(
                    sizeBytes = connection.contentLengthLong.takeIf { it >= 0 } ?: RemoteEntry.UNKNOWN_SIZE,
                    lastModifiedMillis = connection.lastModified,
                )
            }
        }.getOrNull()?.also { value -> synchronized(metadata) { metadata[url] = value } }
    }

    private fun <T> request(url: String, method: String, read: (HttpURLConnection) -> T): T {
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (error: IllegalArgumentException) {
            throw IOException("Invalid server address: $url", error)
        }
        try {
            connection.requestMethod = method
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.instanceFollowRedirects = true
            val status = connection.responseCode
            if (status !in 200..299) {
                throw IOException("HTTP $status ${connection.responseMessage.orEmpty()}".trim())
            }
            return read(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun InputStream.readLimited(maxBytes: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) throw IOException("Response is larger than the ${maxBytes / (1024 * 1024)} MB limit")
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun String.charset(): Charset =
        substringAfter("charset=", "")
            .substringBefore(';')
            .trim()
            .trim('"')
            .takeIf(String::isNotEmpty)
            ?.let { name -> runCatching { Charset.forName(name) }.getOrNull() }
            ?: Charsets.UTF_8

    private fun <K, V> lruMap(maxEntries: Int): MutableMap<K, V> =
        object : LinkedHashMap<K, V>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > maxEntries
        }

    private class CachedListing(val entries: List<RemoteEntry>, val fetchedAtMillis: Long)

    private class RemoteFileMetadata(val sizeBytes: Long, val lastModifiedMillis: Long)
}
