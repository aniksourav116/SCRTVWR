package srv.scrtvwr

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import srv.scrtvwr.ui.components.BaseScaffold
import srv.scrtvwr.ui.theme.SCRTVWRTheme

class DashboardActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SCRTVWRTheme {
                ScrtvwrApp()
            }
        }
    }
}

@PreviewScreenSizes
@Composable
fun ScrtvwrApp(viewModel: DashboardViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()
    val destinations = state.destinations
    val initialPage = remember(destinations) {
        destinations.indexOf(state.currentDestination).coerceAtLeast(0)
    }
    val pagerState = rememberPagerState(
        initialPage = initialPage,
        pageCount = destinations::size,
    )
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(pagerState, destinations) {
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .collect { page ->
                destinations.getOrNull(page)?.let(viewModel::select)
            }
    }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            destinations.forEachIndexed { page, destination ->
                item(
                    icon = {
                        Icon(
                            imageVector = destination.icon,
                            contentDescription = stringResource(destination.label),
                        )
                    },
                    label = { Text(stringResource(destination.label)) },
                    selected = destination == state.currentDestination,
                    onClick = {
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(page)
                        }
                    },
                )
            }
        },
    ) {
        val visibleDestination = destinations[pagerState.currentPage]
        BaseScaffold(
            title = stringResource(visibleDestination.label),
            showBackButton = false,
        ) { modifier ->
            HorizontalPager(
                state = pagerState,
                modifier = modifier.fillMaxSize(),
            ) { page ->
                DashboardPanel(
                    destination = destinations[page],
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun DashboardPanel(
    destination: AppDestination,
    modifier: Modifier = Modifier,
) {
    val message = when (destination) {
        AppDestination.STORAGE -> R.string.storage_hint
        AppDestination.DIRECTORY -> R.string.directory_hint
        AppDestination.HISTORY -> R.string.history_hint
        AppDestination.OTHERS -> R.string.others_hint
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = destination.icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(destination.label),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = stringResource(message),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
