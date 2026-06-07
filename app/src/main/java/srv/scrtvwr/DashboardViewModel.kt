package srv.scrtvwr

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Storage
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class DashboardViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState = _uiState.asStateFlow()

    fun select(destination: AppDestination) {
        _uiState.update { it.copy(currentDestination = destination) }
    }
}

data class DashboardUiState(
    val currentDestination: AppDestination = AppDestination.STORAGE,
    val destinations: List<AppDestination> = AppDestination.entries,
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
