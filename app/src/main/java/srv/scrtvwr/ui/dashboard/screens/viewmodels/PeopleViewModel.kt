package srv.scrtvwr.ui.dashboard.screens.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import srv.scrtvwr.preferences.ScrtvwrPreferences
import srv.scrtvwr.tagging.CharacterProfile
import srv.scrtvwr.tagging.CharacterTagRepository
import srv.scrtvwr.tagging.ImageTags
import srv.scrtvwr.tagging.StorageScanConsent
import srv.scrtvwr.tagging.TagScanProgress
import srv.scrtvwr.tagging.TaggingState

/** Backs the People tab: the characters tagging has found, and the controls for the tagging itself. */
class PeopleViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = ScrtvwrPreferences(application)
    private val _uiState = MutableStateFlow(
        PeopleUiState(lastStorageScanMillis = preferences.getLastStorageScanMillis()),
    )
    val uiState = _uiState.asStateFlow()
    private var imagesJob: Job? = null

    init {
        viewModelScope.launch {
            CharacterTagRepository.characters.collect { characters ->
                _uiState.update { state ->
                    state.copy(
                        characters = characters,
                        // Keep the open character page in step with renames and new images.
                        selectedCharacter = state.selectedCharacter
                            ?.let { selected -> characters.firstOrNull { it.id == selected.id } },
                    )
                }
                _uiState.value.selectedCharacter?.let(::loadImages)
            }
        }
        viewModelScope.launch {
            CharacterTagRepository.taggedImageCount.collect { count ->
                _uiState.update { it.copy(taggedImageCount = count) }
            }
        }
        viewModelScope.launch {
            CharacterTagRepository.scanProgress.collect { progress ->
                _uiState.update {
                    it.copy(
                        scanProgress = progress,
                        lastStorageScanMillis = preferences.getLastStorageScanMillis(),
                    )
                }
            }
        }
        viewModelScope.launch {
            CharacterTagRepository.enabled.collect { enabled ->
                _uiState.update { it.copy(taggingEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            CharacterTagRepository.paused.collect { paused ->
                _uiState.update { it.copy(taggingPaused = paused) }
            }
        }
    }

    fun openCharacter(profile: CharacterProfile) {
        _uiState.update { it.copy(selectedCharacter = profile, selectedCharacterImages = emptyList()) }
        loadImages(profile)
    }

    fun closeCharacter() {
        imagesJob?.cancel()
        _uiState.update { it.copy(selectedCharacter = null, selectedCharacterImages = emptyList()) }
    }

    fun rename(characterId: String, label: String?) {
        viewModelScope.launch { CharacterTagRepository.rename(characterId, label) }
    }

    fun setNote(characterId: String, note: String?) {
        viewModelScope.launch { CharacterTagRepository.setNote(characterId, note) }
    }

    fun setCover(characterId: String, image: ImageTags) {
        viewModelScope.launch { CharacterTagRepository.setCover(characterId, image.path, image.modifiedMillis) }
    }

    /** Saves both editable fields in one go, so the page has a single Save. */
    fun saveDetails(characterId: String, label: String?, note: String?) {
        viewModelScope.launch {
            CharacterTagRepository.rename(characterId, label)
            CharacterTagRepository.setNote(characterId, note)
        }
    }

    fun removeCharacter(characterId: String) {
        viewModelScope.launch {
            CharacterTagRepository.removeCharacter(characterId)
            closeCharacter()
        }
    }

    fun setTaggingEnabled(enabled: Boolean) {
        CharacterTagRepository.setEnabled(enabled)
    }

    fun setPaused(paused: Boolean) {
        CharacterTagRepository.setPaused(paused)
    }

    /** Starting the pass from here is itself the consent, so it is recorded as granted. */
    fun startStorageScan() {
        CharacterTagRepository.setStorageScanConsent(StorageScanConsent.Granted)
        CharacterTagRepository.startStorageScan()
    }

    fun stopStorageScan() {
        CharacterTagRepository.cancelStorageScan()
    }

    private fun loadImages(profile: CharacterProfile) {
        imagesJob?.cancel()
        imagesJob = viewModelScope.launch {
            val images = CharacterTagRepository.imagesFor(profile.id)
            _uiState.update { state ->
                if (state.selectedCharacter?.id != profile.id) state else state.copy(selectedCharacterImages = images)
            }
        }
    }

    override fun onCleared() {
        imagesJob?.cancel()
        super.onCleared()
    }
}

data class PeopleUiState(
    val characters: List<CharacterProfile> = emptyList(),
    val selectedCharacter: CharacterProfile? = null,
    val selectedCharacterImages: List<ImageTags> = emptyList(),
    val taggedImageCount: Int = 0,
    val taggingEnabled: Boolean = true,
    val taggingPaused: Boolean = false,
    val scanProgress: TagScanProgress = TagScanProgress(),
    val lastStorageScanMillis: Long = 0L,
) {
    val taggingState: TaggingState
        get() = when {
            !taggingEnabled -> TaggingState.Off
            taggingPaused -> TaggingState.Paused
            else -> TaggingState.Running
        }
}
