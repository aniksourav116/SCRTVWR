package srv.scrtvwr.ui.dashboard.screens.viewmodels

import android.app.Application
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import srv.scrtvwr.preferences.ScrtvwrPreferences
import java.io.File

class HistoryViewModel(application: Application) : AndroidViewModel(application) {
    private val rootDirectory = Environment.getExternalStorageDirectory()
    private val preferences = ScrtvwrPreferences(application)
    private val _uiState = MutableStateFlow(
        HistoryUiState(directories = preferences.getRecentDirectories().historyDirectoryItems(rootDirectory)),
    )
    val uiState = _uiState.asStateFlow()

    fun selectFilter(filter: HistoryFilter) {
        _uiState.update { it.copy(selectedFilter = filter) }
    }

    fun refresh() {
        _uiState.update { it.copy(directories = preferences.getRecentDirectories().historyDirectoryItems(rootDirectory)) }
    }
}

data class HistoryUiState(
    val selectedFilter: HistoryFilter = HistoryFilter.ALL,
    val directories: List<HistoryDirectoryItem> = emptyList(),
) {
    val visibleDirectories: List<HistoryDirectoryItem>
        get() = when (selectedFilter) {
            HistoryFilter.ALL,
            HistoryFilter.RECENT -> directories

            HistoryFilter.FAVORITES -> emptyList()
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
)

enum class HistoryFilter(val label: String) {
    ALL("All"),
    RECENT("Recent"),
    FAVORITES("Favorites"),
}

private fun List<String>.historyDirectoryItems(root: File): List<HistoryDirectoryItem> =
    map(::File)
        .filter { it.exists() && it.isDirectory && it.isSameOrChildOf(root) }
        .map { directory ->
            HistoryDirectoryItem(
                name = directory.name.ifBlank { directory.absolutePath },
                path = directory.absolutePath,
            )
        }

private fun File.isSameOrChildOf(root: File): Boolean {
    val rootPath = root.canonicalPathOrAbsolute()
    val filePath = canonicalPathOrAbsolute()
    return filePath == rootPath || filePath.startsWith("$rootPath${File.separator}")
}

private fun File.canonicalPathOrAbsolute(): String =
    runCatching { canonicalPath }.getOrElse { absolutePath }
