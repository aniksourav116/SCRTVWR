package srv.scrtvwr.ui.dashboard.screens.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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

    fun selectFilter(filter: HistoryFilter) {
        savedStateHandle[STATE_FILTER] = filter.name
        _uiState.update { it.copy(selectedFilter = filter) }
    }

    fun refresh() {
        _uiState.value = historyState(_uiState.value.selectedFilter)
    }

    private fun historyState(
        filter: HistoryFilter = savedStateHandle.get<String>(STATE_FILTER)
            ?.let { value -> HistoryFilter.entries.firstOrNull { it.name == value } }
            ?: HistoryFilter.ALL,
    ): HistoryUiState = HistoryUiState(
        selectedFilter = filter,
        directories = preferences.getMediaHistory().mediaHistoryItems(),
        favoriteDirectories = preferences.getBookmarkedMedia()
            .mapNotNull(String::bookmarkSession)
            .distinctBy { it.directoryPath }
            .mediaHistoryItems(),
    )

    private companion object {
        const val STATE_FILTER = "history_filter"
    }
}

data class HistoryUiState(
    val selectedFilter: HistoryFilter = HistoryFilter.ALL,
    val directories: List<HistoryDirectoryItem> = emptyList(),
    val favoriteDirectories: List<HistoryDirectoryItem> = emptyList(),
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

enum class HistoryFilter(val label: String) {
    ALL("All"),
    RECENT("Recent"),
    FAVORITES("Favorites"),
}

private fun List<srv.scrtvwr.preferences.LastMediaSession>.mediaHistoryItems(): List<HistoryDirectoryItem> =
    mapNotNull { session ->
        val directory = File(session.directoryPath)
        val mediaPath = session.mediaPath ?: return@mapNotNull null
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
    val directory = File(source).parentFile?.absolutePath ?: return null
    return srv.scrtvwr.preferences.LastMediaSession(directory, this)
}
