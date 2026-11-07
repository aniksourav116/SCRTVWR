package srv.scrtvwr.ui.dashboard.screens.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import srv.scrtvwr.media.MediaPreviewLoader
import srv.scrtvwr.media.MediaCacheUsage
import srv.scrtvwr.media.mediaCacheUsage
import srv.scrtvwr.media.remote.RemoteArchiveCache
import srv.scrtvwr.preferences.CacheLimits
import srv.scrtvwr.preferences.MEBIBYTE
import srv.scrtvwr.R
import srv.scrtvwr.preferences.DirectoryVideoPlayer
import srv.scrtvwr.preferences.ScrtvwrPreferences
import srv.scrtvwr.ui.media.MediaEdgeDialogStyle
import srv.scrtvwr.ui.media.SlideShowDirection
import srv.scrtvwr.ui.theme.ThemeMode

class OthersViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = ScrtvwrPreferences(application)
    private val _uiState = MutableStateFlow(settingsState())
    val uiState = _uiState.asStateFlow()
    private val cacheOperation = Mutex()
    private var cacheLimitJob: Job? = null

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
            serverDetailSorting = preferences.getServerDetailSorting(),
            directoryVideoPlayer = preferences.getDirectoryVideoPlayer(),
            cacheLimits = preferences.getCacheLimits(),
        )

    fun setThemeMode(mode: ThemeMode) {
        preferences.saveThemeMode(mode)
        _uiState.update { it.copy(themeMode = mode) }
    }

    fun resetToDefaults() {
        preferences.resetSettings()
        // Video thumbnails come back on, so their slow-loading check starts over too.
        MediaPreviewLoader.resetVideoFrameLoadWarning()
        val previous = _uiState.value
        _uiState.value = settingsState().copy(cacheUsage = previous.cacheUsage, cacheBusy = previous.cacheBusy)
        setCacheLimits(preferences.getCacheLimits())
    }

    suspend fun refreshCacheUsage() {
        val usage = withContext(Dispatchers.IO) { mediaCacheUsage(getApplication<Application>().cacheDir) }
        _uiState.update { it.copy(cacheUsage = usage) }
    }

    fun setCacheLimits(limits: CacheLimits) {
        val bounded = limits.bounded()
        preferences.saveCacheLimits(bounded)
        _uiState.update { it.copy(cacheLimits = bounded) }
        cacheLimitJob?.cancel()
        cacheLimitJob = viewModelScope.launch {
            cacheOperation.withLock {
                _uiState.update { it.copy(cacheBusy = true, cacheMessage = null) }
                try {
                    withContext(Dispatchers.IO) {
                        MediaPreviewLoader.setCacheLimits(bounded)
                        RemoteArchiveCache.setMaxBytes(bounded.archivesMiB * MEBIBYTE)
                    }
                    refreshCacheUsage()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    _uiState.update { it.copy(cacheMessage = getApplication<Application>().getString(R.string.cache_operation_failed)) }
                } finally {
                    _uiState.update { it.copy(cacheBusy = false) }
                }
            }
        }
    }

    fun clearCache() {
        if (_uiState.value.cacheBusy) return
        _uiState.update { it.copy(cacheBusy = true, cacheMessage = null) }
        viewModelScope.launch {
            cacheOperation.withLock {
                try {
                    val cleared = withContext(Dispatchers.IO) {
                        val previews = MediaPreviewLoader.clearCaches()
                        val archives = RemoteArchiveCache.clear()
                        val context = getApplication<Application>()
                        val shared = java.io.File(context.cacheDir, "shared_media").listFiles()?.filter { it.isFile }
                            ?.map { file ->
                                val uri = androidx.core.content.FileProvider.getUriForFile(context,
                                    "${context.packageName}.fileprovider", file)
                                context.revokeUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                file.delete() || !file.exists()
                            }?.all { it } ?: true
                        previews && archives && shared
                    }
                    refreshCacheUsage()
                    _uiState.update { it.copy(cacheMessage = getApplication<Application>().getString(
                        if (cleared) R.string.cache_cleared else R.string.cache_clear_incomplete,
                    )) }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    _uiState.update { it.copy(cacheMessage = getApplication<Application>().getString(R.string.cache_operation_failed)) }
                } finally {
                    _uiState.update { it.copy(cacheBusy = false) }
                }
            }
        }
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

    fun setServerDetailSorting(enabled: Boolean) {
        preferences.saveServerDetailSorting(enabled)
        _uiState.update { it.copy(serverDetailSorting = enabled) }
    }

    fun setSlideShowStayMillis(stayMillis: Long) {
        val boundedStayMillis = stayMillis.coerceIn(SLIDE_SHOW_STAY_MILLIS_RANGE)
        preferences.saveSlideShowStayMillis(boundedStayMillis)
        _uiState.update { it.copy(slideShowStayMillis = boundedStayMillis) }
    }

    fun setDirectoryVideoPlayer(player: DirectoryVideoPlayer) {
        preferences.saveDirectoryVideoPlayer(player)
        _uiState.update { it.copy(directoryVideoPlayer = player) }
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
    val cacheLimits: CacheLimits = CacheLimits(),
    val cacheUsage: MediaCacheUsage? = null,
    val cacheBusy: Boolean = false,
    val cacheMessage: String? = null,
    val themeMode: ThemeMode = ThemeMode.System,
    val showHiddenFiles: Boolean = false,
    val showDirectoryFileActions: Boolean = false,
    val loadVideoThumbnails: Boolean = true,
    val serverDetailSorting: Boolean = false,
    val directoryVideoPlayer: DirectoryVideoPlayer = DirectoryVideoPlayer.VideoPlayer,
    val slideShowStayMillis: Long = 100L,
    val holdSlideShowEnabled: Boolean = false,
    val slideShowDirection: SlideShowDirection = SlideShowDirection.Next,
    val imagePreloadDistance: Int = 5,
    val videoPreloadDistance: Int = 0,
    val mediaEdgeDialogStyle: MediaEdgeDialogStyle = MediaEdgeDialogStyle.Bottom,
)
