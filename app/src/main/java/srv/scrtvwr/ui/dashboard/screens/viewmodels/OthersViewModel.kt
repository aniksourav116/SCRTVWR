package srv.scrtvwr.ui.dashboard.screens.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import srv.scrtvwr.preferences.ScrtvwrPreferences

class OthersViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = ScrtvwrPreferences(application)
    private val _uiState = MutableStateFlow(
        OthersUiState(
            slideShowStayMillis = preferences.getSlideShowStayMillis(),
            holdSlideShowEnabled = preferences.getHoldSlideShowEnabled(),
            imagePreloadDistance = preferences.getImagePreloadDistance(),
            videoPreloadDistance = preferences.getVideoPreloadDistance(),
            showHiddenFiles = preferences.getShowHiddenFiles(),
        ),
    )
    val uiState = _uiState.asStateFlow()

    fun setShowHiddenFiles(enabled: Boolean) {
        preferences.saveShowHiddenFiles(enabled)
        _uiState.update { it.copy(showHiddenFiles = enabled) }
    }

    fun setSlideShowStayMillis(stayMillis: Long) {
        val boundedStayMillis = stayMillis.coerceAtLeast(1L)
        preferences.saveSlideShowStayMillis(boundedStayMillis)
        _uiState.update { it.copy(slideShowStayMillis = boundedStayMillis) }
    }

    fun setHoldSlideShowEnabled(enabled: Boolean) {
        preferences.saveHoldSlideShowEnabled(enabled)
        _uiState.update { it.copy(holdSlideShowEnabled = enabled) }
    }

    fun setImagePreloadDistance(distance: Int) {
        val boundedDistance = distance.coerceAtLeast(0)
        preferences.saveImagePreloadDistance(boundedDistance)
        _uiState.update { it.copy(imagePreloadDistance = boundedDistance) }
    }

    fun setVideoPreloadDistance(distance: Int) {
        val boundedDistance = distance.coerceAtLeast(0)
        preferences.saveVideoPreloadDistance(boundedDistance)
        _uiState.update { it.copy(videoPreloadDistance = boundedDistance) }
    }}

data class OthersUiState(
    val showHiddenFiles: Boolean = false,
    val slideShowStayMillis: Long = 50L,
    val holdSlideShowEnabled: Boolean = false,
    val imagePreloadDistance: Int = 5,
    val videoPreloadDistance: Int = 0,
)
