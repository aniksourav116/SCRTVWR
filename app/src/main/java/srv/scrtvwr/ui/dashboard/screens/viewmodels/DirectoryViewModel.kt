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
import srv.scrtvwr.preferences.LastMediaSession
import srv.scrtvwr.preferences.ScrtvwrPreferences
import srv.scrtvwr.ui.sort.DirectorySortOrder
import srv.scrtvwr.ui.sort.fileComparator
import java.io.File

class DirectoryViewModel(application: Application) : AndroidViewModel(application) {
    private val rootDirectory = Environment.getExternalStorageDirectory()
    private val preferences = ScrtvwrPreferences(application)
    private val initialDirectory = preferences.getLastDirectory()
        ?.let(::File)
        ?.takeIf { it.isBrowsableDirectory(rootDirectory) }
        ?: rootDirectory
    private val _currentDirectory = MutableStateFlow(initialDirectory)
    val currentDirectory: StateFlow<File> = _currentDirectory

    private val searchQuery = MutableStateFlow("")
    private val viewMode = MutableStateFlow(DirectoryViewMode.List)
    private val sortOrder = MutableStateFlow(preferences.getDirectorySortOrder())
    private val recentDirectories = MutableStateFlow(preferences.getRecentDirectories().validDirectories(rootDirectory))
    private val lastMediaSession = MutableStateFlow(preferences.getLastMediaSession()?.takeIfValid(rootDirectory))

    val uiState: StateFlow<DirectoryUiState> = combine(
        combine(
            _currentDirectory,
            searchQuery,
            viewMode,
            sortOrder,
        ) { currentDirectory, query, mode, order ->
            DirectoryUiState(
                rootDirectory = rootDirectory,
                currentDirectory = currentDirectory,
                searchQuery = query,
                entries = currentDirectory.fileExplorerEntries(query, order),
                viewMode = mode,
                sortOrder = order,
                canNavigateUp = currentDirectory.isDifferentDirectory(rootDirectory),
            )
        },
        recentDirectories,
        lastMediaSession,
    ) { state, recent, session ->
        state.copy(
            recentDirectories = recent,
            lastMediaSession = session,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DirectoryUiState(
            rootDirectory = rootDirectory,
            currentDirectory = initialDirectory,
            entries = initialDirectory.fileExplorerEntries(sortOrder = sortOrder.value),
            sortOrder = sortOrder.value,
            recentDirectories = recentDirectories.value,
            lastMediaSession = lastMediaSession.value,
        ),
    )

    init {
        rememberDirectory(initialDirectory)
    }

    fun updateSearchQuery(query: String) {
        searchQuery.value = query
    }

    fun setViewMode(mode: DirectoryViewMode) {
        viewMode.value = mode
    }

    fun setSortOrder(order: DirectorySortOrder) {
        sortOrder.value = order
        preferences.saveDirectorySortOrder(order)
    }

    fun openDirectory(entry: DirectoryEntry) {
        if (!entry.isDirectory) return

        openDirectoryPath(entry.path)
    }

    fun openDirectoryPath(path: String) {
        val directory = File(path)
        if (!directory.isBrowsableDirectory(rootDirectory)) return

        _currentDirectory.value = directory
        searchQuery.value = ""
        rememberDirectory(directory)
    }

    fun navigateUp() {
        val parentDirectory = _currentDirectory.value.parentFile ?: return
        if (parentDirectory.isSameOrChildOf(rootDirectory)) {
            _currentDirectory.value = parentDirectory
            searchQuery.value = ""
            rememberDirectory(parentDirectory)
        }
    }

    fun refresh() {
        recentDirectories.value = preferences.getRecentDirectories().validDirectories(rootDirectory)
        lastMediaSession.value = preferences.getLastMediaSession()?.takeIfValid(rootDirectory)
        _currentDirectory.update { it }
    }

    private fun rememberDirectory(directory: File) {
        preferences.rememberDirectory(directory.absolutePath)
        recentDirectories.value = preferences.getRecentDirectories().validDirectories(rootDirectory)
    }
}

data class DirectoryUiState(
    val rootDirectory: File = Environment.getExternalStorageDirectory(),
    val currentDirectory: File = rootDirectory,
    val searchQuery: String = "",
    val entries: List<DirectoryEntry> = emptyList(),
    val viewMode: DirectoryViewMode = DirectoryViewMode.List,
    val sortOrder: DirectorySortOrder = DirectorySortOrder.NameAscending,
    val canNavigateUp: Boolean = false,
    val recentDirectories: List<String> = emptyList(),
    val lastMediaSession: LastMediaSession? = null,
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

private fun File.fileExplorerEntries(
    searchQuery: String = "",
    sortOrder: DirectorySortOrder = DirectorySortOrder.NameAscending,
): List<DirectoryEntry> {
    val normalizedQuery = searchQuery.trim()
    return runCatching {
        listFiles()
            ?.asSequence()
            ?.filter { file ->
                normalizedQuery.isBlank() || file.name.contains(normalizedQuery, ignoreCase = true)
            }
            ?.sortedWith(sortOrder.fileComparator(directoriesFirst = true))
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

private fun File.isBrowsableDirectory(root: File): Boolean =
    exists() && isDirectory && isSameOrChildOf(root)

private fun List<String>.validDirectories(root: File): List<String> =
    filter { File(it).isBrowsableDirectory(root) }

private fun LastMediaSession.takeIfValid(root: File): LastMediaSession? =
    takeIf {
        File(directoryPath).isBrowsableDirectory(root) &&
            (mediaPath == null || File(mediaPath).exists())
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
