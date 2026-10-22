package srv.scrtvwr.media.remote

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicInteger

/**
 * Reads directory listings and files from HTTP storage servers.
 *
 * Listings are cached briefly so a traversal that revisits folders, or a screen that refreshes
 * on resume, does not hit the server each time, and callers asking for the same folder at once
 * share one request. Sizes and modification times come from one HEAD request per file, so they
 * are only fetched for the files a caller asks about.
 */
object HttpDirectoryClient {
    internal const val CONNECT_TIMEOUT_MILLIS = 15_000
    internal const val READ_TIMEOUT_MILLIS = 60_000
    private const val LISTING_CACHE_MILLIS = 30_000L
    private const val MAX_LISTING_BYTES = 8L * 1024L * 1024L
    private const val MAX_CACHED_LISTINGS = 256
    private const val MAX_CACHED_METADATA_DIRECTORIES = 64
    private const val METADATA_REQUEST_PARALLELISM = 6
    private const val METADATA_FILL_WORKERS = 4
    private const val DOWNLOAD_BUFFER_BYTES = 64 * 1024

    private val listings = lruMap<String, CachedListing>(MAX_CACHED_LISTINGS)
    private val listingRequests = ConcurrentHashMap<String, FutureTask<List<RemoteEntry>>>()

    // Kept per folder, so filling in one huge folder never evicts the results it just fetched.
    private val metadata = lruMap<String, MutableMap<String, RemoteFileMetadata>>(MAX_CACHED_METADATA_DIRECTORIES)
    private val metadataRequests = Semaphore(METADATA_REQUEST_PARALLELISM)

    /**
     * Entries [directoryUrl] lists, in server order, without sizes or times. Blocks on the network,
     * so call it off the main thread. [refresh] skips the cache and forgets the folder's sizes and
     * times. A call made while the same folder is already downloading waits for that download.
     */
    @Throws(IOException::class)
    fun list(directoryUrl: String, refresh: Boolean = false): List<RemoteEntry> {
        val url = RemotePaths.directoryUrl(directoryUrl)
        if (!refresh) cachedListing(url)?.let { return it }
        // The cache is checked again inside the task, in case a request finished since the check above.
        val task = FutureTask { (if (refresh) null else cachedListing(url)) ?: fetchListing(url) }
        val running = listingRequests.putIfAbsent(url, task)
        if (running == null) {
            try {
                task.run()
            } finally {
                listingRequests.remove(url, task)
            }
        }
        val entries = try {
            (running ?: task).get()
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        }
        if (refresh) {
            val directories = entries.mapNotNullTo(HashSet()) { RemotePaths.parent(it.url) }
            synchronized(metadata) { directories.forEach { metadata.remove(it) } }
        }
        return entries
    }

    /** Whether [entry] needs no HEAD request: a folder, or a file whose size and time are cached. */
    fun hasMetadata(entry: RemoteEntry): Boolean = entry.isDirectory || cachedMetadata(entry.url) != null

    /** [entries] with the sizes and times fetched earlier filled in. Makes no requests. */
    fun withCachedMetadata(entries: List<RemoteEntry>): List<RemoteEntry> =
        entries.map { entry ->
            if (entry.isDirectory) entry else cachedMetadata(entry.url)?.let { entry.filledWith(it) } ?: entry
        }

    /**
     * [entry] with its size and modification time, from the cache or a HEAD request. Folders, and
     * files the server would not describe, come back unchanged.
     */
    suspend fun withMetadata(entry: RemoteEntry): RemoteEntry {
        if (entry.isDirectory) return entry
        val fileMetadata = cachedMetadata(entry.url)
            ?: withContext(Dispatchers.IO) {
                metadataRequests.withPermit {
                    ensureActive()
                    fetchMetadata(entry.url)
                }
            }
            ?: return entry
        return entry.filledWith(fileMetadata)
    }

    /**
     * [entries] with every file's size and modification time, in the same order. Only a few
     * requests queue for a connection at a time, so a file scrolling into view waits behind those
     * rather than behind the rest of the folder.
     */
    suspend fun withMetadata(entries: List<RemoteEntry>): List<RemoteEntry> = withContext(Dispatchers.IO) {
        val results = entries.toTypedArray()
        val next = AtomicInteger()
        List(METADATA_FILL_WORKERS) {
            launch {
                while (true) {
                    val index = next.getAndIncrement()
                    if (index >= results.size) break
                    results[index] = withMetadata(results[index])
                }
            }
        }.joinAll()
        results.asList()
    }

    /** [list] with each file's size and modification time filled in from HEAD requests. */
    suspend fun listWithMetadata(directoryUrl: String, refresh: Boolean = false): List<RemoteEntry> =
        withMetadata(withContext(Dispatchers.IO) { list(directoryUrl, refresh) })

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

    private fun cachedListing(url: String): List<RemoteEntry>? =
        synchronized(listings) { listings[url] }
            ?.takeIf { System.currentTimeMillis() - it.fetchedAtMillis < LISTING_CACHE_MILLIS }
            ?.entries

    private fun fetchListing(url: String): List<RemoteEntry> {
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
        return entries
    }

    private fun cachedMetadata(url: String): RemoteFileMetadata? {
        val directory = RemotePaths.parent(url) ?: return null
        return synchronized(metadata) { metadata[directory]?.get(url) }
    }

    private fun fetchMetadata(url: String): RemoteFileMetadata? {
        cachedMetadata(url)?.let { return it }
        val value = runCatching {
            request(url, method = "HEAD") { connection ->
                RemoteFileMetadata(
                    sizeBytes = connection.contentLengthLong.takeIf { it >= 0 } ?: RemoteEntry.UNKNOWN_SIZE,
                    lastModifiedMillis = connection.lastModified,
                )
            }
        }.getOrNull() ?: return null
        RemotePaths.parent(url)?.let { directory ->
            synchronized(metadata) { metadata.getOrPut(directory) { HashMap() }[url] = value }
        }
        return value
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
            HttpServerAuth.authorizationFor(url)?.let { connection.setRequestProperty("Authorization", it) }
            val status = connection.responseCode
            if (status == HttpURLConnection.HTTP_UNAUTHORIZED) throw HttpAuthenticationException()
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

    private fun RemoteEntry.filledWith(value: RemoteFileMetadata): RemoteEntry =
        copy(sizeBytes = value.sizeBytes, lastModifiedMillis = value.lastModifiedMillis)
}

/** The server answered 401: the username and password saved for it are missing or wrong. */
class HttpAuthenticationException : IOException("HTTP 401: wrong or missing username and password")
