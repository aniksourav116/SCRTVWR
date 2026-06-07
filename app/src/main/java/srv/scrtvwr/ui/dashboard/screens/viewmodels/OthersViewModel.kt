package srv.scrtvwr.ui.dashboard.screens.viewmodels

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class OthersViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(OthersUiState())
    val uiState = _uiState.asStateFlow()

    fun setShowHiddenFiles(enabled: Boolean) {
        _uiState.update { it.copy(showHiddenFiles = enabled) }
    }
}

data class OthersUiState(
    val showHiddenFiles: Boolean = false,
)
