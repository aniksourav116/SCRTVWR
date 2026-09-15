package srv.scrtvwr.ui.dashboard.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.res.Configuration
import android.content.Intent
import android.text.TextPaint
import android.text.TextUtils
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import srv.scrtvwr.media.MediaPreviewLoader
import srv.scrtvwr.media.PreviewResult
import srv.scrtvwr.MediaViewerActivity
import srv.scrtvwr.VideoPlayerActivity
import srv.scrtvwr.R
import srv.scrtvwr.ui.dashboard.screens.viewmodels.DirectoryEntry
import srv.scrtvwr.ui.dashboard.screens.viewmodels.DirectoryFileOperation
import srv.scrtvwr.ui.dashboard.screens.viewmodels.DirectoryFileOperationStatus
import srv.scrtvwr.ui.dashboard.screens.viewmodels.DirectoryListing
import srv.scrtvwr.ui.dashboard.screens.viewmodels.DirectoryMediaFilter
import srv.scrtvwr.ui.dashboard.screens.viewmodels.DirectoryMediaType
import srv.scrtvwr.ui.dashboard.screens.viewmodels.DirectoryScrollPosition
import srv.scrtvwr.ui.dashboard.screens.viewmodels.DirectoryUiState
import srv.scrtvwr.ui.dashboard.screens.viewmodels.DirectoryViewModel
import srv.scrtvwr.ui.dashboard.screens.viewmodels.DirectoryViewMode
import srv.scrtvwr.ui.dashboard.screens.viewmodels.PendingDirectoryFileOperation
import srv.scrtvwr.ui.components.BaseScaffold
import srv.scrtvwr.ui.sort.DirectorySortOrder
import srv.scrtvwr.tagging.ImageTags
import srv.scrtvwr.ui.tagging.CharacterTagBadge
import srv.scrtvwr.ui.tagging.CharactersDialog
import srv.scrtvwr.ui.tagging.StorageScanConsentDialog
import srv.scrtvwr.ui.tagging.TaggingProgressPanel
import srv.scrtvwr.ui.theme.SCRTVWRTheme
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

@Composable
fun Directory(
    modifier: Modifier = Modifier,
    targetDirectoryPath: String? = null,
    onTargetDirectoryConsumed: () -> Unit = {},
    backHandlerEnabled: Boolean = true,
    isVisible: Boolean = true,
    viewModel: DirectoryViewModel = viewModel(),
) {
    LaunchedEffect(isVisible) {
        if (isVisible) viewModel.refresh()
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, isVisible) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && isVisible) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val mediaLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        result.data
            ?.getStringExtra(MediaViewerActivity.RESULT_DIRECTORY_PATH)
            ?.let(viewModel::openDirectoryPath)
    }
    LaunchedEffect(targetDirectoryPath) {
        val directoryPath = targetDirectoryPath ?: return@LaunchedEffect
        viewModel.openDirectoryPath(directoryPath)
        onTargetDirectoryConsumed()
    }
    BackHandler(enabled = backHandlerEnabled && uiState.canNavigateUp) {
        viewModel.navigateUp()
    }

    DirectoryScreen(
        uiState = uiState,
        onScrollPositionRequested = viewModel::scrollPositionFor,
        onSaveScrollPosition = viewModel::saveScrollPosition,
        onSearchQueryChange = viewModel::updateSearchQuery,
        onViewModeChange = viewModel::setViewMode,
        onMediaFilterChange = viewModel::setMediaFilter,
        onSortOrderChange = viewModel::setSortOrder,
        onOpenDirectory = viewModel::openDirectory,
        onOpenRecentDirectory = viewModel::openDirectoryPath,
        onOpenMedia = { entry ->
            val activityClass = if (entry.mediaType == DirectoryMediaType.Video) {
                VideoPlayerActivity::class.java
            } else {
                MediaViewerActivity::class.java
            }
            val intent = Intent(context, activityClass).apply {
                    if (entry.mediaType == DirectoryMediaType.Video) {
                        putExtra(VideoPlayerActivity.EXTRA_DIRECTORY_PATH, uiState.currentDirectory.absolutePath)
                        putExtra(VideoPlayerActivity.EXTRA_VIDEO_PATH, entry.path)
                    } else {
                        putExtra(MediaViewerActivity.EXTRA_DIRECTORY_PATH, uiState.currentDirectory.absolutePath)
                        putExtra(MediaViewerActivity.EXTRA_MEDIA_PATH, entry.path)
                    }
                }
            mediaLauncher.launch(intent)
        },
        onOpenWithOtherApp = { entry -> context.openWithOtherApp(entry) },
        onResumeMedia = {
            uiState.lastMediaSession?.let { session ->
                val isVideo = session.mediaPath?.substringAfterLast('.', "")?.lowercase() in VIDEO_EXTENSIONS
                val activityClass = if (isVideo) VideoPlayerActivity::class.java else MediaViewerActivity::class.java
                val intent = Intent(context, activityClass).apply {
                        if (isVideo) {
                            putExtra(VideoPlayerActivity.EXTRA_DIRECTORY_PATH, session.directoryPath)
                            putExtra(VideoPlayerActivity.EXTRA_VIDEO_PATH, session.mediaPath)
                        } else {
                            putExtra(MediaViewerActivity.EXTRA_DIRECTORY_PATH, session.directoryPath)
                            putExtra(MediaViewerActivity.EXTRA_MEDIA_PATH, session.mediaPath)
                        }
                    }
                mediaLauncher.launch(intent)
            }
        },
        onNavigateUp = viewModel::navigateUp,
        onRefresh = viewModel::refresh,
        onCopyEntry = viewModel::prepareCopy,
        onMoveEntry = viewModel::prepareMove,
        onDeleteEntry = viewModel::deleteEntry,
        onPasteFileOperation = viewModel::pastePendingFileOperation,
        onCancelFileOperation = viewModel::cancelFileOperation,
        onClearFileOperationStatus = viewModel::clearFileOperationStatus,
        onCharacterFilterChange = viewModel::setCharacterFilter,
        onRenameCharacter = viewModel::renameCharacter,
        onRemoveCharacter = viewModel::removeCharacter,
        onStorageScanConsent = viewModel::onStorageScanConsent,
        onStopStorageScan = viewModel::stopStorageScan,
        onTaggingPausedChange = viewModel::setTaggingPaused,
        modifier = modifier,
    )
}

@Composable
fun DirectoryScreen(
    uiState: DirectoryUiState,
    onScrollPositionRequested: (String) -> DirectoryScrollPosition?,
    onSaveScrollPosition: (String, DirectoryScrollPosition) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onViewModeChange: (DirectoryViewMode) -> Unit,
    onMediaFilterChange: (DirectoryMediaFilter) -> Unit,
    onSortOrderChange: (DirectorySortOrder) -> Unit,
    onOpenDirectory: (DirectoryEntry) -> Unit,
    onOpenRecentDirectory: (String) -> Unit,
    onOpenMedia: (DirectoryEntry) -> Unit,
    onOpenWithOtherApp: (DirectoryEntry) -> Unit,
    onResumeMedia: () -> Unit,
    onNavigateUp: () -> Unit,
    onRefresh: () -> Unit,
    onCopyEntry: (DirectoryEntry) -> Unit,
    onMoveEntry: (DirectoryEntry) -> Unit,
    onDeleteEntry: (DirectoryEntry) -> Unit,
    onPasteFileOperation: () -> Unit,
    onCancelFileOperation: () -> Unit,
    onClearFileOperationStatus: () -> Unit,
    onCharacterFilterChange: (String?) -> Unit = {},
    onRenameCharacter: (String, String?) -> Unit = { _, _ -> },
    onRemoveCharacter: (String) -> Unit = {},
    onStorageScanConsent: (Boolean) -> Unit = {},
    onStopStorageScan: () -> Unit = {},
    onTaggingPausedChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var moreMenuExpanded by remember { mutableStateOf(false) }
    var sortMenuExpanded by remember { mutableStateOf(false) }
    var searchVisible by remember { mutableStateOf(false) }
    var charactersVisible by remember { mutableStateOf(false) }
    var entryPendingDeletion by remember { mutableStateOf<DirectoryEntry?>(null) }
    val directoryName = uiState.currentDirectory.name.ifBlank { uiState.currentDirectory.absolutePath }
    val context = LocalContext.current
    // Characters keep a generated name until they are renamed, and that name is a string resource.
    val characterNames = remember(uiState.characters) {
        uiState.characters.associate { profile ->
            profile.id to (profile.label ?: context.getString(R.string.character_number, profile.ordinal))
        }
    }

    BaseScaffold(
        title = directoryName,
        subtitle = uiState.currentDirectory.absolutePath,
        showBackButton = false,
        actions = {
            Box {
                IconButton(onClick = { sortMenuExpanded = true }) {
                    Icon(
                        Icons.AutoMirrored.Filled.Sort,
                        contentDescription = stringResource(R.string.sort_order),
                    )
                }
                DropdownMenu(
                    expanded = sortMenuExpanded,
                    onDismissRequest = { sortMenuExpanded = false },
                ) {
                    DirectorySortOrder.entries.forEach { sortOrder ->
                        DropdownMenuItem(
                            text = { Text(stringResource(sortOrder.labelResource())) },
                            onClick = {
                                onSortOrderChange(sortOrder)
                                sortMenuExpanded = false
                            },
                            trailingIcon = if (sortOrder == uiState.sortOrder) {
                                { Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
            IconButton(
                onClick = {
                    onViewModeChange(
                        if (uiState.viewMode == DirectoryViewMode.List) DirectoryViewMode.Grid else DirectoryViewMode.List,
                    )
                },
            ) {
                Icon(
                    imageVector = if (uiState.viewMode == DirectoryViewMode.List) Icons.Default.GridView else Icons.AutoMirrored.Filled.ViewList,
                    contentDescription = stringResource(
                        if (uiState.viewMode == DirectoryViewMode.List) R.string.grid_view else R.string.list_view,
                    ),
                )
            }
            IconButton(onClick = { searchVisible = true }) {
                Icon(Icons.Default.Search, contentDescription = stringResource(R.string.search_files_folders))
            }
            Box {
                IconButton(onClick = { moreMenuExpanded = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_options))
                }
                DirectoryOptionsMenu(
                    expanded = moreMenuExpanded,
                    onDismissRequest = { moreMenuExpanded = false },
                    uiState = uiState,
                    onRefresh = onRefresh,
                    onResumeMedia = onResumeMedia,
                    onOpenRecentDirectory = onOpenRecentDirectory,
                    onShowCharacters = { charactersVisible = true },
                )
            }
        },
    ) { contentModifier ->
        Column(
            modifier = contentModifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
        if (searchVisible) {
            DirectorySearchField(
                query = uiState.searchQuery,
                onQueryChange = onSearchQueryChange,
                onDismiss = { searchVisible = false },
            )
        }

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DirectoryMediaFilter.entries.forEach { filter ->
                FilterChip(
                    selected = uiState.mediaFilter == filter,
                    onClick = { onMediaFilterChange(filter) },
                    label = { Text(stringResource(filter.labelResource())) },
                )
            }
        }

        uiState.activeCharacter?.let { character ->
            FilterChip(
                selected = true,
                onClick = { onCharacterFilterChange(null) },
                label = {
                    Text(
                        stringResource(
                            R.string.character_filter_active,
                            characterNames[character.id].orEmpty(),
                        ),
                    )
                },
                leadingIcon = { Icon(Icons.Default.Face, contentDescription = null) },
                trailingIcon = {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.clear_character_filter))
                },
            )
        }

        if (uiState.tagScanProgress.isRunning) {
            TaggingProgressPanel(
                progress = uiState.tagScanProgress,
                paused = uiState.taggingPaused,
                onPausedChange = onTaggingPausedChange,
                onStop = onStopStorageScan,
            )
        }

        if (uiState.showDirectoryFileActions) {
            DirectoryFileOperationPanel(
                pendingOperation = uiState.pendingFileOperation,
                status = uiState.fileOperationStatus,
                isRunning = uiState.isFileOperationRunning,
                onPaste = onPasteFileOperation,
                onCancel = onCancelFileOperation,
                onDismissStatus = onClearFileOperationStatus,
            )
        }

        if (uiState.isLoading && uiState.entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else when (uiState.viewMode) {
            DirectoryViewMode.List -> DirectoryList(
                uiState = uiState,
                characterNames = characterNames,
                onScrollPositionRequested = onScrollPositionRequested,
                onSaveScrollPosition = onSaveScrollPosition,
                onOpenDirectory = onOpenDirectory,
                onOpenMedia = onOpenMedia,
                onOpenWithOtherApp = onOpenWithOtherApp,
                onNavigateUp = onNavigateUp,
                showFileActions = uiState.showDirectoryFileActions,
                onCopyEntry = onCopyEntry,
                onMoveEntry = onMoveEntry,
                onRequestDeleteEntry = { entryPendingDeletion = it },
            )

            DirectoryViewMode.Grid -> DirectoryGrid(
                uiState = uiState,
                characterNames = characterNames,
                onScrollPositionRequested = onScrollPositionRequested,
                onSaveScrollPosition = onSaveScrollPosition,
                onOpenDirectory = onOpenDirectory,
                onOpenMedia = onOpenMedia,
                onOpenWithOtherApp = onOpenWithOtherApp,
                onNavigateUp = onNavigateUp,
                showFileActions = uiState.showDirectoryFileActions,
                onCopyEntry = onCopyEntry,
                onMoveEntry = onMoveEntry,
                onRequestDeleteEntry = { entryPendingDeletion = it },
            )
        }
    }

    entryPendingDeletion?.let { entry ->
        DeleteEntryDialog(
            entry = entry,
            onDismiss = { entryPendingDeletion = null },
            onDelete = {
                onDeleteEntry(entry)
                entryPendingDeletion = null
            },
        )
    }

    if (charactersVisible) {
        CharactersDialog(
            characters = uiState.characters,
            onRename = onRenameCharacter,
            onRemove = onRemoveCharacter,
            onFilter = onCharacterFilterChange,
            onDismiss = { charactersVisible = false },
        )
    }

    if (uiState.showStorageScanPrompt) {
        StorageScanConsentDialog(
            onConfirm = { onStorageScanConsent(true) },
            onDecline = { onStorageScanConsent(false) },
        )
    }
}
}

@Composable
private fun DirectorySearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text(stringResource(R.string.search_files_folders)) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            IconButton(onClick = {
                onQueryChange("")
                onDismiss()
            }) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.clear_search))
            }
        },
        singleLine = true,
        modifier = modifier
            .fillMaxWidth()
            .wrapContentHeight(),
    )
}

@Composable
private fun DirectoryOptionsMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    uiState: DirectoryUiState,
    onRefresh: () -> Unit,
    onResumeMedia: () -> Unit,
    onOpenRecentDirectory: (String) -> Unit,
    onShowCharacters: () -> Unit = {},
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
    ) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.refresh)) },
            leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
            onClick = {
                onRefresh()
                onDismissRequest()
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.characters)) },
            leadingIcon = { Icon(Icons.Default.Face, contentDescription = null) },
            onClick = {
                onShowCharacters()
                onDismissRequest()
            },
        )
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(stringResource(R.string.resume)) },
            leadingIcon = { Icon(Icons.Default.PlayCircle, contentDescription = null) },
            enabled = uiState.lastMediaSession != null,
            onClick = {
                onResumeMedia()
                onDismissRequest()
            },
        )
        if (uiState.recentDirectories.isNotEmpty()) {
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.recent)) },
                leadingIcon = { Icon(Icons.Default.History, contentDescription = null) },
                enabled = false,
                onClick = {},
            )
        }
        uiState.recentDirectories.forEach { path ->
            DropdownMenuItem(
                text = {
                    Column {
                        Text(
                            text = File(path).name.ifBlank { path },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = path,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null) },
                onClick = {
                    onOpenRecentDirectory(path)
                    onDismissRequest()
                },
            )
        }
    }
}

@Composable
private fun DirectoryToolbarButton(
    icon: @Composable () -> Unit,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
) {
    val contentColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        selected -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = modifier
            .semantics { contentDescription = label }
            .then(
                if (enabled) {
                    Modifier.clickable(role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            ),
        shape = MaterialTheme.shapes.small,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            Color.Transparent
        },
        contentColor = contentColor,
    ) {
        Box(
            modifier = Modifier.padding(8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                icon()
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun DirectoryFileOperationPanel(
    pendingOperation: PendingDirectoryFileOperation?,
    status: DirectoryFileOperationStatus?,
    isRunning: Boolean,
    onPaste: () -> Unit,
    onCancel: () -> Unit,
    onDismissStatus: () -> Unit,
) {
    if (pendingOperation == null && status == null) return

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        pendingOperation?.let { operation ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = stringResource(operation.operation.pendingLabelResource(), operation.name),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = operation.path,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(
                        onClick = onCancel,
                        enabled = !isRunning,
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                    Button(
                        onClick = onPaste,
                        enabled = !isRunning,
                    ) {
                        Text(stringResource(R.string.paste_here))
                    }
                }
            }
        }

        status?.let { operationStatus ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = if (operationStatus.isError) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                },
                contentColor = if (operationStatus.isError) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSecondaryContainer
                },
            ) {
                Row(
                    modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = operationStatus.message,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    IconButton(onClick = onDismissStatus) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close))
                    }
                }
            }
        }
    }
}

@Composable
private fun DeleteEntryDialog(
    entry: DirectoryEntry,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (entry.isDirectory) R.string.delete_folder_title else R.string.delete_file_title,
                ),
            )
        },
        text = {
            Text(stringResource(R.string.delete_item_message, entry.name))
        },
        confirmButton = {
            Button(onClick = onDelete) {
                Text(stringResource(R.string.delete))
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
private fun DirectoryEntryActionMenu(
    entry: DirectoryEntry,
    onCopyEntry: (DirectoryEntry) -> Unit,
    onMoveEntry: (DirectoryEntry) -> Unit,
    onRequestDeleteEntry: (DirectoryEntry) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        Surface(
            shape = MaterialTheme.shapes.extraSmall,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f),
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            IconButton(
                onClick = { expanded = true },
                modifier = Modifier.size(40.dp),
            ) {
                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_options))
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.copy)) },
                onClick = {
                    onCopyEntry(entry)
                    expanded = false
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.move)) },
                onClick = {
                    onMoveEntry(entry)
                    expanded = false
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.delete)) },
                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                onClick = {
                    onRequestDeleteEntry(entry)
                    expanded = false
                },
            )
        }
    }
}

@Composable
private fun DirectoryList(
    uiState: DirectoryUiState,
    characterNames: Map<String, String>,
    onScrollPositionRequested: (String) -> DirectoryScrollPosition?,
    onSaveScrollPosition: (String, DirectoryScrollPosition) -> Unit,
    onOpenDirectory: (DirectoryEntry) -> Unit,
    onOpenMedia: (DirectoryEntry) -> Unit,
    onOpenWithOtherApp: (DirectoryEntry) -> Unit,
    onNavigateUp: () -> Unit,
    showFileActions: Boolean,
    onCopyEntry: (DirectoryEntry) -> Unit,
    onMoveEntry: (DirectoryEntry) -> Unit,
    onRequestDeleteEntry: (DirectoryEntry) -> Unit,
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val directoryPath = uiState.currentDirectory.absolutePath
    val itemCount = uiState.entries.size + if (uiState.canNavigateUp) 1 else if (uiState.entries.isEmpty()) 1 else 0

    LaunchedEffect(directoryPath) {
        val position = onScrollPositionRequested(directoryPath) ?: return@LaunchedEffect
        listState.scrollToItem(
            index = position.firstVisibleItemIndex.coerceIn(0, itemCount - 1),
            scrollOffset = position.firstVisibleItemScrollOffset,
        )
    }
    DisposableEffect(directoryPath) {
        onDispose {
            onSaveScrollPosition(
                directoryPath,
                DirectoryScrollPosition(
                    firstVisibleItemIndex = listState.firstVisibleItemIndex,
                    firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
                ),
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp, end = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (uiState.canNavigateUp) {
                item(key = "parent") {
                    DirectoryListRow(
                        title = "..",
                        subtitle = stringResource(R.string.go_up),
                        thumbnail = { Icon(Icons.Default.ArrowUpward, contentDescription = null) },
                        style = navigationEntryVisualStyle(),
                        onClick = onNavigateUp,
                    )
                }
            }

            if (uiState.entries.isEmpty()) {
                item(key = "empty") {
                    EmptyDirectoryMessage(uiState)
                }
            }

            items(uiState.entries, key = DirectoryEntry::path) { entry ->
                val style = entry.entryVisualStyle()
                DirectoryListRow(
                    title = entry.name,
                    subtitle = entry.supportingText(),
                    thumbnail = {
                        EntryThumbnail(
                            entry = entry,
                            size = 48,
                            style = style,
                            tags = uiState.imageTags[entry.path],
                            characterNames = characterNames,
                        )
                    },
                    style = style,
                    onClick = {
                        when {
                            entry.isDirectory -> onOpenDirectory(entry)
                            entry.mediaType != DirectoryMediaType.Other -> onOpenMedia(entry)
                            else -> onOpenWithOtherApp(entry)
                        }
                    },
                    actions = if (showFileActions) {
                        {
                            DirectoryEntryActionMenu(
                                entry = entry,
                                onCopyEntry = onCopyEntry,
                                onMoveEntry = onMoveEntry,
                                onRequestDeleteEntry = onRequestDeleteEntry,
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }

        DirectoryFastScroller(
            totalItems = listState.layoutInfo.totalItemsCount,
            visibleItems = listState.layoutInfo.visibleItemsInfo.size,
            firstVisibleItemIndex = listState.firstVisibleItemIndex,
            onScrollToItem = { index ->
                coroutineScope.launch { listState.scrollToItem(index) }
            },
            modifier = Modifier.align(Alignment.CenterEnd),
        )
    }
}

@Composable
private fun DirectoryListRow(
    title: String,
    subtitle: String,
    thumbnail: @Composable () -> Unit,
    style: EntryVisualStyle,
    onClick: (() -> Unit)?,
    actions: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(style.containerColor)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(56.dp),
            contentAlignment = Alignment.Center,
        ) {
            thumbnail()
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        actions?.let {
            Spacer(modifier = Modifier.width(8.dp))
            it()
        }
    }
}

@Composable
private fun DirectoryGrid(
    uiState: DirectoryUiState,
    characterNames: Map<String, String>,
    onScrollPositionRequested: (String) -> DirectoryScrollPosition?,
    onSaveScrollPosition: (String, DirectoryScrollPosition) -> Unit,
    onOpenDirectory: (DirectoryEntry) -> Unit,
    onOpenMedia: (DirectoryEntry) -> Unit,
    onOpenWithOtherApp: (DirectoryEntry) -> Unit,
    onNavigateUp: () -> Unit,
    showFileActions: Boolean,
    onCopyEntry: (DirectoryEntry) -> Unit,
    onMoveEntry: (DirectoryEntry) -> Unit,
    onRequestDeleteEntry: (DirectoryEntry) -> Unit,
) {
    val gridState = rememberLazyGridState()
    val coroutineScope = rememberCoroutineScope()
    val directoryPath = uiState.currentDirectory.absolutePath
    val itemCount = uiState.entries.size + if (uiState.canNavigateUp) 1 else if (uiState.entries.isEmpty()) 1 else 0

    LaunchedEffect(directoryPath) {
        val position = onScrollPositionRequested(directoryPath) ?: return@LaunchedEffect
        gridState.scrollToItem(
            index = position.firstVisibleItemIndex.coerceIn(0, itemCount - 1),
            scrollOffset = position.firstVisibleItemScrollOffset,
        )
    }
    DisposableEffect(directoryPath) {
        onDispose {
            onSaveScrollPosition(
                directoryPath,
                DirectoryScrollPosition(
                    firstVisibleItemIndex = gridState.firstVisibleItemIndex,
                    firstVisibleItemScrollOffset = gridState.firstVisibleItemScrollOffset,
                ),
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(112.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 4.dp, end = 28.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (uiState.canNavigateUp) {
                item(key = "parent") {
                    val style = navigationEntryVisualStyle()
                    DirectoryGridCard(
                        title = "..",
                        subtitle = stringResource(R.string.go_up),
                        thumbnail = {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .background(style.thumbnailColor),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Default.ArrowUpward,
                                    contentDescription = null,
                                    tint = style.accentColor,
                                )
                            }
                        },
                        onClick = onNavigateUp,
                    )
                }
            }

            if (uiState.entries.isEmpty()) {
                item(key = "empty") {
                    EmptyDirectoryMessage(uiState)
                }
            }

            items(uiState.entries, key = DirectoryEntry::path) { entry ->
                val style = entry.entryVisualStyle()
                DirectoryGridCard(
                    title = entry.name,
                    subtitle = entry.typeText(),
                    thumbnail = {
                        EntryThumbnail(
                            entry = entry,
                            size = 128,
                            style = style,
                            showFolderBadge = entry.isDirectory,
                            tags = uiState.imageTags[entry.path],
                            characterNames = characterNames,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f),
                        )
                    },
                    onClick = {
                        when {
                            entry.isDirectory -> onOpenDirectory(entry)
                            entry.mediaType != DirectoryMediaType.Other -> onOpenMedia(entry)
                            else -> onOpenWithOtherApp(entry)
                        }
                    },
                    actions = if (showFileActions) {
                        {
                            DirectoryEntryActionMenu(
                                entry = entry,
                                onCopyEntry = onCopyEntry,
                                onMoveEntry = onMoveEntry,
                                onRequestDeleteEntry = onRequestDeleteEntry,
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }

        DirectoryFastScroller(
            totalItems = gridState.layoutInfo.totalItemsCount,
            visibleItems = gridState.layoutInfo.visibleItemsInfo.size,
            firstVisibleItemIndex = gridState.firstVisibleItemIndex,
            onScrollToItem = { index ->
                coroutineScope.launch { gridState.scrollToItem(index) }
            },
            modifier = Modifier.align(Alignment.CenterEnd),
        )
    }
}

@Composable
private fun DirectoryFastScroller(
    totalItems: Int,
    visibleItems: Int,
    firstVisibleItemIndex: Int,
    onScrollToItem: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val maxIndex = (totalItems - visibleItems).coerceAtLeast(0)
    if (totalItems <= 0 || maxIndex == 0) return

    var trackHeightPx by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val handleHeight = 52.dp
    val handleHeightPx = with(density) { handleHeight.toPx() }
    val progress = firstVisibleItemIndex.coerceIn(0, maxIndex) / maxIndex.toFloat()
    val handleOffset = ((trackHeightPx - handleHeightPx).coerceAtLeast(0f) * progress).roundToInt()

    fun scrollToOffset(y: Float) {
        val travel = (trackHeightPx - handleHeightPx).coerceAtLeast(1f)
        val targetProgress = ((y - handleHeightPx / 2f) / travel).coerceIn(0f, 1f)
        onScrollToItem((targetProgress * maxIndex).roundToInt())
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(28.dp)
            .padding(vertical = 8.dp)
            .onSizeChanged { trackHeightPx = it.height }
            .pointerInput(totalItems, visibleItems, trackHeightPx) {
                detectDragGestures(
                    onDragStart = { offset -> scrollToOffset(offset.y) },
                    onDrag = { change, _ -> scrollToOffset(change.position.y) },
                )
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .clip(MaterialTheme.shapes.extraSmall)
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
        )
        Surface(
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .fillMaxWidth()
                .height(handleHeight)
                .align(Alignment.TopCenter)
                .offset { IntOffset(0, handleOffset) },
            shape = MaterialTheme.shapes.extraSmall,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.72f),
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {}
    }
}

@Composable
private fun DirectoryGridCard(
    title: String,
    subtitle: String,
    thumbnail: @Composable () -> Unit,
    onClick: (() -> Unit)?,
    actions: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box {
            thumbnail()
            actions?.let {
                Box(modifier = Modifier.align(Alignment.TopEnd)) {
                    it.invoke()
                }
            }
        }
        MiddleEllipsizedText(
            text = title,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
        )
        Text(
            text = subtitle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun EntryThumbnail(
    entry: DirectoryEntry,
    size: Int,
    style: EntryVisualStyle,
    showFolderBadge: Boolean = false,
    tags: ImageTags? = null,
    characterNames: Map<String, String> = emptyMap(),
    modifier: Modifier = Modifier.size(size.dp),
) {
    val preview by produceState<PreviewResult?>(
        initialValue = null,
        key1 = entry.path,
        key2 = entry.lastModifiedMillis,
        key3 = entry.isDirectory,
    ) {
        value = when {
            entry.isDirectory -> MediaPreviewLoader.loadDirectoryThumbnail(entry.path, entry.lastModifiedMillis, size * 2)
            entry.mediaType == DirectoryMediaType.Image -> MediaPreviewLoader.loadImage(entry.path, entry.lastModifiedMillis, size * 2)
            entry.mediaType == DirectoryMediaType.Video -> MediaPreviewLoader.loadVideoFrame(entry.path, entry.lastModifiedMillis, size * 2)
            else -> PreviewResult()
        }
    }

    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(style.thumbnailColor),
        contentAlignment = Alignment.Center,
    ) {
        val previewResult = preview
        if (previewResult == null) {
            CircularProgressIndicator(
                modifier = Modifier.size((size / 4).coerceAtLeast(20).dp),
                strokeWidth = 2.dp,
            )
        } else if (previewResult.bitmap != null) {
            Image(
                bitmap = previewResult.bitmap!!.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            if (entry.mediaType == DirectoryMediaType.Video) {
                Icon(
                    Icons.Default.PlayCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
            if (showFolderBadge) {
                FolderPreviewBadge(style)
            }
            CharacterTagBadge(tags = tags, characterNames = characterNames)
        } else {
            Icon(
                imageVector = if (entry.isDirectory) {
                    Icons.Default.Folder
                } else if (entry.mediaType == DirectoryMediaType.Archive) {
                    Icons.Default.Archive
                } else {
                    Icons.AutoMirrored.Filled.InsertDriveFile
                },
                contentDescription = null,
                tint = style.accentColor,
            )
        }
    }
}

@Composable
private fun BoxScope.FolderPreviewBadge(style: EntryVisualStyle) {
    Surface(
        modifier = Modifier
            .align(Alignment.BottomStart)
            .padding(6.dp)
            .size(28.dp),
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
        contentColor = style.accentColor,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Default.Folder,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun MiddleEllipsizedText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val maxWidthPx = with(density) { maxWidth.toPx() }
        val fontSizePx = with(density) { style.fontSize.toPx() }
        val displayText = remember(text, maxWidthPx, fontSizePx) {
            if (maxWidthPx > 0f) {
                TextUtils.ellipsize(
                    text,
                    TextPaint().apply { textSize = fontSizePx },
                    maxWidthPx,
                    TextUtils.TruncateAt.MIDDLE,
                ).toString()
            } else {
                text
            }
        }

        Text(
            text = displayText,
            style = style,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
        )
    }
}

private data class EntryVisualStyle(
    val containerColor: Color,
    val thumbnailColor: Color,
    val accentColor: Color,
)

@Composable
private fun DirectoryEntry.entryVisualStyle(): EntryVisualStyle {
    val colors = MaterialTheme.colorScheme
    return when {
        isDirectory -> EntryVisualStyle(
            containerColor = colors.primaryContainer.copy(alpha = 0.24f),
            thumbnailColor = colors.primaryContainer.copy(alpha = 0.62f),
            accentColor = colors.primary,
        )

        mediaType == DirectoryMediaType.Image || mediaType == DirectoryMediaType.Video -> EntryVisualStyle(
            containerColor = Color.Transparent,
            thumbnailColor = Color.Transparent,
            accentColor = colors.tertiary,
        )

        mediaType == DirectoryMediaType.Archive -> EntryVisualStyle(
            containerColor = Color.Transparent,
            thumbnailColor = Color.Transparent,
            accentColor = colors.secondary,
        )

        else -> defaultEntryVisualStyle()
    }
}

@Composable
private fun navigationEntryVisualStyle(): EntryVisualStyle {
    val colors = MaterialTheme.colorScheme
    return EntryVisualStyle(
        containerColor = colors.surfaceVariant.copy(alpha = 0.32f),
        thumbnailColor = colors.surfaceVariant,
        accentColor = colors.onSurfaceVariant,
    )
}

@Composable
private fun defaultEntryVisualStyle(): EntryVisualStyle {
    val colors = MaterialTheme.colorScheme
    return EntryVisualStyle(
        containerColor = colors.surface,
        thumbnailColor = colors.surfaceVariant,
        accentColor = colors.onSurfaceVariant,
    )
}

@Composable
private fun EmptyDirectoryMessage(uiState: DirectoryUiState) {
    Text(
        text = when {
            uiState.errorMessage != null -> uiState.errorMessage
            !uiState.canReadCurrentDirectory -> {
                stringResource(R.string.directory_unreadable)
            }
            uiState.searchQuery.isBlank() -> {
                stringResource(R.string.directory_empty)
            }
            else -> {
                stringResource(R.string.no_search_results, uiState.searchQuery)
            }
        },
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 12.dp),
    )
}

@Composable
private fun DirectoryEntry.supportingText(): String =
    when {
        isDirectory -> stringResource(R.string.folder)
        mediaType == DirectoryMediaType.Image -> stringResource(R.string.image_with_size, formatBytes(sizeBytes))
        mediaType == DirectoryMediaType.Video -> stringResource(R.string.video_with_size, formatBytes(sizeBytes))
        mediaType == DirectoryMediaType.Archive -> stringResource(R.string.archive_with_size, formatBytes(sizeBytes))
        else -> formatBytes(sizeBytes)
    }

@Composable
private fun DirectoryEntry.typeText(): String =
    when {
        isDirectory -> stringResource(R.string.folder)
        mediaType == DirectoryMediaType.Image -> stringResource(R.string.image)
        mediaType == DirectoryMediaType.Video -> stringResource(R.string.video)
        mediaType == DirectoryMediaType.Archive -> stringResource(R.string.archive)
        else -> stringResource(R.string.file)
    }

private fun DirectoryFileOperation.pendingLabelResource(): Int = when (this) {
    DirectoryFileOperation.Copy -> R.string.copy_pending
    DirectoryFileOperation.Move -> R.string.move_pending
}

private fun DirectorySortOrder.labelResource(): Int = when (this) {
    DirectorySortOrder.NameAscending -> R.string.sort_name_ascending
    DirectorySortOrder.NameDescending -> R.string.sort_name_descending
    DirectorySortOrder.NewestFirst -> R.string.sort_newest
    DirectorySortOrder.OldestFirst -> R.string.sort_oldest
    DirectorySortOrder.LargestFirst -> R.string.sort_largest
    DirectorySortOrder.SmallestFirst -> R.string.sort_smallest
}

private fun DirectoryMediaFilter.labelResource(): Int = when (this) {
    DirectoryMediaFilter.All -> R.string.all
    DirectoryMediaFilter.Folders -> R.string.folders
    DirectoryMediaFilter.Images -> R.string.images
    DirectoryMediaFilter.Videos -> R.string.videos
    DirectoryMediaFilter.Archives -> R.string.archives
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1_000L) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes / 1_000.0
    var unitIndex = 0

    while (value >= 1_000 && unitIndex < units.lastIndex) {
        value /= 1_000
        unitIndex++
    }

    return "%.1f %s".format(value, units[unitIndex])
}

private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "webm", "3gp", "mov", "avi", "m4v")

private const val FILE_PROVIDER_AUTHORITY = "srv.scrtvwr.fileprovider"

private fun Context.openWithOtherApp(entry: DirectoryEntry) {
    val file = File(entry.path)
    val uri = FileProvider.getUriForFile(this, FILE_PROVIDER_AUTHORITY, file)
    val mimeType = MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(file.extension.lowercase())
        ?: "*/*"

    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mimeType)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    try {
        startActivity(Intent.createChooser(intent, entry.name))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, getString(R.string.no_app_found_to_open_file), Toast.LENGTH_SHORT).show()
    }
}

@Preview(showBackground = true)
@Composable
fun DirectoryScreenPreview() {
    SCRTVWRTheme {
        DirectoryScreen(
            uiState = DirectoryUiState(
                rootDirectory = File("/storage/emulated/0"),
                currentDirectory = File("/storage/emulated/0/Movies"),
                directoryListing = DirectoryListing(
                    entries = listOf(
                        DirectoryEntry(
                            name = "Recordings of when we went fishing or something",
                            path = "/storage/emulated/0/Movies/Recordings of when we went fishing or something",
                            isDirectory = true,
                            sizeBytes = 0L,
                            lastModifiedMillis = 0L,
                        ),
                        DirectoryEntry(
                            name = "clip.mp4",
                            path = "/storage/emulated/0/Movies/clip.mp4",
                            isDirectory = false,
                            sizeBytes = 24_000_000L,
                            lastModifiedMillis = 0L,
                        ),
                        DirectoryEntry(
                            name = "megamind_78681236172_81736172371276.mp3",
                            path = "/storage/emulated/0/Movies/megamind_78681236172_81736172371276.mp3",
                            isDirectory = false,
                            sizeBytes = 24_000_000L,
                            lastModifiedMillis = 0L,
                        ),
                    ),
                ),
                canNavigateUp = true,
                viewMode = DirectoryViewMode.Grid,
            ),
            onScrollPositionRequested = { null },
            onSaveScrollPosition = { _, _ -> },
            onSearchQueryChange = {},
            onViewModeChange = {},
            onMediaFilterChange = {},
            onSortOrderChange = {},
            onOpenDirectory = {},
            onOpenRecentDirectory = {},
            onOpenMedia = {},
            onOpenWithOtherApp = {},
            onResumeMedia = {},
            onNavigateUp = {},
            onRefresh = {},
            onCopyEntry = {},
            onMoveEntry = {},
            onDeleteEntry = {},
            onPasteFileOperation = {},
            onCancelFileOperation = {},
            onClearFileOperationStatus = {},
        )
    }
}
