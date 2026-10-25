package srv.scrtvwr

import android.os.Bundle
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import srv.scrtvwr.ui.components.BaseScaffold
import srv.scrtvwr.ui.components.NotificationPermissionRequest
import srv.scrtvwr.ui.dashboard.screens.Directory
import srv.scrtvwr.ui.dashboard.screens.History
import srv.scrtvwr.ui.dashboard.screens.Others
import srv.scrtvwr.ui.dashboard.screens.Storage
import srv.scrtvwr.preferences.ScrtvwrPreferences
import srv.scrtvwr.ui.theme.SCRTVWRTheme
import srv.scrtvwr.ui.theme.isDark

class DashboardActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val preferences = ScrtvwrPreferences(this)
        setContent {
            val themeMode by remember { preferences.themeModeChanges() }
                .collectAsStateWithLifecycle(initialValue = preferences.getThemeMode())
            val darkTheme = themeMode.isDark()
            // The bars follow the app's theme rather than the system's, so their icons stay readable.
            LaunchedEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT,
                    ) { darkTheme },
                    navigationBarStyle = SystemBarStyle.auto(
                        NAVIGATION_BAR_LIGHT_SCRIM,
                        NAVIGATION_BAR_DARK_SCRIM,
                    ) { darkTheme },
                )
            }
            SCRTVWRTheme(darkTheme = darkTheme) {
                NotificationPermissionRequest()
                ScrtvwrApp()
            }
        }
    }
}

@PreviewScreenSizes
@Composable
fun ScrtvwrApp(viewModel: DashboardViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
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
        layoutType = if (isLandscape) {
            NavigationSuiteType.NavigationRail
        } else {
            NavigationSuiteType.NavigationBar
        },
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
        BackHandler(enabled = visibleDestination != AppDestination.STORAGE) {
            val storagePage = destinations.indexOf(AppDestination.STORAGE)
            if (storagePage >= 0) {
                coroutineScope.launch {
                    pagerState.animateScrollToPage(storagePage)
                }
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            when (destinations[page]) {
                AppDestination.STORAGE -> BaseScaffold(
                    title = stringResource(AppDestination.STORAGE.topBarLabel()),
                    showBackButton = false,
                ) { modifier ->
                    Storage(
                        modifier = modifier,
                        onOpenDirectory = viewModel::openDirectoryFromStorage,
                    )
                }
                AppDestination.DIRECTORY -> Directory(
                    modifier = Modifier.fillMaxSize(),
                    targetDirectoryPath = state.pendingDirectoryPath,
                    onTargetDirectoryConsumed = viewModel::clearPendingDirectoryPath,
                    backHandlerEnabled = visibleDestination == AppDestination.DIRECTORY,
                    isVisible = visibleDestination == AppDestination.DIRECTORY,
                )
                AppDestination.HISTORY -> BaseScaffold(
                    title = stringResource(AppDestination.HISTORY.topBarLabel()),
                    showBackButton = false,
                ) { modifier ->
                    History(modifier)
                }
                AppDestination.OTHERS -> BaseScaffold(
                    title = stringResource(AppDestination.OTHERS.topBarLabel()),
                    showBackButton = false,
                ) { modifier ->
                    Others(modifier)
                }
            }
        }
    }
}

// The scrims enableEdgeToEdge() uses by default for three-button navigation.
private val NAVIGATION_BAR_LIGHT_SCRIM = android.graphics.Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
private val NAVIGATION_BAR_DARK_SCRIM = android.graphics.Color.argb(0x80, 0x1b, 0x1b, 0x1b)

private fun AppDestination.topBarLabel(): Int = when (this) {
    AppDestination.STORAGE -> R.string.storage_devices
    AppDestination.DIRECTORY -> R.string.directory
    AppDestination.HISTORY -> R.string.viewing_history
    AppDestination.OTHERS -> R.string.other_options
}
