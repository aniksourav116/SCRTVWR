package srv.scrtvwr.ui.dashboard.screens.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import srv.scrtvwr.preferences.ScrtvwrPreferences
import srv.scrtvwr.ui.media.MediaEdgeDialogStyle
import srv.scrtvwr.ui.media.SlideShowDirection

class OthersViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = ScrtvwrPreferences(application)
    private val _uiState = MutableStateFlow(
        OthersUiState(
            slideShowStayMillis = preferences.getSlideShowStayMillis(),
            holdSlideShowEnabled = preferences.getHoldSlideShowEnabled(),
            slideShowDirection = preferences.getSlideShowDirection(),
            imagePreloadDistance = preferences.getImagePreloadDistance(),
            videoPreloadDistance = preferences.getVideoPreloadDistance(),
            mediaEdgeDialogStyle = preferences.getMediaEdgeDialogStyle(),
            showHiddenFiles = preferences.getShowHiddenFiles(),
            showDirectoryFileActions = preferences.getShowDirectoryFileActions(),
            loadHttpVideoThumbnails = preferences.getLoadHttpVideoThumbnails(),
        ),
    )
    val uiState = _uiState.asStateFlow()

    fun setShowHiddenFiles(enabled: Boolean) {
        preferences.saveShowHiddenFiles(enabled)
        _uiState.update { it.copy(showHiddenFiles = enabled) }
    }

    fun setShowDirectoryFileActions(enabled: Boolean) {
        preferences.saveShowDirectoryFileActions(enabled)
        _uiState.update { it.copy(showDirectoryFileActions = enabled) }
    }

    fun setLoadHttpVideoThumbnails(enabled: Boolean) {
        preferences.saveLoadHttpVideoThumbnails(enabled)
        _uiState.update { it.copy(loadHttpVideoThumbnails = enabled) }
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

    fun setSlideShowDirection(direction: SlideShowDirection) {
        preferences.saveSlideShowDirection(direction)
        _uiState.update { it.copy(slideShowDirection = direction) }
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
    }

    fun setMediaEdgeDialogStyle(style: MediaEdgeDialogStyle) {
        preferences.saveMediaEdgeDialogStyle(style)
        _uiState.update { it.copy(mediaEdgeDialogStyle = style) }
    }
}

data class OthersUiState(
    val showHiddenFiles: Boolean = false,
    val showDirectoryFileActions: Boolean = false,
    val loadHttpVideoThumbnails: Boolean = true,
    val slideShowStayMillis: Long = 100L,
    val holdSlideShowEnabled: Boolean = false,
    val slideShowDirection: SlideShowDirection = SlideShowDirection.Next,
    val imagePreloadDistance: Int = 5,
    val videoPreloadDistance: Int = 0,
    val mediaEdgeDialogStyle: MediaEdgeDialogStyle = MediaEdgeDialogStyle.Bottom,
)
