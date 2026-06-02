package srv.scrt.vwr

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class DashboardViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    fun updateDestination(destination: AppDestinations) {
        _uiState.value = _uiState.value.copy(currentDestination = destination)
    }
}

data class DashboardUiState(
    val currentDestination: AppDestinations = AppDestinations.HOME,
    val destinations: List<AppDestinations> = AppDestinations.entries
)

enum class AppDestinations(
    @StringRes val label: Int,
    val icon: ImageVector,
) {
    HOME(R.string.home, Icons.Default.Home),
    DIRECTORY(R.string.directory, Icons.Default.Folder),
    HISTORY(R.string.history, Icons.Default.History),
    BOOKMARKS(R.string.bookmarks, Icons.Default.Bookmarks),
}
