package srv.scrtvwr.ui.dashboard.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import srv.scrtvwr.ui.dashboard.screens.viewmodels.OthersUiState
import srv.scrtvwr.ui.dashboard.screens.viewmodels.OthersViewModel

@Composable
fun Others(
    modifier: Modifier = Modifier,
    viewModel: OthersViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    OthersScreen(uiState, viewModel::setShowHiddenFiles, modifier)
}

@Composable
fun OthersScreen(
    uiState: OthersUiState,
    onShowHiddenFilesChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Other options", style = MaterialTheme.typography.headlineSmall)
        ListItem(
            modifier = Modifier.fillMaxWidth(),
            headlineContent = { Text("Show hidden files") },
            supportingContent = { Text("Include hidden items in directory listings.") },
            leadingContent = { Icon(Icons.Default.Visibility, contentDescription = null) },
            trailingContent = {
                Switch(
                    checked = uiState.showHiddenFiles,
                    onCheckedChange = onShowHiddenFilesChange,
                )
            },
        )
    }
}
