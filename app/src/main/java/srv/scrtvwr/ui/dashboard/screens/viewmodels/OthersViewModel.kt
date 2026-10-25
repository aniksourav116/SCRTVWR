package srv.scrtvwr.ui.dashboard.screens.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import srv.scrtvwr.media.MediaPreviewLoader
import srv.scrtvwr.preferences.ScrtvwrPreferences
import srv.scrtvwr.ui.media.MediaEdgeDialogStyle
import srv.scrtvwr.ui.media.SlideShowDirection
import srv.scrtvwr.ui.theme.ThemeMode

class OthersViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = ScrtvwrPreferences(application)
    private val _uiState = MutableStateFlow(settingsState())
    val uiState = _uiState.asStateFlow()

    private fun settingsState(): OthersUiState =
        OthersUiState(
            themeMode = preferences.getThemeMode(),
            slideShowStayMillis = preferences.getSlideShowStayMillis(),
            holdSlideShowEnabled = preferences.getHoldSlideShowEnabled(),
            slideShowDirection = preferences.getSlideShowDirection(),
            imagePreloadDistance = preferences.getImagePreloadDistance(),
            videoPreloadDistance = preferences.getVideoPreloadDistance(),
            mediaEdgeDialogStyle = preferences.getMediaEdgeDialogStyle(),
            showHiddenFiles = preferences.getShowHiddenFiles(),
            showDirectoryFileActions = preferences.getShowDirectoryFileActions(),
            loadVideoThumbnails = preferences.getLoadVideoThumbnails(),
        )

    fun setThemeMode(mode: ThemeMode) {
        preferences.saveThemeMode(mode)
        _uiState.update { it.copy(themeMode = mode) }
    }

    fun resetToDefaults() {
        preferences.resetSettings()
        // Video thumbnails come back on, so their slow-loading check starts over too.
        MediaPreviewLoader.resetVideoFrameLoadWarning()
        _uiState.value = settingsState()
    }

    fun setShowHiddenFiles(enabled: Boolean) {
        preferences.saveShowHiddenFiles(enabled)
        _uiState.update { it.copy(showHiddenFiles = enabled) }
    }

    fun setShowDirectoryFileActions(enabled: Boolean) {
        preferences.saveShowDirectoryFileActions(enabled)
        _uiState.update { it.copy(showDirectoryFileActions = enabled) }
    }

    fun setLoadVideoThumbnails(enabled: Boolean) {
        preferences.saveLoadVideoThumbnails(enabled)
        // Turning frames back on starts the slow-loading check over.
        if (enabled) MediaPreviewLoader.resetVideoFrameLoadWarning()
        _uiState.update { it.copy(loadVideoThumbnails = enabled) }
    }

    fun setSlideShowStayMillis(stayMillis: Long) {
        val boundedStayMillis = stayMillis.coerceIn(SLIDE_SHOW_STAY_MILLIS_RANGE)
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
        val boundedDistance = distance.coerceIn(PRELOAD_DISTANCE_RANGE)
        preferences.saveImagePreloadDistance(boundedDistance)
        _uiState.update { it.copy(imagePreloadDistance = boundedDistance) }
    }

    fun setVideoPreloadDistance(distance: Int) {
        val boundedDistance = distance.coerceIn(PRELOAD_DISTANCE_RANGE)
        preferences.saveVideoPreloadDistance(boundedDistance)
        _uiState.update { it.copy(videoPreloadDistance = boundedDistance) }
    }

    fun showGestureTipsAgain() {
        preferences.resetGestureTips()
    }

    fun setMediaEdgeDialogStyle(style: MediaEdgeDialogStyle) {
        preferences.saveMediaEdgeDialogStyle(style)
        _uiState.update { it.copy(mediaEdgeDialogStyle = style) }
    }
}

/** Slide show stay times the settings accept: up to ten minutes an image. */
val SLIDE_SHOW_STAY_MILLIS_RANGE = 1L..600_000L

/** Preload distances the settings accept; more pages than this mostly costs memory. */
val PRELOAD_DISTANCE_RANGE = 0..20

data class OthersUiState(
    val themeMode: ThemeMode = ThemeMode.System,
    val showHiddenFiles: Boolean = false,
    val showDirectoryFileActions: Boolean = false,
    val loadVideoThumbnails: Boolean = true,
    val slideShowStayMillis: Long = 100L,
    val holdSlideShowEnabled: Boolean = false,
    val slideShowDirection: SlideShowDirection = SlideShowDirection.Next,
    val imagePreloadDistance: Int = 5,
    val videoPreloadDistance: Int = 0,
    val mediaEdgeDialogStyle: MediaEdgeDialogStyle = MediaEdgeDialogStyle.Bottom,
)
