package srv.scrtvwr.ui.media

import android.app.Application
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import srv.scrtvwr.preferences.ScrtvwrPreferences
import srv.scrtvwr.media.MediaCatalog
import srv.scrtvwr.media.MediaPaths
import srv.scrtvwr.media.MediaTypes
import srv.scrtvwr.media.remote.DownloadProgress
import srv.scrtvwr.media.remote.RemotePaths
import srv.scrtvwr.media.remote.serverFor
import srv.scrtvwr.ui.sort.DirectorySortOrder
import srv.scrtvwr.ui.sort.needsFileMetadata
import java.io.File
import kotlin.math.abs

class MediaViewerViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {
    private val preferences = ScrtvwrPreferences(application)
    private val traversalRoot = runCatching { Environment.getExternalStorageDirectory() }.getOrNull()
    private var loadJob: Job? = null
    private var neighbourJob: Job? = null
    private var neighbourSearchLoadId = -1
    // What the current list was read from, for finding the sources around it.
    @Volatile private var loadedSource: ResolvedSource? = null
    @Volatile private var preparedSource: PreparedSource? = null
    @Volatile private var sortJob: Job? = null
    private var timeJob: Job? = null
    private val _uiState = MutableStateFlow(
        MediaViewerUiState(
            slideShowStayMillis = preferences.getSlideShowStayMillis(),
            slideShowDirection = preferences.getSlideShowDirection(),
            holdSlideShowEnabled = preferences.getHoldSlideShowEnabled(),
            slideShowSkipsVideos = preferences.getSlideShowSkipsVideos(),
            holdSlideShowSkipsVideos = preferences.getHoldSlideShowSkipsVideos(),
            fillFrame = preferences.getFillFrame(),
            imagePreloadDistance = preferences.getImagePreloadDistance(),
            loadVideoThumbnails = preferences.getLoadVideoThumbnails(),
            edgeDialogStyle = preferences.getMediaEdgeDialogStyle(),
            sortOrder = preferences.getDirectorySortOrder(),
        ),
    )
    val uiState = _uiState.asStateFlow()

    /**
     * Loads the source the screen was opened on, once per viewer. A recreated screen (a rotation)
     * asks again with the same extras, but browsing may have moved to another source since, so the
     * current one stays. After process death the source last asked for is reloaded instead.
     */
    fun open(directoryPath: String?, selectedPath: String?) {
        if (loadJob != null) return
        if (savedStateHandle.contains(STATE_REQUESTED_DIRECTORY)) {
            load(savedStateHandle[STATE_REQUESTED_DIRECTORY], savedStateHandle[STATE_REQUESTED_SELECTED])
        } else {
            load(directoryPath, selectedPath)
        }
    }

    fun load(directoryPath: String?, selectedPath: String?, force: Boolean = false) {
        val currentState = _uiState.value
        if (!force && currentState.sourceDirectoryPath == directoryPath && currentState.selectedPath == selectedPath) {
            return
        }

        loadJob?.cancel()
        neighbourJob?.cancel()
        sortJob?.cancel()
        neighbourSearchLoadId = -1
        savedStateHandle[STATE_REQUESTED_DIRECTORY] = directoryPath
        savedStateHandle[STATE_REQUESTED_SELECTED] = selectedPath
        _uiState.update { it.copy(isLoading = true, errorMessage = null, downloadProgress = null) }
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            runCatching { loadMedia(directoryPath, selectedPath, force) }
                .onFailure { error ->
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    // A blocking server read ignores cancellation and can fail after a newer load
                    // replaced this one; its error must not end that load's spinner.
                    ensureActive()
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = error.message ?: "Unable to load media.",
                            downloadProgress = null,
                        )
                    }
                }
        }
    }

    /** Loads the source last asked for again, after it failed. */
    fun retry() {
        load(savedStateHandle[STATE_REQUESTED_DIRECTORY], savedStateHandle[STATE_REQUESTED_SELECTED], force = true)
    }

    private suspend fun loadMedia(directoryPath: String?, selectedPath: String?, force: Boolean = false) {
        val sortOrder = preferences.getDirectorySortOrder()
        // Moving to the next source finds its list already read; any other load drops it.
        val prepared = preparedSource
        preparedSource = null
        val source = prepared?.takeIf { it.matches(directoryPath, selectedPath, sortOrder) }?.source
            ?: resolveSource(directoryPath, selectedPath, sortOrder) { progress ->
                _uiState.update { it.copy(downloadProgress = progress) }
            }
        val items = source.items
        val sourceDirectoryPath = source.sourceDirectoryPath
        var initialPage = source.initialPage

        val sourceKey = "${directoryPath.orEmpty()}|${selectedPath.orEmpty()}"
        val pageCount = items.size
        // Reopening the same source (a rotation recreates the screen) keeps the jump history;
        // anything else renumbers the pages, so it starts empty.
        var jumpHistory = JumpHistory()
        if (!force && savedStateHandle.get<String>(STATE_SOURCE) == sourceKey) {
            // By path first: a list still waiting to be sorted is not in the order the page was saved in.
            initialPage = savedStateHandle.get<String>(STATE_SELECTED_ITEM)
                ?.let { path -> items.indexOfFirst { it.path == path } }
                ?.takeIf { it >= 0 }
                ?: savedStateHandle.get<Int>(STATE_SELECTED_PAGE)
                    ?.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
                ?: initialPage
            jumpHistory = _uiState.value.jumpHistory
        } else {
            savedStateHandle[STATE_SOURCE] = sourceKey
            savedStateHandle[STATE_SELECTED_PAGE] = initialPage
            savedStateHandle[STATE_SELECTED_ITEM] = items.getOrNull(initialPage)?.path
        }
        currentCoroutineContext().ensureActive()
        loadedSource = source
        // The neighbouring sources are left unknown: findNeighbourSources looks for them once the
        // media on screen is done, so the list shows without waiting for a walk of other folders.
        _uiState.update {
            MediaViewerUiState(
                loadId = it.loadId + 1,
                sourceDirectoryPath = sourceDirectoryPath,
                selectedPath = selectedPath,
                items = items,
                initialPage = initialPage,
                selectedPage = initialPage,
                slideShowStayMillis = preferences.getSlideShowStayMillis(),
                slideShowDirection = preferences.getSlideShowDirection(),
                holdSlideShowEnabled = preferences.getHoldSlideShowEnabled(),
                slideShowSkipsVideos = preferences.getSlideShowSkipsVideos(),
                holdSlideShowSkipsVideos = preferences.getHoldSlideShowSkipsVideos(),
                fillFrame = preferences.getFillFrame(),
                imagePreloadDistance = preferences.getImagePreloadDistance(),
                loadVideoThumbnails = preferences.getLoadVideoThumbnails(),
                edgeDialogStyle = preferences.getMediaEdgeDialogStyle(),
                sortOrder = sortOrder,
                jumpHistory = jumpHistory,
                bookmarked =preferences.isMediaBookmarked(items.getOrNull(initialPage)?.path ?: selectedPath),
                isLoading = false,
            )
        }
        items.getOrNull(initialPage)?.path?.let { mediaPath ->
            rememberMediaSession(sourceDirectoryPath, mediaPath)
        }
        if (source.needsSorting) sortInPlace(sortOrder)
    }

    /** Reads the list for [directoryPath] and [selectedPath], without looking at other sources. */
    private suspend fun resolveSource(
        directoryPath: String?,
        selectedPath: String?,
        sortOrder: DirectorySortOrder,
        onDownloadProgress: (DownloadProgress) -> Unit,
    ): ResolvedSource {
        val selectedFile = selectedPath?.let(::File)
        val isArchive = selectedPath != null && MediaTypes.isArchive(selectedPath)
        val remoteDirectory = directoryPath?.takeIf(MediaPaths::isRemote)?.let(RemotePaths::directoryUrl)
            ?: selectedPath?.takeIf(MediaPaths::isRemote)?.let(RemotePaths::parent)

        // Archives come first: one on a server is also remote, but is read as an archive.
        if (isArchive) {
            val archivePath = MediaPaths.archivePath(selectedPath)
            val items = MediaCatalog.listArchive(archivePath, onDownloadProgress)
            return ResolvedSource(
                items = items,
                sourceDirectoryPath = MediaPaths.parent(archivePath),
                initialPage = if (MediaPaths.archiveEntryName(selectedPath) != null) {
                    items.indexOfFirst { it.path == selectedPath }.coerceAtLeast(0)
                } else {
                    0
                },
                findNeighbour = { next, order ->
                    if (next) MediaCatalog.nextArchive(archivePath, order) else MediaCatalog.previousArchive(archivePath, order)
                },
                isArchive = true,
            )
        }
        if (remoteDirectory != null) {
            val listed = MediaCatalog.listRemoteDirectoryUnsorted(remoteDirectory)
            // Sizes and times cost a request per file. Until they arrive the list shows in name
            // order, the order most folders are browsed in, and sortInPlace sorts it afterwards.
            val needsSorting = sortOrder.needsFileMetadata && !MediaCatalog.hasRemoteMetadata(listed)
            val items = MediaCatalog.sorted(listed, if (needsSorting) DirectorySortOrder.NameAscending else sortOrder)
                .ifEmpty { selectedPath?.let(MediaCatalog::remoteItem)?.let(::listOf).orEmpty() }
            return ResolvedSource(
                items = items,
                sourceDirectoryPath = remoteDirectory,
                initialPage = items.indexOfFirst { it.path == selectedPath }.coerceAtLeast(0),
                isRemote = true,
                needsSorting = needsSorting,
                findNeighbour = { next, order ->
                    val serverRoot = preferences.getHttpServers().serverFor(remoteDirectory)?.baseUrl
                    if (next) {
                        MediaCatalog.nextRemoteDirectory(remoteDirectory, order, serverRoot)
                    } else {
                        MediaCatalog.previousRemoteDirectory(remoteDirectory, order, serverRoot)
                    }
                },
            )
        }
        val directory = directoryPath?.let(::File)
        val sourceDirectory = when {
            directory?.isDirectory == true -> directory
            selectedFile?.parentFile?.isDirectory == true -> selectedFile.parentFile
            else -> null
        }
        val items = sourceDirectory
            ?.let { MediaCatalog.listDirectory(it, sortOrder) }
            ?.ifEmpty { selectedFile?.let(MediaCatalog::toItem)?.let(::listOf).orEmpty() }
            ?: selectedFile?.let(MediaCatalog::toItem)?.let(::listOf).orEmpty()
        return ResolvedSource(
            items = items,
            sourceDirectoryPath = sourceDirectory?.absolutePath ?: directoryPath,
            initialPage = items.indexOfFirst { it.path == selectedPath }.coerceAtLeast(0),
            findNeighbour = { next, order ->
                sourceDirectory?.let {
                    if (next) {
                        MediaCatalog.nextDirectory(it, order, traversalRoot)
                    } else {
                        MediaCatalog.previousDirectory(it, order, traversalRoot)
                    }
                }
            },
        )
    }

    /**
     * Looks for the sources before and after the current one, the next one first since browsing
     * mostly goes forward. The screen calls this once the media on screen is done, or sooner when
     * an edge dialog needs the answer; each list is searched once.
     */
    fun findNeighbourSources() {
        val state = _uiState.value
        val source = loadedSource
        if (source == null || state.isLoading || neighbourSearchLoadId == state.loadId) return
        val loadId = state.loadId
        val sortOrder = state.sortOrder
        neighbourSearchLoadId = loadId
        neighbourJob?.cancel()
        neighbourJob = viewModelScope.launch(Dispatchers.IO) {
            val nextSource = findNeighbour(source, next = true, sortOrder)
            ensureActive()
            _uiState.update {
                if (it.loadId == loadId) it.copy(nextSource = nextSource, isNextSourceKnown = true) else it
            }
            nextSource?.let { prepareSource(it, sortOrder) }
            val previousSource = findNeighbour(source, next = false, sortOrder)
            ensureActive()
            _uiState.update {
                if (it.loadId == loadId) it.copy(previousSource = previousSource, isPreviousSourceKnown = true) else it
            }
        }
    }

    /** Reads the list of [source] ahead, for [loadMedia] to use when the viewer moves to it. */
    private suspend fun prepareSource(source: MediaViewerSource, sortOrder: DirectorySortOrder) {
        // A server archive would have to be downloaded whole just to be listed.
        if (source.isArchive && MediaPaths.isRemote(source.path)) return
        val (directoryPath, selectedPath) = source.loadPaths
        val resolved = try {
            resolveSource(directoryPath, selectedPath, sortOrder) {}
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            // Moving there reads it again, and reports what went wrong.
            return
        }
        currentCoroutineContext().ensureActive()
        preparedSource = PreparedSource(directoryPath, selectedPath, sortOrder, resolved)
    }

    private suspend fun findNeighbour(
        source: ResolvedSource,
        next: Boolean,
        sortOrder: DirectorySortOrder,
    ): MediaViewerSource? =
        try {
            source.findNeighbour(next, sortOrder)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            // A source that cannot be walked from simply has no neighbour to offer.
            null
        }

    fun selectPage(page: Int) {
        _uiState.update { state ->
            val boundedPage = page.coerceIn(0, state.lastPageIndex)
            val selectedPath = state.itemForPage(boundedPage)?.path
            rememberMediaSession(state.sourceDirectoryPath, selectedPath)
            savedStateHandle[STATE_SELECTED_PAGE] = boundedPage
            savedStateHandle[STATE_SELECTED_ITEM] = selectedPath
            // A sort moves the media on screen to another index; it is still the same picture.
            val samePicture = selectedPath != null && selectedPath == state.itemForPage(state.selectedPage)?.path
            state.copy(
                selectedPage = boundedPage,
                selectedPath = selectedPath ?: state.selectedPath,
                rotation = if (samePicture) state.rotation else 0,
                flipped = samePicture && state.flipped,
                bookmarked = preferences.isMediaBookmarked(selectedPath),
            )
        }
        fetchTimeOfSelectedPage()
    }

    /**
     * Asks the server when the file on screen last changed, if its listing did not say. It waits
     * until the page has stayed a moment, so swiping or a slide show past pages sends no requests.
     */
    private fun fetchTimeOfSelectedPage() {
        timeJob?.cancel()
        val state = _uiState.value
        val item = state.itemForPage(state.selectedPage) ?: return
        if (item.lastModifiedMillis > 0L || item.path in state.fetchedTimes) return
        timeJob = viewModelScope.launch(Dispatchers.IO) {
            delay(TIME_FETCH_DELAY_MILLIS)
            val time = MediaCatalog.remoteLastModifiedMillis(item) ?: return@launch
            _uiState.update { it.copy(fetchedTimes = it.fetchedTimes + (item.path to time)) }
        }
    }

    /** Remembers a jump over several pages so it can be undone; page-by-page browsing is ignored. */
    fun recordJump(fromPage: Int, toPage: Int) {
        _uiState.update { it.copy(jumpHistory = it.jumpHistory.record(fromPage, toPage)) }
    }

    fun jumpBack(currentPage: Int) {
        val (page, history) = _uiState.value.jumpHistory.stepBack(currentPage) ?: return
        _uiState.update { it.copy(jumpHistory = history) }
        selectPage(page)
    }

    fun jumpForward(currentPage: Int) {
        val (page, history) = _uiState.value.jumpHistory.stepForward(currentPage) ?: return
        _uiState.update { it.copy(jumpHistory = history) }
        selectPage(page)
    }

    fun loadPreviousSource() {
        val (directoryPath, selectedPath) = _uiState.value.previousSource?.loadPaths ?: return
        load(directoryPath, selectedPath)
    }

    fun loadNextSource() {
        val (directoryPath, selectedPath) = _uiState.value.nextSource?.loadPaths ?: return
        load(directoryPath, selectedPath)
    }

    /**
     * Applies [sortOrder] to the list on screen without reloading it. A load still under way, or
     * one that found nothing, is started again instead, since it read the previous order.
     */
    fun setSortOrder(sortOrder: DirectorySortOrder) {
        preferences.saveDirectorySortOrder(sortOrder)
        _uiState.update { it.copy(sortOrder = sortOrder) }
        val state = _uiState.value
        if (state.isLoading || state.items.isEmpty()) {
            load(savedStateHandle[STATE_REQUESTED_DIRECTORY], savedStateHandle[STATE_REQUESTED_SELECTED], force = true)
            return
        }
        sortInPlace(sortOrder)
        // Which folder or archive comes next depends on the order too, so the search starts over.
        neighbourJob?.cancel()
        preparedSource = null
        _uiState.update {
            it.copy(
                previousSource = null,
                nextSource = null,
                isPreviousSourceKnown = false,
                isNextSourceKnown = false,
            )
        }
        if (neighbourSearchLoadId == state.loadId) {
            neighbourSearchLoadId = -1
            findNeighbourSources()
        }
    }

    /**
     * Puts the current list in [sortOrder] while it stays on screen, keeping the media shown. Server
     * files first get the sizes and times the order compares, with the sorting indicator up
     * meanwhile. Archive pages keep their name order.
     */
    private fun sortInPlace(sortOrder: DirectorySortOrder) {
        sortJob?.cancel()
        val source = loadedSource ?: return
        val state = _uiState.value
        if (source.isArchive || state.items.isEmpty()) return
        val loadId = state.loadId
        val fetchesDetails = source.isRemote && sortOrder.needsFileMetadata
        if (fetchesDetails && !MediaCatalog.hasRemoteMetadata(state.items)) {
            _uiState.update { if (it.loadId == loadId) it.copy(isSorting = true) else it }
        }
        sortJob = viewModelScope.launch(Dispatchers.IO) {
            val sorted = try {
                val items = if (fetchesDetails) MediaCatalog.withRemoteMetadata(state.items) else state.items
                MediaCatalog.sorted(items, sortOrder)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
                // The list stays in the order it is in.
                null
            }
            ensureActive()
            var selectedPage = -1
            _uiState.update { current ->
                when {
                    current.loadId != loadId -> current
                    sorted == null -> current.copy(isSorting = false)
                    else -> current.resorted(sorted).also { selectedPage = it.selectedPage }
                }
            }
            if (selectedPage >= 0) savedStateHandle[STATE_SELECTED_PAGE] = selectedPage
        }
    }

    fun toggleFillFrame() {
        val enabled = !_uiState.value.fillFrame
        preferences.saveFillFrame(enabled)
        _uiState.update { it.copy(fillFrame = enabled) }
    }

    fun rotateClockwise() {
        _uiState.update { it.copy(rotation = (it.rotation + 90) % 360) }
    }

    fun toggleFlip() {
        _uiState.update { it.copy(flipped = !it.flipped) }
    }

    fun toggleBookmark() {
        _uiState.update { state ->
            val path = state.selectedPath ?: return@update state
            val bookmarked = !state.bookmarked
            preferences.setMediaBookmarked(path, bookmarked)
            state.copy(bookmarked = bookmarked)
        }
    }

    /**
     * Double-tap "like": only ever adds, so a stray double tap can never drop an existing
     * bookmark. Removing one stays an explicit action in the options menu.
     */
    fun addBookmark(path: String) {
        preferences.setMediaBookmarked(path, true)
        _uiState.update { state ->
            if (path == state.selectedPath) state.copy(bookmarked = true) else state
        }
    }

    fun toggleChrome() {
        _uiState.update { it.copy(isChromeHidden = !it.isChromeHidden) }
    }

    fun startSlideShow(stayMillis: Long, direction: SlideShowDirection) {
        val boundedStayMillis = stayMillis.coerceAtLeast(1L)
        preferences.saveSlideShowStayMillis(boundedStayMillis)
        preferences.saveSlideShowDirection(direction)
        _uiState.update {
            it.copy(
                slideShowStayMillis = boundedStayMillis,
                slideShowDirection = direction,
                isSlideShowRunning = true,
                isChromeHidden = true,
            )
        }
    }

    fun stopSlideShow() {
        _uiState.update {
            it.copy(
                isSlideShowRunning = false,
                isChromeHidden = false,
            )
        }
    }

    fun toggleSlideShowSkipsVideos() {
        val enabled = !_uiState.value.slideShowSkipsVideos
        preferences.saveSlideShowSkipsVideos(enabled)
        _uiState.update { it.copy(slideShowSkipsVideos = enabled) }
    }

    fun toggleHoldSlideShowSkipsVideos() {
        val enabled = !_uiState.value.holdSlideShowSkipsVideos
        preferences.saveHoldSlideShowSkipsVideos(enabled)
        _uiState.update { it.copy(holdSlideShowSkipsVideos = enabled) }
    }

    private fun rememberMediaSession(directoryPath: String?, mediaPath: String?) {
        directoryPath ?: return
        preferences.saveLastMediaSession(directoryPath, mediaPath)
    }

    private companion object {
        const val STATE_SELECTED_PAGE = "media_selected_page"
        const val STATE_SELECTED_ITEM = "media_selected_item"
        const val STATE_SOURCE = "media_source"
        const val STATE_REQUESTED_DIRECTORY = "media_requested_directory"
        const val STATE_REQUESTED_SELECTED = "media_requested_selected"
        const val TIME_FETCH_DELAY_MILLIS = 300L
    }
}

data class MediaViewerUiState(
    /** Counts finished loads; a new value means a new list, whose page indexes start over. */
    val loadId: Int = 0,
    val sourceDirectoryPath: String? = null,
    val selectedPath: String? = null,
    val items: List<MediaViewerItem> = emptyList(),
    val previousSource: MediaViewerSource? = null,
    val nextSource: MediaViewerSource? = null,
    /** Whether [previousSource] is the search's answer, rather than null because it has not finished. */
    val isPreviousSourceKnown: Boolean = false,
    /** Whether [nextSource] is the search's answer, rather than null because it has not finished. */
    val isNextSourceKnown: Boolean = false,
    val initialPage: Int = 0,
    val selectedPage: Int = 0,
    val fillFrame: Boolean = false,
    val rotation: Int = 0,
    val flipped: Boolean = false,
    val bookmarked: Boolean = false,
    val isChromeHidden: Boolean = false,
    val isSlideShowRunning: Boolean = false,
    val slideShowStayMillis: Long = 100L,
    val slideShowDirection: SlideShowDirection = SlideShowDirection.Next,
    val holdSlideShowEnabled: Boolean = false,
    /** Whether the timed slide show jumps over video pages. */
    val slideShowSkipsVideos: Boolean = false,
    /** Whether the hold-to-slide show jumps over video pages. */
    val holdSlideShowSkipsVideos: Boolean = false,
    val imagePreloadDistance: Int = 5,
    /** Whether server video pages show their first frame; local videos always do. */
    val loadVideoThumbnails: Boolean = true,
    val edgeDialogStyle: MediaEdgeDialogStyle = MediaEdgeDialogStyle.Bottom,
    val sortOrder: DirectorySortOrder = DirectorySortOrder.NameAscending,
    /** Page indexes are only valid for [items]; it is kept only when the same source is reopened. */
    val jumpHistory: JumpHistory = JumpHistory(),
    val isLoading: Boolean = false,
    /** Set while the list waits for the file sizes and times its sort order compares. */
    val isSorting: Boolean = false,
    /** Set while an archive on a server downloads before it can be opened. */
    val downloadProgress: DownloadProgress? = null,
    val errorMessage: String? = null,
    /** Times of server files whose listing gave none, fetched once shown, by path. */
    val fetchedTimes: Map<String, Long> = emptyMap(),
) {
    val pageCount: Int
        get() = items.size

    val lastPageIndex: Int
        get() = (pageCount - 1).coerceAtLeast(0)

    fun itemForPage(page: Int): MediaViewerItem? =
        items.getOrNull(page)

    /** This state with [sorted], its items in a new order, staying on the media shown. */
    fun resorted(sorted: List<MediaViewerItem>): MediaViewerUiState {
        val shownPath = itemForPage(selectedPage)?.path
        val page = sorted.indexOfFirst { it.path == shownPath }.coerceAtLeast(0)
        return copy(
            items = sorted,
            selectedPage = page,
            initialPage = page,
            // Its page indexes point into the old order.
            jumpHistory = JumpHistory(),
            isSorting = false,
        )
    }
}

/**
 * Back/forward stacks of page indexes, kept in memory only, for undoing accidental jumps
 * such as a stray tap on the timeline.
 */
data class JumpHistory(
    val back: List<Int> = emptyList(),
    val forward: List<Int> = emptyList(),
) {
    fun record(fromPage: Int, toPage: Int): JumpHistory =
        if (abs(toPage - fromPage) <= 1) {
            this
        } else {
            JumpHistory(back = back.pushed(fromPage), forward = emptyList())
        }

    fun backTarget(currentPage: Int): Int? = back.dropLastWhile { it == currentPage }.lastOrNull()

    fun forwardTarget(currentPage: Int): Int? = forward.dropLastWhile { it == currentPage }.lastOrNull()

    /** Leaves for the newest back entry and remembers the page being left, wherever browsing took it since. */
    fun stepBack(currentPage: Int): Pair<Int, JumpHistory>? {
        val remaining = back.dropLastWhile { it == currentPage }
        val target = remaining.lastOrNull() ?: return null
        return target to JumpHistory(back = remaining.dropLast(1), forward = forward.pushed(currentPage))
    }

    fun stepForward(currentPage: Int): Pair<Int, JumpHistory>? {
        val remaining = forward.dropLastWhile { it == currentPage }
        val target = remaining.lastOrNull() ?: return null
        return target to JumpHistory(back = back.pushed(currentPage), forward = remaining.dropLast(1))
    }

    private fun List<Int>.pushed(page: Int): List<Int> =
        if (lastOrNull() == page) this else (this + page).takeLast(MAX_JUMP_HISTORY)

    private companion object {
        const val MAX_JUMP_HISTORY = 20
    }
}

/** A list read for the viewer, and how to find the sources before and after it in a sort order. */
private class ResolvedSource(
    val items: List<MediaViewerItem>,
    val sourceDirectoryPath: String?,
    val initialPage: Int,
    val findNeighbour: suspend (next: Boolean, sortOrder: DirectorySortOrder) -> MediaViewerSource?,
    /** Archive pages stay in name order, their reading order, whatever the sort order. */
    val isArchive: Boolean = false,
    val isRemote: Boolean = false,
    /** Whether [items] are in name order only because their sizes and times were not known yet. */
    val needsSorting: Boolean = false,
)

/** A neighbouring source read ahead, for the [load] that asks for the same paths and order. */
private class PreparedSource(
    val directoryPath: String?,
    val selectedPath: String?,
    val sortOrder: DirectorySortOrder,
    val source: ResolvedSource,
) {
    fun matches(directoryPath: String?, selectedPath: String?, sortOrder: DirectorySortOrder): Boolean =
        directoryPath == this.directoryPath && selectedPath == this.selectedPath && sortOrder == this.sortOrder
}

/** What [MediaViewerViewModel.load] is called with to open this source: its folder, or an archive. */
private val MediaViewerSource.loadPaths: Pair<String?, String?>
    get() = if (isArchive) MediaPaths.parent(path) to path else path to null

data class MediaViewerSource(
    val name: String,
    val path: String,
    val isArchive: Boolean = false,
)

data class MediaViewerItem(
    val name: String,
    val path: String,
    val type: MediaViewerType,
    val lastModifiedMillis: Long,
    val sizeBytes: Long,
) {
    /** This item with the time [fetchedTimes] holds for it, when its listing gave none. */
    fun withFetchedTime(fetchedTimes: Map<String, Long>): MediaViewerItem =
        fetchedTimes[path]?.takeIf { lastModifiedMillis <= 0L }?.let { copy(lastModifiedMillis = it) } ?: this
}

enum class MediaViewerType {
    Image,
    Video,
}
