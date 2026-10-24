package srv.scrtvwr.ui.media

import android.app.Application
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
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
import java.io.File
import java.io.IOException
import kotlin.math.abs

class MediaViewerViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {
    private val preferences = ScrtvwrPreferences(application)
    private val traversalRoot = runCatching { Environment.getExternalStorageDirectory() }.getOrNull()
    private var loadJob: Job? = null
    private val _uiState = MutableStateFlow(
        MediaViewerUiState(
            slideShowStayMillis = preferences.getSlideShowStayMillis(),
            slideShowDirection = preferences.getSlideShowDirection(),
            holdSlideShowEnabled = preferences.getHoldSlideShowEnabled(),
            imagePreloadDistance = preferences.getImagePreloadDistance(),
            edgeDialogStyle = preferences.getMediaEdgeDialogStyle(),
            sortOrder = preferences.getDirectorySortOrder(),
        ),
    )
    val uiState = _uiState.asStateFlow()

    fun load(directoryPath: String?, selectedPath: String?, force: Boolean = false) {
        val currentState = _uiState.value
        if (!force && currentState.sourceDirectoryPath == directoryPath && currentState.selectedPath == selectedPath) {
            return
        }

        loadJob?.cancel()
        _uiState.update { it.copy(isLoading = true, errorMessage = null, downloadProgress = null) }
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            runCatching { loadMedia(directoryPath, selectedPath, force) }
                .onFailure { error ->
                    if (error is kotlinx.coroutines.CancellationException) throw error
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

    private suspend fun loadMedia(directoryPath: String?, selectedPath: String?, force: Boolean = false) {
        val items: List<MediaViewerItem>
        val sourceDirectoryPath: String?
        var initialPage: Int
        val previousSource: MediaViewerSource?
        val nextSource: MediaViewerSource?
        val sortOrder = preferences.getDirectorySortOrder()

        val selectedFile = selectedPath?.let(::File)
        val isArchive = selectedPath != null && MediaTypes.isArchive(selectedPath)
        val remoteDirectory = directoryPath?.takeIf(MediaPaths::isRemote)?.let(RemotePaths::directoryUrl)
            ?: selectedPath?.takeIf(MediaPaths::isRemote)?.let(RemotePaths::parent)

        // Archives come first: one on a server is also remote, but is read as an archive.
        if (isArchive) {
            val archivePath = MediaPaths.archivePath(selectedPath)
            items = MediaCatalog.listArchive(archivePath) { progress ->
                _uiState.update { it.copy(downloadProgress = progress) }
            }
            _uiState.update { it.copy(downloadProgress = null) }
            sourceDirectoryPath = MediaPaths.parent(archivePath)
            initialPage = if (MediaPaths.archiveEntryName(selectedPath) != null) {
                items.indexOfFirst { it.path == selectedPath }.coerceAtLeast(0)
            } else {
                0
            }
            previousSource = null
            nextSource = null
        } else if (remoteDirectory != null) {
            items = try {
                MediaCatalog.listRemoteDirectory(remoteDirectory, sortOrder)
            } catch (error: IOException) {
                currentCoroutineContext().ensureActive()
                if (selectedPath?.let(MediaCatalog::remoteItem) == null) throw error
                emptyList()
            }
                .ifEmpty { selectedPath?.let(MediaCatalog::remoteItem)?.let(::listOf).orEmpty() }
            sourceDirectoryPath = remoteDirectory
            initialPage = items.indexOfFirst { it.path == selectedPath }.coerceAtLeast(0)
            previousSource = null
            nextSource = null
        } else {
            val directory = directoryPath?.let(::File)
            val sourceDirectory = when {
                directory?.isDirectory == true -> directory
                selectedFile?.parentFile?.isDirectory == true -> selectedFile.parentFile
                else -> null
            }

            items = sourceDirectory
                ?.let { MediaCatalog.listDirectory(it, sortOrder) }
                ?.ifEmpty { selectedFile?.let(MediaCatalog::toItem)?.let(::listOf).orEmpty() }
                ?: selectedFile?.let(MediaCatalog::toItem)?.let(::listOf).orEmpty()

            sourceDirectoryPath = sourceDirectory?.absolutePath ?: directoryPath
            initialPage = items.indexOfFirst { it.path == selectedPath }.coerceAtLeast(0)
            previousSource = sourceDirectory?.let { MediaCatalog.previousDirectory(it, sortOrder, traversalRoot) }
            nextSource = sourceDirectory?.let { MediaCatalog.nextDirectory(it, sortOrder, traversalRoot) }
        }

        val sourceKey = "${directoryPath.orEmpty()}|${selectedPath.orEmpty()}"
        val pageCount = items.size
        // Reopening the same source (a rotation recreates the screen) keeps the jump history;
        // anything else renumbers the pages, so it starts empty.
        var jumpHistory = JumpHistory()
        if (!force && savedStateHandle.get<String>(STATE_SOURCE) == sourceKey) {
            initialPage = savedStateHandle.get<Int>(STATE_SELECTED_PAGE)
                ?.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
                ?: initialPage
            jumpHistory = _uiState.value.jumpHistory
        } else {
            savedStateHandle[STATE_SOURCE] = sourceKey
            savedStateHandle[STATE_SELECTED_PAGE] = initialPage
        }
        currentCoroutineContext().ensureActive()
        _uiState.update {
            MediaViewerUiState(
                sourceDirectoryPath = sourceDirectoryPath,
                selectedPath = selectedPath,
                items = items,
                previousSource = previousSource,
                nextSource = nextSource,
                initialPage = initialPage,
                selectedPage = initialPage,
                slideShowStayMillis = preferences.getSlideShowStayMillis(),
                slideShowDirection = preferences.getSlideShowDirection(),
                holdSlideShowEnabled = preferences.getHoldSlideShowEnabled(),
                imagePreloadDistance = preferences.getImagePreloadDistance(),
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
        if (isArchive) coroutineScope {
            val archivePath = MediaPaths.archivePath(requireNotNull(selectedPath))
            launch {
                val source = MediaCatalog.previousArchive(archivePath, sortOrder)
                currentCoroutineContext().ensureActive()
                _uiState.update { it.copy(previousSource = source) }
            }
            launch {
                val source = MediaCatalog.nextArchive(archivePath, sortOrder)
                currentCoroutineContext().ensureActive()
                _uiState.update { it.copy(nextSource = source) }
            }
        } else if (remoteDirectory != null) coroutineScope {
            val serverRoot = preferences.getHttpServers().serverFor(remoteDirectory)?.baseUrl
            launch {
                val source = MediaCatalog.previousRemoteDirectory(remoteDirectory, sortOrder, serverRoot)
                currentCoroutineContext().ensureActive()
                _uiState.update { it.copy(previousSource = source) }
            }
            launch {
                val source = MediaCatalog.nextRemoteDirectory(remoteDirectory, sortOrder, serverRoot)
                currentCoroutineContext().ensureActive()
                _uiState.update { it.copy(nextSource = source) }
            }
        }
    }

    fun selectPage(page: Int) {
        _uiState.update { state ->
            val boundedPage = page.coerceIn(0, state.lastPageIndex)
            val selectedPath = state.itemForPage(boundedPage)?.path
            rememberMediaSession(state.sourceDirectoryPath, selectedPath)
            savedStateHandle[STATE_SELECTED_PAGE] = boundedPage
            state.copy(
                selectedPage = boundedPage,
                selectedPath = selectedPath ?: state.selectedPath,
                rotation = 0,
                flipped = false,
                bookmarked = preferences.isMediaBookmarked(selectedPath),
            )
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
        val previousSource = _uiState.value.previousSource ?: return
        if (previousSource.isArchive) {
            load(MediaPaths.parent(previousSource.path), previousSource.path)
        } else {
            load(previousSource.path, null)
        }
    }

    fun loadNextSource() {
        val nextSource = _uiState.value.nextSource ?: return
        if (nextSource.isArchive) {
            load(MediaPaths.parent(nextSource.path), nextSource.path)
        } else {
            load(nextSource.path, null)
        }
    }

    fun setSortOrder(sortOrder: DirectorySortOrder) {
        preferences.saveDirectorySortOrder(sortOrder)
        _uiState.update { it.copy(sortOrder = sortOrder) }
        val state = _uiState.value
        load(state.sourceDirectoryPath, null, force = true)
    }

    fun toggleFillFrame() {
        _uiState.update { it.copy(fillFrame = !it.fillFrame) }
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

    private fun rememberMediaSession(directoryPath: String?, mediaPath: String?) {
        directoryPath ?: return
        preferences.saveLastMediaSession(directoryPath, mediaPath)
    }

    private companion object {
        const val STATE_SELECTED_PAGE = "media_selected_page"
        const val STATE_SOURCE = "media_source"
    }
}

data class MediaViewerUiState(
    val sourceDirectoryPath: String? = null,
    val selectedPath: String? = null,
    val items: List<MediaViewerItem> = emptyList(),
    val previousSource: MediaViewerSource? = null,
    val nextSource: MediaViewerSource? = null,
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
    val imagePreloadDistance: Int = 5,
    val edgeDialogStyle: MediaEdgeDialogStyle = MediaEdgeDialogStyle.Bottom,
    val sortOrder: DirectorySortOrder = DirectorySortOrder.NameAscending,
    /** Page indexes are only valid for [items]; it is kept only when the same source is reopened. */
    val jumpHistory: JumpHistory = JumpHistory(),
    val isLoading: Boolean = false,
    /** Set while an archive on a server downloads before it can be opened. */
    val downloadProgress: DownloadProgress? = null,
    val errorMessage: String? = null,
) {
    val pageCount: Int
        get() = items.size

    val lastPageIndex: Int
        get() = (pageCount - 1).coerceAtLeast(0)

    fun itemForPage(page: Int): MediaViewerItem? =
        items.getOrNull(page)
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
)

enum class MediaViewerType {
    Image,
    Video,
}
