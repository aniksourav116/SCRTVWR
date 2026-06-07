package srv.scrtvwr.ui.dashboard.screens.viewmodels

import android.app.Application
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.io.File

class DirectoryViewModel(application: Application) : AndroidViewModel(application) {
    private val rootDirectory = Environment.getExternalStorageDirectory()
    private val _currentDirectory = MutableStateFlow(rootDirectory)
    val currentDirectory: StateFlow<File> = _currentDirectory

    private val searchQuery = MutableStateFlow("")
    private val viewMode = MutableStateFlow(DirectoryViewMode.List)

    val uiState: StateFlow<DirectoryUiState> = combine(
        _currentDirectory,
        searchQuery,
        viewMode,
    ) { currentDirectory, query, mode ->
        DirectoryUiState(
            rootDirectory = rootDirectory,
            currentDirectory = currentDirectory,
            searchQuery = query,
            entries = currentDirectory.fileExplorerEntries(query),
            viewMode = mode,
            canNavigateUp = currentDirectory.isDifferentDirectory(rootDirectory),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DirectoryUiState(
            rootDirectory = rootDirectory,
            currentDirectory = rootDirectory,
            entries = rootDirectory.fileExplorerEntries(),
        ),
    )

    fun updateSearchQuery(query: String) {
        searchQuery.value = query
    }

    fun setViewMode(mode: DirectoryViewMode) {
        viewMode.value = mode
    }

    fun openDirectory(entry: DirectoryEntry) {
        if (!entry.isDirectory) return

        val directory = File(entry.path)
        if (directory.exists() && directory.isDirectory && directory.isSameOrChildOf(rootDirectory)) {
            _currentDirectory.value = directory
            searchQuery.value = ""
        }
    }

    fun navigateUp() {
        val parentDirectory = _currentDirectory.value.parentFile ?: return
        if (parentDirectory.isSameOrChildOf(rootDirectory)) {
            _currentDirectory.value = parentDirectory
            searchQuery.value = ""
        }
    }

    fun refresh() {
        _currentDirectory.update { it }
    }
}

data class DirectoryUiState(
    val rootDirectory: File = Environment.getExternalStorageDirectory(),
    val currentDirectory: File = rootDirectory,
    val searchQuery: String = "",
    val entries: List<DirectoryEntry> = emptyList(),
    val viewMode: DirectoryViewMode = DirectoryViewMode.List,
    val canNavigateUp: Boolean = false,
)

data class DirectoryEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModifiedMillis: Long,
    val mediaType: DirectoryMediaType = DirectoryMediaType.Other,
)

enum class DirectoryViewMode {
    List,
    Grid,
}

enum class DirectoryMediaType {
    Image,
    Video,
    Other,
}

private fun File.fileExplorerEntries(searchQuery: String = ""): List<DirectoryEntry> {
    val normalizedQuery = searchQuery.trim()
    return runCatching {
        listFiles()
            ?.asSequence()
            ?.filter { file ->
                normalizedQuery.isBlank() || file.name.contains(normalizedQuery, ignoreCase = true)
            }
            ?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
            ?.map { file ->
                DirectoryEntry(
                    name = file.name.ifBlank { file.absolutePath },
                    path = file.absolutePath,
                    isDirectory = file.isDirectory,
                    sizeBytes = if (file.isFile) file.length() else 0L,
                    lastModifiedMillis = file.lastModified(),
                    mediaType = file.mediaType(),
                )
            }
            ?.toList()
    }.getOrNull().orEmpty()
}

private fun File.isSameOrChildOf(root: File): Boolean {
    val rootPath = root.canonicalPathOrAbsolute()
    val filePath = canonicalPathOrAbsolute()
    return filePath == rootPath || filePath.startsWith("$rootPath${File.separator}")
}

private fun File.isDifferentDirectory(other: File): Boolean =
    canonicalPathOrAbsolute() != other.canonicalPathOrAbsolute()

private fun File.canonicalPathOrAbsolute(): String =
    runCatching { canonicalPath }.getOrElse { absolutePath }

private fun File.mediaType(): DirectoryMediaType {
    if (isDirectory) return DirectoryMediaType.Other

    return when (extension.lowercase()) {
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif" -> DirectoryMediaType.Image
        "mp4", "mkv", "webm", "3gp", "mov", "avi", "m4v" -> DirectoryMediaType.Video
        else -> DirectoryMediaType.Other
    }
}
