package srv.scrtvwr.ui.dashboard.screens

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import srv.scrtvwr.MediaViewerActivity
import srv.scrtvwr.R
import srv.scrtvwr.tagging.CharacterProfile
import srv.scrtvwr.tagging.ImageTags
import srv.scrtvwr.tagging.TagScanProgress
import srv.scrtvwr.tagging.TaggingState
import srv.scrtvwr.ui.components.BaseScaffold
import srv.scrtvwr.ui.dashboard.screens.viewmodels.PeopleUiState
import srv.scrtvwr.ui.dashboard.screens.viewmodels.PeopleViewModel
import srv.scrtvwr.ui.tagging.CharacterCover
import srv.scrtvwr.ui.tagging.TaggedImageThumbnail
import srv.scrtvwr.ui.tagging.displayLabel
import srv.scrtvwr.util.time.TimeUtil
import java.io.File

/**
 * The People tab: everyone tagging has found, and the controls for the tagging itself.
 * Opening a person swaps this screen for their page, which is where their details are edited.
 */
@Composable
fun People(
    modifier: Modifier = Modifier,
    isVisible: Boolean = true,
    viewModel: PeopleViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val mediaLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { }
    val selected = uiState.selectedCharacter

    BackHandler(enabled = isVisible && selected != null) { viewModel.closeCharacter() }

    if (selected == null) {
        PeopleScreen(
            uiState = uiState,
            onOpenCharacter = viewModel::openCharacter,
            onPausedChange = viewModel::setPaused,
            onStartStorageScan = viewModel::startStorageScan,
            onStopStorageScan = viewModel::stopStorageScan,
            modifier = modifier,
        )
    } else {
        CharacterScreen(
            profile = selected,
            images = uiState.selectedCharacterImages,
            onBack = viewModel::closeCharacter,
            onSave = { label, note -> viewModel.saveDetails(selected.id, label, note) },
            onSetCover = { image -> viewModel.setCover(selected.id, image) },
            onRemove = { viewModel.removeCharacter(selected.id) },
            onOpenImage = { image ->
                val directoryPath = File(image.path.substringBefore("::")).parent ?: return@CharacterScreen
                mediaLauncher.launch(
                    Intent(context, MediaViewerActivity::class.java).apply {
                        putExtra(MediaViewerActivity.EXTRA_DIRECTORY_PATH, directoryPath)
                        putExtra(MediaViewerActivity.EXTRA_MEDIA_PATH, image.path)
                    },
                )
            },
            modifier = modifier,
        )
    }
}

@Composable
fun PeopleScreen(
    uiState: PeopleUiState,
    onOpenCharacter: (CharacterProfile) -> Unit,
    onPausedChange: (Boolean) -> Unit,
    onStartStorageScan: () -> Unit,
    onStopStorageScan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BaseScaffold(
        title = stringResource(R.string.people),
        subtitle = stringResource(
            R.string.people_summary,
            uiState.characters.size,
            uiState.taggedImageCount,
        ),
        showBackButton = false,
        modifier = modifier,
        actions = {
            if (uiState.taggingEnabled) {
                IconButton(onClick = { onPausedChange(!uiState.taggingPaused) }) {
                    Icon(
                        imageVector = if (uiState.taggingPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = stringResource(
                            if (uiState.taggingPaused) R.string.resume_tagging else R.string.pause_tagging,
                        ),
                    )
                }
            }
        },
    ) { contentModifier ->
        Column(
            modifier = contentModifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TaggingStatusCard(
                state = uiState.taggingState,
                progress = uiState.scanProgress,
                lastScanMillis = uiState.lastStorageScanMillis,
                onPausedChange = onPausedChange,
                onStartStorageScan = onStartStorageScan,
                onStopStorageScan = onStopStorageScan,
            )

            if (uiState.characters.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.people_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(104.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(uiState.characters, key = CharacterProfile::id) { profile ->
                        PersonCard(profile = profile, onClick = { onOpenCharacter(profile) })
                    }
                }
            }
        }
    }
}

@Composable
private fun PersonCard(profile: CharacterProfile, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        CharacterCover(
            profile = profile,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
        )
        Text(
            text = profile.displayLabel(),
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(R.string.character_image_count, profile.imageCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** Tagging state in one place: what it is doing now, and the two controls that change it. */
@Composable
private fun TaggingStatusCard(
    state: TaggingState,
    progress: TagScanProgress,
    lastScanMillis: Long,
    onPausedChange: (Boolean) -> Unit,
    onStartStorageScan: () -> Unit,
    onStopStorageScan: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = when (state) {
                        TaggingState.Paused -> Icons.Default.Pause
                        TaggingState.Off -> Icons.Default.Face
                        TaggingState.Running -> Icons.Default.Face
                    },
                    contentDescription = null,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(
                            when (state) {
                                TaggingState.Off -> R.string.tagging_status_off
                                TaggingState.Paused -> R.string.tagging_status_paused
                                TaggingState.Running -> R.string.tagging_status_running
                            },
                        ),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = when {
                            state == TaggingState.Paused -> stringResource(R.string.tagging_paused_hint)
                            progress.isRunning && progress.total > 0 -> stringResource(
                                R.string.tagging_progress,
                                progress.processed,
                                progress.total,
                            )
                            progress.isRunning -> stringResource(R.string.tagging_counting_images)
                            lastScanMillis > 0L -> TimeUtil.formatDisplayDate(lastScanMillis)
                            else -> stringResource(R.string.never)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (state != TaggingState.Off) {
                    TextButton(onClick = { onPausedChange(state != TaggingState.Paused) }) {
                        Text(
                            stringResource(
                                if (state == TaggingState.Paused) R.string.resume_tagging else R.string.pause_tagging,
                            ),
                        )
                    }
                }
            }

            if (progress.isRunning && progress.total > 0) {
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (state != TaggingState.Off) {
                TextButton(
                    onClick = { if (progress.isRunning) onStopStorageScan() else onStartStorageScan() },
                ) {
                    Icon(
                        imageVector = Icons.Default.ImageSearch,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(
                            if (progress.isRunning) R.string.stop_tagging else R.string.scan_storage_for_characters,
                        ),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

/** One person's page: their name and note, which photo represents them, and every image they are in. */
@Composable
fun CharacterScreen(
    profile: CharacterProfile,
    images: List<ImageTags>,
    onBack: () -> Unit,
    onSave: (String?, String?) -> Unit,
    onSetCover: (ImageTags) -> Unit,
    onRemove: () -> Unit,
    onOpenImage: (ImageTags) -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by rememberSaveable(profile.id) { mutableStateOf(profile.label.orEmpty()) }
    var note by rememberSaveable(profile.id) { mutableStateOf(profile.note.orEmpty()) }
    var pickingCover by rememberSaveable(profile.id) { mutableStateOf(false) }
    var confirmingRemoval by rememberSaveable(profile.id) { mutableStateOf(false) }
    val hasChanges = name.trim() != profile.label.orEmpty() || note.trim() != profile.note.orEmpty()

    BaseScaffold(
        title = profile.displayLabel(),
        subtitle = stringResource(R.string.character_image_count, profile.imageCount),
        showBackButton = true,
        onBackClick = onBack,
        modifier = modifier,
        actions = {
            IconButton(onClick = { confirmingRemoval = true }) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.remove_character))
            }
        },
    ) { contentModifier ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(96.dp),
            modifier = contentModifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    CharacterCover(profile = profile, modifier = Modifier.size(112.dp))
                    TextButton(onClick = { pickingCover = !pickingCover }) {
                        Text(stringResource(R.string.change_cover))
                    }
                }
            }

            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.character_name)) },
                        placeholder = { Text(profile.displayLabel()) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text(stringResource(R.string.character_note)) },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = stringResource(
                            R.string.character_stats,
                            profile.imageCount,
                            profile.sampleCount,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = {
                            onSave(name.trim().takeIf(String::isNotEmpty), note.trim().takeIf(String::isNotEmpty))
                        },
                        enabled = hasChanges,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.save))
                    }
                }
            }

            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = if (pickingCover) {
                        stringResource(R.string.pick_cover_hint)
                    } else {
                        stringResource(R.string.photos_count, images.size)
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = if (pickingCover) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
            }

            items(images, key = ImageTags::path) { image ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clickable {
                            if (pickingCover) {
                                onSetCover(image)
                                pickingCover = false
                            } else {
                                onOpenImage(image)
                            }
                        },
                ) {
                    TaggedImageThumbnail(
                        path = image.path,
                        modifiedMillis = image.modifiedMillis,
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (image.path == profile.coverPath) {
                        Surface(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(4.dp),
                            shape = MaterialTheme.shapes.extraSmall,
                            color = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ) {
                            Text(
                                text = stringResource(R.string.cover_label),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirmingRemoval) {
        AlertDialog(
            onDismissRequest = { confirmingRemoval = false },
            title = { Text(stringResource(R.string.remove_person_title)) },
            text = { Text(stringResource(R.string.remove_person_message, profile.imageCount)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingRemoval = false
                        onRemove()
                    },
                ) {
                    Text(
                        text = stringResource(R.string.remove),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingRemoval = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}
