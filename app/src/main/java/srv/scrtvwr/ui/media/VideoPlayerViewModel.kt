package srv.scrtvwr.ui.media

import android.app.Application
import android.os.Environment
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

    /** Records [path] as the video on screen, for the saved session and a recreated screen. */
    fun selectVideo(path: String) {
        val state = _uiState.value
        if (state.selectedPath == path) return
        savedStateHandle[STATE_SELECTED] = path
        state.directoryPath?.let { preferences.saveLastMediaSession(it, path) }
        _uiState.update { it.copy(selectedPath = path) }
    }

    private fun load(directoryPath: String?, selectedPath: String?) {
        loadJob?.cancel()
        savedStateHandle[STATE_DIRECTORY] = directoryPath
        savedStateHandle[STATE_SELECTED] = selectedPath
        _uiState.update { it.copy(isLoading = true) }
        val sortOrder = preferences.getDirectorySortOrder()
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            val directory = if (MediaPaths.isRemote(directoryPath ?: selectedPath)) {
                loadRemoteVideoDirectory(directoryPath, selectedPath, sortOrder)
            } else {
                loadVideoDirectory(directoryPath, selectedPath, sortOrder, traversalRoot)
            }
            ensureActive()
            _uiState.update {
                VideoPlayerUiState(
                    loadId = it.loadId + 1,
                    directoryPath = directory.path ?: directoryPath,
                    videos = directory.videos,
                    selectedPath = selectedPath?.takeIf { path -> directory.videos.any { video -> video.path == path } }
                        ?: directory.videos.firstOrNull()?.path,
                    previousDirectory = directory.previousDirectory,
                    nextDirectory = directory.nextDirectory,
                    isLoading = false,
                )
            }
        }
    }

    /** [loadVideoDirectory] for a folder on an HTTP server, hopping only within that server. */
    private suspend fun loadRemoteVideoDirectory(
        directoryPath: String?,
        selectedPath: String?,
        sortOrder: DirectorySortOrder,
    ): VideoDirectory {
        val directory = directoryPath?.takeIf(MediaPaths::isRemote)?.let(RemotePaths::directoryUrl)
            ?: selectedPath?.let(RemotePaths::parent)
            ?: return VideoDirectory(path = null, videos = emptyList(), previousDirectory = null, nextDirectory = null)
        val videos = remoteMedia(directory, sortOrder).videos()
        val fallbackVideos = videos.ifEmpty {
            selectedPath?.let(MediaCatalog::remoteItem)?.let(::listOf).orEmpty()
        }
        val root = RemotePaths.resolveRoot(directory, preferences.getHttpServers().serverFor(directory)?.baseUrl)
        return VideoDirectory(
            path = directory,
            videos = fallbackVideos,
            previousDirectory = firstRemoteMediaDirectory(
                from = directory,
                candidates = DirectoryTraversal.backward(RemoteDirectoryTree, directory, root, sortOrder),
                sortOrder = sortOrder,
            ),
            nextDirectory = firstRemoteMediaDirectory(
                from = directory,
                candidates = DirectoryTraversal.forward(RemoteDirectoryTree, directory, root, sortOrder),
                sortOrder = sortOrder,
            ),
        )
    }

    private companion object {
        const val STATE_DIRECTORY = "video_directory"
        const val STATE_SELECTED = "video_selected"
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
    val isLoading: Boolean = true,
)

/** A folder next to the player's, opened at [selectedMediaPath]: in the player, or in the viewer for images. */
data class VideoDirectoryTarget(
    val name: String,
    val path: String,
    val type: VideoDirectoryType,
    val selectedMediaPath: String,
)

enum class VideoDirectoryType { Video, Image }

private class VideoDirectory(
    val path: String?,
    val videos: List<MediaViewerItem>,
    val previousDirectory: VideoDirectoryTarget?,
    val nextDirectory: VideoDirectoryTarget?,
)

private fun loadVideoDirectory(
    directoryPath: String?,
    selectedPath: String?,
    sortOrder: DirectorySortOrder,
    traversalRoot: File?,
): VideoDirectory {
    val directory = directoryPath?.let(::File)?.takeIf(File::isDirectory)
        ?: selectedPath?.let(::File)?.parentFile
    val videos = directory.listVideos(sortOrder)
    val fallbackVideos = videos.ifEmpty {
        selectedPath?.let(::File)?.takeIf(File::isFile)?.let(MediaCatalog::toItem)?.let(::listOf).orEmpty()
    }
    return VideoDirectory(
        path = directory?.absolutePath,
        videos = fallbackVideos,
        previousDirectory = directory?.findPreviousMediaDirectory(sortOrder, traversalRoot),
        nextDirectory = directory?.findNextMediaDirectory(sortOrder, traversalRoot),
    )
}

private fun File?.listVideos(sortOrder: DirectorySortOrder): List<MediaViewerItem> = this?.listFiles()
    ?.asSequence()
    ?.filter(File::isFile)
    ?.filter { MediaTypes.isVideo(it.name) }
    ?.sortedWith(sortOrder.fileComparator(directoriesFirst = false))
    ?.mapNotNull(MediaCatalog::toItem)
    ?.toList()
    .orEmpty()

private fun File.findNextMediaDirectory(
    sortOrder: DirectorySortOrder,
    traversalRoot: File?,
): VideoDirectoryTarget? = firstMediaDirectory(sortOrder, traversalRoot) { root ->
    DirectoryTraversal.forward(this, root, sortOrder)
}

private fun File.findPreviousMediaDirectory(
    sortOrder: DirectorySortOrder,
    traversalRoot: File?,
): VideoDirectoryTarget? = firstMediaDirectory(sortOrder, traversalRoot) { root ->
    DirectoryTraversal.backward(this, root, sortOrder)
}

/**
 * First directory holding playable media along [candidates], the depth-first walk starting here:
 * subfolders first, then following siblings, then the siblings of each ancestor up to the root.
 */
private fun File.firstMediaDirectory(
    sortOrder: DirectorySortOrder,
    traversalRoot: File?,
    candidates: (root: File) -> Sequence<File>,
): VideoDirectoryTarget? {
    val root = DirectoryTraversal.resolveRoot(this, traversalRoot)
    return candidates(root)
        .mapNotNull { candidate ->
            val name = DirectoryTraversal.displayName(this, candidate)
            val videos = candidate.listVideos(sortOrder)
            if (videos.isNotEmpty()) {
                VideoDirectoryTarget(
                    name = name,
                    path = candidate.absolutePath,
                    type = VideoDirectoryType.Video,
                    selectedMediaPath = videos.first().path,
                )
            } else {
                candidate.listImages(sortOrder).firstOrNull()?.let { image ->
                    VideoDirectoryTarget(
                        name = name,
                        path = candidate.absolutePath,
                        type = VideoDirectoryType.Image,
                        selectedMediaPath = image.absolutePath,
                    )
                }
            }
        }
        .firstOrNull()
}

/** Remote counterpart of [firstMediaDirectory]: videos win, otherwise the first image opens the viewer. */
private suspend fun firstRemoteMediaDirectory(
    from: String,
    candidates: Sequence<String>,
    sortOrder: DirectorySortOrder,
): VideoDirectoryTarget? {
    for (candidate in candidates) {
        currentCoroutineContext().ensureActive()
        val media = remoteMedia(candidate, sortOrder)
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

private suspend fun remoteMedia(directoryUrl: String, sortOrder: DirectorySortOrder): List<MediaViewerItem> =
    try {
        MediaCatalog.listRemoteDirectory(directoryUrl, sortOrder)
    } catch (_: IOException) {
        emptyList()
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
