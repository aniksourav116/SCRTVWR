package srv.scrtvwr.ui.dashboard.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import srv.scrtvwr.ui.dashboard.screens.viewmodels.StorageUiState
import srv.scrtvwr.ui.dashboard.screens.viewmodels.StorageViewModel
import srv.scrtvwr.ui.theme.SCRTVWRTheme

@Composable
fun Storage(
    modifier: Modifier = Modifier,
    viewModel: StorageViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    StorageScreen(uiState, viewModel::refresh, modifier)
}

@Composable
fun StorageScreen(
    uiState: StorageUiState,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Default.Storage, contentDescription = null)
            Text("Storage overview", style = MaterialTheme.typography.headlineSmall)
        }
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("${uiState.usedSpaceGb} GB of ${uiState.totalSpaceGb} GB used")
                LinearProgressIndicator(
                    progress = { uiState.usedFraction },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Last checked: ${uiState.lastChecked}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Button(onClick = onRefresh) {
            Icon(Icons.Default.Refresh, contentDescription = null)
            Text("Refresh", Modifier.padding(start = 8.dp))
        }
    }
}

@Preview(showBackground = true)
@Composable
fun StorageScreenPreview() {
    SCRTVWRTheme {
        StorageScreen(
            uiState = StorageUiState(
                totalSpaceGb = 256,
                usedSpaceGb = 120,
                lastChecked = "Oct 27, 2023 10:00 AM"
            ),
            onRefresh = {}
        )
    }
}
