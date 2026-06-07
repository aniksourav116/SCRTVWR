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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import srv.scrtvwr.ui.dashboard.screens.viewmodels.StorageDevice
import srv.scrtvwr.ui.dashboard.screens.viewmodels.StorageUiState
import srv.scrtvwr.ui.dashboard.screens.viewmodels.StorageViewModel
import srv.scrtvwr.ui.theme.SCRTVWRTheme
import java.text.DecimalFormat

@Composable
fun Storage(
    modifier: Modifier = Modifier,
    viewModel: StorageViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val legacyPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.refresh() }
    val allFilesAccessLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { viewModel.refresh() }

    LaunchedEffect(Unit) {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                !Environment.isExternalStorageManager() -> {
                val intent = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${context.packageName}"),
                )
                allFilesAccessLauncher.launch(intent)
            }

            Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                ) != PackageManager.PERMISSION_GRANTED -> {
                legacyPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
    }

    StorageScreen(uiState, viewModel::refresh, modifier)
}

@Composable
fun StorageScreen(
    uiState: StorageUiState,
    onRefresh: () -> Unit,
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
                Text("Storage devices", style = MaterialTheme.typography.headlineSmall)
            }
        }

        if (uiState.devices.isEmpty()) {
            item {
                Text(
                    "No mounted storage devices are available.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(uiState.devices, key = StorageDevice::id) { device ->
            StorageDeviceCard(device)
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Last checked: ${uiState.lastChecked}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onRefresh) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Text("Refresh", Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun StorageDeviceCard(device: StorageDevice) {
    ElevatedCard(Modifier.fillMaxWidth()) {
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
            Text("${formatBytes(device.usedBytes)} of ${formatBytes(device.totalBytes)} used")
            LinearProgressIndicator(
                progress = { device.usedFraction },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "${formatBytes(device.availableBytes)} available",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun StorageDevice.icon(): ImageVector = when (type) {
    "USB storage" -> Icons.Default.Usb
    "Removable storage" -> Icons.Default.SdStorage
    else -> Icons.Default.Storage
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
                        totalBytes = 256_000_000_000,
                        availableBytes = 136_000_000_000,
                        isRemovable = false,
                    ),
                    StorageDevice(
                        id = "sd-card",
                        name = "SD card",
                        type = "Removable storage",
                        totalBytes = 64_000_000_000,
                        availableBytes = 20_000_000_000,
                        isRemovable = true,
                    ),
                ),
                lastChecked = "Oct 27, 2023 10:00 AM"
            ),
            onRefresh = {}
        )
    }
}
