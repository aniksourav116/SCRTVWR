package srv.scrtvwr.ui.dashboard.screens.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import srv.scrtvwr.preferences.ScrtvwrPreferences
import srv.scrtvwr.tagging.CharacterTagRepository
import srv.scrtvwr.tagging.StorageScanConsent
import srv.scrtvwr.tagging.TagScanProgress
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
            faceTaggingEnabled = preferences.getFaceTaggingEnabled(),
            lastStorageScanMillis = preferences.getLastStorageScanMillis(),
        ),
    )
    val uiState = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            CharacterTagRepository.taggedImageCount.collect { count ->
                _uiState.update { it.copy(taggedImageCount = count) }
            }
        }
        viewModelScope.launch {
            CharacterTagRepository.characters.collect { characters ->
                _uiState.update { it.copy(characterCount = characters.size) }
            }
        }
        viewModelScope.launch {
            CharacterTagRepository.paused.collect { paused ->
                _uiState.update { it.copy(taggingPaused = paused) }
            }
        }
        viewModelScope.launch {
            CharacterTagRepository.scanProgress.collect { progress ->
                _uiState.update {
                    it.copy(
                        tagScanProgress = progress,
                        lastStorageScanMillis = preferences.getLastStorageScanMillis(),
                    )
                }
            }
        }
    }

    fun setFaceTaggingEnabled(enabled: Boolean) {
        CharacterTagRepository.setEnabled(enabled)
        _uiState.update { it.copy(faceTaggingEnabled = enabled) }
    }

    /** Starting the pass from settings is itself the consent, so it is recorded as granted. */
    fun startStorageScan() {
        CharacterTagRepository.setStorageScanConsent(StorageScanConsent.Granted)
        CharacterTagRepository.startStorageScan()
    }

    fun stopStorageScan() {
        CharacterTagRepository.cancelStorageScan()
    }

    fun setTaggingPaused(paused: Boolean) {
        CharacterTagRepository.setPaused(paused)
    }

    fun clearCharacterTags() {
        viewModelScope.launch { CharacterTagRepository.clearAll() }
    }

    fun setShowHiddenFiles(enabled: Boolean) {
        preferences.saveShowHiddenFiles(enabled)
        _uiState.update { it.copy(showHiddenFiles = enabled) }
    }

    fun setShowDirectoryFileActions(enabled: Boolean) {
        preferences.saveShowDirectoryFileActions(enabled)
        _uiState.update { it.copy(showDirectoryFileActions = enabled) }
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
    val slideShowStayMillis: Long = 3_000L,
    val holdSlideShowEnabled: Boolean = false,
    val slideShowDirection: SlideShowDirection = SlideShowDirection.Next,
    val imagePreloadDistance: Int = 5,
    val videoPreloadDistance: Int = 0,
    val mediaEdgeDialogStyle: MediaEdgeDialogStyle = MediaEdgeDialogStyle.Bottom,
    val faceTaggingEnabled: Boolean = true,
    val taggingPaused: Boolean = false,
    val taggedImageCount: Int = 0,
    val characterCount: Int = 0,
    val lastStorageScanMillis: Long = 0L,
    val tagScanProgress: TagScanProgress = TagScanProgress(),
)
