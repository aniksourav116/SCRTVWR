package srv.scrtvwr.ui.media

import android.app.Application
import androidx.lifecycle.AndroidViewModel
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
import srv.scrtvwr.ui.sort.DirectorySortOrder
import srv.scrtvwr.ui.sort.fileComparator
import java.io.File
import java.util.zip.ZipFile
import com.github.junrar.Archive

class MediaViewerViewModel(application: Application) : AndroidViewModel(application) {
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
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            loadMedia(directoryPath, selectedPath)
        }
    }

    private suspend fun loadMedia(directoryPath: String?, selectedPath: String?) {
        val items: List<MediaViewerItem>
        val sourceDirectoryPath: String?
        val initialPage: Int
        val nextDirectory: MediaViewerDirectory?

        val selectedFile = selectedPath?.let(::File)
        val isArchive = selectedPath != null && (selectedPath.isArchive() || selectedPath.contains("::"))

        if (isArchive) {
            val archivePath = selectedPath!!.substringBefore("::")
            val archiveFile = File(archivePath)
            items = archiveFile.archiveMediaItems()
            sourceDirectoryPath = archivePath
            initialPage = if (selectedPath.contains("::")) {
                items.indexOfFirst { it.path == selectedPath }.coerceAtLeast(0)
            } else {
                0
            }
            nextDirectory = null
        } else {
            val directory = directoryPath?.let(::File)
            val selected = selectedFile
            val sourceDirectory = when {
                directory?.isDirectory == true -> directory
                selected?.parentFile?.isDirectory == true -> selected.parentFile
                else -> null
            }

            val sortOrder = preferences.getDirectorySortOrder()
            items = sourceDirectory
                ?.mediaViewerItems(sortOrder)
                ?.ifEmpty { selected?.toMediaViewerItem()?.let(::listOf).orEmpty() }
                ?: selected?.toMediaViewerItem()?.let(::listOf).orEmpty()

            sourceDirectoryPath = sourceDirectory?.absolutePath ?: directoryPath
            initialPage = items.indexOfFirst { it.path == selectedPath }.coerceAtLeast(0)
            nextDirectory = sourceDirectory?.nextMediaDirectory(sortOrder)
        }

        currentCoroutineContext().ensureActive()
        _uiState.update {
            MediaViewerUiState(
                sourceDirectoryPath = sourceDirectoryPath,
                selectedPath = selectedPath,
                items = items,
                nextDirectory = nextDirectory,
                initialPage = initialPage,
                selectedPage = initialPage,
                slideShowStayMillis = preferences.getSlideShowStayMillis(),
                imagePreloadDistance = preferences.getImagePreloadDistance(),
            )
        }
        rememberMediaSession(sourceDirectoryPath, items.getOrNull(initialPage)?.path ?: selectedPath)
    }

    fun selectPage(page: Int) {
        _uiState.update { state ->
            val boundedPage = page.coerceIn(0, state.lastPageIndex)
            val selectedPath = state.items.getOrNull(boundedPage)?.path
            rememberMediaSession(state.sourceDirectoryPath, selectedPath)
            state.copy(
                selectedPage = boundedPage,
                selectedPath = selectedPath ?: state.selectedPath,
            )
        }
    }

    fun loadNextDirectory() {
        val nextDirectory = _uiState.value.nextDirectory ?: return
        load(nextDirectory.path, null)
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
}

data class MediaViewerUiState(
    val sourceDirectoryPath: String? = null,
    val selectedPath: String? = null,
    val items: List<MediaViewerItem> = emptyList(),
    val nextDirectory: MediaViewerDirectory? = null,
    val initialPage: Int = 0,
    val selectedPage: Int = 0,
    val fillFrame: Boolean = false,
    val rotation: Int = 0,
    val flipped: Boolean = false,
    val bookmarked: Boolean = false,
    val isChromeHidden: Boolean = false,
    val isSlideShowRunning: Boolean = false,
    val slideShowStayMillis: Long = 50L,
    val imagePreloadDistance: Int = 20,
) {
    val pageCount: Int
        get() = items.size + if (nextDirectory != null) 1 else 0

    val lastPageIndex: Int
        get() = (pageCount - 1).coerceAtLeast(0)

    val nextDirectoryPageIndex: Int?
        get() = nextDirectory?.let { items.size }
}

data class MediaViewerDirectory(
    val name: String,
    val path: String,
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

private fun File.mediaViewerItems(sortOrder: DirectorySortOrder): List<MediaViewerItem> =
    runCatching {
        listFiles()
            ?.asSequence()
            ?.filter { it.isFile }
            ?.filter { it.toMediaViewerItem() != null }
            ?.sortedWith(sortOrder.fileComparator(directoriesFirst = false))
            ?.mapNotNull { it.toMediaViewerItem() }
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
        sizeBytes = length(),
    )
}

private fun File.nextMediaDirectory(sortOrder: DirectorySortOrder): MediaViewerDirectory? {
    val currentPath = canonicalPathOrAbsolute()
    return parentFile
        ?.listFiles()
        ?.asSequence()
        ?.filter { it.isDirectory }
        ?.sortedWith(sortOrder.fileComparator(directoriesFirst = false))
        ?.dropWhile { it.canonicalPathOrAbsolute() != currentPath }
        ?.drop(1)
        ?.firstOrNull { it.mediaViewerItems(sortOrder).isNotEmpty() }
        ?.let {
            MediaViewerDirectory(
                name = it.name.ifBlank { it.absolutePath },
                path = it.absolutePath,
            )
        }
}

private fun File.canonicalPathOrAbsolute(): String =
    runCatching { canonicalPath }.getOrElse { absolutePath }

private fun String.isArchive(): Boolean {
    val lower = lowercase()
    return lower.endsWith(".zip") || lower.endsWith(".cbz") || lower.endsWith(".rar") || lower.endsWith(".cbr")
}

private fun File.archiveMediaItems(): List<MediaViewerItem> {
    val limits = listOf(-1, 1000, 900, 800, 700, 600, 500, 400, 300, 200, 100)
    for (limit in limits) {
        try {
            val items = when (extension.lowercase()) {
                "zip", "cbz" -> getZipImageEntries(this, limit)
                "rar", "cbr" -> getRarImageEntries(this, limit)
                else -> emptyList()
            }
            if (items.isNotEmpty()) return items
        } catch (e: Exception) {
            // Try with a smaller limit
        }
    }
    return emptyList()
}

private fun getZipImageEntries(file: File, limit: Int): List<MediaViewerItem> {
    return ZipFile(file).use { zip ->
        var sequence = zip.entries().asSequence()
            .filter { !it.isDirectory }
            .filter { it.name.isImageExtension() }

        if (limit > 0) {
            sequence = sequence.take(limit)
        }

        sequence.map { entry ->
            MediaViewerItem(
                name = entry.name.substringAfterLast('/'),
                path = "${file.absolutePath}::${entry.name}",
                type = MediaViewerType.Image,
                lastModifiedMillis = entry.time,
                sizeBytes = entry.size
            )
        }
        .sortedBy { it.name }
        .toList()
    }
}

private fun getRarImageEntries(file: File, limit: Int): List<MediaViewerItem> {
    return Archive(file).use { archive ->
        var sequence = archive.fileHeaders.asSequence()
            .filter { !it.isDirectory }
            .filter { it.fileName.isImageExtension() }

        if (limit > 0) {
            sequence = sequence.take(limit)
        }

        sequence.map { header ->
            MediaViewerItem(
                name = header.fileName.substringAfterLast('\\').substringAfterLast('/'),
                path = "${file.absolutePath}::${header.fileName}",
                type = MediaViewerType.Image,
                lastModifiedMillis = header.mTime?.time ?: file.lastModified(),
                sizeBytes = header.fullUnpackSize
            )
        }
        .sortedBy { it.name }
        .toList()
    }
}

private fun String.isImageExtension(): Boolean {
    val ext = substringAfterLast('.', "").lowercase()
    return when (ext) {
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif" -> true
        else -> false
    }
}
