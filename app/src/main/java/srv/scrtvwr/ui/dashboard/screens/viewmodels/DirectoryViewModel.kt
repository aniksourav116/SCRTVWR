package srv.scrtvwr.ui.dashboard.screens.viewmodels

import android.app.Application
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import srv.scrtvwr.preferences.LastMediaSession
import srv.scrtvwr.preferences.ScrtvwrPreferences
import srv.scrtvwr.ui.sort.DirectorySortOrder
import srv.scrtvwr.ui.sort.fileComparator
import java.io.File

class DirectoryViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {
    private val defaultRootDirectory = Environment.getExternalStorageDirectory()
    private val preferences = ScrtvwrPreferences(application)
    private val initialDirectory = savedStateHandle.get<String>(STATE_DIRECTORY)
        ?.let(::File)
        ?.takeIf { it.exists() && it.isDirectory }
        ?: preferences.getLastDirectory()
        ?.let(::File)
        ?.takeIf { it.exists() && it.isDirectory }
        ?: defaultRootDirectory
    private val initialRoot = if (initialDirectory.isSameOrChildOf(defaultRootDirectory)) {
        defaultRootDirectory
    } else {
        initialDirectory
    }
    private val _uiState = MutableStateFlow(
        DirectoryUiState(
            rootDirectory = initialRoot,
            currentDirectory = initialDirectory,
            searchQuery = savedStateHandle[STATE_SEARCH] ?: "",
            viewMode = savedStateHandle.get<String>(STATE_VIEW_MODE)
                ?.let { value -> DirectoryViewMode.entries.firstOrNull { it.name == value } }
                ?: DirectoryViewMode.List,
            sortOrder = preferences.getDirectorySortOrder(),
            showHiddenFiles = preferences.getShowHiddenFiles(),
            canNavigateUp = initialDirectory.isDifferentDirectory(initialRoot),
            recentDirectories = preferences.getRecentDirectories().validDirectories(),
            lastMediaSession = preferences.getLastMediaSession()?.takeIfValid(),
            isLoading = true,
        ),
    )
    val uiState: StateFlow<DirectoryUiState> = _uiState.asStateFlow()
    private var scanJob: Job? = null

    init {
        saveLastDirectory(initialDirectory)
        scanDirectory()
    }

    fun updateSearchQuery(query: String) {
        savedStateHandle[STATE_SEARCH] = query
        _uiState.update { it.copy(searchQuery = query) }
        scanDirectory(delayMillis = 200)
    }

    fun setViewMode(mode: DirectoryViewMode) {
        savedStateHandle[STATE_VIEW_MODE] = mode.name
        _uiState.update { it.copy(viewMode = mode) }
    }

    fun setSortOrder(order: DirectorySortOrder) {
        preferences.saveDirectorySortOrder(order)
        _uiState.update { it.copy(sortOrder = order) }
        scanDirectory()
    }

    fun openDirectory(entry: DirectoryEntry) {
        if (!entry.isDirectory) return

        openDirectoryPath(entry.path)
    }

    fun openDirectoryPath(path: String) {
        val directory = File(path)
        if (!directory.exists() || !directory.isDirectory) return
        val state = _uiState.value
        val root = if (directory.isSameOrChildOf(state.rootDirectory)) state.rootDirectory else directory
        savedStateHandle[STATE_DIRECTORY] = directory.absolutePath
        savedStateHandle[STATE_SEARCH] = ""
        _uiState.update {
            it.copy(
                rootDirectory = root,
                currentDirectory = directory,
                searchQuery = "",
                canNavigateUp = directory.isDifferentDirectory(root),
            )
        }
        saveLastDirectory(directory)
        scanDirectory()
    }

    fun navigateUp() {
        val state = _uiState.value
        val parentDirectory = state.currentDirectory.parentFile ?: return
        if (parentDirectory.isSameOrChildOf(state.rootDirectory)) {
            savedStateHandle[STATE_DIRECTORY] = parentDirectory.absolutePath
            savedStateHandle[STATE_SEARCH] = ""
            _uiState.update {
                it.copy(
                    currentDirectory = parentDirectory,
                    searchQuery = "",
                    canNavigateUp = parentDirectory.isDifferentDirectory(state.rootDirectory),
                )
            }
            saveLastDirectory(parentDirectory)
            scanDirectory()
        }
    }

    fun refresh() {
        _uiState.update {
            it.copy(
                showHiddenFiles = preferences.getShowHiddenFiles(),
                recentDirectories = preferences.getRecentDirectories().validDirectories(),
                lastMediaSession = preferences.getLastMediaSession()?.takeIfValid(),
            )
        }
        scanDirectory()
    }

    private fun saveLastDirectory(directory: File) {
        preferences.saveLastDirectory(directory.absolutePath)
    }

    private fun scanDirectory(delayMillis: Long = 0L) {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            if (delayMillis > 0) delay(delayMillis)
            val request = _uiState.value
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val listing = withContext(Dispatchers.IO) {
                request.currentDirectory.fileExplorerEntries(
                    searchQuery = request.searchQuery,
                    sortOrder = request.sortOrder,
                    showHiddenFiles = request.showHiddenFiles,
                )
            }
            _uiState.update { current ->
                if (current.currentDirectory.absolutePath != request.currentDirectory.absolutePath ||
                    current.searchQuery != request.searchQuery || current.sortOrder != request.sortOrder
                ) current else current.copy(
                    directoryListing = listing,
                    isLoading = false,
                    errorMessage = listing.errorMessage,
                )
            }
        }
    }

    private companion object {
        const val STATE_DIRECTORY = "directory_path"
        const val STATE_SEARCH = "directory_search"
        const val STATE_VIEW_MODE = "directory_view_mode"
    }
}

data class DirectoryUiState(
    val rootDirectory: File = Environment.getExternalStorageDirectory(),
    val currentDirectory: File = rootDirectory,
    val searchQuery: String = "",
    val directoryListing: DirectoryListing = DirectoryListing(entries = emptyList()),
    val viewMode: DirectoryViewMode = DirectoryViewMode.List,
    val sortOrder: DirectorySortOrder = DirectorySortOrder.NameAscending,
    val canNavigateUp: Boolean = false,
    val recentDirectories: List<String> = emptyList(),
    val lastMediaSession: LastMediaSession? = null,
    val showHiddenFiles: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
) {
    val entries: List<DirectoryEntry>
        get() = directoryListing.entries

    val canReadCurrentDirectory: Boolean
        get() = directoryListing.canRead
}

data class DirectoryListing(
    val entries: List<DirectoryEntry>,
    val canRead: Boolean = true,
    val errorMessage: String? = null,
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
    Archive,
    Other,
}

private suspend fun File.fileExplorerEntries(
    searchQuery: String = "",
    sortOrder: DirectorySortOrder = DirectorySortOrder.NameAscending,
    showHiddenFiles: Boolean = false,
): DirectoryListing {
    val normalizedQuery = searchQuery.trim()
    return runCatching {
        val context = currentCoroutineContext()
        val files = listFiles() ?: return@runCatching DirectoryListing(
            entries = emptyList(),
            canRead = false,
        )

        DirectoryListing(
            entries = files.asSequence()
            ?.onEach { context.ensureActive() }
            ?.filter { showHiddenFiles || !it.isHidden }
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
                .orEmpty(),
            canRead = canRead() || files.isNotEmpty(),
        )
    }.getOrElse { error ->
        DirectoryListing(
            entries = emptyList(),
            canRead = false,
            errorMessage = error.message ?: "Unable to read this directory.",
        )
    }
}

private fun File.isSameOrChildOf(root: File): Boolean {
    val rootPath = root.canonicalPathOrAbsolute()
    val filePath = canonicalPathOrAbsolute()
    return filePath == rootPath || filePath.startsWith("$rootPath${File.separator}")
}

private fun List<String>.validDirectories(): List<String> =
    filter { File(it).exists() && File(it).isDirectory }

private fun LastMediaSession.takeIfValid(): LastMediaSession? =
    takeIf {
        File(directoryPath).exists() &&
            File(directoryPath).isDirectory &&
            (mediaPath == null || if (mediaPath.contains("::")) {
                File(mediaPath.substringBefore("::")).exists()
            } else {
                File(mediaPath).exists()
            })
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
        "zip", "cbz", "rar", "cbr" -> DirectoryMediaType.Archive
        else -> DirectoryMediaType.Other
    }
}
