package srv.scrtvwr.ui.media

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

class MediaViewerViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(MediaViewerUiState())
    val uiState = _uiState.asStateFlow()

    fun load(directoryPath: String?, selectedPath: String?) {
        val currentState = _uiState.value
        if (currentState.sourceDirectoryPath == directoryPath && currentState.selectedPath == selectedPath) {
            return
        }

        val directory = directoryPath?.let(::File)
        val selected = selectedPath?.let(::File)
        val sourceDirectory = when {
            directory?.isDirectory == true -> directory
            selected?.parentFile?.isDirectory == true -> selected.parentFile
            else -> null
        }

        val items = sourceDirectory
            ?.mediaViewerItems()
            ?.ifEmpty { selected?.toMediaViewerItem()?.let(::listOf).orEmpty() }
            ?: selected?.toMediaViewerItem()?.let(::listOf).orEmpty()
        val selectedIndex = items.indexOfFirst { it.path == selectedPath }.coerceAtLeast(0)

        _uiState.update {
            MediaViewerUiState(
                sourceDirectoryPath = directoryPath,
                selectedPath = selectedPath,
                items = items,
                initialPage = selectedIndex,
                selectedPage = selectedIndex,
            )
        }
    }

    fun selectPage(page: Int) {
        _uiState.update { state ->
            val boundedPage = page.coerceIn(0, state.items.lastIndex.coerceAtLeast(0))
            state.copy(selectedPage = boundedPage)
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
        _uiState.update { it.copy(bookmarked = !it.bookmarked) }
    }
}

data class MediaViewerUiState(
    val sourceDirectoryPath: String? = null,
    val selectedPath: String? = null,
    val items: List<MediaViewerItem> = emptyList(),
    val initialPage: Int = 0,
    val selectedPage: Int = 0,
    val fillFrame: Boolean = false,
    val rotation: Int = 0,
    val flipped: Boolean = false,
    val bookmarked: Boolean = false,
)

data class MediaViewerItem(
    val name: String,
    val path: String,
    val type: MediaViewerType,
    val lastModifiedMillis: Long,
)

enum class MediaViewerType {
    Image,
    Video,
}

private fun File.mediaViewerItems(): List<MediaViewerItem> =
    runCatching {
        listFiles()
            ?.asSequence()
            ?.filter { it.isFile }
            ?.mapNotNull { it.toMediaViewerItem() }
            ?.sortedBy { it.name.lowercase() }
            ?.toList()
    }.getOrNull().orEmpty()

private fun File.toMediaViewerItem(): MediaViewerItem? {
    val type = when (extension.lowercase()) {
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif" -> MediaViewerType.Image
        "mp4", "mkv", "webm", "3gp", "mov", "avi", "m4v" -> MediaViewerType.Video
        else -> return null
    }
    return MediaViewerItem(
        name = name.ifBlank { absolutePath },
        path = absolutePath,
        type = type,
        lastModifiedMillis = lastModified(),
    )
}
