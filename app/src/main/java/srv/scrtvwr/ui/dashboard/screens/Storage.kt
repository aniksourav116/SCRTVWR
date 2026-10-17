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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import srv.scrtvwr.media.remote.HttpServer
import srv.scrtvwr.media.remote.Ipv4ServerAddress
import srv.scrtvwr.ui.dashboard.screens.viewmodels.HttpServerDevice
import srv.scrtvwr.ui.dashboard.screens.viewmodels.HttpServerInputError
import srv.scrtvwr.ui.dashboard.screens.viewmodels.HttpServerStatus
import srv.scrtvwr.ui.dashboard.screens.viewmodels.StorageDevice
import srv.scrtvwr.ui.dashboard.screens.viewmodels.StorageUiState
import srv.scrtvwr.ui.dashboard.screens.viewmodels.StorageViewModel
import srv.scrtvwr.ui.components.hasLocalNetworkAccess
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

    var hasLocalNetworkAccess by remember { mutableStateOf(context.hasLocalNetworkAccess()) }
    var pendingServerUrl by remember { mutableStateOf<String?>(null) }
    val localNetworkPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasLocalNetworkAccess = granted
        viewModel.refresh()
        // Servers outside the local network work without the permission, so open either way.
        pendingServerUrl?.let(onOpenDirectory)
        pendingServerUrl = null
    }
    val requestLocalNetworkAccess = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
            localNetworkPermissionLauncher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            // Access can also be granted or revoked in system settings while the app is away.
            if (event == Lifecycle.Event.ON_RESUME && context.hasLocalNetworkAccess() != hasLocalNetworkAccess) {
                hasLocalNetworkAccess = context.hasLocalNetworkAccess()
                viewModel.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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
        // Server folders are read over the network, so they need no file access permission.
        onOpenHttpServer = { server ->
            if (context.hasLocalNetworkAccess()) {
                onOpenDirectory(server.baseUrl)
            } else {
                pendingServerUrl = server.baseUrl
                requestLocalNetworkAccess()
            }
        },
        onAddHttpServer = { name, address ->
            viewModel.addHttpServer(name, address).also { error ->
                if (error == null && !context.hasLocalNetworkAccess()) requestLocalNetworkAccess()
            }
        },
        onRemoveHttpServer = viewModel::removeHttpServer,
        onConnectHttpServer = { server ->
            viewModel.connectHttpServer(server)
            if (!context.hasLocalNetworkAccess()) requestLocalNetworkAccess()
        },
        onDisconnectHttpServer = viewModel::disconnectHttpServer,
        onHttpServerAutoReconnectChange = viewModel::setHttpServerAutoReconnect,
        hasLocalNetworkAccess = hasLocalNetworkAccess,
        onGrantLocalNetworkAccess = requestLocalNetworkAccess,
        onOpenAppSettings = {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
            )
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
    onOpenHttpServer: (HttpServer) -> Unit = {},
    onAddHttpServer: (name: String, address: String) -> HttpServerInputError? = { _, _ -> null },
    onRemoveHttpServer: (HttpServer) -> Unit = {},
    onConnectHttpServer: (HttpServer) -> Unit = {},
    onDisconnectHttpServer: (HttpServer) -> Unit = {},
    onHttpServerAutoReconnectChange: (HttpServer, Boolean) -> Unit = { _, _ -> },
    hasLocalNetworkAccess: Boolean = true,
    onGrantLocalNetworkAccess: () -> Unit = {},
    onOpenAppSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var showAddServerDialog by remember { mutableStateOf(false) }
    var serverPendingRemoval by remember { mutableStateOf<HttpServer?>(null) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
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

        item(key = "http_servers_header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.http_servers),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = { showAddServerDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text(stringResource(R.string.add), Modifier.padding(start = 8.dp))
                }
            }
        }

        if (uiState.httpServers.isNotEmpty() && !hasLocalNetworkAccess) {
            item(key = "local_network_access") {
                LocalNetworkAccessCard(
                    onAllow = onGrantLocalNetworkAccess,
                    onOpenAppSettings = onOpenAppSettings,
                )
            }
        }

        if (uiState.httpServers.isEmpty()) {
            item(key = "http_servers_empty") {
                HttpServerHelp()
            }
        }

        items(uiState.httpServers, key = { "http:${it.server.id}" }) { device ->
            HttpServerCard(
                device = device,
                onClick = { onOpenHttpServer(device.server) },
                onRemove = { serverPendingRemoval = device.server },
                onConnect = { onConnectHttpServer(device.server) },
                onDisconnect = { onDisconnectHttpServer(device.server) },
                onAutoReconnectChange = { enabled -> onHttpServerAutoReconnectChange(device.server, enabled) },
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

    if (showAddServerDialog) {
        AddHttpServerDialog(
            onDismiss = { showAddServerDialog = false },
            onAdd = { name, address ->
                onAddHttpServer(name, address).also { error ->
                    if (error == null) showAddServerDialog = false
                }
            },
        )
    }

    serverPendingRemoval?.let { server ->
        AlertDialog(
            onDismissRequest = { serverPendingRemoval = null },
            title = { Text(stringResource(R.string.remove_http_server_title)) },
            text = { Text(stringResource(R.string.remove_http_server_message, server.name)) },
            confirmButton = {
                Button(
                    onClick = {
                        onRemoveHttpServer(server)
                        serverPendingRemoval = null
                    },
                ) {
                    Text(stringResource(R.string.remove))
                }
            },
            dismissButton = {
                TextButton(onClick = { serverPendingRemoval = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun LocalNetworkAccessCard(
    onAllow: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.local_network_access_required), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.local_network_access_explanation),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAllow) { Text(stringResource(R.string.allow_local_network_access)) }
                TextButton(onClick = onOpenAppSettings) { Text(stringResource(R.string.app_settings)) }
            }
        }
    }
}

@Composable
private fun HttpServerHelp() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.http_server_help),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.http_server_command),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun HttpServerCard(
    device: HttpServerDevice,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onAutoReconnectChange: (Boolean) -> Unit,
) {
    ElevatedCard(
        Modifier
            .fillMaxWidth()
            // A disconnected server is not browsed until it is connected again.
            .clickable(enabled = device.isConnected, role = Role.Button, onClick = onClick),
    ) {
        Column(
            Modifier.padding(start = 20.dp, top = 12.dp, end = 8.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Default.Dns, contentDescription = null)
                Column(Modifier.weight(1f)) {
                    Text(
                        device.server.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        device.server.baseUrl,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onRemove) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.remove_http_server))
                }
            }
            when (val status = device.status) {
                HttpServerStatus.Disconnected -> Text(
                    stringResource(R.string.http_server_disconnected),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HttpServerStatus.Checking -> {
                    Text(
                        stringResource(R.string.http_server_checking),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(end = 12.dp))
                }
                is HttpServerStatus.Online -> Text(
                    pluralStringResource(R.plurals.http_server_online, status.itemCount, status.itemCount),
                )
                is HttpServerStatus.Offline -> {
                    Text(
                        status.reason?.let { stringResource(R.string.http_server_offline, it) }
                            ?: stringResource(R.string.http_server_offline_unknown),
                        color = MaterialTheme.colorScheme.error,
                    )
                    if (device.server.autoReconnect) {
                        Text(
                            stringResource(R.string.http_server_reconnecting),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                HttpServerStatus.LocalNetworkBlocked -> Text(
                    stringResource(R.string.http_server_local_network_blocked),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(
                modifier = Modifier.padding(end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.http_server_auto_reconnect))
                    Text(
                        stringResource(R.string.http_server_auto_reconnect_summary),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = device.server.autoReconnect,
                    onCheckedChange = onAutoReconnectChange,
                )
            }
            Row(
                modifier = Modifier.padding(end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (device.isConnected) {
                    Text(
                        stringResource(R.string.tap_open_directory),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = onDisconnect) {
                        Icon(Icons.Default.LinkOff, contentDescription = null)
                        Text(stringResource(R.string.disconnect), Modifier.padding(start = 8.dp))
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                    Button(onClick = onConnect) {
                        Icon(Icons.Default.Link, contentDescription = null)
                        Text(stringResource(R.string.connect), Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun AddHttpServerDialog(
    onDismiss: () -> Unit,
    onAdd: (name: String, address: String) -> HttpServerInputError?,
) {
    var name by remember { mutableStateOf("") }
    var splitInput by remember { mutableStateOf(true) }
    var splitAddress by remember { mutableStateOf(Ipv4ServerAddress.Default) }
    var address by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<HttpServerInputError?>(null) }
    val submit = {
        val input = if (splitInput) splitAddress.toAddress() else address
        error = if (input == null) HttpServerInputError.InvalidAddress else onAdd(name, input)
    }
    val canSubmit = if (splitInput) {
        splitAddress.octets.none(String::isBlank) && splitAddress.port.isNotBlank()
    } else {
        address.isNotBlank()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_http_server)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = splitInput,
                        onClick = {
                            // Carry what was typed over, so switching never loses an address.
                            Ipv4ServerAddress.parse(address)?.let { splitAddress = it }
                            splitInput = true
                            error = null
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) {
                        Text(stringResource(R.string.http_server_input_split))
                    }
                    SegmentedButton(
                        selected = !splitInput,
                        onClick = {
                            splitAddress.toAddress()?.let { address = it }
                            splitInput = false
                            error = null
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) {
                        Text(stringResource(R.string.http_server_input_address))
                    }
                }
                if (splitInput) {
                    SplitServerAddressField(
                        value = splitAddress,
                        onValueChange = {
                            splitAddress = it
                            error = null
                        },
                        error = error,
                    )
                } else {
                    OutlinedTextField(
                        value = address,
                        onValueChange = {
                            address = it
                            error = null
                        },
                        label = { Text(stringResource(R.string.http_server_address)) },
                        placeholder = { Text(stringResource(R.string.http_server_address_placeholder)) },
                        supportingText = { Text(error.message(splitInput = false)) },
                        isError = error != null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.http_server_name)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                HttpServerHelp()
            }
        },
        confirmButton = {
            Button(onClick = submit, enabled = canSubmit) {
                Text(stringResource(R.string.add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

/**
 * Four octet boxes and a port box. Focus moves on by itself once an octet has three digits or a
 * `.` is typed, back again on backspace in an empty box, and pasting a whole address into an
 * octet box fills every box.
 */
@Composable
private fun SplitServerAddressField(
    value: Ipv4ServerAddress,
    onValueChange: (Ipv4ServerAddress) -> Unit,
    error: HttpServerInputError?,
) {
    val portIndex = Ipv4ServerAddress.OCTET_COUNT
    val focusRequesters = remember { List(portIndex + 1) { FocusRequester() } }
    val isError = error != null
    val messageColor = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.http_server_address),
            style = MaterialTheme.typography.labelLarge,
            color = messageColor,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            value.octets.forEachIndexed { index, octet ->
                AddressPartField(
                    value = octet,
                    onValueChange = { input ->
                        val pasted = Ipv4ServerAddress.parse(input)
                        if (pasted != null) {
                            onValueChange(pasted.copy(port = pasted.port.ifEmpty { value.port }))
                            focusRequesters[portIndex].requestFocus()
                        } else {
                            val digits = input.filter(Char::isDigit).take(3)
                            onValueChange(value.copy(octets = value.octets.toMutableList().apply { set(index, digits) }))
                            val typedSeparator = input.endsWith('.') || input.endsWith(':')
                            if (typedSeparator || (digits.length == 3 && digits.length > octet.length)) {
                                focusRequesters[index + 1].requestFocus()
                            }
                        }
                    },
                    onBackspaceWhenEmpty = { if (index > 0) focusRequesters[index - 1].requestFocus() },
                    isError = isError,
                    keyboardType = KeyboardType.Decimal,
                    contentDescription = stringResource(R.string.http_server_octet, index + 1),
                    modifier = Modifier.weight(1f).focusRequester(focusRequesters[index]),
                )
                Text(
                    if (index < portIndex - 1) "." else ":",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
            }
            AddressPartField(
                value = value.port,
                onValueChange = { input -> onValueChange(value.copy(port = input.filter(Char::isDigit).take(5))) },
                onBackspaceWhenEmpty = { focusRequesters[portIndex - 1].requestFocus() },
                isError = isError,
                keyboardType = KeyboardType.Number,
                contentDescription = stringResource(R.string.http_server_port),
                placeholder = stringResource(R.string.http_server_port_placeholder),
                modifier = Modifier.weight(1.4f).focusRequester(focusRequesters[portIndex]),
            )
        }
        Text(
            error.message(splitInput = true),
            style = MaterialTheme.typography.bodySmall,
            color = messageColor,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

/**
 * Outlined box narrow enough for five to share a dialog row; [OutlinedTextField] pads its text
 * too widely for that.
 */
@Composable
private fun AddressPartField(
    value: String,
    onValueChange: (String) -> Unit,
    onBackspaceWhenEmpty: () -> Unit,
    isError: Boolean,
    keyboardType: KeyboardType,
    contentDescription: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val colors = OutlinedTextFieldDefaults.colors()
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        ),
        cursorBrush = SolidColor(if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Next),
        interactionSource = interactionSource,
        modifier = modifier
            .semantics { this.contentDescription = contentDescription }
            .onPreviewKeyEvent { event ->
                val isBackspace = event.type == KeyEventType.KeyDown && event.key == Key.Backspace
                if (isBackspace && value.isEmpty()) {
                    onBackspaceWhenEmpty()
                    true
                } else {
                    false
                }
            },
        decorationBox = { innerTextField ->
            OutlinedTextFieldDefaults.DecorationBox(
                value = value,
                innerTextField = innerTextField,
                enabled = true,
                singleLine = true,
                visualTransformation = VisualTransformation.None,
                interactionSource = interactionSource,
                isError = isError,
                placeholder = placeholder?.let { text ->
                    { Text(text, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
                },
                colors = colors,
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 14.dp),
            )
        },
    )
}

@Composable
private fun HttpServerInputError?.message(splitInput: Boolean): String = stringResource(
    when (this) {
        HttpServerInputError.InvalidAddress ->
            if (splitInput) R.string.http_server_invalid_ip_port else R.string.http_server_invalid_address
        HttpServerInputError.AlreadyAdded -> R.string.http_server_already_added
        null -> R.string.http_server_address_help
    },
)

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
                httpServers = listOf(
                    HttpServerDevice(
                        server = HttpServer(
                            id = "desktop",
                            name = "Desktop",
                            baseUrl = "http://192.168.1.20:8000/",
                        ),
                        status = HttpServerStatus.Online(itemCount = 12),
                    ),
                ),
                lastChecked = "Oct 27, 2023 10:00 AM"
            ),
            onRefresh = {},
            onOpenDevice = {},
        )
    }
}
