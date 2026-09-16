package srv.scrtvwr.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.LruCache
import com.github.junrar.Archive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

data class PreviewResult(
    val bitmap: Bitmap? = null,
    val error: String? = null,
)

object MediaPreviewLoader {
    private const val MAX_ARCHIVE_ENTRY_BYTES = 128L * 1024L * 1024L
    private const val MAX_CACHE_KIB = 48 * 1024
    private const val MAX_DISK_CACHE_BYTES = 64L * 1024L * 1024L
    private const val MAX_DISK_CACHE_DIMENSION = 256
    private const val DISK_WRITES_PER_TRIM = 32
    private val directoryThumbnailSemaphore = Semaphore(2)
    private val diskCacheScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val diskWritesSinceTrim = AtomicInteger()
    @Volatile private var diskCacheDirectory: File? = null

    private val cache = object : LruCache<String, Bitmap>(MAX_CACHE_KIB) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.allocationByteCount / 1024).coerceAtLeast(1)
    }

    suspend fun loadImage(path: String, modified: Long, maxDimension: Int): PreviewResult =
        loadCached("image:$path:$modified:$maxDimension", maxDimension) { decodeImage(path, maxDimension) }

    /**
     * A square crop around one face, for a character's cover. [face] is the box to build it around, in
     * fractions of the upright image, which is how tagging records where it found somebody.
     *
     * The picture is decoded far larger than the crop is drawn, because a face only fills a fraction
     * of the frame - but only the crop is cached, so a list of covers costs thumbnails rather than
     * photographs.
     */
    suspend fun loadFaceCrop(path: String, modified: Long, face: RectF, maxDimension: Int): PreviewResult =
        loadCached("face:$path:$modified:${face.toShortString()}:$maxDimension", maxDimension) {
            // How much larger than the crop the picture has to be decoded is exactly how small a
            // part of it the face is, up to a ceiling on what is worth holding in memory at once.
            val coverage = minOf(face.width(), face.height()).coerceAtLeast(MIN_FACE_COVERAGE)
            val sourceDimension = (maxDimension / coverage).roundToInt().coerceAtMost(MAX_FACE_SOURCE_DIMENSION)
            val decoded = decodeImage(path, sourceDimension)
            val source = decoded.bitmap ?: return@loadCached decoded
            val upright = ImageOrientation.turnedUpright(source, ImageOrientation.rotationDegrees(path))
            val cropped = runCatching { upright.faceCrop(face, maxDimension) }.getOrNull()
            // Nothing else can be holding the decode: it went nowhere near the caches, which only
            // ever see the crop. The crop itself can be one of them, though, when there was nothing
            // to turn and nothing to cut away.
            if (upright !== source && upright !== cropped) upright.recycle()
            if (source !== cropped) source.recycle()
            cropped?.let { PreviewResult(bitmap = it) } ?: PreviewResult(error = "Unable to crop this image")
        }

    suspend fun loadVideoFrame(path: String, modified: Long, maxDimension: Int): PreviewResult =
        loadCached("video:$path:$modified:$maxDimension", maxDimension) {
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

    suspend fun loadDirectoryThumbnail(path: String, modified: Long, maxDimension: Int): PreviewResult =
        loadCached("directory:$path:$modified:$maxDimension", maxDimension) {
            directoryThumbnailSemaphore.withPermit {
                val image = File(path).listFiles()?.firstOrNull { file ->
                    coroutineContext.ensureActive()
                    file.isFile && file.extension.lowercase() in IMAGE_EXTENSIONS
                } ?: return@withPermit PreviewResult()
                decodeFile(image.path, maxDimension)
            }
        }

    /**
     * Decodes an image for analysis rather than display: it neither reads nor writes the preview
     * caches, so tagging a whole device cannot evict the thumbnails the grid is showing.
     */
    suspend fun decodeForAnalysis(path: String, maxDimension: Int): PreviewResult =
        withContext(Dispatchers.IO) {
            coroutineContext.ensureActive()
            decodeImage(path, maxDimension)
        }

    private suspend fun loadCached(
        key: String,
        maxDimension: Int,
        loader: suspend () -> PreviewResult,
    ): PreviewResult {
        cache.get(key)?.let { return PreviewResult(bitmap = it) }
        return withContext(Dispatchers.IO) {
            coroutineContext.ensureActive()
            cache.get(key)?.let { return@withContext PreviewResult(bitmap = it) }
            if (maxDimension <= MAX_DISK_CACHE_DIMENSION) {
                readDiskCache(key)?.let { bitmap ->
                    cache.put(key, bitmap)
                    return@withContext PreviewResult(bitmap = bitmap)
                }
            }
            loader().also { result ->
                coroutineContext.ensureActive()
                result.bitmap?.let { bitmap ->
                    cache.put(key, bitmap)
                    if (maxDimension <= MAX_DISK_CACHE_DIMENSION) writeDiskCache(key, bitmap)
                }
            }
        }
    }

    private fun readDiskCache(key: String): Bitmap? {
        val file = diskCacheFile(key) ?: return null
        if (!file.isFile) return null
        return BitmapFactory.decodeFile(file.path)?.also {
            file.setLastModified(System.currentTimeMillis())
        } ?: run {
            file.delete()
            null
        }
    }

    private fun writeDiskCache(key: String, bitmap: Bitmap) {
        val target = diskCacheFile(key) ?: return
        if (target.isFile) {
            target.setLastModified(System.currentTimeMillis())
            return
        }
        val directory = target.parentFile ?: return
        runCatching {
            directory.mkdirs()
            val temporary = File.createTempFile("thumbnail-", ".tmp", directory)
            try {
                temporary.outputStream().buffered().use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.WEBP, 80, output))
                }
                if (!temporary.renameTo(target) && !target.isFile) error("Unable to store thumbnail")
            } finally {
                temporary.delete()
            }
        }
        if (diskWritesSinceTrim.incrementAndGet() >= DISK_WRITES_PER_TRIM) {
            diskWritesSinceTrim.set(0)
            diskCacheScope.launch { trimDiskCache() }
        }
    }

    private fun diskCacheFile(key: String): File? {
        val directory = diskCacheDirectory ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        val name = digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
        return File(directory, "$name.webp")
    }

    private fun trimDiskCache() {
        val files = diskCacheDirectory?.listFiles()?.filter(File::isFile)
            ?.sortedByDescending(File::lastModified)
            ?: return
        var retainedBytes = 0L
        files.forEach { file ->
            retainedBytes += file.length()
            if (retainedBytes > MAX_DISK_CACHE_BYTES) file.delete()
        }
    }

    private fun decodeImage(path: String, maxDimension: Int): PreviewResult =
        if (path.contains("::")) {
            loadArchiveImage(path.substringBefore("::"), path.substringAfter("::"), maxDimension)
        } else {
            decodeFile(path, maxDimension)
        }

    /**
     * A square around the face with room for the head, kept inside the image and never enlarged past
     * what was decoded. The padding is what makes it read as a portrait: the detected box alone stops
     * at the eyebrows and the chin, which looks like a fragment of somebody rather than a face.
     */
    private fun Bitmap.faceCrop(face: RectF, maxDimension: Int): Bitmap {
        val widest = maxOf(face.width() * width, face.height() * height)
        val side = (widest * FACE_CROP_SCALE).roundToInt().coerceIn(1, minOf(width, height))
        val left = (face.centerX() * width - side / 2f).roundToInt().coerceIn(0, width - side)
        val top = (face.centerY() * height - side / 2f).roundToInt().coerceIn(0, height - side)
        val scale = maxDimension.toFloat() / side
        val matrix = Matrix().apply { if (scale < 1f) postScale(scale, scale) }
        return Bitmap.createBitmap(this, left, top, side, side, matrix, true)
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
        return when (MediaCatalog.archiveFormat(file.path)) {
            MediaCatalog.ArchiveFormat.Zip -> ZipFile(file).use { zip ->
                val entry = zip.getEntry(entryName) ?: error("Archive entry no longer exists")
                require(entry.size < 0 || entry.size <= MAX_ARCHIVE_ENTRY_BYTES) {
                    "Archive image is larger than the 128 MB safety limit"
                }
                zip.getInputStream(entry).use { block(LimitedInputStream(it, MAX_ARCHIVE_ENTRY_BYTES)) }
            }
            MediaCatalog.ArchiveFormat.Rar -> Archive(file).use { archive ->
                val header = archive.fileHeaders.firstOrNull { it.fileName == entryName }
                    ?: error("Archive entry no longer exists")
                require(header.fullUnpackSize <= MAX_ARCHIVE_ENTRY_BYTES) {
                    "Archive image is larger than the 128 MB safety limit"
                }
                archive.getInputStream(header).use { block(LimitedInputStream(it, MAX_ARCHIVE_ENTRY_BYTES)) }
            }
            null -> error("Unsupported archive type")
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

    fun initialize(cacheDirectory: File) {
        val directory = File(cacheDirectory, "media_thumbnails")
        diskCacheDirectory = directory
        diskCacheScope.launch {
            directory.mkdirs()
            trimDiskCache()
        }
    }

    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")

    /** How much wider than the face itself the crop is taken, to leave room for hair and shoulders. */
    private const val FACE_CROP_SCALE = 1.7f

    /**
     * A floor on how small a face is taken to be when working out how large to decode. Without it a
     * face lost in a crowd would ask for a decode of the whole picture at poster size.
     */
    private const val MIN_FACE_COVERAGE = 0.05f
    private const val MAX_FACE_SOURCE_DIMENSION = 1536
}
