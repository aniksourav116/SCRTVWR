package srv.scrtvwr.ui.dashboard.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import srv.scrtvwr.R
import srv.scrtvwr.ui.components.SimpleDropdown
import srv.scrtvwr.ui.dashboard.screens.viewmodels.DirectorySearchOptions

@Composable
internal fun DirectorySearchControls(
    options: DirectorySearchOptions,
    onChange: (DirectorySearchOptions) -> Unit,
) {
    var filterDialog by rememberSaveable { mutableStateOf(false) }
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = options.includeSubfolders,
            onClick = { onChange(options.copy(includeSubfolders = !options.includeSubfolders)) },
            label = { Text(stringResource(R.string.search_subfolders)) },
        )
        FilterChip(
            selected = options.hasMetadataFilters,
            onClick = { filterDialog = true },
            label = { Text(stringResource(R.string.search_size_date)) },
        )
        if (options.hasMetadataFilters) {
            TextButton(onClick = { onChange(DirectorySearchOptions(options.includeSubfolders)) }) {
                Text(stringResource(R.string.search_reset_filters))
            }
        }
    }
    if (filterDialog) {
        SearchFiltersDialog(options, onDismiss = { filterDialog = false }) {
            onChange(it)
            filterDialog = false
        }
    }
}

@Composable
private fun SearchFiltersDialog(
    options: DirectorySearchOptions,
    onDismiss: () -> Unit,
    onApply: (DirectorySearchOptions) -> Unit,
) {
    var minimum by rememberSaveable { mutableStateOf(options.minimumSizeMiB?.toString().orEmpty()) }
    var maximum by rememberSaveable { mutableStateOf(options.maximumSizeMiB?.toString().orEmpty()) }
    var days by rememberSaveable { mutableStateOf(options.modifiedWithinDays ?: 0) }
    val min = minimum.toLongOrNull()
    val max = maximum.toLongOrNull()
    val minInvalid = minimum.isNotEmpty() && (min == null || min !in 0L..1_000_000_000L)
    val maxInvalid = maximum.isNotEmpty() && (max == null || max !in 0L..1_000_000_000L || max < (min ?: 0L))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.search_size_date)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.search_filter_summary))
                OutlinedTextField(
                    value = minimum,
                    onValueChange = { minimum = it.filter(Char::isDigit).take(10) },
                    label = { Text(stringResource(R.string.search_min_size)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    isError = minInvalid,
                )
                OutlinedTextField(
                    value = maximum,
                    onValueChange = { maximum = it.filter(Char::isDigit).take(10) },
                    label = { Text(stringResource(R.string.search_max_size)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    isError = maxInvalid,
                )
                if (minInvalid || maxInvalid) Text(stringResource(R.string.search_invalid_size))
                SimpleDropdown(
                    label = stringResource(R.string.search_modified),
                    items = listOf(0, 1, 7, 30, 365),
                    selected = days,
                    onSelected = { days = it },
                    itemLabel = { if (it == 0) stringResource(R.string.search_any_time) else stringResource(R.string.search_last_days, it) },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !minInvalid && !maxInvalid,
                onClick = { onApply(options.copy(minimumSizeMiB = min, maximumSizeMiB = max, modifiedWithinDays = days.takeIf { it > 0 })) },
            ) { Text(stringResource(R.string.search_apply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
