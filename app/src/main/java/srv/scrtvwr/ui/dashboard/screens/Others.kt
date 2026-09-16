package srv.scrtvwr.ui.dashboard.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import srv.scrtvwr.ui.dashboard.screens.viewmodels.OthersUiState
import srv.scrtvwr.ui.dashboard.screens.viewmodels.OthersViewModel
import srv.scrtvwr.tagging.TagFolderRule
import srv.scrtvwr.tagging.TagScopeMode
import srv.scrtvwr.ui.components.SimpleDropdown
import srv.scrtvwr.ui.tagging.TagScopeDialog
import srv.scrtvwr.util.time.TimeUtil
import srv.scrtvwr.ui.media.MediaEdgeDialogStyle
import srv.scrtvwr.ui.media.SlideShowDirection
import srv.scrtvwr.ui.theme.SCRTVWRTheme
import srv.scrtvwr.R

@Composable
fun Others(
    modifier: Modifier = Modifier,
    viewModel: OthersViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    OthersScreen(
        uiState = uiState,
        onShowHiddenFilesChange = viewModel::setShowHiddenFiles,
        onShowDirectoryFileActionsChange = viewModel::setShowDirectoryFileActions,
        onSlideShowStayMillisChange = viewModel::setSlideShowStayMillis,
        onHoldSlideShowEnabledChange = viewModel::setHoldSlideShowEnabled,
        onSlideShowDirectionChange = viewModel::setSlideShowDirection,
        onImagePreloadDistanceChange = viewModel::setImagePreloadDistance,
        onVideoPreloadDistanceChange = viewModel::setVideoPreloadDistance,
        onMediaEdgeDialogStyleChange = viewModel::setMediaEdgeDialogStyle,
        onFaceTaggingEnabledChange = viewModel::setFaceTaggingEnabled,
        onTaggingPausedChange = viewModel::setTaggingPaused,
        onStartStorageScan = viewModel::startStorageScan,
        onStopStorageScan = viewModel::stopStorageScan,
        onScopeModeChange = viewModel::setScopeMode,
        onFolderRuleChange = viewModel::setFolderRule,
        onFolderRuleRemove = viewModel::removeFolderRule,
        onClearCharacterTags = viewModel::clearCharacterTags,
        modifier = modifier,
    )
}

@Composable
fun OthersScreen(
    uiState: OthersUiState,
    onShowHiddenFilesChange: (Boolean) -> Unit,
    onShowDirectoryFileActionsChange: (Boolean) -> Unit,
    onSlideShowStayMillisChange: (Long) -> Unit,
    onHoldSlideShowEnabledChange: (Boolean) -> Unit,
    onSlideShowDirectionChange: (SlideShowDirection) -> Unit,
    onImagePreloadDistanceChange: (Int) -> Unit,
    onVideoPreloadDistanceChange: (Int) -> Unit,
    onMediaEdgeDialogStyleChange: (MediaEdgeDialogStyle) -> Unit,
    onFaceTaggingEnabledChange: (Boolean) -> Unit = {},
    onTaggingPausedChange: (Boolean) -> Unit = {},
    onStartStorageScan: () -> Unit = {},
    onStopStorageScan: () -> Unit = {},
    onScopeModeChange: (TagScopeMode) -> Unit = {},
    onFolderRuleChange: (TagFolderRule) -> Unit = {},
    onFolderRuleRemove: (String) -> Unit = {},
    onClearCharacterTags: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var editingTagFolders by rememberSaveable { mutableStateOf(false) }
    // Clearing every tag throws away work that can take a long time to rebuild, so it is confirmed twice.
    var clearTagsStep by rememberSaveable { mutableStateOf(0) }
    var slideShowStayMillisText by rememberSaveable { mutableStateOf(uiState.slideShowStayMillis.toString()) }
    var imagePreloadDistanceText by rememberSaveable { mutableStateOf(uiState.imagePreloadDistance.toString()) }
    var videoPreloadDistanceText by rememberSaveable { mutableStateOf(uiState.videoPreloadDistance.toString()) }
    val slideShowWarning = when (slideShowStayMillisText.toLongOrNull()) {
        null -> stringResource(R.string.invalid_whole_number)
        in 1L..99L -> stringResource(R.string.short_slideshow_warning)
        in 600_001L..Long.MAX_VALUE -> stringResource(R.string.long_slideshow_warning)
        else -> null
    }
    val imagePreloadWarning = when (imagePreloadDistanceText.toIntOrNull()) {
        null -> stringResource(R.string.invalid_whole_number)
        in 11..Int.MAX_VALUE -> stringResource(R.string.large_preload_warning)
        else -> null
    }

    LaunchedEffect(uiState.slideShowStayMillis) {
        if (slideShowStayMillisText.toLongOrNull() != uiState.slideShowStayMillis) {
            slideShowStayMillisText = uiState.slideShowStayMillis.toString()
        }
    }

    LaunchedEffect(uiState.imagePreloadDistance) {
        if (imagePreloadDistanceText.toIntOrNull() != uiState.imagePreloadDistance) {
            imagePreloadDistanceText = uiState.imagePreloadDistance.toString()
        }
    }

    val videoPreloadWarning = when (videoPreloadDistanceText.toIntOrNull()) {
        null -> stringResource(R.string.invalid_whole_number)
        in 11..Int.MAX_VALUE -> stringResource(R.string.large_preload_warning)
        else -> null
    }

    LaunchedEffect(uiState.videoPreloadDistance) {
        if (videoPreloadDistanceText.toIntOrNull() != uiState.videoPreloadDistance) {
            videoPreloadDistanceText = uiState.videoPreloadDistance.toString()
        }
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ListItem(
                modifier = Modifier.fillMaxWidth(),
                headlineContent = { Text(stringResource(R.string.show_hidden_files)) },
                supportingContent = { Text(stringResource(R.string.show_hidden_files_summary)) },
                leadingContent = { Icon(Icons.Default.Visibility, contentDescription = null) },
                trailingContent = {
                    Switch(
                        checked = uiState.showHiddenFiles,
                        onCheckedChange = onShowHiddenFilesChange,
                    )
                },
            )
        }
        item {
            NumericPreferenceListItem(
                headline = stringResource(R.string.slideshow_speed),
                supporting = stringResource(R.string.slideshow_speed_summary),
                value = slideShowStayMillisText,
                onValueChange = { value ->
                    slideShowStayMillisText = value.filter(Char::isDigit)
                    slideShowStayMillisText.toLongOrNull()?.let(onSlideShowStayMillisChange)
                },
                leadingContent = { Icon(Icons.Default.Slideshow, contentDescription = null) },
                suffix = "ms",
                warning = slideShowWarning,
            )
        }
        item {
            val nextMediaLabel = stringResource(R.string.next_media)
            val previousMediaLabel = stringResource(R.string.previous_media)
            ListItem(
                modifier = Modifier.fillMaxWidth(),
                headlineContent = { Text(stringResource(R.string.slideshow_direction)) },
                supportingContent = { Text(stringResource(R.string.slideshow_direction_summary)) },
                leadingContent = { Icon(Icons.Default.Slideshow, contentDescription = null) },
                trailingContent = {
                    SimpleDropdown(
                        label = stringResource(R.string.direction),
                        items = SlideShowDirection.entries,
                        selected = uiState.slideShowDirection,
                        onSelected = onSlideShowDirectionChange,
                        modifier = Modifier.fillMaxWidth(0.46f),
                        itemLabel = { direction ->
                            when (direction) {
                                SlideShowDirection.Next -> nextMediaLabel
                                SlideShowDirection.Previous -> previousMediaLabel
                            }
                        },
                    )
                },
            )
        }
        item {
            ListItem(
                modifier = Modifier.fillMaxWidth(),
                headlineContent = { Text(stringResource(R.string.show_directory_file_actions)) },
                supportingContent = { Text(stringResource(R.string.show_directory_file_actions_summary)) },
                leadingContent = { Icon(Icons.Default.MoreVert, contentDescription = null) },
                trailingContent = {
                    Switch(
                        checked = uiState.showDirectoryFileActions,
                        onCheckedChange = onShowDirectoryFileActionsChange,
                    )
                },
            )
        }
        item {
            NumericPreferenceListItem(
                headline = stringResource(R.string.image_preload_distance),
                supporting = stringResource(R.string.image_preload_summary),
                value = imagePreloadDistanceText,
                onValueChange = { value ->
                    imagePreloadDistanceText = value.filter(Char::isDigit)
                    imagePreloadDistanceText.toIntOrNull()?.let(onImagePreloadDistanceChange)
                },
                leadingContent = { Icon(Icons.Default.PhotoLibrary, contentDescription = null) },
                warning = imagePreloadWarning,
            )
        }
        item {
            NumericPreferenceListItem(
                headline = stringResource(R.string.video_preload_distance),
                supporting = stringResource(R.string.video_preload_summary),
                value = videoPreloadDistanceText,
                onValueChange = { value ->
                    videoPreloadDistanceText = value.filter(Char::isDigit)
                    videoPreloadDistanceText.toIntOrNull()?.let(onVideoPreloadDistanceChange)
                },
                leadingContent = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                warning = videoPreloadWarning,
            )
        }
        item {
            ListItem(
                modifier = Modifier.fillMaxWidth(),
                headlineContent = { Text(stringResource(R.string.hold_slideshow)) },
                supportingContent = { Text(stringResource(R.string.hold_slideshow_summary)) },
                leadingContent = { Icon(Icons.Default.Slideshow, contentDescription = null) },
                trailingContent = {
                    Switch(
                        checked = uiState.holdSlideShowEnabled,
                        onCheckedChange = onHoldSlideShowEnabledChange,
                    )
                },
            )
        }
        item {
            val bottomDialogLabel = stringResource(R.string.edge_dialog_style_bottom)
            val centerDialogLabel = stringResource(R.string.edge_dialog_style_center)
            ListItem(
                modifier = Modifier.fillMaxWidth(),
                headlineContent = { Text(stringResource(R.string.edge_dialog_style)) },
                supportingContent = { Text(stringResource(R.string.edge_dialog_style_summary)) },
                leadingContent = { Icon(Icons.Default.Info, contentDescription = null) },
                trailingContent = {
                    SimpleDropdown(
                        label = stringResource(R.string.edge_dialog_style),
                        items = MediaEdgeDialogStyle.entries,
                        selected = uiState.mediaEdgeDialogStyle,
                        onSelected = onMediaEdgeDialogStyleChange,
                        modifier = Modifier.fillMaxWidth(0.46f),
                        itemLabel = { style ->
                            when (style) {
                                MediaEdgeDialogStyle.Bottom -> bottomDialogLabel
                                MediaEdgeDialogStyle.Center -> centerDialogLabel
                            }
                        },
                    )
                },
            )
        }
        item {
            ListItem(
                modifier = Modifier.fillMaxWidth(),
                headlineContent = { Text(stringResource(R.string.face_tagging)) },
                supportingContent = { Text(stringResource(R.string.face_tagging_summary)) },
                leadingContent = { Icon(Icons.Default.Face, contentDescription = null) },
                trailingContent = {
                    Switch(
                        checked = uiState.faceTaggingEnabled,
                        onCheckedChange = onFaceTaggingEnabledChange,
                    )
                },
            )
        }
        if (uiState.faceTaggingEnabled) {
            item {
                ListItem(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onTaggingPausedChange(!uiState.taggingPaused) },
                    headlineContent = {
                        Text(
                            stringResource(
                                if (uiState.taggingPaused) R.string.resume_tagging else R.string.pause_tagging,
                            ),
                        )
                    },
                    supportingContent = {
                        Text(
                            stringResource(
                                if (uiState.taggingPaused) {
                                    R.string.tagging_paused_hint
                                } else {
                                    R.string.tagging_status_running
                                },
                            ),
                        )
                    },
                    leadingContent = {
                        Icon(
                            imageVector = if (uiState.taggingPaused) {
                                Icons.Default.PlayArrow
                            } else {
                                Icons.Default.Pause
                            },
                            contentDescription = null,
                        )
                    },
                )
            }
        }
        item {
            val scope = uiState.tagScanScope
            ListItem(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { editingTagFolders = true },
                headlineContent = { Text(stringResource(R.string.tagging_folders)) },
                supportingContent = {
                    Text(
                        when {
                            scope.isEmpty -> stringResource(R.string.tagging_folders_none_summary)
                            scope.mode == TagScopeMode.SelectedFolders -> stringResource(
                                R.string.tagging_folders_selected_summary,
                                scope.includedFolders.size,
                            )
                            scope.skippedFolders.isNotEmpty() -> stringResource(
                                R.string.tagging_folders_all_except_summary,
                                scope.skippedFolders.size,
                            )
                            else -> stringResource(R.string.tagging_folders_all_summary)
                        },
                    )
                },
                leadingContent = { Icon(Icons.Default.FolderSpecial, contentDescription = null) },
            )
        }
        item {
            val isScanning = uiState.tagScanProgress.isRunning
            val lastScan = if (uiState.lastStorageScanMillis > 0L) {
                TimeUtil.formatDisplayDate(uiState.lastStorageScanMillis)
            } else {
                stringResource(R.string.never)
            }
            // Nowhere in scope means there is nothing for a pass to walk through.
            val canScan = uiState.faceTaggingEnabled && !uiState.tagScanScope.isEmpty
            ListItem(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = canScan) {
                        if (isScanning) onStopStorageScan() else onStartStorageScan()
                    },
                headlineContent = {
                    Text(
                        stringResource(
                            when {
                                isScanning -> R.string.stop_tagging
                                uiState.tagScanScope.mode == TagScopeMode.SelectedFolders ->
                                    R.string.scan_chosen_folders
                                else -> R.string.scan_storage_for_characters
                            },
                        ),
                    )
                },
                supportingContent = {
                    Text(
                        if (isScanning) {
                            stringResource(
                                R.string.tagging_progress,
                                uiState.tagScanProgress.processed,
                                uiState.tagScanProgress.total,
                            )
                        } else {
                            stringResource(R.string.scan_storage_summary, uiState.taggedImageCount, lastScan)
                        },
                    )
                },
                leadingContent = { Icon(Icons.Default.ImageSearch, contentDescription = null) },
            )
        }
        item {
            ListItem(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { clearTagsStep = 1 },
                headlineContent = { Text(stringResource(R.string.clear_character_tags)) },
                supportingContent = { Text(stringResource(R.string.clear_character_tags_summary)) },
                leadingContent = { Icon(Icons.Default.DeleteSweep, contentDescription = null) },
                trailingContent = {
                    Text(
                        text = stringResource(R.string.character_count, uiState.characterCount),
                        style = MaterialTheme.typography.labelLarge,
                    )
                },
            )
        }
        item {
            val context = LocalContext.current
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val versionName = packageInfo.versionName ?: "Unknown"

            OutlinedCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text(stringResource(R.string.version)) },
                    supportingContent = { Text(versionName) },
                    leadingContent = {
                        Icon(Icons.Default.Info, contentDescription = null)
                    }
                )
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

    when (clearTagsStep) {
        1 -> AlertDialog(
            onDismissRequest = { clearTagsStep = 0 },
            title = { Text(stringResource(R.string.clear_character_tags_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.clear_character_tags_confirm_message,
                        uiState.characterCount,
                        uiState.taggedImageCount,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = { clearTagsStep = 2 }) {
                    Text(stringResource(R.string.continue_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { clearTagsStep = 0 }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )

        2 -> AlertDialog(
            onDismissRequest = { clearTagsStep = 0 },
            title = { Text(stringResource(R.string.clear_character_tags_final_title)) },
            text = { Text(stringResource(R.string.clear_character_tags_final_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClearCharacterTags()
                        clearTagsStep = 0
                    },
                ) {
                    Text(
                        text = stringResource(R.string.delete_everything),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { clearTagsStep = 0 }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun NumericPreferenceListItem(
    headline: String,
    supporting: String,
    value: String,
    onValueChange: (String) -> Unit,
    leadingContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    suffix: String? = null,
    warning: String? = null,
) {
    ListItem(
        modifier = modifier.fillMaxWidth(),
        headlineContent = { Text(headline) },
        supportingContent = {
            Column {
                Text(supporting)
                warning?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        leadingContent = leadingContent,
        trailingContent = {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                suffix = suffix?.let { { Text(it) } },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                ),
                modifier = Modifier.fillMaxWidth(0.42f),
            )
        },
    )
}

@Preview(showBackground = true)
@Composable
fun OthersPreview() {
    SCRTVWRTheme {
        OthersScreen(
            uiState = OthersUiState(
                showHiddenFiles = true,
                showDirectoryFileActions = false,
                slideShowStayMillis = 3000L,
                holdSlideShowEnabled = true,
                slideShowDirection = SlideShowDirection.Next,
                imagePreloadDistance = 5,
                videoPreloadDistance = 1,
                mediaEdgeDialogStyle = MediaEdgeDialogStyle.Bottom,
            ),
            onShowHiddenFilesChange = {},
            onShowDirectoryFileActionsChange = {},
            onSlideShowStayMillisChange = {},
            onHoldSlideShowEnabledChange = {},
            onSlideShowDirectionChange = {},
            onImagePreloadDistanceChange = {},
            onVideoPreloadDistanceChange = {},
            onMediaEdgeDialogStyleChange = {},
        )
    }
}
