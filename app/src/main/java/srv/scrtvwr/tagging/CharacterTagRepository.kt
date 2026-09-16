package srv.scrtvwr.tagging

import android.content.Context
import android.net.Uri
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
import srv.scrtvwr.media.ImageOrientation
import srv.scrtvwr.media.MediaPreviewLoader
import srv.scrtvwr.preferences.ScrtvwrPreferences
import timber.log.Timber
import java.io.File

/**
 * Runs face detection and recognition over images and keeps the resulting character tags.
 *
 * Two ways in: [tagDirectory] tags what the user is currently browsing, and [startStorageScan] walks
 * the folders the user opened up to tagging. Both funnel through [tag], which skips images that are
 * already tagged and unchanged, so the two can overlap without repeating work.
 *
 * Neither goes anywhere the [scanScope] does not allow: that scope is the standing answer to "which
 * folders may be tagged", and the only thing that overrules it is [startFolderScan], where naming a
 * folder is itself the permission to read it.
 *
 * Identity comes from a recognition model when one is installed (see [FaceEmbeddingModel]); without
 * it the app falls back to landmark geometry, which can only group obviously similar faces.
 */
object CharacterTagRepository {
    private val registry = CharacterRegistry()
    private val mutex = Mutex()

    /** Face detection and embedding are memory hungry; two images in flight keeps the app responsive. */
    private val detectionSemaphore = Semaphore(2)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val saveRequests = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val ready = CompletableDeferred<Unit>()

    private var store: CharacterTagStore? = null
    private var preferences: ScrtvwrPreferences? = null
    private var appContext: Context? = null

    /** Set while the embedder is being replaced, so no image is tagged into a half-swapped state. */
    @Volatile private var swappingModel = false
    private var tagger: FaceTagger? = null
    private var embeddingModel: FaceEmbeddingModel? = null
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

    /** False when no recognition model is installed and tagging is running on landmark geometry. */
    private val _usingRecognitionModel = MutableStateFlow(false)
    val usingRecognitionModel: StateFlow<Boolean> = _usingRecognitionModel.asStateFlow()

    private val _rebuilding = MutableStateFlow(false)
    val rebuilding: StateFlow<Boolean> = _rebuilding.asStateFlow()

    /** The folders tagging is allowed into. Everything that reads an image checks this first. */
    private val _scanScope = MutableStateFlow(TagScanScope())
    val scanScope: StateFlow<TagScanScope> = _scanScope.asStateFlow()

    fun initialize(context: Context) {
        if (store != null) return
        val applicationContext = context.applicationContext
        appContext = applicationContext
        val scrtvwrPreferences = ScrtvwrPreferences(applicationContext)
        preferences = scrtvwrPreferences
        _enabled.value = scrtvwrPreferences.getFaceTaggingEnabled()
        _paused.value = scrtvwrPreferences.getTaggingPaused()
        _scanScope.value = scrtvwrPreferences.getTagScanScope()
        FaceCropDebug.initialize(applicationContext)
        val tagStore = CharacterTagStore(File(applicationContext.filesDir, TAG_DIRECTORY_NAME))
        store = tagStore

        scope.launch {
            applyEmbedder(applicationContext)

            val snapshot = withContext(Dispatchers.IO) { tagStore.read() }
            val restored = mutex.withLock { registry.restore(snapshot) }
            if (!restored) {
                // Tags came from a different embedder: their vectors mean nothing here, so the
                // images are tagged again rather than compared across incompatible spaces.
                Timber.i("Stored tags were built by another embedder; clearing them")
                withContext(Dispatchers.IO) { tagStore.clear() }
            }
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

    /**
     * Installs a recognition model the user picked. Tags built by the previous embedder are dropped,
     * because vectors from two different models describe different spaces and cannot be compared.
     */
    suspend fun installModel(uri: Uri): FaceModelInstaller.InstallResult {
        val context = appContext
            ?: return FaceModelInstaller.InstallResult.Rejected("Tagging is not ready yet")
        val result = withContext(Dispatchers.IO) { FaceModelInstaller.install(context, uri) }
        if (result is FaceModelInstaller.InstallResult.Installed) {
            cancelStorageScan()
            swappingModel = true
            try {
                val kept = applyEmbedder(context)
                if (!kept) withContext(NonCancellable + Dispatchers.IO) { store?.clear() }
            } finally {
                swappingModel = false
            }
            publish(force = true)
        }
        return result
    }

    /** Loads whichever model is available and rebuilds the tagger and matching rules around it. */
    private suspend fun applyEmbedder(context: Context): Boolean {
        val model = withContext(Dispatchers.IO) { FaceEmbeddingModel.load(context) }
        embeddingModel?.close()
        embeddingModel = model
        val embedder = model?.let(::ModelFaceEmbedder) ?: GeometricFaceEmbedder()
        tagger?.close()
        tagger = FaceTagger(embedder)
        _usingRecognitionModel.value = model != null
        return mutex.withLock { registry.useRules(MatchingRules.forEmbedder(embedder)) }
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

    fun setScanScope(folders: TagScanScope) {
        preferences?.saveTagScanScope(folders)
        _scanScope.value = folders
    }

    fun setScopeMode(mode: TagScopeMode) {
        setScanScope(_scanScope.value.copy(mode = mode))
    }

    /** Says what happens in one folder, replacing whatever was said about it before. */
    fun setFolderRule(path: String, includeSubfolders: Boolean, included: Boolean) {
        setScanScope(_scanScope.value.withRule(TagFolderRule(path, includeSubfolders, included)))
    }

    /** Drops the rule for one folder, so it falls back to whatever the mode says. */
    fun clearFolderRule(path: String) {
        setScanScope(_scanScope.value.withoutRule(path))
    }

    /** "Only here": narrows tagging to one folder, keeping the folders already ruled out. */
    fun limitToFolder(path: String, includeSubfolders: Boolean = true) {
        setScanScope(
            TagScanScope(mode = TagScopeMode.SelectedFolders, rules = _scanScope.value.skippedFolders)
                .withRule(TagFolderRule(path, includeSubfolders, included = true)),
        )
    }

    suspend fun tagsFor(paths: Collection<String>): Map<String, ImageTags> {
        if (store == null || paths.isEmpty()) return emptyMap()
        ready.await()
        return mutex.withLock { registry.tagsFor(paths) }
    }

    /** Every image a character appears in, for their page and their album. */
    suspend fun imagesFor(characterId: String): List<ImageTags> {
        if (store == null) return emptyList()
        ready.await()
        return mutex.withLock { registry.imagesFor(characterId) }
    }

    /**
     * Tags one image unless it is already tagged at this [modifiedMillis]. Returns null when tagging is
     * switched off, the image cannot be decoded, or detection failed.
     */
    suspend fun tag(path: String, modifiedMillis: Long): ImageTags? = tag(path, modifiedMillis, _scanScope.value)

    private suspend fun tag(path: String, modifiedMillis: Long, folders: TagScanScope): ImageTags? {
        if (store == null || !isEnabled() || swappingModel) return null
        // Out of scope means the image is never read, not even to answer with what is already known.
        if (!folders.allowsFile(path)) return null
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
                faces = detection.faces,
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
        val folders = _scanScope.value
        val allowed = images.filter { (path, _) -> folders.allowsFile(path) }
        if (store == null || !isEnabled() || allowed.isEmpty()) return null
        return scope.launch {
            allowed.forEach { (path, modifiedMillis) ->
                currentCoroutineContext().ensureActive()
                tag(path, modifiedMillis, folders)
            }
            publish(force = true)
        }
    }

    /**
     * Walks every image the [scanScope] allows. Only start this once the user has agreed to it.
     * Returns false when there is nowhere to look, which is what an empty folder list means.
     */
    fun startStorageScan(): Boolean {
        val folders = _scanScope.value
        if (folders.isEmpty) return false
        return startScan(folders, label = null, isFullPass = true)
    }

    /**
     * Tags one folder now. This is the one pass the standing scope does not gate - asking for a
     * folder by name is the permission to read it - though folders ruled out below it stay out.
     */
    fun startFolderScan(path: String, includeSubfolders: Boolean = true): Boolean {
        val folders = TagScanScope(mode = TagScopeMode.SelectedFolders, rules = _scanScope.value.skippedFolders)
            .withRule(TagFolderRule(path, includeSubfolders, included = true))
        return startScan(folders, label = File(path).name.ifBlank { path }, isFullPass = false)
    }

    private fun startScan(folders: TagScanScope, label: String?, isFullPass: Boolean): Boolean {
        if (store == null || !isEnabled() || scanJob?.isActive == true) return false
        scanJob = scope.launch { runScan(folders, label, isFullPass) }
        return true
    }

    fun cancelStorageScan() {
        scanJob?.cancel()
    }

    /**
     * Regroups every stored face. Worth doing after a big scan, after corrections, or whenever the
     * groups look wrong: it removes the effect of the order images happened to be tagged in.
     */
    suspend fun rebuildGroups(): Boolean {
        val store = store ?: return false
        if (_rebuilding.value) return false
        ready.await()
        _rebuilding.value = true
        return try {
            persist()
            val descriptorId = mutex.withLock { registry.descriptorId }
            val faces = withContext(Dispatchers.IO) { store.readFaces(descriptorId) }
            if (faces.isEmpty()) {
                false
            } else {
                val regrouped = mutex.withLock { registry.rebuild(faces) }
                withContext(NonCancellable + Dispatchers.IO) { store.writeFaces(descriptorId, regrouped) }
                persist()
                publish(force = true)
                true
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            Timber.w(error, "Rebuilding character groups failed")
            false
        } finally {
            _rebuilding.value = false
        }
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

    /** Folds two characters together when tagging split one person in two. */
    suspend fun merge(characterId: String, otherId: String): Boolean {
        val merged = mutex.withLock { registry.merge(characterId, otherId) }
        if (merged) {
            publish(force = true)
            saveRequests.tryEmit(Unit)
        }
        return merged
    }

    /** "Not this person": drops one image from a character. */
    suspend fun unassign(characterId: String, path: String): Boolean {
        val changed = mutex.withLock { registry.unassign(characterId, path) }
        if (changed) {
            publish(force = true)
            saveRequests.tryEmit(Unit)
        }
        return changed
    }

    suspend fun removeCharacter(characterId: String) {
        mutex.withLock { registry.remove(characterId) }
        publish(force = true)
        saveRequests.tryEmit(Unit)
    }

    suspend fun clearAll() {
        cancelStorageScan()
        mutex.withLock {
            registry.clear()
            registry.drainPendingFaces()
        }
        _facesFound.value = false
        withContext(NonCancellable + Dispatchers.IO) { store?.clear() }
        publish(force = true)
    }

    private suspend fun runScan(folders: TagScanScope, label: String?, isFullPass: Boolean) {
        _scanProgress.value = TagScanProgress(isRunning = true, scopeLabel = label)
        var completed = false
        try {
            val images = withContext(Dispatchers.IO) { collectScanImages(folders) }
            _scanProgress.update { it.copy(total = images.size) }
            images.forEachIndexed { index, image ->
                currentCoroutineContext().ensureActive()
                _scanProgress.update { it.copy(processed = index, currentName = image.name) }
                tag(image.absolutePath, image.lastModified(), folders)
            }
            if (isFullPass) preferences?.saveLastStorageScanMillis(System.currentTimeMillis())
            _scanProgress.update { it.copy(processed = it.total, currentName = null) }
            completed = true
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
        // A full pass is exactly when regrouping pays off: every face in scope is known, so nobody
        // stays split up just because their worst photo happened to be tagged first. A single folder
        // is too small a slice to be worth regrouping everyone over.
        if (completed && isFullPass) rebuildGroups()
    }

    private fun collectScanImages(folders: TagScanScope): List<File> {
        val images = mutableListOf<File>()
        folders.scanRoots(Environment.getExternalStorageDirectory())
            .filter(File::isDirectory)
            .forEach { root ->
                if (images.size >= MAX_SCAN_IMAGES) return images
                collectImages(root, folders, images)
            }
        return images
    }

    private fun collectImages(root: File, folders: TagScanScope, images: MutableList<File>) {
        root.walkTopDown()
            .onEnter { directory ->
                folders.allowsDescent(directory.absolutePath) && directory.isWorthWalking(folders)
            }
            .forEach { file ->
                if (images.size >= MAX_SCAN_IMAGES) return
                if (file.isFile && file.extension.lowercase() in IMAGE_EXTENSIONS &&
                    folders.allowsFile(file.absolutePath)
                ) {
                    images += file
                }
            }
    }

    /** Hidden folders and caches stay out of a walk unless the user chose something inside them. */
    private fun File.isWorthWalking(folders: TagScanScope): Boolean =
        (!name.startsWith(".") && name !in SKIPPED_DIRECTORY_NAMES) || folders.reachesInto(absolutePath)

    private suspend fun detect(path: String): FaceDetectionResult? {
        val tagger = tagger ?: return null
        val decoded = MediaPreviewLoader.decodeForAnalysis(path, ANALYSIS_DIMENSION).bitmap ?: return null
        // ML Kit reports landmarks in the orientation it was told to read the image in, so the
        // picture has to be turned upright before detection rather than after. Detecting on a
        // sideways bitmap and then cropping from it lands the crop somewhere else entirely - which
        // produces confident matches between people who look nothing alike, because what is being
        // compared is background rather than faces.
        val upright = ImageOrientation.turnedUpright(decoded, ImageOrientation.rotationDegrees(path))
        return try {
            tagger.detect(upright, rotationDegrees = 0)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            Timber.w(error, "Face detection failed for %s", path)
            null
        } finally {
            // decodeForAnalysis bypasses the preview caches, so nothing else can be holding these.
            if (upright !== decoded) upright.recycle()
            decoded.recycle()
        }
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
        val (snapshot, faces) = mutex.withLock { registry.snapshot() to registry.drainPendingFaces() }
        withContext(NonCancellable + Dispatchers.IO) {
            store.write(snapshot)
            store.appendFaces(snapshot.descriptorId, faces)
        }
    }

    private const val TAG_DIRECTORY_NAME = "character_tags"

    /**
     * Faces have to survive being decoded before they can be recognised. Decoding larger keeps more
     * of them above the identity floor - a face filling a twentieth of the frame lands at about 77
     * pixels here against 51 at 1024 - at the cost of roughly twice the pixels to decode and detect.
     * Images smaller than this are never scaled up; there is no detail to invent.
     */
    private const val ANALYSIS_DIMENSION = 1536
    private const val MAX_SCAN_IMAGES = 100_000
    private const val SAVE_DEBOUNCE_MILLIS = 2_000L
    private const val PUBLISH_INTERVAL_MILLIS = 300L
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")

    /** App sandboxes and thumbnail caches hold copies and generated images that are not worth tagging. */
    private val SKIPPED_DIRECTORY_NAMES = setOf("Android", "cache", "Cache")
}
