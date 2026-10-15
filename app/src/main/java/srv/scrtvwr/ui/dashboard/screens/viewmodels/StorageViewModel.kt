package srv.scrtvwr.ui.dashboard.screens.viewmodels

import android.app.Application
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import srv.scrtvwr.R
import srv.scrtvwr.media.remote.HttpDirectoryClient
import srv.scrtvwr.media.remote.HttpServer
import srv.scrtvwr.media.remote.RemotePaths
import srv.scrtvwr.preferences.ScrtvwrPreferences
import srv.scrtvwr.ui.components.hasLocalNetworkAccess
import java.io.File
import java.io.IOException
import java.text.DateFormat
import java.util.Date
import java.util.UUID

class StorageViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = ScrtvwrPreferences(application)
    private val _uiState = MutableStateFlow(
        StorageUiState(lastChecked = application.getString(R.string.last_checked_not_yet)),
    )
    val uiState = _uiState.asStateFlow()
    private var serverCheckJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        val context = getApplication<Application>()
        val storageManager = context.getSystemService(StorageManager::class.java)
        val volumes = storageManager.storageVolumes.mapNotNull { volume ->
            val directory = volume.directory(context, storageManager) ?: return@mapNotNull null
            if (!directory.exists() || volume.state != Environment.MEDIA_MOUNTED) {
                return@mapNotNull null
            }

            val totalBytes = directory.totalSpace
            if (totalBytes <= 0L) return@mapNotNull null

            StorageDevice(
                id = volume.deviceId(directory),
                name = volume.getDescription(context),
                type = volume.deviceType(context),
                rootPath = directory.absolutePath,
                totalBytes = totalBytes,
                availableBytes = directory.usableSpace,
                isRemovable = volume.isRemovable,
            )
        }.distinctBy(StorageDevice::id)

        _uiState.value = StorageUiState(
            devices = volumes,
            httpServers = preferences.getHttpServers().map(::HttpServerDevice),
            lastChecked = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date()),
        )
        checkHttpServers()
    }

    /** Saves a new server, or returns why [address] cannot be added. */
    fun addHttpServer(name: String, address: String): HttpServerInputError? {
        val baseUrl = RemotePaths.normalizeServerUrl(address) ?: return HttpServerInputError.InvalidAddress
        val servers = preferences.getHttpServers()
        if (servers.any { it.baseUrl == baseUrl }) return HttpServerInputError.AlreadyAdded

        val server = HttpServer(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifBlank { RemotePaths.name(baseUrl) },
            baseUrl = baseUrl,
        )
        preferences.saveHttpServers(servers + server)
        refresh()
        return null
    }

    fun removeHttpServer(server: HttpServer) {
        preferences.saveHttpServers(preferences.getHttpServers().filterNot { it.id == server.id })
        _uiState.update { state -> state.copy(httpServers = state.httpServers.filterNot { it.server.id == server.id }) }
    }

    /** Lists each server's root once, so its card can say whether it is reachable. */
    private fun checkHttpServers() {
        serverCheckJob?.cancel()
        val servers = _uiState.value.httpServers.map(HttpServerDevice::server)
        serverCheckJob = viewModelScope.launch {
            servers.forEach { server ->
                launch(Dispatchers.IO) {
                    val status = try {
                        HttpServerStatus.Online(itemCount = HttpDirectoryClient.list(server.baseUrl, refresh = true).size)
                    } catch (error: IOException) {
                        // Without local network access Android drops LAN connections, which
                        // only surfaces as a timeout, so name the actual cause instead.
                        if (getApplication<Application>().hasLocalNetworkAccess()) {
                            HttpServerStatus.Offline(reason = error.message)
                        } else {
                            HttpServerStatus.LocalNetworkBlocked
                        }
                    }
                    _uiState.update { state ->
                        state.copy(
                            httpServers = state.httpServers.map { device ->
                                if (device.server.id == server.id) device.copy(status = status) else device
                            },
                        )
                    }
                }
            }
        }
    }
}

data class StorageUiState(
    val devices: List<StorageDevice> = emptyList(),
    val httpServers: List<HttpServerDevice> = emptyList(),
    val lastChecked: String = "",
)

data class HttpServerDevice(
    val server: HttpServer,
    val status: HttpServerStatus = HttpServerStatus.Checking,
)

sealed interface HttpServerStatus {
    data object Checking : HttpServerStatus

    data class Online(val itemCount: Int) : HttpServerStatus

    data class Offline(val reason: String?) : HttpServerStatus

    data object LocalNetworkBlocked : HttpServerStatus
}

enum class HttpServerInputError {
    InvalidAddress,
    AlreadyAdded,
}

data class StorageDevice(
    val id: String,
    val name: String,
    val type: String,
    val rootPath: String,
    val totalBytes: Long,
    val availableBytes: Long,
    val isRemovable: Boolean,
) {
    val usedBytes: Long
        get() = (totalBytes - availableBytes).coerceAtLeast(0L)

    val usedFraction: Float
        get() = if (totalBytes == 0L) 0f else usedBytes.toFloat() / totalBytes
}

private fun StorageVolume.directory(
    context: Application,
    storageManager: StorageManager,
): File? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return directory
    if (isPrimary) return Environment.getExternalStorageDirectory()

    return context.getExternalFilesDirs(null)
        .firstOrNull { it != null && storageManager.getStorageVolume(it) == this }
        ?.let { appDirectory ->
            generateSequence(appDirectory) { it.parentFile }
                .firstOrNull { it.name.equals("Android", ignoreCase = true) }
                ?.parentFile
        }
}

private fun StorageVolume.deviceType(context: Application): String {
    if (!isRemovable) return context.getString(R.string.device_storage)

    return if (getDescription(context).contains("usb", ignoreCase = true)) {
        context.getString(R.string.usb_storage)
    } else {
        context.getString(R.string.removable_storage)
    }
}

private fun StorageVolume.deviceId(directory: File): String =
    uuid ?: if (isPrimary) "primary" else directory.absolutePath
