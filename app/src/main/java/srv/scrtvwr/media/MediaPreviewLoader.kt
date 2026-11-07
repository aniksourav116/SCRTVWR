package srv.scrtvwr.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.SystemClock
import android.util.LruCache
import com.github.junrar.Archive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import srv.scrtvwr.media.remote.HttpAuthenticationException
import srv.scrtvwr.media.remote.HttpDirectoryClient
import srv.scrtvwr.media.remote.HttpRangeReader
import srv.scrtvwr.media.remote.RemoteArchiveCache
import srv.scrtvwr.media.remote.RemoteMediaStore
import srv.scrtvwr.preferences.CacheLimits
import srv.scrtvwr.preferences.MEBIBYTE
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext

data class PreviewResult(
    val bitmap: Bitmap? = null,
    val error: String? = null,
)

object MediaPreviewLoader {
    private const val MAX_ARCHIVE_ENTRY_BYTES = 128L * 1024L * 1024L
    private const val MAX_CACHE_KIB = 48 * 1024
    private const val MAX_DISK_CACHE_BYTES = 64L * 1024L * 1024L
    @Volatile private var diskCacheLimitBytes = MAX_DISK_CACHE_BYTES
    @Volatile private var viewerCacheLimitBytes = 512L * MEBIBYTE
    private val cacheAccess = CacheAccess()
    private const val MAX_DISK_CACHE_DIMENSION = 256
    private const val DISK_WRITES_PER_TRIM = 32
    private const val HEX_DIGITS = "0123456789abcdef"
    // Servers are expected on the local network. Long videos keep a large frame index, at the end
    // for many camera files and every AVI, so the cap only stops files that are clearly broken.
    private const val REMOTE_VIDEO_MAX_BYTES = 32L * 1024L * 1024L
    private const val REMOTE_VIDEO_TIMEOUT_MILLIS = 30_000L
    private const val REMOTE_VIDEO_SETTLE_MILLIS = 200L
    // Copies of server files viewer pages show. Enough for a slide show's prefetch window to stay
    // clear of the copies of pages it already showed; see VIEWER_PREFETCH_BUDGET_BYTES.
    private const val MAX_VIEWER_STORE_BYTES = 512L * 1024L * 1024L
    private const val VIEWER_STORE_DIRECTORY = "remote_media"
    private const val VIEWER_IMAGE_COPY = "image"
    private const val VIEWER_FRAME_QUALITY = 90

    /**
     * How many bytes of copies a slide show may fetch ahead: well under half the store, since the
     * copies of pages already shown are used more recently than the farthest ones still ahead.
     */
    val VIEWER_PREFETCH_BUDGET_BYTES: Long
        get() = viewerCacheLimitBytes * 2 / 5
    private val viewerStore = RemoteMediaStore(MAX_VIEWER_STORE_BYTES)
    private val directoryThumbnailSemaphore = Semaphore(2)
    private val comicBookCoverSemaphore = Semaphore(2)
    private val remoteImageSemaphore = Semaphore(4)
    private val remoteVideoSemaphore = Semaphore(3)
    // Server loads for the page on screen, so it never queues behind thumbnails or preloads. Two,
    // so a page swiped to while the previous one still downloads does not wait for it either.
    private val onScreenRemoteSemaphore = Semaphore(2)
    private val remotePreloadPace = RemotePreloadPace()
    private val _remoteDownloadsSlow = MutableStateFlow(false)
    private val unreadableRemoteVideos = LruCache<String, Boolean>(256)
    private val videoFrameLoad = VideoFrameLoadMonitor()
    private val _videoFrameLoadWarning = MutableStateFlow(false)
    @Volatile private var videoFrameLoadWarningDismissed = false

    /** True once reading video frames from servers has been slow enough to suggest turning it off. */
    val videoFrameLoadWarning: StateFlow<Boolean> = _videoFrameLoadWarning.asStateFlow()

    /** True while recent server downloads have been slow or failing, so fewer should run at once. */
    val remoteDownloadsSlow: StateFlow<Boolean> = _remoteDownloadsSlow.asStateFlow()
    private val _forgottenPreviews = MutableStateFlow(0)

    /** Counts calls to [forget], so previews already on screen can load again. */
    val forgottenPreviews: StateFlow<Int> = _forgottenPreviews.asStateFlow()

    /** Names of the server copies viewer pages decode from; see [isViewerCopy]. */
    val viewerCopies: StateFlow<Set<String>>
        get() = viewerStore.names
    private val diskCacheScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val diskWritesSinceTrim = AtomicInteger()
    @Volatile private var diskCacheDirectory: File? = null
    @Volatile private var imageDownloadDirectory: File? = null

    private val cache = object : LruCache<String, Bitmap>(MAX_CACHE_KIB) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.allocationByteCount / 1024).coerceAtLeast(1)
    }

    /**
     * Image at [path], which may be an entry of a local or server archive. A server archive that is
     * not cached yet is downloaded first, unless [downloadArchive] is false; then the result is
     * empty, so a small thumbnail never costs a whole archive. A server image [onScreen] downloads
     * through slots of its own. A viewer-sized server image keeps its download as a copy, and one
     * with a copy, such as one a slide show prefetched, decodes from it without a request.
     */
    suspend fun loadImage(
        path: String,
        modified: Long,
        maxDimension: Int,
        downloadArchive: Boolean = true,
        onScreen: Boolean = false,
    ): PreviewResult =
        loadCached("image:$path:$modified:$maxDimension", maxDimension) {
            val entryName = MediaPaths.archiveEntryName(path)
            if (entryName != null) {
                loadArchiveImage(MediaPaths.archivePath(path), entryName, maxDimension, downloadArchive)
            } else if (MediaPaths.isRemote(path)) {
                val slots = remoteSemaphore(remoteImageSemaphore, onScreen)
                if (maxDimension > MAX_DISK_CACHE_DIMENSION) {
                    decodeViewerImage(path, maxDimension, slots)
                } else {
                    slots.withPermit { decodeRemoteImage(path, maxDimension) }
                }
            } else {
                decodeFile(path, maxDimension)
            }
        }

    /**
     * Whether [copies], a value of [viewerCopies], holds the copy a viewer page of the server file
     * at [url] decodes from, so showing it needs no download: the image itself, or for a [video],
     * its first frame at [maxDimension].
     */
    fun isViewerCopy(copies: Set<String>, url: String, video: Boolean, maxDimension: Int): Boolean =
        viewerStore.name(url, if (video) viewerFrameCopy(maxDimension) else VIEWER_IMAGE_COPY) in copies

    /**
     * Downloads the server image at [url] as a viewer copy, for a slide show about to reach it.
     * Returns the bytes downloaded, or null when this call downloaded nothing: the copy was there
     * already, or another load was fetching it. Throws what the download threw.
     */
    suspend fun prefetchImage(url: String): Long? = cacheAccess.read {
        var downloadedBytes: Long? = null
        viewerStore.file(url, VIEWER_IMAGE_COPY) { target ->
            downloadImageCopy(url, target)
            downloadedBytes = target.length()
        }
        downloadedBytes
    }

    /**
     * Reads the first frame of the server video at [url] at [maxDimension] into a viewer copy, for
     * a slide show about to reach it; [modified] is the stamp its page loads it with. Returns the
     * bytes stored, or null when nothing was read: the frame was there already, another load was
     * reading it, or the video cannot give one. Throws when storing the frame failed.
     */
    suspend fun prefetchVideoFrame(url: String, modified: Long, maxDimension: Int): Long? =
        cacheAccess.read { withContext(Dispatchers.IO) {
            val key = "video:$url:$modified:$maxDimension"
            if (cache.get(key) != null || !HttpRangeReader.supportsRanges(url) || unreadableRemoteVideos.get(key) != null) {
                return@withContext null
            }
            var storedBytes: Long? = null
            try {
                viewerStore.file(url, viewerFrameCopy(maxDimension)) { target ->
                    val frame = decodeRemoteVideoFrame(url, key, maxDimension).bitmap ?: throw NoFrameException()
                    try {
                        val written = target.outputStream().buffered().use { output ->
                            frame.compress(Bitmap.CompressFormat.JPEG, VIEWER_FRAME_QUALITY, output)
                        }
                        if (!written) throw IOException("Unable to store the video frame")
                    } finally {
                        // Never shown: the page decodes the copy when it comes up.
                        frame.recycle()
                    }
                    storedBytes = target.length()
                }
            } catch (_: NoFrameException) {
                return@withContext null
            }
            storedBytes
        } }

    private fun viewerFrameCopy(maxDimension: Int): String = "frame-$maxDimension"

    /** Thrown inside a frame prefetch when the video gave no frame, which is no failure to retry. */
    private class NoFrameException : Exception()

    suspend fun loadVideoFrame(path: String, modified: Long, maxDimension: Int, onScreen: Boolean = false): PreviewResult {
        if (MediaPaths.isRemote(path)) return loadRemoteVideoFrame(path, modified, maxDimension, onScreen)
        return loadCached("video:$path:$modified:$maxDimension", maxDimension) {
            runCatching { retrieveFrame(maxDimension) { setDataSource(path) } }.fold(
                onSuccess = { PreviewResult(bitmap = it) },
                onFailure = { PreviewResult(error = it.readableMessage("Unable to read video preview")) },
            )
        }
    }

    /**
     * First frame of a server video, read with Range requests so it costs a few blocks rather than
     * the whole file. Servers without Range support (python -m http.server) keep the placeholder
     * icon. Viewer-sized frames cost the same reads as thumbnails but stay in memory only.
     */
    private suspend fun loadRemoteVideoFrame(
        url: String,
        modified: Long,
        maxDimension: Int,
        onScreen: Boolean,
    ): PreviewResult {
        val key = "video:$url:$modified:$maxDimension"
        return loadCached(key, maxDimension) {
            if (!HttpRangeReader.supportsRanges(url) || unreadableRemoteVideos.get(key) != null) {
                return@loadCached PreviewResult()
            }
            // A frame a slide show prefetched, or is prefetching now, decodes from its copy.
            if (maxDimension > MAX_DISK_CACHE_DIMENSION) {
                storedVideoFrame(url, maxDimension)?.let { return@loadCached it }
                if (unreadableRemoteVideos.get(key) != null) return@loadCached PreviewResult()
            }
            // Tiles that only flash past during a fling are cancelled here, before any request.
            delay(REMOTE_VIDEO_SETTLE_MILLIS)
            remoteSemaphore(remoteVideoSemaphore, onScreen).withPermit { decodeRemoteVideoFrame(url, key, maxDimension) }
        }
    }

    private suspend fun storedVideoFrame(url: String, maxDimension: Int): PreviewResult? {
        val variant = viewerFrameCopy(maxDimension)
        val copy = viewerStore.existing(url, variant) ?: return null
        coroutineContext.ensureActive()
        val result = decodeFile(copy.path, maxDimension)
        if (result.bitmap != null) return result
        // A damaged copy is dropped, so the frame is read from the server instead.
        viewerStore.remove(url, variant)
        return null
    }

    private fun remoteSemaphore(shared: Semaphore, onScreen: Boolean): Semaphore =
        if (onScreen) onScreenRemoteSemaphore else shared

    private fun noteRemoteDownload(elapsedMillis: Long, failed: Boolean) {
        _remoteDownloadsSlow.value = remotePreloadPace.record(elapsedMillis, failed)
    }

    private suspend fun decodeRemoteVideoFrame(url: String, key: String, maxDimension: Int): PreviewResult {
        val reader = HttpRangeReader(url, REMOTE_VIDEO_MAX_BYTES)
        val startedAt = SystemClock.elapsedRealtime()
        var finished = false
        var exhausted = false
        var timedOut = false
        try {
            val result = withTimeoutOrNull(REMOTE_VIDEO_TIMEOUT_MILLIS) {
                coroutineScope {
                    // The retriever blocks in its reads and ignores cancellation; cancelling the reader fails them.
                    val cancelReads = launch(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            awaitCancellation()
                        } finally {
                            reader.cancel()
                        }
                    }
                    try {
                        val frame = runCatching {
                            retrieveFrame(maxDimension) { setDataSource(RemoteVideoSource(reader)) }
                        }.getOrNull()
                        // Read before cancelReads stops the reader on the way out.
                        frame to reader.stopReason
                    } finally {
                        cancelReads.cancel()
                    }
                }
            }
            coroutineContext.ensureActive()
            finished = true
            timedOut = result == null
            val (bitmap, stopReason) = result ?: (null to HttpRangeReader.StopReason.Cancelled)
            exhausted = result == null || stopReason == HttpRangeReader.StopReason.BudgetExceeded
            // A file that fails by itself, rather than through the network, is not tried again this run.
            if (bitmap == null && (stopReason == null || stopReason == HttpRangeReader.StopReason.BudgetExceeded)) {
                unreadableRemoteVideos.put(key, true)
            }
            return PreviewResult(bitmap = bitmap)
        } finally {
            val elapsedMillis = SystemClock.elapsedRealtime() - startedAt
            noteVideoFrameLoad(elapsedMillis, finished, exhausted)
            // A file too large for its byte budget says nothing about the server's pace.
            if (finished) noteRemoteDownload(elapsedMillis, failed = timedOut)
        }
    }

    private fun noteVideoFrameLoad(elapsedMillis: Long, finished: Boolean, exhausted: Boolean) {
        // A tile scrolled away early says nothing about how heavy the reads are.
        if (!finished && elapsedMillis < videoFrameLoad.slowMillis) return
        if (videoFrameLoad.record(elapsedMillis, exhausted) && !videoFrameLoadWarningDismissed) {
            _videoFrameLoadWarning.value = true
        }
    }

    /** Hides the slow-loading warning until [resetVideoFrameLoadWarning], such as once the user acted on it. */
    fun dismissVideoFrameLoadWarning() {
        videoFrameLoadWarningDismissed = true
        _videoFrameLoadWarning.value = false
    }

    /** Forgets past slow reads, for when the user turns server video frames back on. */
    fun resetVideoFrameLoadWarning() {
        videoFrameLoad.reset()
        videoFrameLoadWarningDismissed = false
        _videoFrameLoadWarning.value = false
    }

    private fun retrieveFrame(maxDimension: Int, open: MediaMetadataRetriever.() -> Unit): Bitmap? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.open()
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
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
    }

    suspend fun loadDirectoryThumbnail(path: String, modified: Long, maxDimension: Int): PreviewResult =
        loadCached("directory:$path:$modified:$maxDimension", maxDimension) {
            directoryThumbnailSemaphore.withPermit {
                if (MediaPaths.isRemote(path)) {
                    val image = runCatching { HttpDirectoryClient.list(path) }.getOrNull()?.firstOrNull { entry ->
                        !entry.isDirectory && MediaTypes.isImage(entry.name)
                    } ?: return@withPermit PreviewResult()
                    return@withPermit remoteImageSemaphore.withPermit { decodeRemoteImage(image.url, maxDimension) }
                }
                val image = File(path).listFiles()?.firstOrNull { file ->
                    coroutineContext.ensureActive()
                    file.isFile && MediaTypes.isImage(file.name)
                } ?: return@withPermit PreviewResult()
                decodeFile(image.path, maxDimension)
            }
        }

    /**
     * First page of the comic book archive at [path], the one the viewer opens on. A server archive
     * is only read once the viewer has downloaded it, so a thumbnail never costs a whole archive.
     */
    suspend fun loadComicBookCover(path: String, modified: Long, maxDimension: Int): PreviewResult =
        loadCached("cover:$path:$modified:$maxDimension", maxDimension) {
            comicBookCoverSemaphore.withPermit {
                val pages = try {
                    withArchiveFile(path, download = false) { MediaCatalog.listArchive(it) }
                        ?: return@withPermit PreviewResult()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    return@withPermit PreviewResult(error = error.readableMessage("Unable to read archive"))
                }
                val entryName = pages.firstOrNull()?.path?.let(MediaPaths::archiveEntryName)
                    ?: return@withPermit PreviewResult()
                loadArchiveImage(path, entryName, maxDimension, downloadArchive = false)
            }
        }

    private suspend fun loadCached(
        key: String,
        maxDimension: Int,
        loader: suspend () -> PreviewResult,
    ): PreviewResult = cacheAccess.read {
        cache.get(key)?.let { return@read PreviewResult(bitmap = it) }
        withContext(Dispatchers.IO) {
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
            diskCacheScope.launch { cacheAccess.read { trimDiskCache() } }
        }
    }

    /**
     * Drops every cached preview of [paths], in memory and on disk. Server previews are cached
     * without a modification time, so this is how a file replaced under the same name, or a
     * folder whose first image changed, gets a new thumbnail. Reads the disk, so call it off the
     * main thread.
     */
    fun forget(paths: Collection<String>) {
        if (paths.isEmpty()) return
        val forgotten = paths.toHashSet()
        cache.snapshot().keys.filter { it.previewPath() in forgotten }.forEach(cache::remove)
        unreadableRemoteVideos.snapshot().keys.filter { it.previewPath() in forgotten }
            .forEach(unreadableRemoteVideos::remove)
        val prefixes = forgotten.mapTo(HashSet(), ::diskCachePrefix)
        diskCacheDirectory?.listFiles()
            ?.filter { file -> file.name.substringBefore('-', missingDelimiterValue = "") in prefixes }
            ?.forEach(File::delete)
        viewerStore.forget(forgotten)
        _forgottenPreviews.update { it + 1 }
    }

    /** The path in a cache key, which reads "kind:path:modified:maxDimension". */
    private fun String.previewPath(): String =
        substringAfter(':').substringBeforeLast(':').substringBeforeLast(':')

    // Named after the previewed path first, so [forget] finds every size of a preview.
    private fun diskCacheFile(key: String): File? {
        val directory = diskCacheDirectory ?: return null
        return File(directory, "${diskCachePrefix(key.previewPath())}-${sha256(key)}.webp")
    }

    private fun diskCachePrefix(path: String): String = sha256(path).take(16)

    // Hex by table: String.format per byte made forgetting a 30,000-file folder take seconds.
    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        val hex = CharArray(digest.size * 2)
        digest.forEachIndexed { index, byte ->
            hex[index * 2] = HEX_DIGITS[(byte.toInt() shr 4) and 0xF]
            hex[index * 2 + 1] = HEX_DIGITS[byte.toInt() and 0xF]
        }
        return String(hex)
    }

    private fun trimDiskCache() {
        val files = diskCacheDirectory?.listFiles()?.filter(File::isFile)
            ?.sortedByDescending(File::lastModified)
            ?: return
        var retainedBytes = 0L
        files.forEach { file ->
            retainedBytes += file.length()
            if (retainedBytes > diskCacheLimitBytes) file.delete()
        }
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

    /** A small preview of the server image at [url], from its viewer copy when there is one. */
    private suspend fun decodeRemoteImage(url: String, maxDimension: Int): PreviewResult {
        // Only a glance: a grid of thumbnails would otherwise keep every copy it passes over.
        viewerStore.cachedFile(url, VIEWER_IMAGE_COPY, touch = false)?.let { copy ->
            val result = decodeFile(copy.path, maxDimension)
            if (result.bitmap != null) return result
            viewerStore.remove(url, VIEWER_IMAGE_COPY)
        }
        var temporary: File? = null
        return try {
            val directory = imageDownloadDirectory ?: throw IOException("Image cache is not ready")
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Unable to create the image cache")
            val download = File.createTempFile("image-", ".part", directory)
            temporary = download
            // Avoid holding the entire compressed file in memory before decoding it.
            downloadRemote(url, download)
            coroutineContext.ensureActive()
            decodeFile(download.path, maxDimension)
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            PreviewResult(error = error.readableMessage("Unable to download image"))
        } finally {
            temporary?.delete()
        }
    }

    /**
     * A viewer-sized picture of the server image at [url], decoded from its copy, which is
     * downloaded first through [slots] when there is none and nobody else is fetching it.
     */
    private suspend fun decodeViewerImage(url: String, maxDimension: Int, slots: Semaphore): PreviewResult {
        // A copy that no longer decodes, such as one cut short on disk, is dropped and downloaded once more.
        repeat(2) { attempt ->
            val copy = try {
                viewerStore.file(url, VIEWER_IMAGE_COPY) { target -> slots.withPermit { downloadImageCopy(url, target) } }
            } catch (error: CancellationException) {
                throw error
            } catch (error: IOException) {
                return PreviewResult(error = error.readableMessage("Unable to download image"))
            }
            coroutineContext.ensureActive()
            val result = decodeFile(copy.path, maxDimension)
            if (result.bitmap == null) viewerStore.remove(url, VIEWER_IMAGE_COPY)
            if (result.bitmap != null || attempt == 1) return result
        }
        error("Unreachable")
    }

    /**
     * Downloads the server image at [url] into [target], to keep as its viewer copy. What does not
     * read as an image, such as an error page a server sent with a success code, is refused, so
     * it is never kept.
     */
    private suspend fun downloadImageCopy(url: String, target: File) {
        downloadRemote(url, target)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(target.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Unsupported or damaged image")
    }

    /** Downloads [url] into [target], noting how long it took for the server's pace. */
    private suspend fun downloadRemote(url: String, target: File) {
        val startedAt = SystemClock.elapsedRealtime()
        try {
            HttpDirectoryClient.download(url, target)
        } catch (error: IOException) {
            // A wrong password fails fast and says nothing about the server's pace.
            if (error !is HttpAuthenticationException) {
                noteRemoteDownload(SystemClock.elapsedRealtime() - startedAt, failed = true)
            }
            throw error
        }
        noteRemoteDownload(SystemClock.elapsedRealtime() - startedAt, failed = false)
    }

    private suspend fun loadArchiveImage(
        archivePath: String,
        entryName: String,
        maxDimension: Int,
        downloadArchive: Boolean,
    ): PreviewResult {
        return try {
            withArchiveFile(archivePath, downloadArchive) { file ->
                runCatching {
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
            } ?: PreviewResult()
        } catch (error: IOException) {
            PreviewResult(error = error.readableMessage("Unable to download archive"))
        }
    }

    /**
     * Readable copy of the archive at [archivePath]: the file itself when local, else the cached
     * download, fetched now only when [download] is true.
     */
    private suspend fun <T> withArchiveFile(
        archivePath: String, download: Boolean, block: suspend (File) -> T,
    ): T? = if (MediaPaths.isRemote(archivePath)) {
        RemoteArchiveCache.withFile(archivePath, download = download, block = block)
    } else block(File(archivePath))

    /** Opens [entryName] in [file], a local archive or the cached copy of a server archive. */
    private fun <T> openArchiveEntry(file: File, entryName: String, block: (InputStream) -> T): T {
        return when (MediaTypes.archiveFormat(file.path)) {
            MediaTypes.ArchiveFormat.Zip -> ZipFile(file).use { zip ->
                val entry = zip.getEntry(entryName) ?: error("Archive entry no longer exists")
                require(entry.size < 0 || entry.size <= MAX_ARCHIVE_ENTRY_BYTES) {
                    "Archive image is larger than the 128 MB safety limit"
                }
                zip.getInputStream(entry).use { block(LimitedInputStream(it, MAX_ARCHIVE_ENTRY_BYTES)) }
            }
            MediaTypes.ArchiveFormat.Rar -> Archive(file).use { archive ->
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

    /** Server video for [MediaMetadataRetriever], read through [reader]. */
    private class RemoteVideoSource(private val reader: HttpRangeReader) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
            reader.readAt(position, buffer, offset, size)

        override fun getSize(): Long = reader.size()

        override fun close() = reader.close()
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

    suspend fun setCacheLimits(limits: CacheLimits) = cacheAccess.exclusive {
        diskCacheLimitBytes = limits.thumbnailsMiB * MEBIBYTE
        viewerCacheLimitBytes = limits.viewerMiB * MEBIBYTE
        viewerStore.setMaxBytes(viewerCacheLimitBytes)
        trimDiskCache()
    }

    suspend fun clearCaches(): Boolean = cacheAccess.exclusive {
        cache.evictAll()
        unreadableRemoteVideos.evictAll()
        val thumbnailsCleared = diskCacheDirectory?.listFiles()?.filter(File::isFile)
            ?.map { it.delete() || !it.exists() }?.all { it } ?: true
        val copiesCleared = viewerStore.clear()
        _forgottenPreviews.update { it + 1 }
        thumbnailsCleared && copiesCleared
    }

    fun initialize(cacheDirectory: File, limits: CacheLimits = CacheLimits()) {
        diskCacheLimitBytes = limits.thumbnailsMiB * MEBIBYTE
        viewerCacheLimitBytes = limits.viewerMiB * MEBIBYTE
        viewerStore.setMaxBytes(viewerCacheLimitBytes)
        // Each process owns its temporary image files. Remove leftovers before any loads start.
        val downloads = File(cacheDirectory, "remote_images")
        downloads.listFiles()?.filter { it.name.endsWith(".part") }?.forEach(File::delete)
        imageDownloadDirectory = downloads
        // Viewer copies last as long as the process, as pictures in memory do, so a server file
        // replaced under the same name is downloaded again after a restart. The last run's are
        // moved aside at once, a single rename, and deleted in the background.
        val copies = File(cacheDirectory, VIEWER_STORE_DIRECTORY)
        if (copies.exists()) copies.renameTo(File(cacheDirectory, "$VIEWER_STORE_DIRECTORY-old-${System.currentTimeMillis()}"))
        // Copies are written in the downloads folder first, so leftovers are removed above too.
        viewerStore.initialize(copies, partialDirectory = downloads)
        val directory = File(cacheDirectory, "media_thumbnails")
        diskCacheDirectory = directory
        diskCacheScope.launch {
            directory.mkdirs()
            cacheAccess.read { trimDiskCache() }
            cacheDirectory.listFiles()
                ?.filter { it.name.startsWith("$VIEWER_STORE_DIRECTORY-old-") }
                ?.forEach(File::deleteRecursively)
        }
    }
}
