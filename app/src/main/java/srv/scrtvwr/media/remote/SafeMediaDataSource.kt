package srv.scrtvwr.media.remote

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection

/** Streaming media with the same transport and redirect rules as directory/image requests. */
@androidx.annotation.OptIn(UnstableApi::class)
class SafeMediaDataSource : BaseDataSource(true) {
    private var connection: HttpURLConnection? = null
    private var input: InputStream? = null
    private var remaining = C.LENGTH_UNSET.toLong()
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        try {
            val headers = dataSpec.httpRequestHeaders.toMutableMap().apply {
                put("Cache-Control", "no-store")
                put("Accept-Encoding", "identity")
                if (dataSpec.position > 0 || dataSpec.length != C.LENGTH_UNSET.toLong()) {
                    val end = if (dataSpec.length == C.LENGTH_UNSET.toLong()) "" else
                        (dataSpec.position + dataSpec.length - 1).toString()
                    put("Range", "bytes=${dataSpec.position}-$end")
                }
            }
            val conn = SafeHttpConnection.open(dataSpec.uri.toString(), headers = headers,
                beforeResponse = { connection = it })
            if (conn.responseCode == 416 &&
                conn.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull() == dataSpec.position) {
                remaining = 0L
                opened = true
                transferStarted(dataSpec)
                return 0L
            }
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            val stream = conn.inputStream
            input = stream
            var skip = if (conn.responseCode == 200) dataSpec.position else 0L
            val length = conn.contentLengthLong
            remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else
                if (length >= 0) (length - skip).coerceAtLeast(0) else C.LENGTH_UNSET.toLong()
            opened = true
            transferStarted(dataSpec)
            // Some simple servers ignore Range. Still seek without buffering the whole response.
            val buffer = ByteArray(16 * 1024)
            while (skip > 0) {
                val count = stream.read(buffer, 0, minOf(skip, buffer.size.toLong()).toInt())
                if (count < 0) throw EOFException("Video ended before the requested position.")
                skip -= count
                bytesTransferred(count)
            }
            return remaining
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val count = input?.read(buffer, offset,
            if (remaining < 0) length else minOf(remaining, length.toLong()).toInt()) ?: C.RESULT_END_OF_INPUT
        if (count < 0) {
            if (remaining > 0) throw EOFException("Video download ended early.")
            return C.RESULT_END_OF_INPUT
        }
        if (remaining >= 0) remaining -= count
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = connection?.url?.toString()?.let(Uri::parse)
    override fun getResponseHeaders(): Map<String, List<String>> = connection?.headerFields
        ?.filterKeys { it != null }?.mapKeys { it.key!! }.orEmpty()

    override fun close() {
        try { input?.close() } finally {
            input = null
            connection?.disconnect()
            connection = null
            if (opened) { opened = false; transferEnded() }
        }
    }
}
