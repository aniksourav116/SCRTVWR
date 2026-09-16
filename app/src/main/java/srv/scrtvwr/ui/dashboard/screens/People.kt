package srv.scrtvwr.ui.dashboard.screens

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Merge
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.remember
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
import srv.scrtvwr.tagging.TagFolderRule
import srv.scrtvwr.tagging.TagScanProgress
import srv.scrtvwr.tagging.TagScanScope
import srv.scrtvwr.tagging.TagScopeMode
import srv.scrtvwr.tagging.TaggingState
import srv.scrtvwr.ui.components.BaseScaffold
import srv.scrtvwr.ui.dashboard.screens.viewmodels.ModelMessage
import srv.scrtvwr.ui.dashboard.screens.viewmodels.PeopleUiState
import srv.scrtvwr.ui.dashboard.screens.viewmodels.PeopleViewModel
import srv.scrtvwr.ui.tagging.CharacterCover
import srv.scrtvwr.ui.tagging.TagScopeDialog
import srv.scrtvwr.ui.tagging.TaggedImageThumbnail
import srv.scrtvwr.ui.tagging.displayLabel

/**
 * The People tab: everyone tagging has found, and the controls for the tagging itself.
 * Opening a person swaps this screen for their page, where their details are edited, their photos
 * are corrected, and their album can be paged through as though it were one folder.
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
    // A .tflite has no registered MIME type, so the picker cannot filter on one.
    val modelPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::installModel) }
    val selected = uiState.selectedCharacter

    uiState.modelMessage?.let { message ->
        ModelMessageDialog(message = message, onDismiss = viewModel::clearModelMessage)
    }

    BackHandler(enabled = isVisible && selected != null) { viewModel.closeCharacter() }

    if (selected == null) {
        PeopleScreen(
            uiState = uiState,
            onOpenCharacter = viewModel::openCharacter,
            onPausedChange = viewModel::setPaused,
            onStartStorageScan = viewModel::startStorageScan,
            onStopStorageScan = viewModel::stopStorageScan,
            onRebuildGroups = viewModel::rebuildGroups,
            onPickModel = { modelPicker.launch(arrayOf("*/*")) },
            onScopeModeChange = viewModel::setScopeMode,
            onFolderRuleChange = viewModel::setFolderRule,
            onFolderRuleRemove = viewModel::removeFolderRule,
            modifier = modifier,
        )
    } else {
        CharacterScreen(
            profile = selected,
            images = uiState.selectedCharacterImages,
            otherCharacters = uiState.characters.filterNot { it.id == selected.id },
            onBack = viewModel::closeCharacter,
            onSave = { label, note -> viewModel.saveDetails(selected.id, label, note) },
            onSetCover = { image -> viewModel.setCover(selected.id, image) },
            onUnassign = { image -> viewModel.unassign(selected.id, image.path) },
            onMerge = { otherId -> viewModel.merge(selected.id, otherId) },
            onRemove = { viewModel.removeCharacter(selected.id) },
            onOpenAlbum = { image, album ->
                // The album is handed to the viewer as an explicit list, so paging through one
                // person's pictures works the same as paging through a folder.
                mediaLauncher.launch(
                    Intent(context, MediaViewerActivity::class.java).apply {
                        putStringArrayListExtra(MediaViewerActivity.EXTRA_MEDIA_PATHS, ArrayList(album))
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
    onRebuildGroups: () -> Unit,
    onPickModel: () -> Unit = {},
    onScopeModeChange: (TagScopeMode) -> Unit = {},
    onFolderRuleChange: (TagFolderRule) -> Unit = {},
    onFolderRuleRemove: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var editingTagFolders by rememberSaveable { mutableStateOf(false) }
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
                scope = uiState.tagScanScope,
                usingRecognitionModel = uiState.usingRecognitionModel,
                rebuilding = uiState.rebuilding,
                canRebuild = uiState.characters.isNotEmpty(),
                onPausedChange = onPausedChange,
                onStartStorageScan = onStartStorageScan,
                onStopStorageScan = onStopStorageScan,
                onRebuildGroups = onRebuildGroups,
                onPickModel = onPickModel,
                onEditFolders = { editingTagFolders = true },
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

    if (editingTagFolders) {
        TagScopeDialog(
            scope = uiState.tagScanScope,
            onModeChange = onScopeModeChange,
            onRuleChange = onFolderRuleChange,
            onRuleRemove = onFolderRuleRemove,
            onDismiss = { editingTagFolders = false },
        )
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

/** Tagging state in one place: what it is doing now, and the controls that change it. */
@Composable
private fun TaggingStatusCard(
    state: TaggingState,
    progress: TagScanProgress,
    scope: TagScanScope,
    usingRecognitionModel: Boolean,
    rebuilding: Boolean,
    canRebuild: Boolean,
    onPausedChange: (Boolean) -> Unit,
    onStartStorageScan: () -> Unit,
    onStopStorageScan: () -> Unit,
    onRebuildGroups: () -> Unit,
    onPickModel: () -> Unit,
    onEditFolders: () -> Unit,
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
                    imageVector = if (state == TaggingState.Paused) Icons.Default.Pause else Icons.Default.Face,
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
                            rebuilding -> stringResource(R.string.regrouping)
                            state == TaggingState.Paused -> stringResource(R.string.tagging_paused_hint)
                            progress.isRunning && progress.total > 0 -> stringResource(
                                R.string.tagging_progress,
                                progress.processed,
                                progress.total,
                            )
                            progress.isRunning -> stringResource(R.string.tagging_counting_images)
                            else -> stringResource(R.string.tagging_idle)
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

            if (rebuilding || (progress.isRunning && progress.total > 0)) {
                if (rebuilding) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(
                        progress = { progress.fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (!usingRecognitionModel) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = stringResource(R.string.recognition_model_missing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            if (state != TaggingState.Off) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(
                        onClick = { if (progress.isRunning) onStopStorageScan() else onStartStorageScan() },
                        // Nowhere in scope means there is nothing for a pass to walk through.
                        enabled = progress.isRunning || !scope.isEmpty,
                    ) {
                        Icon(
                            imageVector = Icons.Default.ImageSearch,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = stringResource(
                                when {
                                    progress.isRunning -> R.string.stop_tagging
                                    scope.mode == TagScopeMode.SelectedFolders -> R.string.scan_chosen_folders
                                    else -> R.string.scan_storage_for_characters
                                },
                            ),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    TextButton(onClick = onEditFolders) {
                        Icon(
                            imageVector = Icons.Default.FolderSpecial,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = stringResource(R.string.tagging_folders),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    TextButton(onClick = onRebuildGroups, enabled = canRebuild && !rebuilding) {
                        Icon(
                            imageVector = Icons.Default.Autorenew,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = stringResource(R.string.regroup_people),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    TextButton(onClick = onPickModel) {
                        Icon(
                            imageVector = Icons.Default.Memory,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = stringResource(
                                if (usingRecognitionModel) R.string.replace_model else R.string.install_model,
                            ),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Reports what happened to a model the user picked, whether it was taken or refused. */
@Composable
private fun ModelMessageDialog(message: ModelMessage, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.model_dialog_title)) },
        text = {
            Text(
                if (message.installed) {
                    stringResource(R.string.model_installed, message.inputSize, message.embeddingSize)
                } else {
                    stringResource(R.string.model_rejected, message.reason)
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}

/** What tapping a photo on a character's page does. */
private enum class PhotoAction {
    Open,
    PickCover,
    Remove,
}

/**
 * One person's page: their name and note, which photo represents them, the corrections that keep
 * their group honest, and every image they appear in.
 */
@Composable
fun CharacterScreen(
    profile: CharacterProfile,
    images: List<ImageTags>,
    otherCharacters: List<CharacterProfile>,
    onBack: () -> Unit,
    onSave: (String?, String?) -> Unit,
    onSetCover: (ImageTags) -> Unit,
    onUnassign: (ImageTags) -> Unit,
    onMerge: (String) -> Unit,
    onRemove: () -> Unit,
    onOpenAlbum: (ImageTags, List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by rememberSaveable(profile.id) { mutableStateOf(profile.label.orEmpty()) }
    var note by rememberSaveable(profile.id) { mutableStateOf(profile.note.orEmpty()) }
    var photoAction by remember(profile.id) { mutableStateOf(PhotoAction.Open) }
    var merging by rememberSaveable(profile.id) { mutableStateOf(false) }
    var confirmingRemoval by rememberSaveable(profile.id) { mutableStateOf(false) }
    val hasChanges = name.trim() != profile.label.orEmpty() || note.trim() != profile.note.orEmpty()
    val albumPaths = remember(images) { images.map(ImageTags::path) }

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
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CharacterCover(profile = profile, modifier = Modifier.size(112.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = photoAction == PhotoAction.PickCover,
                            onClick = {
                                photoAction = if (photoAction == PhotoAction.PickCover) {
                                    PhotoAction.Open
                                } else {
                                    PhotoAction.PickCover
                                }
                            },
                            label = { Text(stringResource(R.string.change_cover)) },
                        )
                        FilterChip(
                            selected = photoAction == PhotoAction.Remove,
                            onClick = {
                                photoAction = if (photoAction == PhotoAction.Remove) {
                                    PhotoAction.Open
                                } else {
                                    PhotoAction.Remove
                                }
                            },
                            label = { Text(stringResource(R.string.remove_photos)) },
                        )
                        FilterChip(
                            selected = false,
                            onClick = { merging = true },
                            label = { Text(stringResource(R.string.merge_person)) },
                            leadingIcon = { Icon(Icons.Default.Merge, contentDescription = null) },
                        )
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
                    text = when (photoAction) {
                        PhotoAction.PickCover -> stringResource(R.string.pick_cover_hint)
                        PhotoAction.Remove -> stringResource(R.string.remove_photo_hint)
                        PhotoAction.Open -> stringResource(R.string.photos_count, images.size)
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = if (photoAction == PhotoAction.Open) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.primary
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
                            when (photoAction) {
                                PhotoAction.Open -> onOpenAlbum(image, albumPaths)
                                PhotoAction.PickCover -> {
                                    onSetCover(image)
                                    photoAction = PhotoAction.Open
                                }
                                // Stays in remove mode: a wrong group usually has several strays.
                                PhotoAction.Remove -> onUnassign(image)
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

    if (merging) {
        MergeCharacterDialog(
            profile = profile,
            candidates = otherCharacters,
            onMerge = { otherId ->
                onMerge(otherId)
                merging = false
            },
            onDismiss = { merging = false },
        )
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

/** Pick the character who is really the same person; their photos move into this one. */
@Composable
private fun MergeCharacterDialog(
    profile: CharacterProfile,
    candidates: List<CharacterProfile>,
    onMerge: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.merge_person_title, profile.displayLabel())) },
        text = {
            if (candidates.isEmpty()) {
                Text(stringResource(R.string.merge_person_empty))
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.merge_person_message),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(candidates, key = CharacterProfile::id) { candidate ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onMerge(candidate.id) }
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CharacterCover(profile = candidate, modifier = Modifier.size(40.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = candidate.displayLabel(),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = stringResource(
                                            R.string.character_image_count,
                                            candidate.imageCount,
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
