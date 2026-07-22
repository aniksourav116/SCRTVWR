package srv.scrtvwr.ui.dashboard.screens

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.ImageNotSupported
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import srv.scrtvwr.ui.dashboard.screens.viewmodels.HistoryFilter
import srv.scrtvwr.ui.dashboard.screens.viewmodels.HistoryUiState
import srv.scrtvwr.ui.dashboard.screens.viewmodels.HistoryViewModel
import srv.scrtvwr.R
import srv.scrtvwr.MediaViewerActivity
import srv.scrtvwr.media.MediaPreviewLoader
import srv.scrtvwr.media.PreviewResult
import srv.scrtvwr.ui.dashboard.screens.viewmodels.HistoryDirectoryItem
import java.io.File

@Composable
fun History(
    modifier: Modifier = Modifier,
    viewModel: HistoryViewModel = viewModel(),
) {
    LaunchedEffect(Unit) {
        viewModel.refresh()
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    HistoryScreen(
        uiState = uiState,
        onFilterSelected = viewModel::selectFilter,
        onRemoveDirectory = viewModel::removeItem,
        onClearVisible = viewModel::clearVisibleItems,
        onOpenDirectory = { directoryPath, mediaPath ->
            context.startActivity(Intent(context, MediaViewerActivity::class.java).apply {
                putExtra(MediaViewerActivity.EXTRA_DIRECTORY_PATH, directoryPath)
                putExtra(MediaViewerActivity.EXTRA_MEDIA_PATH, mediaPath)
            })
        },
        modifier = modifier,
    )
}

@Composable
fun HistoryScreen(
    uiState: HistoryUiState,
    onFilterSelected: (HistoryFilter) -> Unit,
    onRemoveDirectory: (HistoryDirectoryItem) -> Unit,
    onClearVisible: () -> Unit,
    onOpenDirectory: (String, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.viewing_history), style = MaterialTheme.typography.headlineSmall)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HistoryFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = filter == uiState.selectedFilter,
                        onClick = { onFilterSelected(filter) },
                        label = { Text(stringResource(filter.labelResource())) },
                    )
                }
            }
            if (uiState.visibleDirectories.isNotEmpty()) {
                TextButton(onClick = onClearVisible) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = null)
                    Text(stringResource(uiState.selectedFilter.clearLabelResource()))
                }
            }
        }
        if (uiState.visibleDirectories.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Default.History, contentDescription = null)
                Text(
                    stringResource(
                        if (uiState.selectedFilter == HistoryFilter.FAVORITES) R.string.no_favorites else R.string.no_history,
                    ),
                    modifier = Modifier.padding(top = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(uiState.visibleDirectories, key = { it.mediaPath ?: it.path }) { directory ->
                    ListItem(
                        headlineContent = { Text(directory.name) },
                        supportingContent = { Text(directory.path) },
                        leadingContent = { HistoryThumbnail(directory) },
                        trailingContent = {
                            IconButton(onClick = { onRemoveDirectory(directory) }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = stringResource(R.string.remove_history_item),
                                )
                            }
                        },
                        modifier = Modifier.clickable(role = Role.Button) {
                            onOpenDirectory(directory.path, directory.mediaPath)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryThumbnail(
    item: HistoryDirectoryItem,
    modifier: Modifier = Modifier.size(56.dp),
) {
    val mediaPath = item.mediaPath
    val sourceFile = mediaPath?.substringBefore("::")?.let(::File)
    val isVideo = sourceFile?.extension?.lowercase() in VIDEO_EXTENSIONS
    val preview by produceState<PreviewResult?>(
        initialValue = null,
        key1 = mediaPath,
        key2 = sourceFile?.lastModified(),
    ) {
        value = when {
            mediaPath == null || sourceFile == null -> PreviewResult()
            isVideo -> MediaPreviewLoader.loadVideoFrame(mediaPath, sourceFile.lastModified(), 112)
            else -> MediaPreviewLoader.loadImage(mediaPath, sourceFile.lastModified(), 112)
        }
    }

    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = preview?.bitmap
        if (preview == null) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
            )
        } else if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            if (isVideo) Icon(Icons.Default.PlayCircle, contentDescription = null)
        } else {
            Icon(Icons.Default.ImageNotSupported, contentDescription = null)
        }
    }
}

private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "webm", "3gp", "mov", "avi", "m4v")

private fun HistoryFilter.labelResource(): Int = when (this) {
    HistoryFilter.ALL -> R.string.all
    HistoryFilter.RECENT -> R.string.recent
    HistoryFilter.FAVORITES -> R.string.favorites
}

private fun HistoryFilter.clearLabelResource(): Int = when (this) {
    HistoryFilter.ALL,
    HistoryFilter.RECENT -> R.string.clear_history
    HistoryFilter.FAVORITES -> R.string.clear_favorites
}
