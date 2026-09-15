package srv.scrtvwr.ui.dashboard.screens.viewmodels

import android.app.Application
import android.net.Uri
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
import srv.scrtvwr.tagging.FaceModelInstaller
import srv.scrtvwr.tagging.ImageTags
import srv.scrtvwr.tagging.StorageScanConsent
import srv.scrtvwr.tagging.TagScanProgress
import srv.scrtvwr.tagging.TaggingState

/** The outcome of installing a face model, shown once and then dismissed. */
data class ModelMessage(
    val installed: Boolean,
    val inputSize: Int = 0,
    val embeddingSize: Int = 0,
    val reason: String = "",
)

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
        viewModelScope.launch {
            CharacterTagRepository.usingRecognitionModel.collect { usingModel ->
                _uiState.update { it.copy(usingRecognitionModel = usingModel) }
            }
        }
        viewModelScope.launch {
            CharacterTagRepository.rebuilding.collect { rebuilding ->
                _uiState.update { it.copy(rebuilding = rebuilding) }
            }
        }
    }

    /** Folds another character into this one, for when one person was split in two. */
    fun merge(characterId: String, otherId: String) {
        viewModelScope.launch { CharacterTagRepository.merge(characterId, otherId) }
    }

    /** "Not this person": drops one image from the character without touching the file. */
    fun unassign(characterId: String, path: String) {
        viewModelScope.launch { CharacterTagRepository.unassign(characterId, path) }
    }

    fun rebuildGroups() {
        viewModelScope.launch { CharacterTagRepository.rebuildGroups() }
    }

    /** Installs a model the user picked from storage; the result is reported back on screen. */
    fun installModel(uri: Uri) {
        viewModelScope.launch {
            val message = when (val result = CharacterTagRepository.installModel(uri)) {
                is FaceModelInstaller.InstallResult.Installed ->
                    ModelMessage(installed = true, inputSize = result.inputSize, embeddingSize = result.embeddingSize)
                is FaceModelInstaller.InstallResult.Rejected ->
                    ModelMessage(installed = false, reason = result.reason)
            }
            _uiState.update { it.copy(modelMessage = message) }
        }
    }

    fun clearModelMessage() {
        _uiState.update { it.copy(modelMessage = null) }
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
    val usingRecognitionModel: Boolean = true,
    val rebuilding: Boolean = false,
    val modelMessage: ModelMessage? = null,
) {
    val taggingState: TaggingState
        get() = when {
            !taggingEnabled -> TaggingState.Off
            taggingPaused -> TaggingState.Paused
            else -> TaggingState.Running
        }
}
