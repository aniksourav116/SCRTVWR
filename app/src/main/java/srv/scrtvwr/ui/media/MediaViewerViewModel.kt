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
import srv.scrtvwr.ui.sort.fileComparator
import java.io.File
import java.util.zip.ZipFile
import com.github.junrar.Archive

class MediaViewerViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {
    private val preferences = ScrtvwrPreferences(application)
    private var loadJob: Job? = null
    private val _uiState = MutableStateFlow(
        MediaViewerUiState(
            slideShowStayMillis = preferences.getSlideShowStayMillis(),
            imagePreloadDistance = preferences.getImagePreloadDistance(),
        ),
    )
    val uiState = _uiState.asStateFlow()

    fun load(directoryPath: String?, selectedPath: String?) {
        val currentState = _uiState.value
        if (currentState.sourceDirectoryPath == directoryPath && currentState.selectedPath == selectedPath) {
            return
        }

        loadJob?.cancel()
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            runCatching { loadMedia(directoryPath, selectedPath) }
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

    private suspend fun loadMedia(directoryPath: String?, selectedPath: String?) {
        val items: List<MediaViewerItem>
        val sourceDirectoryPath: String?
        var initialPage: Int
        val nextSource: MediaViewerSource?
        val sortOrder = preferences.getDirectorySortOrder()

        val selectedFile = selectedPath?.let(::File)
        val isArchive = selectedPath != null && MediaCatalog.isArchive(selectedPath)

        if (isArchive) {
            val archivePath = selectedPath!!.substringBefore("::")
            val archiveFile = File(archivePath)
            items = MediaCatalog.listArchive(archiveFile)
            sourceDirectoryPath = archiveFile.parentFile?.absolutePath
            initialPage = if (selectedPath.contains("::")) {
                items.indexOfFirst { it.path == selectedPath }.coerceAtLeast(0)
            } else {
                0
            }
            nextSource = MediaCatalog.nextArchive(archiveFile, sortOrder)
        } else {
            val directory = directoryPath?.let(::File)
            val selected = selectedFile
            val sourceDirectory = when {
                directory?.isDirectory == true -> directory
                selected?.parentFile?.isDirectory == true -> selected.parentFile
                else -> null
            }

            items = sourceDirectory
                ?.let { MediaCatalog.listDirectory(it, sortOrder) }
                ?.ifEmpty { selected?.let(MediaCatalog::toItem)?.let(::listOf).orEmpty() }
                ?: selected?.let(MediaCatalog::toItem)?.let(::listOf).orEmpty()

            sourceDirectoryPath = sourceDirectory?.absolutePath ?: directoryPath
            initialPage = items.indexOfFirst { it.path == selectedPath }.coerceAtLeast(0)
            nextSource = sourceDirectory?.let { MediaCatalog.nextDirectory(it, sortOrder) }
        }

        val sourceKey = "${directoryPath.orEmpty()}|${selectedPath.orEmpty()}"
        if (savedStateHandle.get<String>(STATE_SOURCE) == sourceKey) {
            initialPage = savedStateHandle.get<Int>(STATE_SELECTED_PAGE)
                ?.coerceIn(0, (items.lastIndex).coerceAtLeast(0))
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
                nextSource = nextSource,
                initialPage = initialPage,
                selectedPage = initialPage,
                slideShowStayMillis = preferences.getSlideShowStayMillis(),
                imagePreloadDistance = preferences.getImagePreloadDistance(),
                bookmarked = preferences.isMediaBookmarked(items.getOrNull(initialPage)?.path ?: selectedPath),
                isLoading = false,
            )
        }
        rememberMediaSession(sourceDirectoryPath, items.getOrNull(initialPage)?.path ?: selectedPath)
    }

    fun selectPage(page: Int) {
        _uiState.update { state ->
            val boundedPage = page.coerceIn(0, state.lastPageIndex)
            val selectedPath = state.items.getOrNull(boundedPage)?.path
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

    fun loadNextSource() {
        val nextSource = _uiState.value.nextSource ?: return
        if (nextSource.isArchive) {
            load(File(nextSource.path).parentFile?.absolutePath, nextSource.path)
        } else {
            load(nextSource.path, null)
        }
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

    fun startSlideShow(stayMillis: Long) {
        val boundedStayMillis = stayMillis.coerceAtLeast(1L)
        preferences.saveSlideShowStayMillis(boundedStayMillis)
        _uiState.update {
            it.copy(
                slideShowStayMillis = boundedStayMillis,
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
    val imagePreloadDistance: Int = 5,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
) {
    val pageCount: Int
        get() = items.size + if (nextSource != null) 1 else 0

    val lastPageIndex: Int
        get() = (pageCount - 1).coerceAtLeast(0)

    val nextSourcePageIndex: Int?
        get() = nextSource?.let { items.size }
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
