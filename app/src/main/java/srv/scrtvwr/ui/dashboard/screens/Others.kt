package srv.scrtvwr.ui.dashboard.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import srv.scrtvwr.ui.dashboard.screens.viewmodels.OthersUiState
import srv.scrtvwr.ui.dashboard.screens.viewmodels.OthersViewModel
import srv.scrtvwr.ui.dashboard.screens.viewmodels.PRELOAD_DISTANCE_RANGE
import srv.scrtvwr.ui.dashboard.screens.viewmodels.SLIDE_SHOW_STAY_MILLIS_RANGE
import srv.scrtvwr.ui.components.SimpleDropdown
import srv.scrtvwr.ui.media.MediaEdgeDialogStyle
import srv.scrtvwr.ui.media.SlideShowDirection
import srv.scrtvwr.ui.theme.SCRTVWRTheme
import srv.scrtvwr.ui.theme.ThemeMode
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
        onLoadVideoThumbnailsChange = viewModel::setLoadVideoThumbnails,
        onSlideShowStayMillisChange = viewModel::setSlideShowStayMillis,
        onHoldSlideShowEnabledChange = viewModel::setHoldSlideShowEnabled,
        onSlideShowDirectionChange = viewModel::setSlideShowDirection,
        onImagePreloadDistanceChange = viewModel::setImagePreloadDistance,
        onVideoPreloadDistanceChange = viewModel::setVideoPreloadDistance,
        onMediaEdgeDialogStyleChange = viewModel::setMediaEdgeDialogStyle,
        onShowGestureTipsAgain = viewModel::showGestureTipsAgain,
        onThemeModeChange = viewModel::setThemeMode,
        onResetToDefaults = viewModel::resetToDefaults,
        modifier = modifier,
    )
}

@Composable
fun OthersScreen(
    uiState: OthersUiState,
    onShowHiddenFilesChange: (Boolean) -> Unit,
    onShowDirectoryFileActionsChange: (Boolean) -> Unit,
    onLoadVideoThumbnailsChange: (Boolean) -> Unit,
    onSlideShowStayMillisChange: (Long) -> Unit,
    onHoldSlideShowEnabledChange: (Boolean) -> Unit,
    onSlideShowDirectionChange: (SlideShowDirection) -> Unit,
    onImagePreloadDistanceChange: (Int) -> Unit,
    onVideoPreloadDistanceChange: (Int) -> Unit,
    onMediaEdgeDialogStyleChange: (MediaEdgeDialogStyle) -> Unit,
    modifier: Modifier = Modifier,
    onShowGestureTipsAgain: () -> Unit = {},
    onThemeModeChange: (ThemeMode) -> Unit = {},
    onResetToDefaults: () -> Unit = {},
) {
    var resetConfirmationVisible by rememberSaveable { mutableStateOf(false) }
    val shortSlideShowWarning = stringResource(R.string.short_slideshow_warning)
    val largePreloadWarning = stringResource(R.string.large_preload_warning)
    val preloadDistanceRange = PRELOAD_DISTANCE_RANGE.first.toLong()..PRELOAD_DISTANCE_RANGE.last.toLong()
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { SettingsSectionHeader(stringResource(R.string.settings_section_appearance), isFirst = true) }
        item {
            val themeLabels = ThemeMode.entries.associateWith { mode -> stringResource(mode.labelResource()) }
            ListItem(
                modifier = Modifier.fillMaxWidth(),
                headlineContent = { Text(stringResource(R.string.theme)) },
                supportingContent = { Text(stringResource(R.string.theme_summary)) },
                leadingContent = { Icon(Icons.Default.Palette, contentDescription = null) },
                trailingContent = {
                    SimpleDropdown(
                        label = stringResource(R.string.theme),
                        items = ThemeMode.entries,
                        selected = uiState.themeMode,
                        onSelected = onThemeModeChange,
                        modifier = Modifier.fillMaxWidth(0.46f),
                        itemLabel = { mode -> themeLabels.getValue(mode) },
                    )
                },
            )
        }
        item { SettingsSectionHeader(stringResource(R.string.settings_section_browsing)) }
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
            ListItem(
                modifier = Modifier.fillMaxWidth(),
                headlineContent = { Text(stringResource(R.string.load_video_thumbnails)) },
                supportingContent = { Text(stringResource(R.string.load_video_thumbnails_summary)) },
                leadingContent = { Icon(Icons.Default.VideoLibrary, contentDescription = null) },
                trailingContent = {
                    Switch(
                        checked = uiState.loadVideoThumbnails,
                        onCheckedChange = onLoadVideoThumbnailsChange,
                    )
                },
            )
        }
        item { SettingsSectionHeader(stringResource(R.string.settings_section_viewer)) }
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
            val context = LocalContext.current
            ListItem(
                modifier = Modifier.fillMaxWidth(),
                headlineContent = { Text(stringResource(R.string.gesture_tips_setting)) },
                supportingContent = { Text(stringResource(R.string.gesture_tips_setting_summary)) },
                leadingContent = { Icon(Icons.Default.TouchApp, contentDescription = null) },
                trailingContent = {
                    TextButton(
                        onClick = {
                            onShowGestureTipsAgain()
                            Toast.makeText(context, R.string.gesture_tips_reset, Toast.LENGTH_SHORT).show()
                        },
                    ) {
                        Text(stringResource(R.string.show_again))
                    }
                },
            )
        }
        item { SettingsSectionHeader(stringResource(R.string.slideshow)) }
        item {
            NumericPreferenceListItem(
                headline = stringResource(R.string.slideshow_speed),
                supporting = stringResource(R.string.slideshow_speed_summary),
                value = uiState.slideShowStayMillis,
                range = SLIDE_SHOW_STAY_MILLIS_RANGE,
                stepDown = { value -> value.slideShowStep(increasing = false) },
                stepUp = { value -> value.slideShowStep(increasing = true) },
                onValueChange = onSlideShowStayMillisChange,
                leadingContent = { Icon(Icons.Default.Slideshow, contentDescription = null) },
                suffix = "ms",
                warning = { value -> shortSlideShowWarning.takeIf { value < 100L } },
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
        item { SettingsSectionHeader(stringResource(R.string.settings_section_performance)) }
        item {
            NumericPreferenceListItem(
                headline = stringResource(R.string.image_preload_distance),
                supporting = stringResource(R.string.image_preload_summary),
                value = uiState.imagePreloadDistance.toLong(),
                range = preloadDistanceRange,
                stepDown = { value -> value - 1 },
                stepUp = { value -> value + 1 },
                onValueChange = { value -> onImagePreloadDistanceChange(value.toInt()) },
                leadingContent = { Icon(Icons.Default.PhotoLibrary, contentDescription = null) },
                warning = { value -> largePreloadWarning.takeIf { value > 10L } },
            )
        }
        item {
            NumericPreferenceListItem(
                headline = stringResource(R.string.video_preload_distance),
                supporting = stringResource(R.string.video_preload_summary),
                value = uiState.videoPreloadDistance.toLong(),
                range = preloadDistanceRange,
                stepDown = { value -> value - 1 },
                stepUp = { value -> value + 1 },
                onValueChange = { value -> onVideoPreloadDistanceChange(value.toInt()) },
                leadingContent = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                warning = { value -> largePreloadWarning.takeIf { value > 10L } },
            )
        }
        item {
            ListItem(
                modifier = Modifier.fillMaxWidth(),
                headlineContent = { Text(stringResource(R.string.reset_settings)) },
                supportingContent = { Text(stringResource(R.string.reset_settings_summary)) },
                leadingContent = { Icon(Icons.Default.RestartAlt, contentDescription = null) },
                trailingContent = {
                    TextButton(onClick = { resetConfirmationVisible = true }) {
                        Text(stringResource(R.string.reset))
                    }
                },
            )
        }
        item { SettingsSectionHeader(stringResource(R.string.settings_section_about)) }
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

    if (resetConfirmationVisible) {
        AlertDialog(
            onDismissRequest = { resetConfirmationVisible = false },
            title = { Text(stringResource(R.string.reset_settings_title)) },
            text = { Text(stringResource(R.string.reset_settings_message)) },
            confirmButton = {
                Button(
                    onClick = {
                        onResetToDefaults()
                        resetConfirmationVisible = false
                    },
                ) {
                    Text(stringResource(R.string.reset))
                }
            },
            dismissButton = {
                TextButton(onClick = { resetConfirmationVisible = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

private fun ThemeMode.labelResource(): Int = when (this) {
    ThemeMode.System -> R.string.theme_system
    ThemeMode.Light -> R.string.theme_light
    ThemeMode.Dark -> R.string.theme_dark
}

@Composable
private fun SettingsSectionHeader(
    text: String,
    isFirst: Boolean = false,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = if (isFirst) 0.dp else 12.dp),
    )
}

/**
 * A whole number setting that can be typed or stepped with − and +. Typed values inside [range]
 * save as they are typed; one outside it shows an error and is brought into [range] when the field
 * loses focus. [stepDown] and [stepUp] give the next value either way.
 */
@Composable
private fun NumericPreferenceListItem(
    headline: String,
    supporting: String,
    value: Long,
    range: LongRange,
    stepDown: (Long) -> Long,
    stepUp: (Long) -> Long,
    onValueChange: (Long) -> Unit,
    leadingContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    suffix: String? = null,
    warning: (Long) -> String? = { null },
) {
    var text by rememberSaveable { mutableStateOf(value.toString()) }
    LaunchedEffect(value) {
        if (text.toLongOrNull() != value) text = value.toString()
    }
    val typed = text.toLongOrNull()
    val message = when {
        typed == null -> stringResource(R.string.invalid_whole_number)
        typed !in range -> stringResource(R.string.value_out_of_range, range.first, range.last)
        else -> warning(typed)
    }
    val isError = typed == null || typed !in range
    // The buttons step from what is shown when it is valid, otherwise from the saved value.
    val current = typed?.takeIf { it in range } ?: value.coerceIn(range)
    val focusManager = LocalFocusManager.current

    fun commit(newValue: Long) {
        val bounded = newValue.coerceIn(range)
        text = bounded.toString()
        onValueChange(bounded)
    }

    ListItem(
        modifier = modifier.fillMaxWidth(),
        headlineContent = { Text(headline) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(supporting)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { commit(stepDown(current)) },
                        enabled = current > range.first,
                    ) {
                        Icon(Icons.Default.Remove, contentDescription = stringResource(R.string.decrease_value, headline))
                    }
                    OutlinedTextField(
                        value = text,
                        onValueChange = { newText ->
                            // Long enough for any value in range, short enough never to overflow.
                            text = newText.filter(Char::isDigit).take(MAX_NUMBER_LENGTH)
                            text.toLongOrNull()?.takeIf { it in range }?.let(onValueChange)
                        },
                        singleLine = true,
                        isError = isError,
                        suffix = suffix?.let { { Text(it) } },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                        modifier = Modifier
                            .width(140.dp)
                            .onFocusChanged { focus ->
                                if (!focus.isFocused && (typed == null || typed !in range)) {
                                    commit(typed ?: value)
                                }
                            },
                    )
                    IconButton(
                        onClick = { commit(stepUp(current)) },
                        enabled = current < range.last,
                    ) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.increase_value, headline))
                    }
                }
                message?.let {
                    Text(
                        text = it,
                        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        leadingContent = leadingContent,
    )
}

/**
 * The next slide show stay time either way. The step grows with the time, and lands on a multiple
 * of itself, so stepping up and back down retraces the same values.
 */
private fun Long.slideShowStep(increasing: Boolean): Long {
    // Stepping down uses the step of the range it lands in.
    val reference = if (increasing) this else this - 1
    val step = when {
        reference < 100L -> 10L
        reference < 1_000L -> 50L
        reference < 5_000L -> 250L
        else -> 1_000L
    }
    return if (increasing) (this / step + 1) * step else ((this - 1) / step) * step
}

private const val MAX_NUMBER_LENGTH = 9

@Preview(showBackground = true)
@Composable
fun OthersPreview() {
    SCRTVWRTheme {
        OthersScreen(
            uiState = OthersUiState(
                showHiddenFiles = true,
                showDirectoryFileActions = false,
                slideShowStayMillis = 100L,
                holdSlideShowEnabled = true,
                slideShowDirection = SlideShowDirection.Next,
                imagePreloadDistance = 5,
                videoPreloadDistance = 1,
                mediaEdgeDialogStyle = MediaEdgeDialogStyle.Bottom,
            ),
            onShowHiddenFilesChange = {},
            onShowDirectoryFileActionsChange = {},
            onLoadVideoThumbnailsChange = {},
            onSlideShowStayMillisChange = {},
            onHoldSlideShowEnabledChange = {},
            onSlideShowDirectionChange = {},
            onImagePreloadDistanceChange = {},
            onVideoPreloadDistanceChange = {},
            onMediaEdgeDialogStyleChange = {},
        )
    }
}
