package srv.scrtvwr.ui.dashboard.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import srv.scrtvwr.ui.dashboard.screens.viewmodels.StorageDevice
import srv.scrtvwr.ui.dashboard.screens.viewmodels.StorageUiState
import srv.scrtvwr.ui.dashboard.screens.viewmodels.StorageViewModel
import srv.scrtvwr.ui.theme.SCRTVWRTheme
import srv.scrtvwr.R
import java.text.DecimalFormat

@Composable
fun Storage(
    modifier: Modifier = Modifier,
    onOpenDirectory: (String) -> Unit = {},
    viewModel: StorageViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pendingDirectoryPath by remember { mutableStateOf<String?>(null) }
    var hasStorageAccess by remember { mutableStateOf(context.hasRequiredStorageAccess()) }
    val legacyPermissions = remember {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
    val legacyPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        hasStorageAccess = grants.values.all { it }
        viewModel.refresh()
        if (hasStorageAccess) {
            pendingDirectoryPath?.let(onOpenDirectory)
            pendingDirectoryPath = null
        }
    }
    val allFilesAccessLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        hasStorageAccess = context.hasRequiredStorageAccess()
        viewModel.refresh()
        if (hasStorageAccess) {
            pendingDirectoryPath?.let(onOpenDirectory)
            pendingDirectoryPath = null
        }
    }
    val requestStorageAccess = {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                val intent = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${context.packageName}"),
                )
                allFilesAccessLauncher.launch(intent)
            }

            else -> {
                legacyPermissionLauncher.launch(legacyPermissions)
            }
        }
    }

    StorageScreen(
        uiState = uiState,
        onRefresh = viewModel::refresh,
        hasStorageAccess = hasStorageAccess,
        onGrantStorageAccess = requestStorageAccess,
        onOpenDevice = { device ->
            if (!context.hasRequiredStorageAccess()) {
                pendingDirectoryPath = device.rootPath
                requestStorageAccess()
            } else {
                onOpenDirectory(device.rootPath)
            }
        },
        modifier = modifier,
    )
}

@Composable
fun StorageScreen(
    uiState: StorageUiState,
    onRefresh: () -> Unit,
    onOpenDevice: (StorageDevice) -> Unit,
    hasStorageAccess: Boolean = true,
    onGrantStorageAccess: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Default.Storage, contentDescription = null)
                Text(stringResource(R.string.storage_devices), style = MaterialTheme.typography.headlineSmall)
            }
        }

        if (!hasStorageAccess) {
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.full_access_required), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.full_access_explanation),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = onGrantStorageAccess) { Text(stringResource(R.string.grant_file_access)) }
                    }
                }
            }
        }

        if (uiState.devices.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.no_storage_devices),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(uiState.devices, key = StorageDevice::id) { device ->
            StorageDeviceCard(
                device = device,
                onClick = { onOpenDevice(device) },
            )
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.last_checked, uiState.lastChecked),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onRefresh) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Text(stringResource(R.string.refresh), Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

private fun android.content.Context.hasRequiredStorageAccess(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
            (Build.VERSION.SDK_INT > Build.VERSION_CODES.P ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED)
    }

@Composable
private fun StorageDeviceCard(
    device: StorageDevice,
    onClick: () -> Unit,
) {
    ElevatedCard(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Column(
            Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(device.icon(), contentDescription = null)
                Column {
                    Text(device.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        device.type,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(stringResource(R.string.storage_used, formatBytes(device.usedBytes), formatBytes(device.totalBytes)))
            LinearProgressIndicator(
                progress = { device.usedFraction },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.storage_available, formatBytes(device.availableBytes)),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.tap_open_directory),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

private fun StorageDevice.icon(): ImageVector = when {
    !isRemovable -> Icons.Default.Storage
    type.contains("usb", ignoreCase = true) || name.contains("usb", ignoreCase = true) -> Icons.Default.Usb
    else -> Icons.Default.SdStorage
}

private fun formatBytes(bytes: Long): String {
    val gigabytes = bytes / 1_000_000_000.0
    return "${DecimalFormat("0.#").format(gigabytes)} GB"
}

@Preview(showBackground = true)
@Composable
fun StorageScreenPreview() {
    SCRTVWRTheme {
        StorageScreen(
            uiState = StorageUiState(
                devices = listOf(
                    StorageDevice(
                        id = "primary",
                        name = "Internal shared storage",
                        type = "Device storage",
                        rootPath = "/storage/emulated/0",
                        totalBytes = 256_000_000_000,
                        availableBytes = 136_000_000_000,
                        isRemovable = false,
                    ),
                    StorageDevice(
                        id = "sd-card",
                        name = "SD card",
                        type = "Removable storage",
                        rootPath = "/storage/1234-5678",
                        totalBytes = 64_000_000_000,
                        availableBytes = 20_000_000_000,
                        isRemovable = true,
                    ),
                ),
                lastChecked = "Oct 27, 2023 10:00 AM"
            ),
            onRefresh = {},
            onOpenDevice = {},
        )
    }
}
