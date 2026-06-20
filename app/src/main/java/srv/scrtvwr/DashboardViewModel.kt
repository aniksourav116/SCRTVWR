package srv.scrtvwr

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Storage
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class DashboardViewModel(private val savedStateHandle: SavedStateHandle) : ViewModel() {
    private val _uiState = MutableStateFlow(
        DashboardUiState(
            currentDestination = savedStateHandle.get<String>(STATE_DESTINATION)
                ?.let { value -> AppDestination.entries.firstOrNull { it.name == value } }
                ?: AppDestination.STORAGE,
        ),
    )
    val uiState = _uiState.asStateFlow()

    fun select(destination: AppDestination) {
        savedStateHandle[STATE_DESTINATION] = destination.name
        _uiState.update { it.copy(currentDestination = destination) }
    }

    fun openDirectoryFromHistory(path: String) {
        openDirectory(path)
    }

    fun openDirectoryFromStorage(path: String) {
        openDirectory(path)
    }

    private fun openDirectory(path: String) {
        _uiState.update {
            it.copy(
                currentDestination = AppDestination.DIRECTORY,
                pendingDirectoryPath = path,
            )
        }
    }

    fun clearPendingDirectoryPath() {
        _uiState.update { it.copy(pendingDirectoryPath = null) }
    }

    private companion object {
        const val STATE_DESTINATION = "dashboard_destination"
    }
}

data class DashboardUiState(
    val currentDestination: AppDestination = AppDestination.STORAGE,
    val destinations: List<AppDestination> = AppDestination.entries,
    val pendingDirectoryPath: String? = null,
)

enum class AppDestination(
    @param:StringRes val label: Int,
    val icon: ImageVector,
) {
    STORAGE(R.string.storage, Icons.Default.Storage),
    DIRECTORY(R.string.directory, Icons.Default.Folder),
    HISTORY(R.string.history, Icons.Default.History),
    OTHERS(R.string.others, Icons.Default.MoreHoriz),
}
