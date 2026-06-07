package srv.scrtvwr.ui.dashboard.screens.viewmodels

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class HistoryViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState = _uiState.asStateFlow()

    fun selectFilter(filter: HistoryFilter) {
        _uiState.update { it.copy(selectedFilter = filter) }
    }
}

data class HistoryUiState(
    val selectedFilter: HistoryFilter = HistoryFilter.ALL,
) {
    val emptyMessage: String
        get() = "No ${selectedFilter.label.lowercase()} history yet."
}

enum class HistoryFilter(val label: String) {
    ALL("All"),
    RECENT("Recent"),
    FAVORITES("Favorites"),
}
