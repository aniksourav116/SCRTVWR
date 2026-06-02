package srv.scrt.vwr

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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import srv.scrt.vwr.ui.commons.BaseScaffoldSimple
import srv.scrt.vwr.ui.dashboard.screens.PlaceholderScreen
import srv.scrt.vwr.ui.theme.SCRTVWRTheme

class DashboardActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SCRTVWRTheme {
                SCRTVWRApp()
            }
        }
    }
}

@PreviewScreenSizes
@Composable
fun SCRTVWRApp(viewModel: DashboardViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val destinations = uiState.destinations
    val initialPage = remember(destinations) {
        destinations.indexOf(uiState.currentDestination).coerceAtLeast(0)
    }
    val pagerState = rememberPagerState(
        initialPage = initialPage,
        pageCount = { destinations.size }
    )
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(pagerState, destinations) {
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .collect { page ->
                viewModel.updateDestination(destinations[page])
            }
    }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            destinations.forEach { destination ->
                item(
                    icon = {
                        Icon(
                            imageVector = destination.icon,
                            contentDescription = stringResource(destination.label)
                        )
                    },
                    label = { Text(stringResource(destination.label)) },
                    selected = uiState.currentDestination == destination,
                    onClick = {
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(destinations.indexOf(destination))
                        }
                    }
                )
            }
        }
    ) {
        val title = stringResource(destinations[pagerState.currentPage].label)

        BaseScaffoldSimple(
            modifier = Modifier.fillMaxSize(),
            title = title,
            hasNavigationIcon = false
        ) { modifier ->
            HorizontalPager(
                state = pagerState,
                modifier = modifier.fillMaxSize(),
            ) { page ->
                PlaceholderScreen(
                    title = stringResource(destinations[page].label),
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.hello_name, name),
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    SCRTVWRTheme {
        Greeting(stringResource(R.string.preview_name))
    }
}
