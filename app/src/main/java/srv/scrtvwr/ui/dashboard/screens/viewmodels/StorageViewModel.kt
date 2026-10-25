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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import srv.scrtvwr.R
import srv.scrtvwr.media.remote.HttpAuthenticationException
import srv.scrtvwr.media.remote.HttpCredentials
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
    /** Running check, or wait before the next reconnect attempt, for each connected server. */
    private val serverChecks = mutableMapOf<String, Job>()

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

        val connectedIds = preferences.getConnectedHttpServers().mapTo(mutableSetOf(), HttpServer::id)
        _uiState.value = StorageUiState(
            devices = volumes,
            httpServers = preferences.getHttpServers().map { server ->
                HttpServerDevice(
                    server = server,
                    status = if (server.id in connectedIds) HttpServerStatus.Checking else HttpServerStatus.Disconnected,
                )
            },
            lastChecked = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date()),
        )
        serverChecks.values.forEach { it.cancel() }
        serverChecks.clear()
        _uiState.value.httpServers.filter(HttpServerDevice::isConnected).forEach { checkHttpServer(it.server) }
    }

    /** Saves a new server, or returns why [address] cannot be added. */
    fun addHttpServer(name: String, address: String, credentials: HttpCredentials?): HttpServerInputError? {
        val baseUrl = RemotePaths.normalizeServerUrl(address) ?: return HttpServerInputError.InvalidAddress
        val servers = preferences.getHttpServers()
        if (servers.any { it.baseUrl == baseUrl }) return HttpServerInputError.AlreadyAdded

        val server = HttpServer(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifBlank { RemotePaths.name(baseUrl) },
            baseUrl = baseUrl,
            credentials = credentials,
        )
        preferences.saveHttpServers(servers + server)
        // A server is added to be used, so it starts connected.
        preferences.setHttpServerConnected(server.id, connected = true)
        refresh()
        return null
    }

    fun removeHttpServer(server: HttpServer) {
        serverChecks.remove(server.id)?.cancel()
        preferences.setHttpServerConnected(server.id, connected = false)
        preferences.saveHttpServers(preferences.getHttpServers().filterNot { it.id == server.id })
        _uiState.update { state -> state.copy(httpServers = state.httpServers.filterNot { it.server.id == server.id }) }
    }

    fun connectHttpServer(server: HttpServer) {
        preferences.setHttpServerConnected(server.id, connected = true)
        updateServerStatus(server.id, HttpServerStatus.Checking)
        checkHttpServer(server)
    }

    fun disconnectHttpServer(server: HttpServer) {
        serverChecks.remove(server.id)?.cancel()
        preferences.setHttpServerConnected(server.id, connected = false)
        updateServerStatus(server.id, HttpServerStatus.Disconnected)
    }

    /**
     * Saves the edited name, address, sign-in and auto reconnect of [server], or returns why
     * [address] cannot be used. A connected server is checked again with what changed.
     */
    fun updateHttpServer(
        server: HttpServer,
        name: String,
        address: String,
        credentials: HttpCredentials?,
        autoReconnect: Boolean,
    ): HttpServerInputError? {
        val baseUrl = RemotePaths.normalizeServerUrl(address) ?: return HttpServerInputError.InvalidAddress
        val servers = preferences.getHttpServers()
        if (servers.any { it.id != server.id && it.baseUrl == baseUrl }) return HttpServerInputError.AlreadyAdded

        val updated = server.copy(
            name = name.trim().ifBlank { RemotePaths.name(baseUrl) },
            baseUrl = baseUrl,
            credentials = credentials,
            autoReconnect = autoReconnect,
        )
        preferences.saveHttpServers(servers.map { if (it.id == server.id) updated else it })
        _uiState.update { state ->
            state.copy(
                httpServers = state.httpServers.map { device ->
                    if (device.server.id == server.id) device.copy(server = updated) else device
                },
            )
        }
        if (_uiState.value.httpServers.firstOrNull { it.server.id == server.id }?.isConnected == true) {
            updateServerStatus(server.id, HttpServerStatus.Checking)
            checkHttpServer(updated)
        }
        return null
    }

    /** Saves [credentials], or none for an open server, and checks a connected server again with them. */
    fun setHttpServerCredentials(server: HttpServer, credentials: HttpCredentials?) {
        preferences.saveHttpServers(
            preferences.getHttpServers().map { if (it.id == server.id) it.copy(credentials = credentials) else it },
        )
        val updated = server.copy(credentials = credentials)
        _uiState.update { state ->
            state.copy(
                httpServers = state.httpServers.map { device ->
                    if (device.server.id == server.id) device.copy(server = updated) else device
                },
            )
        }
        if (_uiState.value.httpServers.firstOrNull { it.server.id == server.id }?.isConnected == true) {
            updateServerStatus(server.id, HttpServerStatus.Checking)
            checkHttpServer(updated)
        }
    }

    /**
     * Lists the server's root, so its card can say whether it is reachable. A server with auto
     * reconnect that turns out offline is tried again, waiting twice as long after each failure.
     */
    private fun checkHttpServer(server: HttpServer, retryDelayMillis: Long = FIRST_RECONNECT_DELAY_MILLIS) {
        serverChecks.remove(server.id)?.cancel()
        serverChecks[server.id] = viewModelScope.launch {
            val status = withContext(Dispatchers.IO) {
                try {
                    HttpServerStatus.Online(itemCount = HttpDirectoryClient.list(server.baseUrl, refresh = true).size)
                } catch (_: HttpAuthenticationException) {
                    // Not retried: the same credentials would fail again until the user changes them.
                    HttpServerStatus.AuthenticationFailed
                } catch (error: IOException) {
                    // Without local network access Android drops LAN connections, which
                    // only surfaces as a timeout, so name the actual cause instead.
                    if (getApplication<Application>().hasLocalNetworkAccess()) {
                        HttpServerStatus.Offline(reason = error.message)
                    } else {
                        HttpServerStatus.LocalNetworkBlocked
                    }
                }
            }
            updateServerStatus(server.id, status)

            if (status !is HttpServerStatus.Offline) return@launch
            delay(retryDelayMillis)
            // Read the server again: auto reconnect may have been turned off while waiting.
            val current = _uiState.value.httpServers.firstOrNull { it.server.id == server.id }
            if (current?.server?.autoReconnect == true && current.isConnected) {
                checkHttpServer(current.server, (retryDelayMillis * 2).coerceAtMost(MAX_RECONNECT_DELAY_MILLIS))
            }
        }
    }

    private fun updateServerStatus(serverId: String, status: HttpServerStatus) {
        _uiState.update { state ->
            state.copy(
                httpServers = state.httpServers.map { device ->
                    if (device.server.id == serverId) device.copy(status = status) else device
                },
            )
        }
    }

    private companion object {
        const val FIRST_RECONNECT_DELAY_MILLIS = 10_000L
        const val MAX_RECONNECT_DELAY_MILLIS = 5 * 60_000L
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
) {
    val isConnected: Boolean
        get() = status != HttpServerStatus.Disconnected
}

sealed interface HttpServerStatus {
    /** Saved but not in use: never checked or browsed until connected again. */
    data object Disconnected : HttpServerStatus

    data object Checking : HttpServerStatus

    data class Online(val itemCount: Int) : HttpServerStatus

    data class Offline(val reason: String?) : HttpServerStatus

    data object LocalNetworkBlocked : HttpServerStatus

    /** The server wants a username and password, and none or the wrong ones are saved. */
    data object AuthenticationFailed : HttpServerStatus
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
