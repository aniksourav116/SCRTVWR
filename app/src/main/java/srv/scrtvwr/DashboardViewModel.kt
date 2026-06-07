package srv.scrtvwr

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
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
    val currentDestination: AppDestination = AppDestination.DASHBOARD,
    val destinations: List<AppDestination> = AppDestination.entries,
)

enum class AppDestination(
    @param:StringRes val label: Int,
    val icon: ImageVector,
) {
    DASHBOARD(R.string.dashboard, Icons.Default.Dashboard),
    LIBRARY(R.string.library, Icons.Default.VideoLibrary),
    SETTINGS(R.string.settings, Icons.Default.Settings),
}
