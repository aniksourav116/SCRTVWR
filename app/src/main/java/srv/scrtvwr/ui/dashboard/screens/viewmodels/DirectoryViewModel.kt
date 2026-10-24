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
import srv.scrtvwr.media.MediaPaths
import srv.scrtvwr.media.MediaTypes
import srv.scrtvwr.media.remote.HttpDirectoryClient
import srv.scrtvwr.media.remote.HttpServer
import srv.scrtvwr.media.remote.RemoteEntry
import srv.scrtvwr.media.remote.RemotePaths
import srv.scrtvwr.media.remote.remoteEntryComparator
import srv.scrtvwr.media.remote.serverFor
import srv.scrtvwr.preferences.LastMediaSession
import srv.scrtvwr.preferences.ScrtvwrPreferences
import srv.scrtvwr.ui.components.hasLocalNetworkAccess
import srv.scrtvwr.ui.sort.DirectorySortOrder
import srv.scrtvwr.ui.sort.fileComparator
import srv.scrtvwr.ui.sort.needsFileMetadata
import java.io.File
import java.io.IOException

class DirectoryViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {
    private val app = application
    private val defaultRootDirectory = Environment.getExternalStorageDirectory()
    private val preferences = ScrtvwrPreferences(application)
    private val initialDirectory = savedStateHandle.get<String>(STATE_DIRECTORY)
        ?.takeIf(::isOpenableDirectory)
        ?: preferences.getLastDirectory()
        ?.takeIf(::isOpenableDirectory)
        ?: defaultRootDirectory.absolutePath
    private val initialRoot = when {
        MediaPaths.isRemote(initialDirectory) -> serverRootFor(initialDirectory)
        File(initialDirectory).isSameOrChildOf(defaultRootDirectory) -> defaultRootDirectory.absolutePath
        else -> initialDirectory
    }
    private val _uiState = MutableStateFlow(
        DirectoryUiState(
            rootPath = initialRoot,
            currentPath = initialDirectory,
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
            recentDirectories = preferences.getRecentDirectories().validDirectories(preferences.getConnectedHttpServers()),
            lastMediaSession = preferences.getLastMediaSession()?.takeIfValid(preferences.getConnectedHttpServers()),
            isLoading = true,
        ),
    )
    val uiState: StateFlow<DirectoryUiState> = _uiState.asStateFlow()
    private var scanJob: Job? = null
    private var scanKey: DirectoryScanKey? = null
    private val scrollPositions = mutableMapOf<String, DirectoryScrollPosition>()

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
        val directory = if (MediaPaths.isRemote(path)) {
            // A server that is offline still opens; its listing explains why nothing loaded. A
            // disconnected one does not, until it is connected again from the storage screen.
            if (preferences.getConnectedHttpServers().serverFor(path) == null) return
            RemotePaths.directoryUrl(path)
        } else {
            File(path).takeIf { it.exists() && it.isDirectory }?.absolutePath ?: return
        }
        val state = _uiState.value
        val root = when {
            directory.isSameOrChildOf(state.rootPath) -> state.rootPath
            MediaPaths.isRemote(directory) -> serverRootFor(directory)
            else -> directory
        }
        savedStateHandle[STATE_DIRECTORY] = directory
        savedStateHandle[STATE_SEARCH] = ""
        _uiState.update {
            it.copy(
                rootPath = root,
                currentPath = directory,
                searchQuery = "",
                canNavigateUp = directory.isDifferentDirectory(root),
                directoryListing = DirectoryListing(entries = emptyList()),
                isLoading = true,
                errorMessage = null,
            )
        }
        saveLastDirectory(directory)
        scanDirectory()
    }

    fun navigateUp() {
        val state = _uiState.value
        val parentDirectory = MediaPaths.parent(state.currentPath) ?: return
        if (parentDirectory.isSameOrChildOf(state.rootPath)) {
            savedStateHandle[STATE_DIRECTORY] = parentDirectory
            savedStateHandle[STATE_SEARCH] = ""
            _uiState.update {
                it.copy(
                    currentPath = parentDirectory,
                    searchQuery = "",
                    canNavigateUp = parentDirectory.isDifferentDirectory(state.rootPath),
                    directoryListing = DirectoryListing(entries = emptyList()),
                    isLoading = true,
                    errorMessage = null,
                )
            }
            saveLastDirectory(parentDirectory)
            scanDirectory()
        }
    }

    fun saveScrollPosition(directoryPath: String, position: DirectoryScrollPosition) {
        scrollPositions[directoryPath] = position.copy(
            firstVisibleItemIndex = position.firstVisibleItemIndex.coerceAtLeast(0),
            firstVisibleItemScrollOffset = position.firstVisibleItemScrollOffset.coerceAtLeast(0),
        )
    }

    fun scrollPositionFor(directoryPath: String): DirectoryScrollPosition? = scrollPositions[directoryPath]

    /**
     * Rereads preferences and the current directory. [reload] also bypasses the short-lived cache
     * of HTTP server listings, for an explicit refresh rather than a screen resuming.
     */
    fun refresh(reload: Boolean = false) {
        val servers = preferences.getConnectedHttpServers()
        val currentPath = _uiState.value.currentPath
        if (MediaPaths.isRemote(currentPath) && servers.serverFor(currentPath) == null) {
            // The server was disconnected or removed while this screen was away.
            openDirectoryPath(defaultRootDirectory.absolutePath)
        }
        _uiState.update {
            it.copy(
                showHiddenFiles = preferences.getShowHiddenFiles(),
                showDirectoryFileActions = preferences.getShowDirectoryFileActions(),
                recentDirectories = preferences.getRecentDirectories().validDirectories(servers),
                lastMediaSession = preferences.getLastMediaSession()?.takeIfValid(servers),
            )
        }
        // Creating, showing, and resuming the screen each refresh it. A scan of the same listing that
        // is still running is kept rather than restarted, so a large server folder downloads once.
        if (!reload && scanJob?.isActive == true && scanKey == _uiState.value.scanKey) return
        scanDirectory(reload = reload)
    }

    /**
     * Fills in the size and modification time of [entry], a file on an HTTP server that just came
     * into view. Suspends while the request runs, so cancel it once the entry leaves the screen.
     */
    suspend fun loadEntryDetails(entry: DirectoryEntry) {
        if (entry.isDirectory || !MediaPaths.isRemote(entry.path) || entry.sizeBytes != RemoteEntry.UNKNOWN_SIZE) return
        val remote = HttpDirectoryClient.withMetadata(RemoteEntry(name = entry.name, url = entry.path, isDirectory = false))
        if (remote.sizeBytes == entry.sizeBytes && remote.lastModifiedMillis == entry.lastModifiedMillis) return
        _uiState.update { state ->
            val index = state.entries.indexOfFirst { it.path == entry.path }
            if (index < 0) return@update state
            val entries = state.entries.toMutableList()
            entries[index] = entries[index].copy(
                sizeBytes = remote.sizeBytes,
                lastModifiedMillis = remote.lastModifiedMillis,
            )
            state.copy(directoryListing = state.directoryListing.copy(entries = entries))
        }
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
        if (!currentState.fileActionsAvailable) return
        val operation = currentState.pendingFileOperation ?: return
        val destinationDirectory = File(currentState.currentPath)
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
        if (currentState.isFileOperationRunning || !currentState.fileActionsAvailable) return
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
            if (state.isFileOperationRunning || !state.fileActionsAvailable) state else state.copy(
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

    private fun saveLastDirectory(directory: String) {
        preferences.saveLastDirectory(directory)
    }

    /** Whether a remembered directory can be reopened: a local folder, or a folder on a connected server. */
    private fun isOpenableDirectory(path: String): Boolean =
        if (MediaPaths.isRemote(path)) {
            preferences.getConnectedHttpServers().serverFor(path) != null
        } else {
            File(path).let { it.exists() && it.isDirectory }
        }

    /** Base URL of the saved server holding [url], so navigating up stops at the shared folder. */
    private fun serverRootFor(url: String): String =
        preferences.getHttpServers().serverFor(url)?.baseUrl ?: RemotePaths.directoryUrl(url)

    private fun scanDirectory(delayMillis: Long = 0L, reload: Boolean = false) {
        scanJob?.cancel()
        scanKey = _uiState.value.scanKey
        scanJob = viewModelScope.launch {
            if (delayMillis > 0) delay(delayMillis)
            val request = _uiState.value
            _uiState.update { it.copy(isLoading = true, isSortPending = false, errorMessage = null) }
            if (request.isRemote) {
                scanRemoteDirectory(request, reload)
                return@launch
            }
            val listing = withContext(Dispatchers.IO) {
                File(request.currentPath).fileExplorerEntries(
                    searchQuery = request.searchQuery,
                    sortOrder = request.sortOrder,
                    showHiddenFiles = request.showHiddenFiles,
                    mediaFilter = request.mediaFilter,
                )
            }
            publishListing(request, listing)
        }
    }

    /**
     * Shows the server listing as soon as it downloads. A date or size order needs every file's
     * HEAD request first, so until those finish the entries are shown by name and then re-sorted.
     */
    private suspend fun scanRemoteDirectory(request: DirectoryUiState, reload: Boolean) {
        val (entries, sortable) = try {
            withContext(Dispatchers.IO) {
                val listed = remoteExplorerEntries(
                    directoryUrl = request.currentPath,
                    searchQuery = request.searchQuery,
                    showHiddenFiles = request.showHiddenFiles,
                    mediaFilter = request.mediaFilter,
                    reload = reload,
                )
                listed to (!request.sortOrder.needsFileMetadata || listed.all(HttpDirectoryClient::hasMetadata))
            }
        } catch (error: IOException) {
            publishListing(request, remoteListingFailure(error, app))
            return
        }
        if (sortable) {
            publishListing(request, withContext(Dispatchers.Default) { entries.toDirectoryListing(request.sortOrder) })
            return
        }
        publishListing(
            request = request,
            listing = withContext(Dispatchers.Default) { entries.toDirectoryListing(DirectorySortOrder.NameAscending) },
            isSortPending = true,
        )
        val filled = HttpDirectoryClient.withMetadata(entries)
        publishListing(request, withContext(Dispatchers.Default) { filled.toDirectoryListing(request.sortOrder) })
    }

    private fun publishListing(request: DirectoryUiState, listing: DirectoryListing, isSortPending: Boolean = false) {
        _uiState.update { current ->
            if (current.currentPath != request.currentPath ||
                current.searchQuery != request.searchQuery || current.sortOrder != request.sortOrder ||
                current.mediaFilter != request.mediaFilter
            ) current else current.copy(
                directoryListing = listing,
                isLoading = false,
                isSortPending = isSortPending,
                errorMessage = listing.errorMessage,
            )
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
    /** Highest directory the browser navigates up to: a local path or an HTTP server's base URL. */
    val rootPath: String = Environment.getExternalStorageDirectory().absolutePath,
    val currentPath: String = rootPath,
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
    /** Server entries are shown by name while the sizes or dates the sort order needs still load. */
    val isSortPending: Boolean = false,
    val isFileOperationRunning: Boolean = false,
    val pendingFileOperation: PendingDirectoryFileOperation? = null,
    val fileOperationStatus: DirectoryFileOperationStatus? = null,
    val errorMessage: String? = null,
) {
    val entries: List<DirectoryEntry>
        get() = directoryListing.entries

    val canReadCurrentDirectory: Boolean
        get() = directoryListing.canRead

    val currentName: String
        get() = MediaPaths.name(currentPath)

    val isRemote: Boolean
        get() = MediaPaths.isRemote(currentPath)

    /** Copy, move, and delete only work on device storage; HTTP servers are browsed read-only. */
    val fileActionsAvailable: Boolean
        get() = showDirectoryFileActions && !isRemote
}

/** What a scan lists. A refresh while a scan for the same key still runs leaves that scan running. */
private data class DirectoryScanKey(
    val path: String,
    val searchQuery: String,
    val sortOrder: DirectorySortOrder,
    val mediaFilter: DirectoryMediaFilter,
    val showHiddenFiles: Boolean,
)

private val DirectoryUiState.scanKey: DirectoryScanKey
    get() = DirectoryScanKey(currentPath, searchQuery, sortOrder, mediaFilter, showHiddenFiles)

data class DirectoryListing(
    val entries: List<DirectoryEntry>,
    val canRead: Boolean = true,
    val errorMessage: String? = null,
)

data class DirectoryScrollPosition(
    val firstVisibleItemIndex: Int,
    val firstVisibleItemScrollOffset: Int,
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

/**
 * Entries an HTTP server lists for [directoryUrl], filtered like local listings, with the sizes and
 * times fetched earlier filled in. Makes no request per file.
 */
@Throws(IOException::class)
private suspend fun remoteExplorerEntries(
    directoryUrl: String,
    searchQuery: String,
    showHiddenFiles: Boolean,
    mediaFilter: DirectoryMediaFilter,
    reload: Boolean,
): List<RemoteEntry> {
    val normalizedQuery = searchQuery.trim()
    val entries = HttpDirectoryClient.listForDisplay(directoryUrl, refresh = reload)
        .asSequence()
        .filter { showHiddenFiles || !it.isHidden }
        .filter { entry ->
            normalizedQuery.isBlank() || entry.name.contains(normalizedQuery, ignoreCase = true)
        }
        .filter { entry ->
            when (mediaFilter) {
                DirectoryMediaFilter.All -> true
                DirectoryMediaFilter.Folders -> entry.isDirectory
                DirectoryMediaFilter.Images -> entry.mediaType() == DirectoryMediaType.Image
                DirectoryMediaFilter.Videos -> entry.mediaType() == DirectoryMediaType.Video
                DirectoryMediaFilter.Archives -> entry.mediaType() == DirectoryMediaType.Archive
            }
        }
        .toList()
    return HttpDirectoryClient.withCachedMetadata(entries)
}

private fun List<RemoteEntry>.toDirectoryListing(sortOrder: DirectorySortOrder): DirectoryListing =
    DirectoryListing(
        entries = sortedWith(sortOrder.remoteEntryComparator(directoriesFirst = true))
            .map { entry ->
                DirectoryEntry(
                    name = entry.name,
                    path = entry.url,
                    isDirectory = entry.isDirectory,
                    sizeBytes = if (entry.isDirectory) 0L else entry.sizeBytes,
                    lastModifiedMillis = entry.lastModifiedMillis,
                    mediaType = entry.mediaType(),
                )
            },
    )

private fun remoteListingFailure(error: IOException, app: Application): DirectoryListing =
    DirectoryListing(
        entries = emptyList(),
        canRead = false,
        errorMessage = if (app.hasLocalNetworkAccess()) {
            app.getString(R.string.http_listing_failed, error.userMessage(app))
        } else {
            app.getString(R.string.http_listing_local_network_blocked)
        },
    )

private fun String.isSameOrChildOf(root: String): Boolean = when {
    MediaPaths.isRemote(this) && MediaPaths.isRemote(root) -> RemotePaths.isSameOrChildOf(this, root)
    MediaPaths.isRemote(this) || MediaPaths.isRemote(root) -> false
    else -> File(this).isSameOrChildOf(File(root))
}

private fun String.isDifferentDirectory(other: String): Boolean = when {
    MediaPaths.isRemote(this) && MediaPaths.isRemote(other) -> !RemotePaths.isSameDirectory(this, other)
    MediaPaths.isRemote(this) || MediaPaths.isRemote(other) -> true
    else -> File(this).isDifferentDirectory(File(other))
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

private fun List<String>.validDirectories(servers: List<HttpServer>): List<String> =
    filter { path ->
        if (MediaPaths.isRemote(path)) servers.serverFor(path) != null else File(path).exists() && File(path).isDirectory
    }

private fun LastMediaSession.takeIfValid(servers: List<HttpServer>): LastMediaSession? =
    takeIf {
        // Checking a server would mean a request on every resume, so trust sessions on saved servers.
        if (MediaPaths.isRemote(directoryPath)) return@takeIf servers.serverFor(directoryPath) != null
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

    return mediaTypeOf(name)
}

private fun RemoteEntry.mediaType(): DirectoryMediaType {
    if (isDirectory) return DirectoryMediaType.Other

    return mediaTypeOf(name)
}

private fun mediaTypeOf(name: String): DirectoryMediaType = when {
    MediaTypes.isImage(name) -> DirectoryMediaType.Image
    MediaTypes.isVideo(name) -> DirectoryMediaType.Video
    MediaTypes.isArchive(name) -> DirectoryMediaType.Archive
    else -> DirectoryMediaType.Other
}
