package srv.scrtvwr.ui.dashboard.screens.viewmodels

import android.app.Application
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.DateFormat
import java.util.Date

class StorageViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(StorageUiState())
    val uiState = _uiState.asStateFlow()

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
                id = volume.uuid ?: if (volume.isPrimary) "primary" else directory.absolutePath,
                name = volume.getDescription(context),
                type = volume.deviceType(context),
                totalBytes = totalBytes,
                availableBytes = directory.usableSpace,
                isRemovable = volume.isRemovable,
            )
        }.distinctBy(StorageDevice::id)

        _uiState.value = StorageUiState(
            devices = volumes,
            lastChecked = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date()),
        )
    }
}

data class StorageUiState(
    val devices: List<StorageDevice> = emptyList(),
    val lastChecked: String = "Not checked yet",
)

data class StorageDevice(
    val id: String,
    val name: String,
    val type: String,
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
    if (!isRemovable) return "Device storage"

    return if (getDescription(context).contains("usb", ignoreCase = true)) {
        "USB storage"
    } else {
        "Removable storage"
    }
}
