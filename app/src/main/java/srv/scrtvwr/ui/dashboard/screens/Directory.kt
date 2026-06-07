package srv.scrtvwr.ui.dashboard.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import srv.scrtvwr.ui.dashboard.screens.viewmodels.DirectoryUiState
import srv.scrtvwr.ui.dashboard.screens.viewmodels.DirectoryViewModel

@Composable
fun Directory(
    modifier: Modifier = Modifier,
    viewModel: DirectoryViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    DirectoryScreen(uiState, viewModel::updateSearchQuery, modifier)
}

@Composable
fun DirectoryScreen(
    uiState: DirectoryUiState,
    onSearchQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Directories", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = uiState.searchQuery,
            onValueChange = onSearchQueryChange,
            label = { Text("Search folders") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        LazyColumn(Modifier.fillMaxSize()) {
            items(uiState.visibleDirectories) { directory ->
                ListItem(
                    headlineContent = { Text(directory) },
                    leadingContent = { Icon(Icons.Default.Folder, contentDescription = null) },
                )
            }
        }
    }
}
