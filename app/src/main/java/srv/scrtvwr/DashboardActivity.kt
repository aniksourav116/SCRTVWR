package srv.scrtvwr

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import srv.scrtvwr.ui.components.BaseScaffold
import srv.scrtvwr.ui.dashboard.screens.Directory
import srv.scrtvwr.ui.dashboard.screens.History
import srv.scrtvwr.ui.dashboard.screens.Others
import srv.scrtvwr.ui.dashboard.screens.Storage
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

    LaunchedEffect(state.currentDestination, destinations) {
        val page = destinations.indexOf(state.currentDestination)
        if (page >= 0 && page != pagerState.currentPage) {
            pagerState.animateScrollToPage(page)
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
                when (destinations[page]) {
                    AppDestination.STORAGE -> Storage(Modifier.fillMaxSize())
                    AppDestination.DIRECTORY -> Directory(
                        modifier = Modifier.fillMaxSize(),
                        targetDirectoryPath = state.pendingDirectoryPath,
                        onTargetDirectoryConsumed = viewModel::clearPendingDirectoryPath,
                    )
                    AppDestination.HISTORY -> History(
                        modifier = Modifier.fillMaxSize(),
                        onOpenDirectory = viewModel::openDirectoryFromHistory,
                    )
                    AppDestination.OTHERS -> Others(Modifier.fillMaxSize())
                }
            }
        }
    }
}
