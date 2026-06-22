package srv.scrtvwr.ui.dashboard.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

@Composable
fun History(
    modifier: Modifier = Modifier,
    onOpenDirectory: (String, String?) -> Unit = { _, _ -> },
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
    HistoryScreen(
        uiState = uiState,
        onFilterSelected = viewModel::selectFilter,
        onOpenDirectory = onOpenDirectory,
        modifier = modifier,
    )
}

@Composable
fun HistoryScreen(
    uiState: HistoryUiState,
    onFilterSelected: (HistoryFilter) -> Unit,
    onOpenDirectory: (String, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.viewing_history), style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HistoryFilter.entries.forEach { filter ->
                FilterChip(
                    selected = filter == uiState.selectedFilter,
                    onClick = { onFilterSelected(filter) },
                    label = { Text(stringResource(filter.labelResource())) },
                )
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
                items(uiState.visibleDirectories, key = { it.path }) { directory ->
                    ListItem(
                        headlineContent = { Text(directory.name) },
                        supportingContent = { Text(directory.path) },
                        leadingContent = { Icon(Icons.Default.Folder, contentDescription = null) },
                        modifier = Modifier.clickable(role = Role.Button) {
                            onOpenDirectory(directory.path, directory.mediaPath)
                        },
                    )
                }
            }
        }
    }
}

private fun HistoryFilter.labelResource(): Int = when (this) {
    HistoryFilter.ALL -> R.string.all
    HistoryFilter.RECENT -> R.string.recent
    HistoryFilter.FAVORITES -> R.string.favorites
}
