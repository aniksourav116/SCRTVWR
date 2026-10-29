package srv.scrtvwr.ui.media

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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import srv.scrtvwr.media.DirectoryTraversal
import srv.scrtvwr.media.MediaCatalog
import srv.scrtvwr.media.MediaPaths
import srv.scrtvwr.media.MediaTypes
import srv.scrtvwr.media.remote.RemoteDirectoryTree
import srv.scrtvwr.media.remote.RemotePaths
import srv.scrtvwr.media.remote.serverFor
import srv.scrtvwr.preferences.ScrtvwrPreferences
import srv.scrtvwr.ui.sort.DirectorySortOrder
import srv.scrtvwr.ui.sort.fileComparator
import srv.scrtvwr.ui.sort.needsFileMetadata
import java.io.File
import java.io.IOException

/**
 * The video player's folder: its videos, and the folders before and after it. Kept here rather
 * than in the screen so a rotation keeps the list instead of reading the folder again.
 */
class VideoPlayerViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {
    private val preferences = ScrtvwrPreferences(application)
    private val traversalRoot = runCatching { Environment.getExternalStorageDirectory() }.getOrNull()
    private var loadJob: Job? = null
    private var neighbourJob: Job? = null
    @Volatile private var sortJob: Job? = null
    private var timeJob: Job? = null
    private var neighbourSearchLoadId = -1
    // The folder the current list was read from, for finding the folders around it.
    @Volatile private var loadedFolder: LoadedFolder? = null
    @Volatile private var preparedFolder: PreparedFolder? = null
    private val _uiState = MutableStateFlow(VideoPlayerUiState())
    val uiState = _uiState.asStateFlow()

    /**
     * Loads the folder the screen was opened on, once per player. A recreated screen asks again
     * with the same extras, but browsing may have moved to another folder since, so that one stays.
     * After process death the folder and video last shown are loaded instead.
     */
    fun open(directoryPath: String?, selectedPath: String?) {
        if (loadJob != null) return
        if (savedStateHandle.contains(STATE_DIRECTORY)) {
            load(savedStateHandle[STATE_DIRECTORY], savedStateHandle[STATE_SELECTED])
        } else {
            load(directoryPath ?: selectedPath?.let(MediaPaths::parent), selectedPath)
        }
    }

    /** Opens the video folder [target], starting on the video it names. */
    fun moveTo(target: VideoDirectoryTarget) {
        load(target.path, target.selectedMediaPath)
    }

    /** Reads the folder again, after it could not be listed. */
    fun retry() {
        load(savedStateHandle[STATE_DIRECTORY], savedStateHandle[STATE_SELECTED])
    }

    /** Records [path] as the video on screen, for the saved session and a recreated screen. */
    fun selectVideo(path: String) {
        fetchTimeOf(path)
        val state = _uiState.value
        if (state.selectedPath == path) return
        savedStateHandle[STATE_SELECTED] = path
        state.directoryPath?.let { preferences.saveLastMediaSession(it, path) }
        _uiState.update { it.copy(selectedPath = path) }
    }

    /**
     * Asks the server when the video at [path] last changed, if its listing did not say. It waits
     * until the video has stayed a moment, so paging quickly past videos sends no requests.
     */
    private fun fetchTimeOf(path: String) {
        timeJob?.cancel()
        val state = _uiState.value
        val item = state.videos.firstOrNull { it.path == path } ?: return
        if (item.lastModifiedMillis > 0L || path in state.fetchedTimes) return
        timeJob = viewModelScope.launch(Dispatchers.IO) {
            delay(TIME_FETCH_DELAY_MILLIS)
            val time = MediaCatalog.remoteLastModifiedMillis(item) ?: return@launch
            _uiState.update { it.copy(fetchedTimes = it.fetchedTimes + (path to time)) }
        }
    }

    /**
     * Looks for the folders before and after this one, the next one first since browsing mostly
     * goes forward. The screen calls this once the video on screen has started or failed, or
     * sooner when a folder page is reached; each list is searched once.
     */
    fun findNeighbourDirectories() {
        val state = _uiState.value
        val folder = loadedFolder
        if (folder == null || state.isLoading || neighbourSearchLoadId == state.loadId) return
        val loadId = state.loadId
        neighbourSearchLoadId = loadId
        neighbourJob?.cancel()
        neighbourJob = viewModelScope.launch(Dispatchers.IO) {
            val nextDirectory = findNeighbour(folder, next = true)
            ensureActive()
            _uiState.update {
                if (it.loadId == loadId) it.copy(nextDirectory = nextDirectory, isNextDirectoryKnown = true) else it
            }
            // Image folders open in the viewer, which reads its own list.
            if (nextDirectory?.type == VideoDirectoryType.Video) prepareFolder(nextDirectory, folder.sortOrder)
            val previousDirectory = findNeighbour(folder, next = false)
            ensureActive()
            _uiState.update {
                if (it.loadId == loadId) {
                    it.copy(previousDirectory = previousDirectory, isPreviousDirectoryKnown = true)
                } else {
                    it
                }
            }
        }
    }

    private fun load(directoryPath: String?, selectedPath: String?) {
        loadJob?.cancel()
        neighbourJob?.cancel()
        sortJob?.cancel()
        neighbourSearchLoadId = -1
        savedStateHandle[STATE_DIRECTORY] = directoryPath
        savedStateHandle[STATE_SELECTED] = selectedPath
        _uiState.update { it.copy(isLoading = true) }
        val sortOrder = preferences.getDirectorySortOrder()
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            val isRemote = MediaPaths.isRemote(directoryPath ?: selectedPath)
            // Moving to the next folder finds its list already read; any other load drops it.
            val prepared = preparedFolder
            preparedFolder = null
            val folder = prepared?.takeIf { it.matches(directoryPath, selectedPath, sortOrder) }?.folder
                ?: readFolder(directoryPath, selectedPath, sortOrder)
            ensureActive()
            loadedFolder = folder.path?.let { LoadedFolder(it, isRemote, sortOrder) }
            // The neighbouring folders are left unknown: findNeighbourDirectories looks for them
            // once the video on screen has started, so the list shows without waiting for a walk.
            val hasNoFolder = folder.path == null
            _uiState.update {
                VideoPlayerUiState(
                    loadId = it.loadId + 1,
                    directoryPath = folder.path ?: directoryPath,
                    videos = folder.videos,
                    selectedPath = selectedPath?.takeIf { path -> folder.videos.any { video -> video.path == path } }
                        ?: folder.videos.firstOrNull()?.path,
                    isPreviousDirectoryKnown = hasNoFolder,
                    isNextDirectoryKnown = hasNoFolder,
                    isLoading = false,
                    isSorting = folder.needsSorting,
                    listingError = folder.listingError,
                )
            }
            if (folder.needsSorting) sortInPlace(sortOrder)
        }
    }

    /**
     * Fetches the sizes and times [sortOrder] compares for the server videos on screen, then puts
     * them in that order. The video playing keeps playing: pages follow their video, not their index.
     */
    private fun sortInPlace(sortOrder: DirectorySortOrder) {
        sortJob?.cancel()
        val state = _uiState.value
        val loadId = state.loadId
        sortJob = viewModelScope.launch(Dispatchers.IO) {
            val sorted = try {
                MediaCatalog.sorted(MediaCatalog.withRemoteMetadata(state.videos), sortOrder)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
                // The list stays in name order.
                null
            }
            ensureActive()
            _uiState.update {
                when {
                    it.loadId != loadId -> it
                    sorted == null -> it.copy(isSorting = false)
                    else -> it.copy(videos = sorted, isSorting = false)
                }
            }
        }
    }

    private suspend fun readFolder(directoryPath: String?, selectedPath: String?, sortOrder: DirectorySortOrder): VideoFolder =
        if (MediaPaths.isRemote(directoryPath ?: selectedPath)) {
            loadRemoteVideoFolder(directoryPath, selectedPath, sortOrder)
        } else {
            loadVideoFolder(directoryPath, selectedPath, sortOrder)
        }

    /** Reads the list of [target] ahead, for [load] to use when the player moves to it. */
    private suspend fun prepareFolder(target: VideoDirectoryTarget, sortOrder: DirectorySortOrder) {
        val folder = try {
            readFolder(target.path, target.selectedMediaPath, sortOrder)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            return
        }
        // Moving there reads it again, and reports what went wrong.
        if (folder.listingError != null) return
        currentCoroutineContext().ensureActive()
        preparedFolder = PreparedFolder(target.path, target.selectedMediaPath, sortOrder, folder)
    }

    private suspend fun findNeighbour(folder: LoadedFolder, next: Boolean): VideoDirectoryTarget? =
        try {
            if (folder.isRemote) {
                val root = RemotePaths.resolveRoot(
                    folder.path,
                    preferences.getHttpServers().serverFor(folder.path)?.baseUrl,
                )
                firstRemoteMediaDirectory(
                    from = folder.path,
                    candidates = if (next) {
                        DirectoryTraversal.forward(RemoteDirectoryTree, folder.path, root, folder.sortOrder)
                    } else {
                        DirectoryTraversal.backward(RemoteDirectoryTree, folder.path, root, folder.sortOrder)
                    },
                    sortOrder = folder.sortOrder,
                )
            } else {
                val directory = File(folder.path)
                val root = DirectoryTraversal.resolveRoot(directory, traversalRoot)
                directory.firstMediaDirectory(
                    sortOrder = folder.sortOrder,
                    candidates = if (next) {
                        DirectoryTraversal.forward(directory, root, folder.sortOrder)
                    } else {
                        DirectoryTraversal.backward(directory, root, folder.sortOrder)
                    },
                )
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            // A folder that cannot be walked from simply has no neighbour to offer.
            null
        }

    private companion object {
        const val STATE_DIRECTORY = "video_directory"
        const val STATE_SELECTED = "video_selected"
        const val TIME_FETCH_DELAY_MILLIS = 300L
    }
}

data class VideoPlayerUiState(
    /** Counts finished loads; a new value means a new list, whose page indexes start over. */
    val loadId: Int = 0,
    val directoryPath: String? = null,
    val videos: List<MediaViewerItem> = emptyList(),
    /** The video on screen. */
    val selectedPath: String? = null,
    val previousDirectory: VideoDirectoryTarget? = null,
    val nextDirectory: VideoDirectoryTarget? = null,
    /** Whether [previousDirectory] is the search's answer, rather than null because it has not finished. */
    val isPreviousDirectoryKnown: Boolean = false,
    /** Whether [nextDirectory] is the search's answer, rather than null because it has not finished. */
    val isNextDirectoryKnown: Boolean = false,
    val isLoading: Boolean = true,
    /** Set while the list waits for the file sizes and times its sort order compares. */
    val isSorting: Boolean = false,
    /** Why the server would not list the folder, when that left nothing to play. */
    val listingError: String? = null,
    /** Times of server videos whose listing gave none, fetched once shown, by path. */
    val fetchedTimes: Map<String, Long> = emptyMap(),
)

/** A folder next to the player's, opened at [selectedMediaPath]: in the player, or in the viewer for images. */
data class VideoDirectoryTarget(
    val name: String,
    val path: String,
    val type: VideoDirectoryType,
    val selectedMediaPath: String,
)

enum class VideoDirectoryType { Video, Image }

/** What a list was read from: an absolute folder path, or a folder URL on a server. */
private class LoadedFolder(val path: String, val isRemote: Boolean, val sortOrder: DirectorySortOrder)

/** The next folder's list, read ahead for the load that asks for the same paths and order. */
private class PreparedFolder(
    val directoryPath: String?,
    val selectedPath: String?,
    val sortOrder: DirectorySortOrder,
    val folder: VideoFolder,
) {
    fun matches(directoryPath: String?, selectedPath: String?, sortOrder: DirectorySortOrder): Boolean =
        directoryPath == this.directoryPath && selectedPath == this.selectedPath && sortOrder == this.sortOrder
}

private class VideoFolder(
    val path: String?,
    val videos: List<MediaViewerItem>,
    /** Whether [videos] are in name order only because their sizes and times were not known yet. */
    val needsSorting: Boolean = false,
    /** Why the server would not list the folder, when that left nothing to play. */
    val listingError: String? = null,
)

private fun loadVideoFolder(directoryPath: String?, selectedPath: String?, sortOrder: DirectorySortOrder): VideoFolder {
    val directory = directoryPath?.let(::File)?.takeIf(File::isDirectory)
        ?: selectedPath?.let(::File)?.parentFile
    val videos = directory.listVideos(sortOrder)
    val fallbackVideos = videos.ifEmpty {
        selectedPath?.let(::File)?.takeIf(File::isFile)?.let(MediaCatalog::toItem)?.let(::listOf).orEmpty()
    }
    return VideoFolder(path = directory?.absolutePath, videos = fallbackVideos)
}

/** [loadVideoFolder] for a folder on an HTTP server. */
private suspend fun loadRemoteVideoFolder(
    directoryPath: String?,
    selectedPath: String?,
    sortOrder: DirectorySortOrder,
): VideoFolder {
    val directory = directoryPath?.takeIf(MediaPaths::isRemote)?.let(RemotePaths::directoryUrl)
        ?: selectedPath?.let(RemotePaths::parent)
        ?: return VideoFolder(path = null, videos = emptyList())
    val listed = try {
        MediaCatalog.listRemoteDirectoryUnsorted(directory).videos()
    } catch (error: IOException) {
        // The tapped video can still be tried on its own; with nothing to play, the reason shows.
        val fallback = selectedPath?.let(MediaCatalog::remoteItem)?.let(::listOf).orEmpty()
        return VideoFolder(
            path = directory,
            videos = fallback,
            listingError = if (fallback.isEmpty()) error.message ?: error.javaClass.simpleName else null,
        )
    }
    // Sizes and times cost a request per file. Until they arrive the list shows in name order, the
    // order most folders are browsed in, and sortInPlace sorts it afterwards.
    val needsSorting = sortOrder.needsFileMetadata && !MediaCatalog.hasRemoteMetadata(listed)
    val videos = MediaCatalog.sorted(listed, if (needsSorting) DirectorySortOrder.NameAscending else sortOrder)
    val fallbackVideos = videos.ifEmpty {
        selectedPath?.let(MediaCatalog::remoteItem)?.let(::listOf).orEmpty()
    }
    return VideoFolder(path = directory, videos = fallbackVideos, needsSorting = needsSorting && videos.isNotEmpty())
}

private fun File?.listVideos(sortOrder: DirectorySortOrder): List<MediaViewerItem> = this?.listFiles()
    ?.asSequence()
    ?.filter(File::isFile)
    ?.filter { MediaTypes.isVideo(it.name) }
    ?.sortedWith(sortOrder.fileComparator(directoriesFirst = false))
    ?.mapNotNull(MediaCatalog::toItem)
    ?.toList()
    .orEmpty()

/**
 * First directory holding playable media along [candidates], the depth-first walk starting here:
 * subfolders first, then following siblings, then the siblings of each ancestor up to the root.
 */
private suspend fun File.firstMediaDirectory(
    sortOrder: DirectorySortOrder,
    candidates: Sequence<File>,
): VideoDirectoryTarget? {
    for (candidate in candidates) {
        currentCoroutineContext().ensureActive()
        val name = DirectoryTraversal.displayName(this, candidate)
        candidate.listVideos(sortOrder).firstOrNull()?.let { video ->
            return VideoDirectoryTarget(name, candidate.absolutePath, VideoDirectoryType.Video, video.path)
        }
        candidate.listImages(sortOrder).firstOrNull()?.let { image ->
            return VideoDirectoryTarget(name, candidate.absolutePath, VideoDirectoryType.Image, image.absolutePath)
        }
    }
    return null
}

/**
 * Remote counterpart of [firstMediaDirectory]: videos win, otherwise the first image opens the
 * viewer. Each folder costs only its listing. Sizes and times would cost a request per file, so a
 * folder's first file comes by name unless they are cached; the folder's own list sorts properly
 * once it is opened.
 */
private suspend fun firstRemoteMediaDirectory(
    from: String,
    candidates: Sequence<String>,
    sortOrder: DirectorySortOrder,
): VideoDirectoryTarget? {
    for (candidate in candidates) {
        currentCoroutineContext().ensureActive()
        val listed = try {
            MediaCatalog.listRemoteDirectoryUnsorted(candidate)
        } catch (_: IOException) {
            continue
        }
        if (listed.isEmpty()) continue
        val known = !sortOrder.needsFileMetadata || MediaCatalog.hasRemoteMetadata(listed)
        val media = MediaCatalog.sorted(listed, if (known) sortOrder else DirectorySortOrder.NameAscending)
        val name = DirectoryTraversal.displayName(RemoteDirectoryTree, from, candidate)
        media.videos().firstOrNull()?.let { video ->
            return VideoDirectoryTarget(name, candidate, VideoDirectoryType.Video, video.path)
        }
        media.firstOrNull { it.type == MediaViewerType.Image }?.let { image ->
            return VideoDirectoryTarget(name, candidate, VideoDirectoryType.Image, image.path)
        }
    }
    return null
}

private fun List<MediaViewerItem>.videos(): List<MediaViewerItem> =
    filter { it.type == MediaViewerType.Video }

private fun File.listImages(sortOrder: DirectorySortOrder): List<File> = listFiles()
    ?.asSequence()
    ?.filter(File::isFile)
    ?.filter { MediaTypes.isImage(it.name) }
    ?.sortedWith(sortOrder.fileComparator(directoriesFirst = false))
    ?.toList()
    .orEmpty()
