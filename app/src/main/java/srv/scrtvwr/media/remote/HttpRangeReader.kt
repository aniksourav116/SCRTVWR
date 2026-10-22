package srv.scrtvwr.media.remote

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Random access to a server file through HTTP Range requests, for decoders that only need a few
 * parts of a large file, such as the first frame of a video. Bytes are fetched a block at a time,
 * and the most recent blocks are kept because extractors reread headers in small steps.
 *
 * Reads fail with an [IOException] once [cancel] is called, once more than [maxBytes] have been
 * downloaded, or when the server answers a ranged request with the whole file. Such a server is
 * remembered for the rest of the process, so [supportsRanges] rules it out without a request.
 * [cancel] may be called from any thread, including while a read is blocked on the network.
 */
class HttpRangeReader(
    private val url: String,
    private val maxBytes: Long,
    private val blockBytes: Int = BLOCK_BYTES,
) {
    enum class StopReason { Cancelled, RangesUnsupported, BudgetExceeded, Failed }

    private val blocks = object : LinkedHashMap<Long, ByteArray>(MAX_CACHED_BLOCKS + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?): Boolean =
            size > MAX_CACHED_BLOCKS
    }
    private val stop = AtomicReference<StopReason?>(null)
    @Volatile private var connection: HttpURLConnection? = null

    // Null until a response reports it; UNKNOWN_SIZE when the server never says.
    private var totalSize: Long? = null
    private var downloadedBytes = 0L

    /** Why reads stopped working, or null while they still work. */
    val stopReason: StopReason?
        get() = stop.get()

    /** Length of the file, or [UNKNOWN_SIZE]. Fetches the first block when nothing has been read yet. */
    @Synchronized
    @Throws(IOException::class)
    fun size(): Long {
        if (totalSize == null) {
            block(0)
            if (totalSize == null) totalSize = UNKNOWN_SIZE
        }
        return totalSize ?: UNKNOWN_SIZE
    }

    /**
     * Copies up to [length] bytes at [position] into [buffer] and returns how many were copied,
     * which may be fewer when the read crosses a block boundary, or [END_OF_FILE].
     */
    @Synchronized
    @Throws(IOException::class)
    fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (length <= 0) return 0
        val total = size()
        if (total >= 0 && position >= total) return END_OF_FILE
        val index = position / blockBytes
        val block = block(index) ?: return END_OF_FILE
        val start = (position - index * blockBytes).toInt()
        if (start >= block.size) return END_OF_FILE
        val count = minOf(length, block.size - start)
        block.copyInto(buffer, destinationOffset = offset, startIndex = start, endIndex = start + count)
        return count
    }

    /** Makes every later read fail, and ends a read that is waiting on the server. */
    fun cancel() {
        stop.compareAndSet(null, StopReason.Cancelled)
        connection?.disconnect()
    }

    @Synchronized
    fun close() {
        blocks.clear()
    }

    private fun block(index: Long): ByteArray? =
        blocks[index] ?: try {
            fetch(index)?.also { blocks[index] = it }
        } catch (error: IOException) {
            stop.compareAndSet(null, StopReason.Failed)
            throw error
        }

    private fun fetch(index: Long): ByteArray? {
        ensureRunning()
        val start = index * blockBytes
        val end = (start + blockBytes - 1).let { end ->
            totalSize?.takeIf { it >= 0 }?.let { minOf(end, it - 1) } ?: end
        }
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (error: IllegalArgumentException) {
            throw IOException("Invalid server address: $url", error)
        }
        this.connection = connection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Range", "bytes=$start-$end")
            // Offsets count the file's own bytes, so the body must not be compressed in transit.
            connection.setRequestProperty("Accept-Encoding", "identity")
            HttpServerAuth.authorizationFor(url)?.let { connection.setRequestProperty("Authorization", it) }
            // A cancel that came before this connection was published could not close it.
            ensureRunning()
            val status = connection.responseCode
            when {
                status == HttpURLConnection.HTTP_PARTIAL -> Unit
                status == HTTP_RANGE_NOT_SATISFIABLE -> {
                    if (totalSize == null) totalSize = contentRangeTotal(connection.getHeaderField("Content-Range"))
                    return null
                }
                status == HttpURLConnection.HTTP_UNAUTHORIZED -> throw HttpAuthenticationException()
                status in 200..299 -> {
                    // The whole file is on its way; disconnecting below drops it unread.
                    RemotePaths.origin(url)?.let(serversWithoutRanges::add)
                    throw stopped(StopReason.RangesUnsupported, "The server does not support ranged reads")
                }
                else -> throw IOException("HTTP $status ${connection.responseMessage.orEmpty()}".trim())
            }
            if (totalSize == null) {
                totalSize = contentRangeTotal(connection.getHeaderField("Content-Range")) ?: UNKNOWN_SIZE
            }
            val body = ByteArray((end - start + 1).toInt())
            var filled = 0
            connection.inputStream.use { input ->
                while (filled < body.size) {
                    val read = input.read(body, filled, body.size - filled)
                    if (read < 0) break
                    filled += read
                    downloadedBytes += read
                    if (downloadedBytes > maxBytes) {
                        throw stopped(StopReason.BudgetExceeded, "Reading this file needs more than $maxBytes bytes")
                    }
                    ensureRunning()
                }
            }
            return if (filled == body.size) body else body.copyOf(filled)
        } finally {
            this.connection = null
            connection.disconnect()
        }
    }

    private fun ensureRunning() {
        stop.get()?.let { reason -> throw IOException("Reading stopped: $reason") }
    }

    private fun stopped(reason: StopReason, message: String): IOException {
        stop.compareAndSet(null, reason)
        return IOException(message)
    }

    companion object {
        const val UNKNOWN_SIZE = -1L
        const val END_OF_FILE = -1
        private const val BLOCK_BYTES = 256 * 1024
        private const val MAX_CACHED_BLOCKS = 8
        private const val CONNECT_TIMEOUT_MILLIS = 10_000
        private const val READ_TIMEOUT_MILLIS = 15_000
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416

        private val serversWithoutRanges: MutableSet<String> = ConcurrentHashMap.newKeySet()

        /** False once the server holding [url] has answered a ranged request with the whole file. */
        fun supportsRanges(url: String): Boolean =
            RemotePaths.origin(url)?.let { it !in serversWithoutRanges } ?: false

        /** File length from a `Content-Range` header such as `bytes 0-99/1234`, or null when it is not given. */
        internal fun contentRangeTotal(header: String?): Long? =
            header?.substringAfterLast('/', "")?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
    }
}
