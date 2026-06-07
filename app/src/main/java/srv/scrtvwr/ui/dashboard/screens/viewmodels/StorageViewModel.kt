package srv.scrtvwr.ui.dashboard.screens.viewmodels

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class StorageViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(StorageUiState())
    val uiState = _uiState.asStateFlow()

    fun refresh() {
        _uiState.update { it.copy(lastChecked = "Just now") }
    }
}

data class StorageUiState(
    val totalSpaceGb: Int = 128,
    val usedSpaceGb: Int = 42,
    val lastChecked: String = "Not checked yet",
) {
    val usedFraction: Float
        get() = if (totalSpaceGb == 0) 0f else usedSpaceGb.toFloat() / totalSpaceGb
}
