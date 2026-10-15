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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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
import srv.scrtvwr.media.remote.PinnedCertificates
import srv.scrtvwr.media.remote.ServerCertificate
import srv.scrtvwr.ui.dashboard.screens.viewmodels.HttpServerDevice
import srv.scrtvwr.ui.dashboard.screens.viewmodels.HttpServerInput
import srv.scrtvwr.ui.dashboard.screens.viewmodels.HttpServerInputError
import srv.scrtvwr.ui.dashboard.screens.viewmodels.HttpServerStatus
import srv.scrtvwr.ui.dashboard.screens.viewmodels.StorageDevice
import srv.scrtvwr.ui.dashboard.screens.viewmodels.StorageUiState
import srv.scrtvwr.ui.dashboard.screens.viewmodels.StorageViewModel
import srv.scrtvwr.ui.components.hasLocalNetworkAccess
import srv.scrtvwr.ui.theme.SCRTVWRTheme
import srv.scrtvwr.R
import java.text.DateFormat
import java.text.DecimalFormat
import java.util.Date

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
        onAddHttpServer = { input ->
            viewModel.addHttpServer(input).also { error ->
                if (error == null && !context.hasLocalNetworkAccess()) requestLocalNetworkAccess()
            }
        },
        onEditHttpServer = { server, input -> viewModel.updateHttpServer(server.id, input) },
        onRemoveHttpServer = viewModel::removeHttpServer,
        onTrustCertificate = viewModel::trustCertificate,
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
    onAddHttpServer: (HttpServerInput) -> HttpServerInputError? = { null },
    onEditHttpServer: (HttpServer, HttpServerInput) -> HttpServerInputError? = { _, _ -> null },
    onRemoveHttpServer: (HttpServer) -> Unit = {},
    onTrustCertificate: (HttpServer, ServerCertificate) -> Unit = { _, _ -> },
    hasLocalNetworkAccess: Boolean = true,
    onGrantLocalNetworkAccess: () -> Unit = {},
    onOpenAppSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var showAddServerDialog by remember { mutableStateOf(false) }
    var serverBeingEdited by remember { mutableStateOf<HttpServerDevice?>(null) }
    var serverPendingRemoval by remember { mutableStateOf<HttpServer?>(null) }
    var certificateUnderReview by remember {
        mutableStateOf<Pair<HttpServer, HttpServerStatus.UntrustedCertificate>?>(null)
    }

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
                onEdit = { serverBeingEdited = device },
                onRemove = { serverPendingRemoval = device.server },
                onReviewCertificate = { status -> certificateUnderReview = device.server to status },
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
        HttpServerDialog(
            initial = null,
            onDismiss = { showAddServerDialog = false },
            onSave = { input ->
                onAddHttpServer(input).also { error ->
                    if (error == null) showAddServerDialog = false
                }
            },
        )
    }

    serverBeingEdited?.let { device ->
        HttpServerDialog(
            initial = device,
            onDismiss = { serverBeingEdited = null },
            onSave = { input ->
                onEditHttpServer(device.server, input).also { error ->
                    if (error == null) serverBeingEdited = null
                }
            },
        )
    }

    certificateUnderReview?.let { (server, status) ->
        CertificateReviewDialog(
            serverName = server.name,
            status = status,
            onDismiss = { certificateUnderReview = null },
            onTrust = {
                onTrustCertificate(server, status.certificate)
                certificateUnderReview = null
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
        Text(
            stringResource(R.string.http_server_auth_help),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            stringResource(R.string.http_server_auth_command),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun HttpServerCard(
    device: HttpServerDevice,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
    onReviewCertificate: (HttpServerStatus.UntrustedCertificate) -> Unit,
) {
    ElevatedCard(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick),
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
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.edit_http_server))
                }
                IconButton(onClick = onRemove) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.remove_http_server))
                }
            }
            when (val status = device.status) {
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
                is HttpServerStatus.Offline -> Text(
                    status.reason?.let { stringResource(R.string.http_server_offline, it) }
                        ?: stringResource(R.string.http_server_offline_unknown),
                    color = MaterialTheme.colorScheme.error,
                )
                HttpServerStatus.LocalNetworkBlocked -> Text(
                    stringResource(R.string.http_server_local_network_blocked),
                    color = MaterialTheme.colorScheme.error,
                )
                HttpServerStatus.AuthRequired -> HttpServerProblem(
                    message = stringResource(R.string.http_server_auth_required),
                    action = stringResource(R.string.http_server_sign_in),
                    onAction = onEdit,
                )
                HttpServerStatus.AuthFailed -> HttpServerProblem(
                    message = stringResource(R.string.http_server_auth_failed),
                    action = stringResource(R.string.http_server_sign_in),
                    onAction = onEdit,
                )
                is HttpServerStatus.UntrustedCertificate -> HttpServerProblem(
                    message = stringResource(
                        if (status.changed) R.string.http_server_certificate_changed else R.string.http_server_certificate_untrusted,
                    ),
                    action = stringResource(R.string.http_server_review_certificate),
                    onAction = { onReviewCertificate(status) },
                )
            }
            Text(
                stringResource(R.string.tap_open_directory),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/** A server card's problem the user can fix, with the action that fixes it. */
@Composable
private fun HttpServerProblem(
    message: String,
    action: String,
    onAction: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            message,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onAction) { Text(action) }
    }
}

/** Adds a server, or edits [initial] when it is given. */
@Composable
private fun HttpServerDialog(
    initial: HttpServerDevice?,
    onDismiss: () -> Unit,
    onSave: (HttpServerInput) -> HttpServerInputError?,
) {
    var name by remember { mutableStateOf(initial?.server?.name.orEmpty()) }
    var address by remember { mutableStateOf(initial?.server?.baseUrl.orEmpty()) }
    var username by remember { mutableStateOf(initial?.username.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<HttpServerInputError?>(null) }
    val hasSavedPassword = initial?.username != null
    // Addresses typed without a scheme are plain http:// too.
    val sendsPasswordUnencrypted = username.isNotBlank() && !address.trim().startsWith("https://", ignoreCase = true)
    val submit = { error = onSave(HttpServerInput(name = name, address = address, username = username, password = password)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) R.string.add_http_server else R.string.edit_http_server)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = address,
                    onValueChange = {
                        address = it
                        error = null
                    },
                    label = { Text(stringResource(R.string.http_server_address)) },
                    placeholder = { Text(stringResource(R.string.http_server_address_placeholder)) },
                    supportingText = {
                        Text(
                            when (error) {
                                HttpServerInputError.InvalidAddress -> stringResource(R.string.http_server_invalid_address)
                                HttpServerInputError.AlreadyAdded -> stringResource(R.string.http_server_already_added)
                                null -> stringResource(R.string.http_server_address_help)
                            },
                        )
                    },
                    isError = error != null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.http_server_name)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(R.string.http_server_username)) },
                    supportingText = { Text(stringResource(R.string.http_server_username_help)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.http_server_password)) },
                    placeholder = {
                        if (hasSavedPassword) Text(stringResource(R.string.http_server_password_unchanged))
                    },
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = stringResource(
                                    if (passwordVisible) R.string.hide_password else R.string.show_password,
                                ),
                            )
                        }
                    },
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { if (address.isNotBlank()) submit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (sendsPasswordUnencrypted) {
                    Text(
                        stringResource(R.string.http_server_unencrypted_password_warning),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (initial == null) HttpServerHelp()
            }
        },
        confirmButton = {
            Button(onClick = submit, enabled = address.isNotBlank()) {
                Text(stringResource(if (initial == null) R.string.add else R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

/** Shows a server's untrusted certificate so the user can compare its fingerprint and trust it. */
@Composable
private fun CertificateReviewDialog(
    serverName: String,
    status: HttpServerStatus.UntrustedCertificate,
    onDismiss: () -> Unit,
    onTrust: () -> Unit,
) {
    val certificate = status.certificate
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (status.changed) R.string.http_server_certificate_changed_title else R.string.http_server_certificate_title,
                ),
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(
                        if (status.changed) {
                            R.string.http_server_certificate_changed_message
                        } else {
                            R.string.http_server_certificate_message
                        },
                        serverName,
                    ),
                )
                CertificateDetail(
                    label = stringResource(R.string.http_server_certificate_fingerprint),
                    value = PinnedCertificates.format(certificate.sha256),
                    monospace = true,
                )
                CertificateDetail(
                    label = stringResource(R.string.http_server_certificate_subject),
                    value = certificate.subject,
                )
                CertificateDetail(
                    label = stringResource(R.string.http_server_certificate_expires),
                    value = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(certificate.expiresAtMillis)),
                )
            }
        },
        confirmButton = {
            Button(onClick = onTrust) {
                Text(stringResource(R.string.trust))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun CertificateDetail(
    label: String,
    value: String,
    monospace: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
        )
        SelectionContainer {
            Text(
                value,
                fontFamily = if (monospace) FontFamily.Monospace else null,
                style = MaterialTheme.typography.bodyMedium,
            )
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
