package srv.scrtvwr.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.LruCache
import com.github.junrar.Archive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext

data class PreviewResult(
    val bitmap: Bitmap? = null,
    val error: String? = null,
)

object MediaPreviewLoader {
    private const val MAX_ARCHIVE_ENTRY_BYTES = 128L * 1024L * 1024L
    private const val MAX_CACHE_KIB = 48 * 1024

    private val cache = object : LruCache<String, Bitmap>(MAX_CACHE_KIB) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.allocationByteCount / 1024).coerceAtLeast(1)
    }

    suspend fun loadImage(path: String, modified: Long, maxDimension: Int): PreviewResult =
        loadCached("image:$path:$modified:$maxDimension") {
            if (path.contains("::")) {
                loadArchiveImage(path.substringBefore("::"), path.substringAfter("::"), maxDimension)
            } else {
                decodeFile(path, maxDimension)
            }
        }

    suspend fun loadVideoFrame(path: String, modified: Long, maxDimension: Int): PreviewResult =
        loadCached("video:$path:$modified:$maxDimension") {
            runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(path)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                        retriever.getScaledFrameAtTime(
                            0L,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                            maxDimension,
                            maxDimension,
                        )
                    } else {
                        retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                            ?.scaledToFit(maxDimension)
                    }
                } finally {
                    retriever.release()
                }
            }.fold(
                onSuccess = { PreviewResult(bitmap = it) },
                onFailure = { PreviewResult(error = it.readableMessage("Unable to read video preview")) },
            )
        }

    private suspend fun loadCached(key: String, loader: suspend () -> PreviewResult): PreviewResult {
        cache.get(key)?.let { return PreviewResult(bitmap = it) }
        val result = withContext(Dispatchers.IO) {
            coroutineContext.ensureActive()
            loader().also { coroutineContext.ensureActive() }
        }
        result.bitmap?.let { cache.put(key, it) }
        return result
    }

    private fun decodeFile(path: String, maxDimension: Int): PreviewResult =
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unsupported or damaged image" }
            val options = BitmapFactory.Options().apply {
                inSampleSize = bounds.sampleSize(maxDimension)
            }
            BitmapFactory.decodeFile(path, options) ?: error("Image decoder returned no bitmap")
        }.fold(
            onSuccess = { PreviewResult(bitmap = it) },
            onFailure = { PreviewResult(error = it.readableMessage("Unable to read image")) },
        )

    private fun loadArchiveImage(archivePath: String, entryName: String, maxDimension: Int): PreviewResult =
        runCatching {
            val file = File(archivePath)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            openArchiveEntry(file, entryName) { input -> BitmapFactory.decodeStream(input, null, bounds) }
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unsupported or damaged archive image" }
            val options = BitmapFactory.Options().apply { inSampleSize = bounds.sampleSize(maxDimension) }
            openArchiveEntry(file, entryName) { input ->
                BitmapFactory.decodeStream(input, null, options)
            } ?: error("Image decoder returned no bitmap")
        }.fold(
            onSuccess = { PreviewResult(bitmap = it) },
            onFailure = { PreviewResult(error = it.readableMessage("Unable to read archive image")) },
        )

    private fun <T> openArchiveEntry(file: File, entryName: String, block: (InputStream) -> T): T {
        return when (file.extension.lowercase()) {
            "zip", "cbz" -> ZipFile(file).use { zip ->
                val entry = zip.getEntry(entryName) ?: error("Archive entry no longer exists")
                require(entry.size < 0 || entry.size <= MAX_ARCHIVE_ENTRY_BYTES) {
                    "Archive image is larger than the 128 MB safety limit"
                }
                zip.getInputStream(entry).use { block(LimitedInputStream(it, MAX_ARCHIVE_ENTRY_BYTES)) }
            }
            "rar", "cbr" -> Archive(file).use { archive ->
                val header = archive.fileHeaders.firstOrNull { it.fileName == entryName }
                    ?: error("Archive entry no longer exists")
                require(header.fullUnpackSize <= MAX_ARCHIVE_ENTRY_BYTES) {
                    "Archive image is larger than the 128 MB safety limit"
                }
                archive.getInputStream(header).use { block(LimitedInputStream(it, MAX_ARCHIVE_ENTRY_BYTES)) }
            }
            else -> error("Unsupported archive type")
        }
    }

    private fun BitmapFactory.Options.sampleSize(maxDimension: Int): Int {
        var sample = 1
        val target = maxDimension.coerceAtLeast(64)
        while (outHeight / sample > target || outWidth / sample > target) sample *= 2
        return sample
    }

    private fun Throwable.readableMessage(fallback: String): String =
        message?.takeIf(String::isNotBlank) ?: fallback

    private fun Bitmap.scaledToFit(maxDimension: Int): Bitmap {
        if (width <= maxDimension && height <= maxDimension) return this
        val scale = maxDimension.toFloat() / maxOf(width, height)
        val scaled = Bitmap.createScaledBitmap(
            this,
            (width * scale).toInt().coerceAtLeast(1),
            (height * scale).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== this) recycle()
        return scaled
    }

    private class LimitedInputStream(input: InputStream, private val limit: Long) : FilterInputStream(input) {
        private var readBytes = 0L

        override fun read(): Int = super.read().also { if (it >= 0) account(1) }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, length).also { if (it > 0) account(it.toLong()) }

        private fun account(count: Long) {
            readBytes += count
            if (readBytes > limit) throw IOException("Archive image exceeds the 128 MB safety limit")
        }
    }
}
