package srv.scrtvwr.ui.dashboard.screens

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
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
import srv.scrtvwr.ui.dashboard.screens.viewmodels.DirectoryListing
import srv.scrtvwr.ui.dashboard.screens.viewmodels.DirectoryMediaType
import srv.scrtvwr.ui.dashboard.screens.viewmodels.DirectoryUiState
import srv.scrtvwr.ui.dashboard.screens.viewmodels.DirectoryViewModel
import srv.scrtvwr.ui.dashboard.screens.viewmodels.DirectoryViewMode
import srv.scrtvwr.ui.sort.DirectorySortOrder
import srv.scrtvwr.ui.theme.SCRTVWRTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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
        onSearchQueryChange = viewModel::updateSearchQuery,
        onViewModeChange = viewModel::setViewMode,
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
        modifier = modifier,
    )
}

@Composable
fun DirectoryScreen(
    uiState: DirectoryUiState,
    onSearchQueryChange: (String) -> Unit,
    onViewModeChange: (DirectoryViewMode) -> Unit,
    onSortOrderChange: (DirectorySortOrder) -> Unit,
    onOpenDirectory: (DirectoryEntry) -> Unit,
    onOpenRecentDirectory: (String) -> Unit,
    onOpenMedia: (DirectoryEntry) -> Unit,
    onResumeMedia: () -> Unit,
    onNavigateUp: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var recentMenuExpanded by remember { mutableStateOf(false) }
    var sortMenuExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DirectoryHeader(uiState = uiState)

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.36f),
        ) {
            Column(
                modifier = Modifier.padding(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DirectoryToolbarButton(
                        icon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                        label = stringResource(R.string.refresh),
                        onClick = onRefresh,
                        modifier = Modifier.weight(1f),
                    )

                    Box(modifier = Modifier.weight(1f)) {
                        DirectoryToolbarButton(
                            icon = { Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null) },
                            label = stringResource(uiState.sortOrder.labelResource()).substringBefore(" "),
                            onClick = { sortMenuExpanded = true },
                            modifier = Modifier.fillMaxWidth(),
                        )
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
                                        {
                                            Icon(
                                                Icons.Default.Check,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    } else {
                                        null
                                    },
                                )
                            }
                        }
                    }

                    DirectoryToolbarButton(
                        icon = { Icon(Icons.Default.Restore, contentDescription = null) },
                        label = stringResource(R.string.resume),
                        onClick = onResumeMedia,
                        enabled = uiState.lastMediaSession != null,
                        modifier = Modifier.weight(1f),
                    )

                    Box(modifier = Modifier.weight(1f)) {
                        DirectoryToolbarButton(
                            icon = { Icon(Icons.Default.History, contentDescription = null) },
                            label = stringResource(R.string.recent),
                            onClick = { recentMenuExpanded = true },
                            enabled = uiState.recentDirectories.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        DropdownMenu(
                            expanded = recentMenuExpanded,
                            onDismissRequest = { recentMenuExpanded = false },
                        ) {
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
                                        recentMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }

                    DirectoryToolbarButton(
                        icon = { Icon(Icons.AutoMirrored.Filled.ViewList, contentDescription = null) },
                        label = stringResource(R.string.list_view),
                        selected = uiState.viewMode == DirectoryViewMode.List,
                        onClick = { onViewModeChange(DirectoryViewMode.List) },
                        modifier = Modifier.weight(1f),
                    )

                    DirectoryToolbarButton(
                        icon = { Icon(Icons.Default.GridView, contentDescription = null) },
                        label = stringResource(R.string.grid_view),
                        selected = uiState.viewMode == DirectoryViewMode.Grid,
                        onClick = { onViewModeChange(DirectoryViewMode.Grid) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        OutlinedTextField(
            value = uiState.searchQuery,
            onValueChange = onSearchQueryChange,
            placeholder = { Text(stringResource(R.string.search_files_folders)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        )

        if (uiState.isLoading && uiState.entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else when (uiState.viewMode) {
            DirectoryViewMode.List -> DirectoryList(
                uiState = uiState,
                onOpenDirectory = onOpenDirectory,
                onOpenMedia = onOpenMedia,
                onNavigateUp = onNavigateUp,
            )

            DirectoryViewMode.Grid -> DirectoryGrid(
                uiState = uiState,
                onOpenDirectory = onOpenDirectory,
                onOpenMedia = onOpenMedia,
                onNavigateUp = onNavigateUp,
            )
        }
    }
}

@Composable
private fun DirectoryHeader(uiState: DirectoryUiState) {
    val folderName = uiState.currentDirectory.name.ifBlank {
        uiState.currentDirectory.absolutePath
    }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = folderName,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = uiState.currentDirectory.absolutePath,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
            .height(44.dp)
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
            icon()
        }
    }
}

@Composable
private fun DirectoryList(
    uiState: DirectoryUiState,
    onOpenDirectory: (DirectoryEntry) -> Unit,
    onOpenMedia: (DirectoryEntry) -> Unit,
    onNavigateUp: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (uiState.canNavigateUp) {
            item(key = "parent") {
                DirectoryListRow(
                    title = "..",
                    subtitle = stringResource(R.string.go_up),
                    thumbnail = { Icon(Icons.Default.ArrowUpward, contentDescription = null) },
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
            DirectoryListRow(
                title = entry.name,
                subtitle = entry.supportingText(),
                thumbnail = { EntryThumbnail(entry, size = 48) },
                onClick = if (entry.isDirectory || entry.mediaType != DirectoryMediaType.Other) {
                    {
                        if (entry.isDirectory) {
                            onOpenDirectory(entry)
                        } else {
                            onOpenMedia(entry)
                        }
                    }
                } else {
                    null
                },
            )
        }
    }
}

@Composable
private fun DirectoryListRow(
    title: String,
    subtitle: String,
    thumbnail: @Composable () -> Unit,
    onClick: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
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
    }
}

@Composable
private fun DirectoryGrid(
    uiState: DirectoryUiState,
    onOpenDirectory: (DirectoryEntry) -> Unit,
    onOpenMedia: (DirectoryEntry) -> Unit,
    onNavigateUp: () -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(140.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (uiState.canNavigateUp) {
            item(key = "parent") {
                DirectoryGridCard(
                    title = "..",
                    subtitle = stringResource(R.string.go_up),
                    thumbnail = {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(4f / 3f)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Default.ArrowUpward, contentDescription = null)
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
            DirectoryGridCard(
                title = entry.name,
                subtitle = entry.supportingText(),
                thumbnail = {
                    EntryThumbnail(
                        entry = entry,
                        size = 128,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(4f / 3f),
                    )
                },
                onClick = if (entry.isDirectory || entry.mediaType != DirectoryMediaType.Other) {
                    {
                        if (entry.isDirectory) {
                            onOpenDirectory(entry)
                        } else {
                            onOpenMedia(entry)
                        }
                    }
                } else {
                    null
                },
            )
        }
    }
}

@Composable
private fun DirectoryGridCard(
    title: String,
    subtitle: String,
    thumbnail: @Composable () -> Unit,
    onClick: (() -> Unit)?,
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 132.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
        ) {
            thumbnail()
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun EntryThumbnail(
    entry: DirectoryEntry,
    size: Int,
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
            .background(MaterialTheme.colorScheme.surfaceVariant),
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
        } else {
            Icon(
                imageVector = if (entry.isDirectory) {
                    Icons.Default.Folder
                } else {
                    Icons.AutoMirrored.Filled.InsertDriveFile
                },
                contentDescription = null,
            )
        }
    }
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

private fun DirectorySortOrder.labelResource(): Int = when (this) {
    DirectorySortOrder.NameAscending -> R.string.sort_name_ascending
    DirectorySortOrder.NameDescending -> R.string.sort_name_descending
    DirectorySortOrder.NewestFirst -> R.string.sort_newest
    DirectorySortOrder.OldestFirst -> R.string.sort_oldest
    DirectorySortOrder.LargestFirst -> R.string.sort_largest
    DirectorySortOrder.SmallestFirst -> R.string.sort_smallest
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
                            name = "Recordings",
                            path = "/storage/emulated/0/Movies/Recordings",
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
                    ),
                ),
                canNavigateUp = true,
            ),
            onSearchQueryChange = {},
            onViewModeChange = {},
            onSortOrderChange = {},
            onOpenDirectory = {},
            onOpenRecentDirectory = {},
            onOpenMedia = {},
            onResumeMedia = {},
            onNavigateUp = {},
            onRefresh = {},
        )
    }
}
