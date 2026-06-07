package srv.scrtvwr.ui.dashboard.screens.viewmodels

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class DirectoryViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(DirectoryUiState())
    val uiState = _uiState.asStateFlow()

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }
}

data class DirectoryUiState(
    val searchQuery: String = "",
    val directories: List<String> = listOf("Movies", "Series", "Downloads", "Recordings"),
) {
    val visibleDirectories: List<String>
        get() = directories.filter { it.contains(searchQuery.trim(), ignoreCase = true) }
}
