package srv.scrtvwr.ui.media

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import srv.scrtvwr.preferences.ScrtvwrPreferences
import srv.scrtvwr.media.MediaCatalog
import srv.scrtvwr.ui.sort.DirectorySortOrder
import java.io.File

class MediaViewerViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {
    private val preferences = ScrtvwrPreferences(application)
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
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            runCatching { loadMedia(directoryPath, selectedPath, force) }
                .onFailure { error ->
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = error.message ?: "Unable to load media.",
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
        val isArchive = selectedPath != null && MediaCatalog.isArchive(selectedPath)

        if (isArchive) {
            val archivePath = selectedPath.substringBefore("::")
            val archiveFile = File(archivePath)
            items = MediaCatalog.listArchive(archiveFile)
            sourceDirectoryPath = archiveFile.parentFile?.absolutePath
            initialPage = if (selectedPath.contains("::")) {
                items.indexOfFirst { it.path == selectedPath }.coerceAtLeast(0)
            } else {
                0
            }
            previousSource = MediaCatalog.previousArchive(archiveFile, sortOrder)
            nextSource = MediaCatalog.nextArchive(archiveFile, sortOrder)
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
            previousSource = sourceDirectory?.let { MediaCatalog.previousDirectory(it, sortOrder) }
            nextSource = sourceDirectory?.let { MediaCatalog.nextDirectory(it, sortOrder) }
        }

        val sourceKey = "${directoryPath.orEmpty()}|${selectedPath.orEmpty()}"
        val pageCount = items.size
        if (!force && savedStateHandle.get<String>(STATE_SOURCE) == sourceKey) {
            initialPage = savedStateHandle.get<Int>(STATE_SELECTED_PAGE)
                ?.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
                ?: initialPage
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
                bookmarked = preferences.isMediaBookmarked(items.getOrNull(initialPage)?.path ?: selectedPath),
                isLoading = false,
            )
        }
        items.getOrNull(initialPage)?.path?.let { mediaPath ->
            rememberMediaSession(sourceDirectoryPath, mediaPath)
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

    fun loadPreviousSource() {
        val previousSource = _uiState.value.previousSource ?: return
        if (previousSource.isArchive) {
            load(File(previousSource.path).parentFile?.absolutePath, previousSource.path)
        } else {
            load(previousSource.path, null)
        }
    }

    fun loadNextSource() {
        val nextSource = _uiState.value.nextSource ?: return
        if (nextSource.isArchive) {
            load(File(nextSource.path).parentFile?.absolutePath, nextSource.path)
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
    val slideShowStayMillis: Long = 50L,
    val slideShowDirection: SlideShowDirection = SlideShowDirection.Next,
    val holdSlideShowEnabled: Boolean = false,
    val imagePreloadDistance: Int = 5,
    val edgeDialogStyle: MediaEdgeDialogStyle = MediaEdgeDialogStyle.Bottom,
    val sortOrder: DirectorySortOrder = DirectorySortOrder.NameAscending,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
) {
    val pageCount: Int
        get() = items.size

    val lastPageIndex: Int
        get() = (pageCount - 1).coerceAtLeast(0)

    fun itemForPage(page: Int): MediaViewerItem? =
        items.getOrNull(page)
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
