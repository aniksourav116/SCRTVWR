package srv.scrtvwr.ui.dashboard.screens.viewmodels

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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import srv.scrtvwr.R
import srv.scrtvwr.media.MediaCatalog
import srv.scrtvwr.preferences.LastMediaSession
import srv.scrtvwr.preferences.ScrtvwrPreferences
import srv.scrtvwr.ui.sort.DirectorySortOrder
import srv.scrtvwr.ui.sort.fileComparator
import java.io.File

class DirectoryViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {
    private val app = application
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
            mediaFilter = savedStateHandle.get<String>(STATE_MEDIA_FILTER)
                ?.let { value -> DirectoryMediaFilter.entries.firstOrNull { it.name == value } }
                ?: DirectoryMediaFilter.All,
            sortOrder = preferences.getDirectorySortOrder(),
            showHiddenFiles = preferences.getShowHiddenFiles(),
            showDirectoryFileActions = preferences.getShowDirectoryFileActions(),
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

    fun setMediaFilter(filter: DirectoryMediaFilter) {
        savedStateHandle[STATE_MEDIA_FILTER] = filter.name
        _uiState.update { it.copy(mediaFilter = filter) }
        scanDirectory()
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
                showDirectoryFileActions = preferences.getShowDirectoryFileActions(),
                recentDirectories = preferences.getRecentDirectories().validDirectories(),
                lastMediaSession = preferences.getLastMediaSession()?.takeIfValid(),
            )
        }
        scanDirectory()
    }

    fun prepareCopy(entry: DirectoryEntry) {
        prepareFileOperation(entry, DirectoryFileOperation.Copy)
    }

    fun prepareMove(entry: DirectoryEntry) {
        prepareFileOperation(entry, DirectoryFileOperation.Move)
    }

    fun cancelFileOperation() {
        _uiState.update { it.copy(pendingFileOperation = null) }
    }

    fun clearFileOperationStatus() {
        _uiState.update { it.copy(fileOperationStatus = null) }
    }

    fun pastePendingFileOperation() {
        val currentState = _uiState.value
        if (currentState.isFileOperationRunning) return
        if (!currentState.showDirectoryFileActions) return
        val operation = currentState.pendingFileOperation ?: return
        val destinationDirectory = currentState.currentDirectory
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isFileOperationRunning = true,
                    fileOperationStatus = null,
                )
            }
            val result = withContext(Dispatchers.IO) {
                operation.perform(destinationDirectory, app)
            }
            _uiState.update { state ->
                state.copy(
                    pendingFileOperation = if (result.isError) state.pendingFileOperation else null,
                    isFileOperationRunning = false,
                    fileOperationStatus = result,
                )
            }
            scanDirectory()
        }
    }

    fun deleteEntry(entry: DirectoryEntry) {
        val currentState = _uiState.value
        if (currentState.isFileOperationRunning || !currentState.showDirectoryFileActions) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isFileOperationRunning = true,
                    fileOperationStatus = null,
                )
            }
            val result = withContext(Dispatchers.IO) {
                File(entry.path).deleteEntry(entry.name, app)
            }
            _uiState.update {
                it.copy(
                    pendingFileOperation = if (!result.isError && it.pendingFileOperation?.path == entry.path) {
                        null
                    } else {
                        it.pendingFileOperation
                    },
                    isFileOperationRunning = false,
                    fileOperationStatus = result,
                )
            }
            scanDirectory()
        }
    }

    private fun prepareFileOperation(entry: DirectoryEntry, operation: DirectoryFileOperation) {
        _uiState.update { state ->
            if (state.isFileOperationRunning || !state.showDirectoryFileActions) state else state.copy(
                pendingFileOperation = PendingDirectoryFileOperation(
                    operation = operation,
                    name = entry.name,
                    path = entry.path,
                    isDirectory = entry.isDirectory,
                ),
                fileOperationStatus = null,
            )
        }
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
                    mediaFilter = request.mediaFilter,
                )
            }
            _uiState.update { current ->
                if (current.currentDirectory.absolutePath != request.currentDirectory.absolutePath ||
                    current.searchQuery != request.searchQuery || current.sortOrder != request.sortOrder ||
                    current.mediaFilter != request.mediaFilter
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
        const val STATE_MEDIA_FILTER = "directory_media_filter"
    }
}

data class DirectoryUiState(
    val rootDirectory: File = Environment.getExternalStorageDirectory(),
    val currentDirectory: File = rootDirectory,
    val searchQuery: String = "",
    val directoryListing: DirectoryListing = DirectoryListing(entries = emptyList()),
    val viewMode: DirectoryViewMode = DirectoryViewMode.List,
    val mediaFilter: DirectoryMediaFilter = DirectoryMediaFilter.All,
    val sortOrder: DirectorySortOrder = DirectorySortOrder.NameAscending,
    val canNavigateUp: Boolean = false,
    val recentDirectories: List<String> = emptyList(),
    val lastMediaSession: LastMediaSession? = null,
    val showHiddenFiles: Boolean = false,
    val showDirectoryFileActions: Boolean = false,
    val isLoading: Boolean = false,
    val isFileOperationRunning: Boolean = false,
    val pendingFileOperation: PendingDirectoryFileOperation? = null,
    val fileOperationStatus: DirectoryFileOperationStatus? = null,
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

data class PendingDirectoryFileOperation(
    val operation: DirectoryFileOperation,
    val name: String,
    val path: String,
    val isDirectory: Boolean,
)

enum class DirectoryFileOperation {
    Copy,
    Move,
}

data class DirectoryFileOperationStatus(
    val message: String,
    val isError: Boolean,
)

enum class DirectoryViewMode {
    List,
    Grid,
}

enum class DirectoryMediaFilter {
    All,
    Folders,
    Images,
    Videos,
    Archives,
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
    mediaFilter: DirectoryMediaFilter = DirectoryMediaFilter.All,
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
            ?.filter { file ->
                when (mediaFilter) {
                    DirectoryMediaFilter.All -> true
                    DirectoryMediaFilter.Folders -> file.isDirectory
                    DirectoryMediaFilter.Images -> file.mediaType() == DirectoryMediaType.Image
                    DirectoryMediaFilter.Videos -> file.mediaType() == DirectoryMediaType.Video
                    DirectoryMediaFilter.Archives -> file.mediaType() == DirectoryMediaType.Archive
                }
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

private fun PendingDirectoryFileOperation.perform(
    destinationDirectory: File,
    app: Application,
): DirectoryFileOperationStatus {
    val source = File(path)
    if (!source.exists()) {
        return operation.failure(app, name, app.getString(R.string.file_operation_source_missing))
    }
    if (!destinationDirectory.exists() || !destinationDirectory.isDirectory) {
        return operation.failure(app, name, app.getString(R.string.file_operation_destination_unavailable))
    }

    return when (operation) {
        DirectoryFileOperation.Copy -> source.copyInto(destinationDirectory, name, app)
        DirectoryFileOperation.Move -> source.moveInto(destinationDirectory, name, app)
    }
}

private fun File.copyInto(
    destinationDirectory: File,
    displayName: String,
    app: Application,
): DirectoryFileOperationStatus = runCatching {
    val destination = destinationDirectory.uniqueCopyDestinationFor(this)
    if (isDirectory && destination.isSameOrChildOf(this)) {
        error(app.getString(R.string.file_operation_destination_inside_source))
    }

    if (isDirectory) {
        if (!copyRecursively(destination, overwrite = false)) {
            error(app.getString(R.string.file_operation_copy_failed_generic))
        }
    } else {
        copyTo(destination, overwrite = false)
    }
}.fold(
    onSuccess = {
        DirectoryFileOperationStatus(
            message = app.getString(R.string.copy_complete, displayName),
            isError = false,
        )
    },
    onFailure = { error ->
        DirectoryFileOperation.Copy.failure(app, displayName, error.userMessage(app))
    },
)

private fun File.moveInto(
    destinationDirectory: File,
    displayName: String,
    app: Application,
): DirectoryFileOperationStatus = runCatching {
    if (isDirectory && destinationDirectory.isSameOrChildOf(this)) {
        error(app.getString(R.string.file_operation_destination_inside_source))
    }

    val destination = File(destinationDirectory, name)
    if (destination.exists()) {
        error(app.getString(R.string.file_operation_destination_exists))
    }

    if (!renameTo(destination)) {
        if (isDirectory) {
            if (!copyRecursively(destination, overwrite = false)) {
                error(app.getString(R.string.file_operation_copy_failed_generic))
            }
            if (!deleteRecursively() && exists()) {
                error(app.getString(R.string.file_operation_original_delete_failed))
            }
        } else {
            copyTo(destination, overwrite = false)
            if (!delete() && exists()) {
                error(app.getString(R.string.file_operation_original_delete_failed))
            }
        }
    }
}.fold(
    onSuccess = {
        DirectoryFileOperationStatus(
            message = app.getString(R.string.move_complete, displayName),
            isError = false,
        )
    },
    onFailure = { error ->
        DirectoryFileOperation.Move.failure(app, displayName, error.userMessage(app))
    },
)

private fun File.deleteEntry(
    displayName: String,
    app: Application,
): DirectoryFileOperationStatus = runCatching {
    if (!exists()) {
        error(app.getString(R.string.file_operation_source_missing))
    }

    val deleted = if (isDirectory) deleteRecursively() else delete()
    if (!deleted && exists()) {
        error(app.getString(R.string.file_operation_delete_failed_generic))
    }
}.fold(
    onSuccess = {
        DirectoryFileOperationStatus(
            message = app.getString(R.string.delete_complete, displayName),
            isError = false,
        )
    },
    onFailure = { error ->
        DirectoryFileOperationStatus(
            message = app.getString(R.string.delete_failed, displayName, error.userMessage(app)),
            isError = true,
        )
    },
)

private fun DirectoryFileOperation.failure(
    app: Application,
    displayName: String,
    reason: String,
): DirectoryFileOperationStatus =
    DirectoryFileOperationStatus(
        message = app.getString(
            when (this) {
                DirectoryFileOperation.Copy -> R.string.copy_failed
                DirectoryFileOperation.Move -> R.string.move_failed
            },
            displayName,
            reason,
        ),
        isError = true,
    )

private fun File.uniqueCopyDestinationFor(source: File): File {
    val initial = File(this, source.name)
    if (!initial.exists()) return initial

    val hasExtension = source.isFile && source.extension.isNotBlank() && source.nameWithoutExtension.isNotBlank()
    val baseName = if (hasExtension) source.nameWithoutExtension else source.name
    val extension = if (hasExtension) ".${source.extension}" else ""
    var index = 1
    while (true) {
        val suffix = if (index == 1) " copy" else " copy $index"
        val candidate = File(this, "$baseName$suffix$extension")
        if (!candidate.exists()) return candidate
        index++
    }
}

private fun Throwable.userMessage(app: Application): String =
    localizedMessage?.takeIf(String::isNotBlank)
        ?: message?.takeIf(String::isNotBlank)
        ?: app.getString(R.string.unknown)

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
        else -> if (MediaCatalog.isArchive(path)) DirectoryMediaType.Archive else DirectoryMediaType.Other
    }
}
