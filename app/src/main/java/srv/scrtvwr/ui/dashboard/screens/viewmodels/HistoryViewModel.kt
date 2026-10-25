package srv.scrtvwr.ui.dashboard.screens.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import srv.scrtvwr.media.MediaPaths
import srv.scrtvwr.media.remote.HttpServer
import srv.scrtvwr.media.remote.RemotePaths
import srv.scrtvwr.media.remote.serverFor
import srv.scrtvwr.preferences.MediaHistorySnapshot
import srv.scrtvwr.preferences.ScrtvwrPreferences
import java.io.File

class HistoryViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {
    private val preferences = ScrtvwrPreferences(application)
    private val _uiState = MutableStateFlow(
        historyState(),
    )
    val uiState = _uiState.asStateFlow()
    // What the last removal took away, kept until its Undo is used or dismissed.
    private var undoSnapshot: MediaHistorySnapshot? = null
    private var nextUndoId = 0L

    fun selectFilter(filter: HistoryFilter) {
        savedStateHandle[STATE_FILTER] = filter.name
        _uiState.update { it.copy(selectedFilter = filter) }
    }

    fun refresh() {
        _uiState.value = historyState(_uiState.value.selectedFilter).copy(undo = _uiState.value.undo)
    }

    fun removeItem(item: HistoryDirectoryItem) {
        if (_uiState.value.selectedFilter == HistoryFilter.FAVORITES) {
            offerUndo(HistoryUndoMessage.FavoriteRemoved)
            preferences.removeBookmarkedMediaDirectory(item.path)
        } else {
            offerUndo(HistoryUndoMessage.HistoryItemRemoved)
            preferences.removeMediaHistoryDirectory(item.path)
        }
        refresh()
    }

    fun clearVisibleItems() {
        when (_uiState.value.selectedFilter) {
            HistoryFilter.ALL,
            HistoryFilter.RECENT -> {
                offerUndo(HistoryUndoMessage.HistoryCleared)
                preferences.clearMediaHistory()
            }
            HistoryFilter.FAVORITES -> {
                offerUndo(HistoryUndoMessage.FavoritesCleared)
                preferences.clearBookmarkedMedia()
            }
        }
        refresh()
    }

    /** Puts back what the removal shown as [undoId] took away. */
    fun undo(undoId: Long) {
        if (_uiState.value.undo?.id != undoId) return
        undoSnapshot?.let(preferences::restoreMediaHistorySnapshot)
        undoSnapshot = null
        _uiState.update { it.copy(undo = null) }
        refresh()
    }

    fun dismissUndo(undoId: Long) {
        if (_uiState.value.undo?.id != undoId) return
        undoSnapshot = null
        _uiState.update { it.copy(undo = null) }
    }

    // Taken before each removal, so Undo always puts back the latest one.
    private fun offerUndo(message: HistoryUndoMessage) {
        undoSnapshot = preferences.getMediaHistorySnapshot()
        _uiState.update { it.copy(undo = HistoryUndo(id = ++nextUndoId, message = message)) }
    }

    private fun historyState(
        filter: HistoryFilter = savedStateHandle.get<String>(STATE_FILTER)
            ?.let { value -> HistoryFilter.entries.firstOrNull { it.name == value } }
            ?: HistoryFilter.ALL,
    ): HistoryUiState {
        val servers = preferences.getConnectedHttpServers()
        return HistoryUiState(
            selectedFilter = filter,
            directories = preferences.getMediaHistory().mediaHistoryItems(servers),
            favoriteDirectories = preferences.getBookmarkedMedia()
                .mapNotNull(String::bookmarkSession)
                .distinctBy { it.directoryPath }
                .mediaHistoryItems(servers),
            loadVideoThumbnails = preferences.getLoadVideoThumbnails(),
        )
    }

    private companion object {
        const val STATE_FILTER = "history_filter"
    }
}

data class HistoryUiState(
    val selectedFilter: HistoryFilter = HistoryFilter.ALL,
    val directories: List<HistoryDirectoryItem> = emptyList(),
    val favoriteDirectories: List<HistoryDirectoryItem> = emptyList(),
    /** Whether server videos show their first frame; local videos always do. */
    val loadVideoThumbnails: Boolean = true,
    /** The latest removal, while its Undo is offered. */
    val undo: HistoryUndo? = null,
) {
    val visibleDirectories: List<HistoryDirectoryItem>
        get() = when (selectedFilter) {
            HistoryFilter.ALL,
            HistoryFilter.RECENT -> directories

            HistoryFilter.FAVORITES -> favoriteDirectories
        }

    val emptyMessage: String
        get() = if (selectedFilter == HistoryFilter.FAVORITES) {
            "No favorites history yet."
        } else {
            "No viewed directories yet."
        }
}

data class HistoryDirectoryItem(
    val name: String,
    val path: String,
    val mediaPath: String? = null,
)

data class HistoryUndo(
    val id: Long,
    val message: HistoryUndoMessage,
)

enum class HistoryUndoMessage {
    HistoryItemRemoved,
    FavoriteRemoved,
    HistoryCleared,
    FavoritesCleared,
}

enum class HistoryFilter(val label: String) {
    ALL("All"),
    RECENT("Recent"),
    FAVORITES("Favorites"),
}

private fun List<srv.scrtvwr.preferences.LastMediaSession>.mediaHistoryItems(
    servers: List<HttpServer>,
): List<HistoryDirectoryItem> =
    mapNotNull { session ->
        val mediaPath = session.mediaPath ?: return@mapNotNull null
        if (MediaPaths.isRemote(session.directoryPath)) {
            // Checking every entry would mean a request per row; keep those on connected servers.
            if (servers.serverFor(session.directoryPath) == null) return@mapNotNull null
            return@mapNotNull HistoryDirectoryItem(
                name = RemotePaths.name(session.directoryPath),
                path = session.directoryPath,
                mediaPath = mediaPath,
            )
        }
        val directory = File(session.directoryPath)
        val mediaFile = File(mediaPath.substringBefore("::"))
        if (!directory.exists() || !directory.isDirectory || !mediaFile.isFile) {
            return@mapNotNull null
        }
            HistoryDirectoryItem(
                name = if (mediaPath.contains("::")) {
                    mediaFile.name.ifBlank { mediaFile.absolutePath }
                } else {
                    directory.name.ifBlank { directory.absolutePath }
                },
                path = directory.absolutePath,
                mediaPath = mediaPath,
            )
    }

private fun String.bookmarkSession(): srv.scrtvwr.preferences.LastMediaSession? {
    val source = substringBefore("::")
    val directory = MediaPaths.parent(source) ?: return null
    return srv.scrtvwr.preferences.LastMediaSession(directory, this)
}
