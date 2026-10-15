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
import srv.scrtvwr.media.remote.HttpAuthException
import srv.scrtvwr.media.remote.HttpCredentials
import srv.scrtvwr.media.remote.HttpDirectoryClient
import srv.scrtvwr.media.remote.HttpServer
import srv.scrtvwr.media.remote.HttpServerAccess
import srv.scrtvwr.media.remote.PinnedCertificates
import srv.scrtvwr.media.remote.RemotePaths
import srv.scrtvwr.media.remote.ServerCertificate
import srv.scrtvwr.preferences.ScrtvwrPreferences
import srv.scrtvwr.ui.components.hasLocalNetworkAccess
import java.io.File
import java.io.IOException
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import javax.net.ssl.SSLException

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

        val credentials = preferences.getHttpCredentials()
        _uiState.value = StorageUiState(
            devices = volumes,
            httpServers = preferences.getHttpServers().map { server ->
                HttpServerDevice(server = server, username = credentials[server.id]?.username)
            },
            lastChecked = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date()),
        )
        checkHttpServers()
    }

    /** Saves a new server, or returns why [input] cannot be added. */
    fun addHttpServer(input: HttpServerInput): HttpServerInputError? {
        val baseUrl = RemotePaths.normalizeServerUrl(input.address) ?: return HttpServerInputError.InvalidAddress
        val servers = preferences.getHttpServers()
        if (servers.any { it.baseUrl == baseUrl }) return HttpServerInputError.AlreadyAdded

        val server = HttpServer(
            id = UUID.randomUUID().toString(),
            name = input.name.trim().ifBlank { RemotePaths.name(baseUrl) },
            baseUrl = baseUrl,
        )
        preferences.saveHttpServers(servers + server)
        preferences.saveHttpCredentials(server.id, input.credentials(saved = null))
        applyHttpServerChanges()
        return null
    }

    /** Saves changes to the server [serverId], or returns why [input] cannot be used. */
    fun updateHttpServer(serverId: String, input: HttpServerInput): HttpServerInputError? {
        val baseUrl = RemotePaths.normalizeServerUrl(input.address) ?: return HttpServerInputError.InvalidAddress
        val servers = preferences.getHttpServers()
        val current = servers.firstOrNull { it.id == serverId } ?: return null
        if (servers.any { it.id != serverId && it.baseUrl == baseUrl }) return HttpServerInputError.AlreadyAdded

        val updated = current.copy(
            name = input.name.trim().ifBlank { RemotePaths.name(baseUrl) },
            baseUrl = baseUrl,
            // A trusted certificate vouches for one machine; another address may be a different one.
            certificateSha256 = current.certificateSha256
                ?.takeIf { RemotePaths.origin(baseUrl) == RemotePaths.origin(current.baseUrl) },
        )
        preferences.saveHttpServers(servers.map { if (it.id == serverId) updated else it })
        preferences.saveHttpCredentials(serverId, input.credentials(saved = preferences.getHttpCredentials()[serverId]))
        HttpDirectoryClient.clearCache(current.baseUrl)
        applyHttpServerChanges()
        return null
    }

    fun removeHttpServer(server: HttpServer) {
        preferences.saveHttpServers(preferences.getHttpServers().filterNot { it.id == server.id })
        preferences.saveHttpCredentials(server.id, credentials = null)
        HttpServerAccess.update(preferences.getHttpServers(), preferences.getHttpCredentials())
        HttpDirectoryClient.clearCache(server.baseUrl)
        _uiState.update { state -> state.copy(httpServers = state.httpServers.filterNot { it.server.id == server.id }) }
    }

    /** Accepts [certificate] as [server]'s own, after the user compared its fingerprint. */
    fun trustCertificate(server: HttpServer, certificate: ServerCertificate) {
        preferences.saveHttpServers(
            preferences.getHttpServers().map { if (it.id == server.id) it.copy(certificateSha256 = certificate.sha256) else it },
        )
        HttpDirectoryClient.clearCache(server.baseUrl)
        applyHttpServerChanges()
    }

    /** Makes requests use the saved servers and credentials, then checks every server again. */
    private fun applyHttpServerChanges() {
        HttpServerAccess.update(preferences.getHttpServers(), preferences.getHttpCredentials())
        refresh()
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
                    } catch (_: HttpAuthException) {
                        if (HttpServerAccess.credentialsFor(server.baseUrl) == null) {
                            HttpServerStatus.AuthRequired
                        } else {
                            HttpServerStatus.AuthFailed
                        }
                    } catch (error: SSLException) {
                        // Show the certificate the server presents so the user can decide whether
                        // to trust it; TLS failures without one stay connection errors.
                        PinnedCertificates.probe(server.baseUrl)
                            ?.takeIf { it.sha256 != server.certificateSha256 }
                            ?.let { HttpServerStatus.UntrustedCertificate(it, changed = server.certificateSha256 != null) }
                            ?: offlineStatus(error)
                    } catch (error: IOException) {
                        offlineStatus(error)
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

    private fun offlineStatus(error: IOException): HttpServerStatus =
        // Without local network access Android drops LAN connections, which
        // only surfaces as a timeout, so name the actual cause instead.
        if (getApplication<Application>().hasLocalNetworkAccess()) {
            HttpServerStatus.Offline(reason = error.message)
        } else {
            HttpServerStatus.LocalNetworkBlocked
        }
}

data class StorageUiState(
    val devices: List<StorageDevice> = emptyList(),
    val httpServers: List<HttpServerDevice> = emptyList(),
    val lastChecked: String = "",
)

data class HttpServerDevice(
    val server: HttpServer,
    /** Username saved for the server, or null when it has no credentials. */
    val username: String? = null,
    val status: HttpServerStatus = HttpServerStatus.Checking,
)

/** What the user typed into the add or edit server dialog. */
data class HttpServerInput(
    val name: String,
    val address: String,
    val username: String = "",
    val password: String = "",
) {
    /**
     * Credentials to save: the typed username and password, or those typed into the address as
     * `user:password@host`. A blank password keeps the [saved] one; a blank username signs out.
     */
    fun credentials(saved: HttpCredentials?): HttpCredentials? {
        val user = username.trim()
        if (user.isEmpty()) return RemotePaths.credentials(address)
        return HttpCredentials(username = user, password = password.ifEmpty { saved?.password.orEmpty() })
    }
}

sealed interface HttpServerStatus {
    data object Checking : HttpServerStatus

    data class Online(val itemCount: Int) : HttpServerStatus

    data class Offline(val reason: String?) : HttpServerStatus

    data object LocalNetworkBlocked : HttpServerStatus

    /** The server wants a username and password, and none are saved. */
    data object AuthRequired : HttpServerStatus

    /** The server turned down the saved username and password. */
    data object AuthFailed : HttpServerStatus

    /** The `https://` server's certificate is not trusted yet, or [changed] from the trusted one. */
    data class UntrustedCertificate(val certificate: ServerCertificate, val changed: Boolean) : HttpServerStatus
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
