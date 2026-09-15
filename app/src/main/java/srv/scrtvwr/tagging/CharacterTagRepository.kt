package srv.scrtvwr.tagging

import android.content.Context
import android.media.ExifInterface
import android.os.Environment
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import srv.scrtvwr.media.MediaPreviewLoader
import srv.scrtvwr.preferences.ScrtvwrPreferences
import timber.log.Timber
import java.io.File

/**
 * Runs ML Kit face detection over images and keeps the resulting character tags.
 *
 * Two ways in: [tagDirectory] tags what the user is currently browsing, and [startStorageScan] walks
 * every image in shared storage once the user has agreed to it. Both funnel through [tag], which skips
 * images that are already tagged and unchanged, so the two can overlap without repeating work.
 */
object CharacterTagRepository {
    private val registry = CharacterRegistry()
    private val mutex = Mutex()

    /** Face detection is memory hungry; two images in flight keeps the rest of the app responsive. */
    private val detectionSemaphore = Semaphore(2)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val saveRequests = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val ready = CompletableDeferred<Unit>()
    private val tagger by lazy { FaceTagger() }

    private var store: CharacterTagStore? = null
    private var preferences: ScrtvwrPreferences? = null
    private var scanJob: Job? = null
    private var lastPublishMillis = 0L

    private val _characters = MutableStateFlow<List<CharacterProfile>>(emptyList())
    val characters: StateFlow<List<CharacterProfile>> = _characters.asStateFlow()

    /** Bumped whenever tags change, so screens can re-read the tags for whatever they are showing. */
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    private val _scanProgress = MutableStateFlow(TagScanProgress())
    val scanProgress: StateFlow<TagScanProgress> = _scanProgress.asStateFlow()

    /** True once tagging has found a face this session - the cue to offer the whole-storage pass. */
    private val _facesFound = MutableStateFlow(false)
    val facesFound: StateFlow<Boolean> = _facesFound.asStateFlow()

    private val _taggedImageCount = MutableStateFlow(0)
    val taggedImageCount: StateFlow<Int> = _taggedImageCount.asStateFlow()

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** Paused work parks where it is instead of being thrown away, so resuming picks up mid-scan. */
    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    fun initialize(context: Context) {
        if (store != null) return
        val applicationContext = context.applicationContext
        val scrtvwrPreferences = ScrtvwrPreferences(applicationContext)
        preferences = scrtvwrPreferences
        _enabled.value = scrtvwrPreferences.getFaceTaggingEnabled()
        _paused.value = scrtvwrPreferences.getTaggingPaused()
        store = CharacterTagStore(File(applicationContext.filesDir, TAG_DIRECTORY_NAME))
        scope.launch {
            val snapshot = withContext(Dispatchers.IO) { store?.read() ?: CharacterTagSnapshot() }
            mutex.withLock { registry.restore(snapshot) }
            ready.complete(Unit)
            publish(force = true)
        }
        scope.launch {
            saveRequests.collectLatest {
                delay(SAVE_DEBOUNCE_MILLIS)
                persist()
            }
        }
    }

    fun isEnabled(): Boolean = _enabled.value

    fun setEnabled(enabled: Boolean) {
        preferences?.saveFaceTaggingEnabled(enabled)
        _enabled.value = enabled
        if (!enabled) cancelStorageScan()
    }

    fun isPaused(): Boolean = _paused.value

    /** Parks every tagging job. The storage scan keeps its place and its progress on screen. */
    fun setPaused(paused: Boolean) {
        preferences?.saveTaggingPaused(paused)
        _paused.value = paused
    }

    fun taggingState(): TaggingState = when {
        !_enabled.value -> TaggingState.Off
        _paused.value -> TaggingState.Paused
        else -> TaggingState.Running
    }

    fun storageScanConsent(): StorageScanConsent =
        preferences?.getStorageScanConsent() ?: StorageScanConsent.Unknown

    fun setStorageScanConsent(consent: StorageScanConsent) {
        preferences?.saveStorageScanConsent(consent)
    }

    fun lastStorageScanMillis(): Long = preferences?.getLastStorageScanMillis() ?: 0L

    suspend fun tagsFor(paths: Collection<String>): Map<String, ImageTags> {
        if (store == null || paths.isEmpty()) return emptyMap()
        ready.await()
        return mutex.withLock { registry.tagsFor(paths) }
    }

    /** Every image a character appears in, for their page in the People tab. */
    suspend fun imagesFor(characterId: String): List<ImageTags> {
        if (store == null) return emptyList()
        ready.await()
        return mutex.withLock { registry.imagesFor(characterId) }
    }

    /**
     * Tags one image unless it is already tagged at this [modifiedMillis]. Returns null when tagging is
     * switched off, the image cannot be decoded, or detection failed.
     */
    suspend fun tag(path: String, modifiedMillis: Long): ImageTags? {
        if (store == null || !isEnabled()) return null
        ready.await()
        // Parking here rather than returning keeps a paused scan exactly where it stopped.
        if (_paused.value) _paused.first { !it }
        mutex.withLock { registry.tagsFor(path) }
            ?.takeIf { it.modifiedMillis == modifiedMillis }
            ?.let { return it }

        val detection = detectionSemaphore.withPermit { detect(path) } ?: return null
        val tags = mutex.withLock {
            registry.record(
                path = path,
                modifiedMillis = modifiedMillis,
                faceCount = detection.faceCount,
                signatures = detection.signatures,
                taggedAtMillis = System.currentTimeMillis(),
            )
        }
        if (tags.hasFaces) _facesFound.value = true
        publish(force = tags.hasFaces)
        saveRequests.tryEmit(Unit)
        return tags
    }

    /** Tags the images the user is currently browsing. Cancel the returned job when they move on. */
    fun tagDirectory(images: List<Pair<String, Long>>): Job? {
        if (store == null || !isEnabled() || images.isEmpty()) return null
        return scope.launch {
            images.forEach { (path, modifiedMillis) ->
                currentCoroutineContext().ensureActive()
                tag(path, modifiedMillis)
            }
            publish(force = true)
        }
    }

    /** Walks every image in shared storage. Only start this once the user has agreed to it. */
    fun startStorageScan(): Boolean {
        if (store == null || !isEnabled() || scanJob?.isActive == true) return false
        scanJob = scope.launch { runStorageScan() }
        return true
    }

    fun cancelStorageScan() {
        scanJob?.cancel()
    }

    suspend fun rename(characterId: String, label: String?) {
        mutex.withLock { registry.rename(characterId, label) }
        publish(force = true)
        saveRequests.tryEmit(Unit)
    }

    suspend fun setNote(characterId: String, note: String?) {
        mutex.withLock { registry.setNote(characterId, note) }
        publish(force = true)
        saveRequests.tryEmit(Unit)
    }

    suspend fun setCover(characterId: String, path: String, modifiedMillis: Long) {
        mutex.withLock { registry.setCover(characterId, path, modifiedMillis) }
        publish(force = true)
        saveRequests.tryEmit(Unit)
    }

    suspend fun removeCharacter(characterId: String) {
        mutex.withLock { registry.remove(characterId) }
        publish(force = true)
        saveRequests.tryEmit(Unit)
    }

    suspend fun clearAll() {
        cancelStorageScan()
        mutex.withLock { registry.clear() }
        _facesFound.value = false
        withContext(NonCancellable + Dispatchers.IO) { store?.clear() }
        publish(force = true)
    }

    private suspend fun runStorageScan() {
        _scanProgress.value = TagScanProgress(isRunning = true)
        try {
            val images = withContext(Dispatchers.IO) { collectStorageImages() }
            _scanProgress.update { it.copy(total = images.size) }
            images.forEachIndexed { index, image ->
                currentCoroutineContext().ensureActive()
                _scanProgress.update { it.copy(processed = index, currentName = image.name) }
                tag(image.absolutePath, image.lastModified())
            }
            preferences?.saveLastStorageScanMillis(System.currentTimeMillis())
            _scanProgress.update { it.copy(processed = it.total, currentName = null) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            Timber.w(error, "Storage tag scan failed")
        } finally {
            _scanProgress.update { it.copy(isRunning = false, currentName = null) }
            withContext(NonCancellable) {
                publish(force = true)
                persist()
            }
        }
    }

    private fun collectStorageImages(): List<File> {
        val root = Environment.getExternalStorageDirectory() ?: return emptyList()
        val images = mutableListOf<File>()
        root.walkTopDown()
            .onEnter { directory ->
                !directory.name.startsWith(".") && directory.name !in SKIPPED_DIRECTORY_NAMES
            }
            .forEach { file ->
                if (images.size >= MAX_SCAN_IMAGES) return images
                if (file.isFile && file.extension.lowercase() in IMAGE_EXTENSIONS) images += file
            }
        return images
    }

    private suspend fun detect(path: String): FaceDetectionResult? {
        val bitmap = MediaPreviewLoader.decodeForAnalysis(path, ANALYSIS_DIMENSION).bitmap ?: return null
        return try {
            tagger.detect(bitmap, rotationDegrees(path))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            Timber.w(error, "Face detection failed for %s", path)
            null
        } finally {
            // decodeForAnalysis bypasses the preview caches, so nothing else can be holding this bitmap.
            bitmap.recycle()
        }
    }

    /** BitmapFactory ignores EXIF, so a portrait photo arrives sideways unless the rotation is passed on. */
    private fun rotationDegrees(path: String): Int {
        if (path.contains("::")) return 0
        return runCatching {
            val orientation = ExifInterface(path)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        }.getOrDefault(0)
    }

    private suspend fun publish(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastPublishMillis < PUBLISH_INTERVAL_MILLIS) return
        lastPublishMillis = now
        val snapshot = mutex.withLock { registry.profiles() to registry.taggedImageCount() }
        _characters.value = snapshot.first
        _taggedImageCount.value = snapshot.second
        _revision.update { it + 1 }
    }

    private suspend fun persist() {
        val store = store ?: return
        val snapshot = mutex.withLock { registry.snapshot() }
        withContext(NonCancellable + Dispatchers.IO) { store.write(snapshot) }
    }

    private const val TAG_DIRECTORY_NAME = "character_tags"

    /** Large enough for ML Kit to find faces in a group shot, small enough to decode quickly. */
    private const val ANALYSIS_DIMENSION = 640
    private const val MAX_SCAN_IMAGES = 100_000
    private const val SAVE_DEBOUNCE_MILLIS = 2_000L
    private const val PUBLISH_INTERVAL_MILLIS = 300L
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")

    /** App sandboxes and thumbnail caches hold copies and generated images that are not worth tagging. */
    private val SKIPPED_DIRECTORY_NAMES = setOf("Android", "cache", "Cache")
}
