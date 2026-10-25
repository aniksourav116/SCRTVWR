package srv.scrtvwr

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.LastPage
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FirstPage
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.ImageNotSupported
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Rotate90DegreesCcw
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import srv.scrtvwr.media.MediaPaths
import srv.scrtvwr.media.MediaPreviewLoader
import srv.scrtvwr.media.remote.DownloadProgress
import srv.scrtvwr.media.PreviewResult
import srv.scrtvwr.ui.components.BookmarkFlash
import srv.scrtvwr.ui.components.copyLink
import srv.scrtvwr.ui.media.MediaEdgeDialogStyle
import srv.scrtvwr.ui.media.MediaViewerItem
import srv.scrtvwr.ui.media.MediaViewerSource
import srv.scrtvwr.ui.media.MediaViewerType
import srv.scrtvwr.ui.media.MediaViewerUiState
import srv.scrtvwr.ui.media.MediaViewerViewModel
import srv.scrtvwr.ui.media.RemotePreloadLimits
import srv.scrtvwr.ui.media.SlideShowDirection
import srv.scrtvwr.ui.media.pageOffset
import srv.scrtvwr.ui.media.pagesAllowedToLoad
import srv.scrtvwr.ui.sort.DirectorySortOrder
import srv.scrtvwr.ui.theme.SCRTVWRTheme
import srv.scrtvwr.util.time.TimeUtil
import kotlin.time.Duration.Companion.milliseconds
import kotlin.math.pow

class MediaViewerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val directoryPath = intent.getStringExtra(EXTRA_DIRECTORY_PATH)
        val mediaPath = intent.getStringExtra(EXTRA_MEDIA_PATH)

        setContent {
            SCRTVWRTheme {
                MediaViewerRoute(
                    directoryPath = directoryPath,
                    selectedPath = mediaPath,
                    onBack = ::finish,
                    onDirectoryChanged = { path ->
                        setResult(
                            Activity.RESULT_OK,
                            Intent().putExtra(RESULT_DIRECTORY_PATH, path),
                        )
                    },
                )
            }
        }
    }

    companion object {
        const val EXTRA_DIRECTORY_PATH = "srv.scrtvwr.extra.DIRECTORY_PATH"
        const val EXTRA_MEDIA_PATH = "srv.scrtvwr.extra.MEDIA_PATH"
        const val RESULT_DIRECTORY_PATH = "srv.scrtvwr.result.MEDIA_DIRECTORY_PATH"
    }
}

@Composable
private fun MediaViewerRoute(
    directoryPath: String?,
    selectedPath: String?,
    onBack: () -> Unit,
    onDirectoryChanged: (String) -> Unit,
    viewModel: MediaViewerViewModel = viewModel(),
) {
    LaunchedEffect(directoryPath, selectedPath) {
        viewModel.open(directoryPath, selectedPath)
    }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(uiState.sourceDirectoryPath) {
        uiState.sourceDirectoryPath?.let(onDirectoryChanged)
    }
    val context = LocalContext.current
    val videoFrameLoadWarning by MediaPreviewLoader.videoFrameLoadWarning.collectAsStateWithLifecycle()
    // Once per viewer; the directory listing keeps the warning, with a button that turns frames off.
    var videoFrameLoadWarningShown by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(videoFrameLoadWarning, uiState.loadVideoThumbnails) {
        if (videoFrameLoadWarning && uiState.loadVideoThumbnails && !videoFrameLoadWarningShown) {
            videoFrameLoadWarningShown = true
            Toast.makeText(context, R.string.video_frame_load_warning_viewer, Toast.LENGTH_LONG).show()
        }
    }
    MediaViewerScreen(
        uiState = uiState,
        onPageChanged = viewModel::selectPage,
        onFitClick = viewModel::toggleFillFrame,
        onRotateClick = viewModel::rotateClockwise,
        onFlipClick = viewModel::toggleFlip,
        onBookmarkClick = viewModel::toggleBookmark,
        onBookmarkAdd = viewModel::addBookmark,
        onChromeToggle = viewModel::toggleChrome,
        onFindSources = viewModel::findNeighbourSources,
        onPreviousSourceClick = viewModel::loadPreviousSource,
        onNextSourceClick = viewModel::loadNextSource,
        onSlideShowStart = viewModel::startSlideShow,
        onSlideShowStop = viewModel::stopSlideShow,
        onSlideShowSkipsVideosToggle = viewModel::toggleSlideShowSkipsVideos,
        onHoldSlideShowSkipsVideosToggle = viewModel::toggleHoldSlideShowSkipsVideos,
        onSortOrderChange = viewModel::setSortOrder,
        onJumpRecorded = viewModel::recordJump,
        onJumpBack = viewModel::jumpBack,
        onJumpForward = viewModel::jumpForward,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaViewerScreen(
    uiState: MediaViewerUiState,
    onPageChanged: (Int) -> Unit,
    onFitClick: () -> Unit,
    onRotateClick: () -> Unit,
    onFlipClick: () -> Unit,
    onBookmarkClick: () -> Unit,
    onBookmarkAdd: (String) -> Unit,
    onChromeToggle: () -> Unit,
    onFindSources: () -> Unit,
    onPreviousSourceClick: () -> Unit,
    onNextSourceClick: () -> Unit,
    onSlideShowStart: (Long, SlideShowDirection) -> Unit,
    onSlideShowStop: () -> Unit,
    onSlideShowSkipsVideosToggle: () -> Unit,
    onHoldSlideShowSkipsVideosToggle: () -> Unit,
    onSortOrderChange: (DirectorySortOrder) -> Unit,
    onJumpRecorded: (fromPage: Int, toPage: Int) -> Unit,
    onJumpBack: (currentPage: Int) -> Unit,
    onJumpForward: (currentPage: Int) -> Unit,
    onBack: () -> Unit,
) {
    // Each loaded list starts its own pager on its selected page. Reusing one would first lay out,
    // and so start loading, the pages around wherever the previous list was left.
    val pagerState = key(uiState.loadId) {
        rememberPagerState(
            initialPage = uiState.selectedPage,
            pageCount = { uiState.pageCount.coerceAtLeast(1) },
        )
    }
    // Pages follow their file rather than their position, so a list sorted again keeps the page on
    // screen and its loaded picture. An archive can hold an entry name twice, and a repeated key
    // would crash the pager, so repeats are numbered.
    val pageKeys = remember(uiState.items) {
        val seen = HashMap<String, Int>()
        uiState.items.map { item ->
            val count = (seen[item.path] ?: 0) + 1
            seen[item.path] = count
            if (count == 1) item.path else "${item.path}#$count"
        }
    }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    var menuExpanded by rememberSaveable { mutableStateOf(false) }
    var slideShowDialogVisible by rememberSaveable { mutableStateOf(false) }
    var sortDialogVisible by rememberSaveable { mutableStateOf(false) }
    var detailsDialogVisible by rememberSaveable { mutableStateOf(false) }
    var edgeDialogDirection by rememberSaveable { mutableStateOf<SourceDirection?>(null) }
    var slideShowStayText by rememberSaveable { mutableStateOf(uiState.slideShowStayMillis.toString()) }
    var slideShowDirection by rememberSaveable { mutableStateOf(uiState.slideShowDirection) }
    var verticalDrag by remember { mutableFloatStateOf(0f) }
    var isPseudoSlideShowRunning by remember { mutableStateOf(false) }
    var pseudoSlideShowStayMillis by remember { mutableStateOf(uiState.slideShowStayMillis) }
    var pseudoSlideShowHorizontalFraction by remember { mutableFloatStateOf(0f) }
    var pseudoSlideShowPageOffset by remember { mutableStateOf(1) }
    var pseudoSlideShowStartPage by remember { mutableStateOf<Int?>(null) }
    // Pages whose load has started, and finished with or without a picture. Keyed by load, not by
    // list: sorting the list again keeps its pages and what they loaded.
    val startedMedia = remember(uiState.loadId) { mutableStateMapOf<String, Boolean>() }
    val settledMedia = remember(uiState.loadId) { mutableStateMapOf<String, Boolean>() }
    val remoteDownloadsSlow by MediaPreviewLoader.remoteDownloadsSlow.collectAsStateWithLifecycle()
    val zoomedMedia = remember(uiState.loadId) { mutableStateMapOf<String, Boolean>() }
    var zoomCommand by remember { mutableStateOf<ZoomCommand?>(null) }
    var bookmarkFlashId by remember { mutableIntStateOf(0) }
    val isAnySlideShowRunning = uiState.isSlideShowRunning || isPseudoSlideShowRunning
    val activeSlideShowStayMillis = if (isPseudoSlideShowRunning) {
        pseudoSlideShowStayMillis
    } else {
        uiState.slideShowStayMillis
    }
    val activeSlideShowPageOffset = if (isPseudoSlideShowRunning) {
        pseudoSlideShowPageOffset
    } else {
        uiState.slideShowDirection.pageOffset
    }
    val activeSlideShowSkipsVideos = if (isPseudoSlideShowRunning) {
        uiState.holdSlideShowSkipsVideos
    } else {
        uiState.slideShowSkipsVideos
    }
    // Only a running show jumps over videos; otherwise they load and stay like any page.
    fun isSkippedBySlideShow(page: Int): Boolean =
        isAnySlideShowRunning && activeSlideShowSkipsVideos &&
            uiState.itemForPage(page)?.type == MediaViewerType.Video

    fun showEdgeDialog(direction: SourceDirection) {
        if (uiState.items.isNotEmpty()) {
            edgeDialogDirection = direction
            // Reaching an edge before the media on screen settled needs the answer now.
            onFindSources()
        }
    }

    fun navigateFromCurrentPage(offset: Int, animated: Boolean) {
        val requestedPage = pagerState.currentPage + offset
        when {
            requestedPage < 0 -> showEdgeDialog(SourceDirection.Previous)
            requestedPage > uiState.lastPageIndex -> showEdgeDialog(SourceDirection.Next)
            requestedPage != pagerState.currentPage -> coroutineScope.launch {
                if (animated) {
                    pagerState.animateScrollToPage(requestedPage)
                } else {
                    pagerState.scrollToPage(requestedPage)
                }
            }
        }
    }

    fun startPseudoSlideShow(pageOffset: Int) {
        if (uiState.items.isEmpty() || uiState.isSlideShowRunning) return
        pseudoSlideShowStayMillis = uiState.slideShowStayMillis
        pseudoSlideShowHorizontalFraction = 0f
        pseudoSlideShowPageOffset = pageOffset
        pseudoSlideShowStartPage = pagerState.currentPage
        isPseudoSlideShowRunning = true
    }

    fun stopPseudoSlideShow() {
        // A whole hold counts as one jump, so it can be undone if it ran past the wanted page.
        pseudoSlideShowStartPage?.let { onJumpRecorded(it, pagerState.currentPage) }
        pseudoSlideShowStartPage = null
        isPseudoSlideShowRunning = false
        pseudoSlideShowHorizontalFraction = 0f
        pseudoSlideShowPageOffset = 1
    }

    LaunchedEffect(slideShowDialogVisible, uiState.slideShowStayMillis, uiState.slideShowDirection) {
        if (slideShowDialogVisible) {
            slideShowStayText = uiState.slideShowStayMillis.toString()
            slideShowDirection = uiState.slideShowDirection
        }
    }

    LaunchedEffect(uiState.selectedPage, uiState.pageCount) {
        if (uiState.items.isNotEmpty()) {
            val selectedPage = uiState.selectedPage.coerceIn(0, uiState.lastPageIndex)
            if (pagerState.currentPage != selectedPage) {
                pagerState.scrollToPage(selectedPage)
            }
        }
    }

    LaunchedEffect(pagerState, uiState.pageCount) {
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .collect(onPageChanged)
    }

    LaunchedEffect(
        isAnySlideShowRunning,
        activeSlideShowStayMillis,
        activeSlideShowPageOffset,
        activeSlideShowSkipsVideos,
        isPseudoSlideShowRunning,
        uiState.items,
        pagerState.currentPage,
    ) {
        if (isAnySlideShowRunning) {
            val currentPage = pagerState.currentPage
            val currentItem = uiState.itemForPage(currentPage)
            if (currentItem == null) {
                if (!isPseudoSlideShowRunning) onSlideShowStop()
                return@LaunchedEffect
            }

            // A skipped video, such as one the show started on, moves on without a stay.
            if (!isSkippedBySlideShow(currentPage)) {
                // A page that failed to load counts too, so a broken file never stops the show.
                snapshotFlow { settledMedia[currentItem.readinessKey] == true }
                    .first { it }
                // Give the loaded bitmap a render frame before even a very short stay begins.
                withFrameNanos { }
                delay(activeSlideShowStayMillis.milliseconds)
            }

            if (pagerState.currentPage == currentPage) {
                val requestedPage = generateSequence(currentPage + activeSlideShowPageOffset) { it + activeSlideShowPageOffset }
                    .takeWhile { it in uiState.items.indices }
                    .firstOrNull { !isSkippedBySlideShow(it) }
                if (requestedPage == null) {
                    if (isPseudoSlideShowRunning) {
                        stopPseudoSlideShow()
                    } else {
                        onSlideShowStop()
                    }
                } else {
                    pagerState.scrollToPage(requestedPage)
                }
            }
        }
    }

    val selectedItem = uiState.itemForPage(pagerState.currentPage)
    val selectedMediaKey = selectedItem?.readinessKey
    val isCurrentMediaZoomed = selectedMediaKey?.let { zoomedMedia[it] == true } == true
    val isCurrentMediaSettled = selectedMediaKey?.let { settledMedia[it] == true } ?: true
    fun pageKey(page: Int): String? = uiState.itemForPage(page)?.readinessKey
    val preloadDistance = minOf(uiState.imagePreloadDistance, uiState.lastPageIndex)
    val loadablePages = pagesAllowedToLoad(
        currentPage = pagerState.currentPage,
        pageCount = uiState.pageCount,
        preloadDistance = preloadDistance,
        isCurrentSettled = isCurrentMediaSettled,
        // A slow server gets one download at a time, close by, until it speeds up again.
        remote = if (remoteDownloadsSlow) {
            RemotePreloadLimits(parallel = 1, distance = minOf(SLOW_SERVER_PRELOAD_DISTANCE, preloadDistance))
        } else {
            RemotePreloadLimits(parallel = SERVER_PRELOAD_PARALLEL, distance = preloadDistance)
        },
        downloadsFromServer = { page ->
            uiState.itemForPage(page)?.downloadsFromServer(uiState.loadVideoThumbnails) == true
        },
        hasStarted = { page -> pageKey(page)?.let { startedMedia[it] == true } == true },
        hasSettled = { page -> pageKey(page)?.let { settledMedia[it] == true } == true },
        isSkipped = ::isSkippedBySlideShow,
    )

    // Neighbouring sources are looked for only once the media on screen is done, so walking other
    // folders never competes with it.
    LaunchedEffect(uiState.loadId, selectedMediaKey) {
        val mediaKey = selectedMediaKey ?: return@LaunchedEffect
        snapshotFlow { settledMedia[mediaKey] == true }.first { it }
        onFindSources()
    }
    fun toggleCurrentImageZoom() {
        val mediaKey = selectedMediaKey ?: return
        zoomCommand = ZoomCommand(
            mediaKey = mediaKey,
            id = (zoomCommand?.id ?: 0) + 1,
        )
    }

    fun bookmarkOnDoubleTap(path: String) {
        onBookmarkAdd(path)
        bookmarkFlashId += 1
    }

    val splits = selectedItem?.name?.split("_") ?: emptyList()

    val selectedName =  selectedItem?.name?: stringResource(R.string.no_media_selected)
    val timestampSeconds = if (splits.size >= 4) {
        splits[(splits.size - 3).coerceAtLeast(0)].toLongOrNull()
    } else {
        selectedItem?.lastModifiedMillis?.let { it / 1_000L }
    }
    val time = TimeUtil.getDateAndTimeWithWeekDay(timestampSeconds)


    Scaffold(
        topBar = {
            if (!uiState.isChromeHidden) {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = selectedName,
                                maxLines = 1,
                                style = MaterialTheme.typography.bodyMedium,
                                overflow = TextOverflow.MiddleEllipsis,
//                                modifier = Modifier.basicMarquee(),
                            )
                            Text(
                                text = time,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                modifier = Modifier.basicMarquee(),
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    },
                    actions = {
                        Box {
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_options))
                            }
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(if (uiState.fillFrame) R.string.fit_screen else R.string.fill_screen)) },
                                    onClick = {
                                        onFitClick()
                                        menuExpanded = false
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.details)) },
                                    onClick = {
                                        detailsDialogVisible = true
                                        menuExpanded = false
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.rotate_90)) },
                                    onClick = {
                                        onRotateClick()
                                        menuExpanded = false
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(if (uiState.bookmarked) R.string.remove_bookmark else R.string.bookmark)) },
                                    leadingIcon = {
                                        Icon(
                                            if (uiState.bookmarked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                            contentDescription = null,
                                        )
                                    },
                                    onClick = {
                                        onBookmarkClick()
                                        menuExpanded = false
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(if (isCurrentMediaZoomed) R.string.reset_zoom else R.string.zoom_in)) },
                                    enabled = selectedItem?.type == MediaViewerType.Image,
                                    onClick = {
                                        toggleCurrentImageZoom()
                                        menuExpanded = false
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.slideshow_skips_videos)) },
                                    trailingIcon = { Checkbox(checked = uiState.slideShowSkipsVideos, onCheckedChange = null) },
                                    onClick = {
                                        onSlideShowSkipsVideosToggle()
                                        menuExpanded = false
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.hold_slideshow_skips_videos)) },
                                    trailingIcon = { Checkbox(checked = uiState.holdSlideShowSkipsVideos, onCheckedChange = null) },
                                    onClick = {
                                        onHoldSlideShowSkipsVideosToggle()
                                        menuExpanded = false
                                    },
                                )
                                MediaPaths.link(selectedItem?.path)?.let { link ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.copy_link)) },
                                        leadingIcon = { Icon(Icons.Default.Link, contentDescription = null) },
                                        onClick = {
                                            context.copyLink(link)
                                            menuExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color(0xFF303030),
                        titleContentColor = Color.White,
                        navigationIconContentColor = Color.White,
                        actionIconContentColor = Color.White,
                    ),
                )
            }
        },
        containerColor = Color.Black,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color.Black),
        ) {
            if (!uiState.isChromeHidden) {
                ViewerOptionsRow(
                    currentPage = pagerState.currentPage,
                    pageCount = uiState.pageCount,
                    fillFrame = uiState.fillFrame,
                    flipped = uiState.flipped,
                    onMoveFirstClick = {
                        onJumpRecorded(pagerState.currentPage, 0)
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(0)
                        }
                    },
                    onFitClick = onFitClick,
                    onRotateClick = onRotateClick,
                    onFlipClick = onFlipClick,
                    onSortClick = { sortDialogVisible = true },
                    onSlideShowClick = { slideShowDialogVisible = true },
                    onMoveLastClick = {
                        onJumpRecorded(pagerState.currentPage, uiState.items.lastIndex)
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(uiState.items.lastIndex)
                        }
                    },
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                if (uiState.isLoading) {
                    MediaLoadingIndicator(uiState.downloadProgress, Modifier.align(Alignment.Center))
                } else if (uiState.errorMessage != null && uiState.items.isEmpty()) {
                    Text(
                        text = uiState.errorMessage,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                    )
                } else HorizontalPager(
                    state = pagerState,
                    key = { page -> pageKeys.getOrElse(page) { page } },
                    beyondViewportPageCount = minOf(uiState.imagePreloadDistance, uiState.lastPageIndex),
                    userScrollEnabled = !isCurrentMediaZoomed,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(uiState.pageCount, isCurrentMediaZoomed) {
                            if (isCurrentMediaZoomed) return@pointerInput
                            detectVerticalDragGestures(
                                onDragStart = { verticalDrag = 0f },
                                onVerticalDrag = { _, dragAmount -> verticalDrag += dragAmount },
                                onDragEnd = {
                                    when {
                                        verticalDrag < -80f -> navigateFromCurrentPage(1, animated = true)
                                        verticalDrag > 80f -> navigateFromCurrentPage(-1, animated = true)
                                    }
                                    verticalDrag = 0f
                                },
                            )
                        }
                        .pointerInput(
                            uiState.pageCount,
                            uiState.isChromeHidden,
                            uiState.isSlideShowRunning,
                            uiState.holdSlideShowEnabled,
                            uiState.slideShowStayMillis,
                            isCurrentMediaZoomed,
                        ) {
                            if (
                                !uiState.holdSlideShowEnabled ||
                                !uiState.isChromeHidden ||
                                uiState.isSlideShowRunning ||
                                isCurrentMediaZoomed
                            ) return@pointerInput

                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val startedInLowerControl =
                                    down.position.x in size.width * 0.15f..size.width * 0.85f &&
                                    down.position.y in size.height * 0.55f..size.height * 0.85f
                                if (!startedInLowerControl) return@awaitEachGesture

                                val longPress = awaitLongPressOrCancellation(down.id)
                                    ?: return@awaitEachGesture
                                longPress.consume()
                                val baseStayMillis = uiState.slideShowStayMillis
                                startPseudoSlideShow(pageOffset = 1)

                                try {
                                    do {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id }
                                            ?: break
                                        val horizontalFraction = (
                                            (change.position.x - down.position.x) / (size.width * 0.5f)
                                        ).coerceIn(-1f, 1f)
                                        pseudoSlideShowHorizontalFraction = horizontalFraction
                                        pseudoSlideShowStayMillis = adjustedPseudoStayMillis(
                                            baseStayMillis = baseStayMillis,
                                            horizontalFraction = horizontalFraction,
                                        )
                                        change.consume()
                                    } while (change.pressed)
                                } finally {
                                    stopPseudoSlideShow()
                                }
                            }
                        }
                        .pointerInput(uiState.pageCount, isCurrentMediaZoomed) {
                            if (isCurrentMediaZoomed) return@pointerInput
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                var totalX = 0f
                                var totalY = 0f
                                var notified = false
                                do {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id }
                                        ?: break
                                    totalX += change.position.x - change.previousPosition.x
                                    totalY += change.position.y - change.previousPosition.y

                                    if (
                                        !notified &&
                                        kotlin.math.abs(totalX) > 80f &&
                                        kotlin.math.abs(totalX) > kotlin.math.abs(totalY) * 1.5f
                                    ) {
                                        when {
                                            pagerState.currentPage == 0 && totalX > 0f -> {
                                                showEdgeDialog(SourceDirection.Previous)
                                                notified = true
                                            }
                                            pagerState.currentPage == uiState.lastPageIndex && totalX < 0f -> {
                                                showEdgeDialog(SourceDirection.Next)
                                                notified = true
                                            }
                                        }
                                    }
                                } while (change.pressed)
                            }
                        }
                        .pointerInput(uiState.pageCount, isCurrentMediaZoomed) {
                            if (isCurrentMediaZoomed) return@pointerInput
                            awaitPointerEventScope {
                                var lastScrollTime = 0L
                                var acceleration = 1f
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (event.type == PointerEventType.Scroll) {
                                        val delta = event.changes.first().scrollDelta.y
                                        if (delta == 0f) continue

                                        val now = System.currentTimeMillis()
                                        val timeDiff = now - lastScrollTime
                                        lastScrollTime = now

                                        // If scrolling rapidly (less than 150ms between events), increase skip multiplier
                                        acceleration = if (timeDiff < 150) {
                                            (acceleration + 1.5f).coerceAtMost(25f)
                                        } else {
                                            1f
                                        }

                                        // Calculate how many pages to skip based on delta and acceleration
                                        val skip = (delta * acceleration).toInt().let {
                                            if (it == 0) (if (delta > 0) 1 else -1) else it
                                        }

                                        val requestedPage = pagerState.currentPage + skip
                                        val nextPage = requestedPage.coerceIn(0, uiState.lastPageIndex)

                                        if (requestedPage < 0) {
                                            showEdgeDialog(SourceDirection.Previous)
                                        } else if (requestedPage > uiState.lastPageIndex) {
                                            showEdgeDialog(SourceDirection.Next)
                                        } else if (nextPage != pagerState.currentPage) {
                                            coroutineScope.launch {
                                                // Use instant scroll for high speed to keep UI responsive
                                                if (kotlin.math.abs(skip) > 1) {
                                                    pagerState.scrollToPage(nextPage)
                                                } else {
                                                    pagerState.animateScrollToPage(nextPage)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        },
                ) { page ->
                    val item = uiState.itemForPage(page)
                    when {
                        item != null -> MediaPage(
                            item = item,
                            // Fill only applies in full screen; with the slider showing, show the whole image.
                            fillFrame = uiState.fillFrame && uiState.isChromeHidden,
                            rotation = uiState.rotation,
                            flipped = uiState.flipped,
                            zoomCommand = zoomCommand,
                            loadVideoFrame = uiState.loadVideoThumbnails,
                            onScreen = page == pagerState.currentPage,
                            mayLoad = page in loadablePages,
                            onLoadStarted = { startedMedia[item.readinessKey] = true },
                            onSettled = { settledMedia[item.readinessKey] = true },
                            onZoomChanged = { zoomed ->
                                if (zoomed) {
                                    zoomedMedia[item.readinessKey] = true
                                } else {
                                    zoomedMedia.remove(item.readinessKey)
                                }
                            },
                            onContentClick = onChromeToggle,
                            onBookmark = { bookmarkOnDoubleTap(item.path) },
                        )
                        else -> EmptyViewerMessage()
                    }
                }

                if (uiState.isChromeHidden && uiState.pageCount > 0 && !isCurrentMediaZoomed) {
                    HiddenNavigationButtons(
                        currentPage = pagerState.currentPage,
                        pageCount = uiState.pageCount,
                        onPreviousClick = {
                            navigateFromCurrentPage(-1, animated = false)
                        },
                        onNextClick = {
                            navigateFromCurrentPage(1, animated = false)
                        },
                        onPreviousLongPressStart = { startPseudoSlideShow(pageOffset = -1) },
                        onNextLongPressStart = { startPseudoSlideShow(pageOffset = 1) },
                        onLongPressEnd = { stopPseudoSlideShow() },
                    )
                }

                if (isPseudoSlideShowRunning) {
                    PseudoSlideShowVisualizer(
                        stayMillis = pseudoSlideShowStayMillis,
                        horizontalFraction = pseudoSlideShowHorizontalFraction,
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(0.85f)
                            .align(Alignment.TopCenter)
                            .padding(bottom = 16.dp, start = 24.dp, end = 24.dp),
                    )
                }

                if (uiState.isSlideShowRunning) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable(onClick = onSlideShowStop),
                    )
                }

                BookmarkFlash(
                    flashId = bookmarkFlashId,
                    modifier = Modifier.align(Alignment.Center),
                )

                if (uiState.isSorting && !uiState.isLoading) {
                    SortingIndicator(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 12.dp),
                    )
                }
            }

            if (!uiState.isChromeHidden) {
                ViewerTimeline(
                    currentPage = pagerState.currentPage,
                    pageCount = uiState.pageCount,
                    backPage = uiState.jumpHistory.backTarget(pagerState.currentPage),
                    forwardPage = uiState.jumpHistory.forwardTarget(pagerState.currentPage),
                    onPageSelected = { page ->
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(page)
                        }
                    },
                    onSeekFinished = onJumpRecorded,
                    onJumpBack = { onJumpBack(pagerState.currentPage) },
                    onJumpForward = { onJumpForward(pagerState.currentPage) },
                )
            }
        }
    }

    if (slideShowDialogVisible) {
        SlideShowDialog(
            stayMillis = slideShowStayText,
            direction = slideShowDirection,
            onStayMillisChange = { slideShowStayText = it.filter(Char::isDigit) },
            onDirectionChange = { slideShowDirection = it },
            onDismiss = { slideShowDialogVisible = false },
            onStart = {
                onSlideShowStart(slideShowStayText.toLongOrNull() ?: 100L, slideShowDirection)
                slideShowDialogVisible = false
            },
        )
    }
    if (detailsDialogVisible && selectedItem != null) {
        MediaDetailsDialog(item = selectedItem, onDismiss = { detailsDialogVisible = false })
    }
    if (sortDialogVisible) {
        SortDialog(
            currentOrder = uiState.sortOrder,
            onOrderSelect = {
                onSortOrderChange(it)
                sortDialogVisible = false
            },
            onDismiss = { sortDialogVisible = false },
        )
    }
    edgeDialogDirection?.let { direction ->
        SourceEdgeDialog(
            direction = direction,
            source = when (direction) {
                SourceDirection.Previous -> uiState.previousSource
                SourceDirection.Next -> uiState.nextSource
            },
            isSearching = when (direction) {
                SourceDirection.Previous -> !uiState.isPreviousSourceKnown
                SourceDirection.Next -> !uiState.isNextSourceKnown
            },
            isArchiveSource = selectedItem?.path?.let(MediaPaths::archiveEntryName) != null,
            style = uiState.edgeDialogStyle,
            onDismiss = { edgeDialogDirection = null },
            onMove = {
                edgeDialogDirection = null
                when (direction) {
                    SourceDirection.Previous -> onPreviousSourceClick()
                    SourceDirection.Next -> onNextSourceClick()
                }
            },
        )
    }
}

@Composable
private fun PseudoSlideShowVisualizer(
    stayMillis: Long,
    horizontalFraction: Float,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .align(Alignment.BottomCenter)
                .background(Color.Black.copy(alpha = 0.27f), RoundedCornerShape(20.dp))
                .padding(horizontal = 18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.alpha(if (horizontalFraction < 0f) 1f else 0.55f),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = Color.White)
                Text(stringResource(R.string.slower), color = Color.White)
            }
            Text(
                text = stringResource(R.string.slideshow_interval, stayMillis),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
            )
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.alpha(if (horizontalFraction > 0f) 1f else 0.55f),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = Color.White)
                Text(stringResource(R.string.faster), color = Color.White)
            }
        }
    }
}

@Composable
private fun ViewerOptionsRow(
    currentPage: Int,
    pageCount: Int,
    fillFrame: Boolean,
    flipped: Boolean,
    onMoveFirstClick: () -> Unit,
    onFitClick: () -> Unit,
    onRotateClick: () -> Unit,
    onFlipClick: () -> Unit,
    onSortClick: () -> Unit,
    onSlideShowClick: () -> Unit,
    onMoveLastClick: () -> Unit,
) {
    val canMoveFirst = pageCount > 0 && currentPage != 0
    val canMoveLast = pageCount > 0 && currentPage != pageCount - 1

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(Color(0xFF303030)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        IconButton(
            onClick = onFitClick,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                Icons.Default.FitScreen,
                contentDescription = stringResource(if (fillFrame) R.string.fit_screen else R.string.fill_screen),
                tint = if (fillFrame) Color(0xFF64B5F6) else Color.White,
            )
        }
        IconButton(
            onClick = onRotateClick,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(Icons.Default.Rotate90DegreesCcw, contentDescription = stringResource(R.string.rotate), tint = Color.White)
        }
        IconButton(
            onClick = onFlipClick,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                Icons.Default.SwapHoriz,
                contentDescription = stringResource(R.string.flip_horizontal),
                tint = if (flipped) Color(0xFF64B5F6) else Color.White,
            )
        }
        IconButton(
            onClick = onSortClick,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = stringResource(R.string.sort_order), tint = Color.White)
        }
        IconButton(
            onClick = onSlideShowClick,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(Icons.Default.Slideshow, contentDescription = stringResource(R.string.start_slideshow), tint = Color.White)
        }
        IconButton(
            onClick = onMoveFirstClick,
            enabled = canMoveFirst,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                Icons.Default.FirstPage,
                contentDescription = stringResource(R.string.move_first_item),
                tint = Color.White.copy(alpha = if (canMoveFirst) 1f else 0.38f),
            )
        }
        IconButton(
            onClick = onMoveLastClick,
            enabled = canMoveLast,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.LastPage,
                contentDescription = stringResource(R.string.move_last_item),
                tint = Color.White.copy(alpha = if (canMoveLast) 1f else 0.38f),
            )
        }
    }
}

@Composable
private fun SlideShowDialog(
    stayMillis: String,
    direction: SlideShowDirection,
    onStayMillisChange: (String) -> Unit,
    onDirectionChange: (SlideShowDirection) -> Unit,
    onDismiss: () -> Unit,
    onStart: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.slideshow)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = stayMillis,
                    onValueChange = onStayMillisChange,
                    label = { Text(stringResource(R.string.per_image_stay)) },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                    ),
                )
                Column {
                    Text(
                        text = stringResource(R.string.slideshow_direction),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    SlideShowDirection.entries.forEach { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onDirectionChange(option) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            androidx.compose.material3.RadioButton(
                                selected = option == direction,
                                onClick = { onDirectionChange(option) },
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = when (option) {
                                    SlideShowDirection.Next -> stringResource(R.string.next_media)
                                    SlideShowDirection.Previous -> stringResource(R.string.previous_media)
                                },
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onStart) {
                Text(stringResource(R.string.start))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun MediaPage(
    item: MediaViewerItem,
    fillFrame: Boolean,
    rotation: Int,
    flipped: Boolean,
    zoomCommand: ZoomCommand?,
    loadVideoFrame: Boolean,
    onScreen: Boolean,
    mayLoad: Boolean,
    onLoadStarted: () -> Unit,
    onSettled: () -> Unit,
    onZoomChanged: (Boolean) -> Unit,
    onContentClick: () -> Unit,
    onBookmark: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // Read inside the loads below, which wait for mayLoad; once started, a load runs to the end,
    // with the priority it started with.
    val latestMayLoad by rememberUpdatedState(mayLoad)
    val latestOnScreen by rememberUpdatedState(onScreen)
    val latestOnLoadStarted by rememberUpdatedState(onLoadStarted)
    if (item.type == MediaViewerType.Video) {
        // Pages within the preload distance are composed early, so server video frames preload like images.
        val readsFrame = loadVideoFrame || !MediaPaths.isRemote(item.path)
        val preview by produceState<PreviewResult?>(
            initialValue = null,
            key1 = item.path,
            key2 = item.contentStamp,
            key3 = readsFrame,
        ) {
            snapshotFlow { latestMayLoad }.first { it }
            latestOnLoadStarted()
            value = if (readsFrame) {
                MediaPreviewLoader.loadVideoFrame(item.path, item.contentStamp, 1600, onScreen = latestOnScreen)
            } else {
                PreviewResult()
            }
        }

        LaunchedEffect(preview, item.path, item.contentStamp) {
            if (preview != null) onSettled()
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(item.path, item.contentStamp) {
                    detectTapGestures(
                        onTap = { onContentClick() },
                        onDoubleTap = { onBookmark() },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            preview?.bitmap?.let { bitmap ->
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = item.name,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(rotationZ = rotation.toFloat())
                        .scale(scaleX = if (flipped) -1f else 1f, scaleY = 1f),
                    contentScale = if (fillFrame) ContentScale.Crop else ContentScale.Fit,
                )
            }
            if (preview != null && preview?.bitmap == null) {
                Icon(
                    Icons.Default.ImageNotSupported,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.7f),
                )
                preview?.error?.let {
                    Text(it, color = Color.White, modifier = Modifier.padding(top = 64.dp, start = 24.dp, end = 24.dp))
                }
            }
            IconButton(
                onClick = {
                    context.startActivity(
                        Intent(context, VideoPlayerActivity::class.java).apply {
                            putExtra(VideoPlayerActivity.EXTRA_DIRECTORY_PATH, MediaPaths.parent(item.path))
                            putExtra(VideoPlayerActivity.EXTRA_VIDEO_PATH, item.path)
                        },
                    )
                },
                modifier = Modifier.size(72.dp),
            ) {
                Icon(
                    Icons.Default.PlayCircle,
                    contentDescription = stringResource(R.string.play_video),
                    tint = Color.White,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        return
    }
    val preview by produceState<PreviewResult?>(initialValue = null, key1 = item.path, key2 = item.contentStamp) {
        snapshotFlow { latestMayLoad }.first { it }
        latestOnLoadStarted()
        value = MediaPreviewLoader.loadImage(item.path, item.contentStamp, 1600, onScreen = latestOnScreen)
    }

    LaunchedEffect(preview, item.path, item.contentStamp) {
        if (preview != null) onSettled()
    }

    var imageScale by remember(item.path, item.contentStamp) { mutableFloatStateOf(MIN_IMAGE_ZOOM) }
    var imageOffsetX by remember(item.path, item.contentStamp) { mutableFloatStateOf(0f) }
    var imageOffsetY by remember(item.path, item.contentStamp) { mutableFloatStateOf(0f) }
    var handledZoomCommandId by remember(item.readinessKey) { mutableStateOf(zoomCommand?.id ?: 0) }
    val isZoomed = imageScale > MIN_IMAGE_ZOOM

    fun resetImageZoom() {
        imageScale = MIN_IMAGE_ZOOM
        imageOffsetX = 0f
        imageOffsetY = 0f
    }

    fun setCenteredImageZoom(targetScale: Float) {
        imageScale = targetScale.coerceIn(MIN_IMAGE_ZOOM, MAX_IMAGE_ZOOM)
        imageOffsetX = 0f
        imageOffsetY = 0f
    }

    LaunchedEffect(item.path, item.contentStamp, fillFrame, rotation, flipped) {
        resetImageZoom()
    }

    LaunchedEffect(item.readinessKey, isZoomed) {
        onZoomChanged(isZoomed)
    }

    LaunchedEffect(zoomCommand, item.readinessKey) {
        val command = zoomCommand
        if (
            command != null &&
            command.mediaKey == item.readinessKey &&
            command.id != handledZoomCommandId
        ) {
            handledZoomCommandId = command.id
            if (isZoomed) {
                resetImageZoom()
            } else {
                setCenteredImageZoom(DOUBLE_TAP_IMAGE_ZOOM)
            }
        }
    }

    DisposableEffect(item.readinessKey) {
        onDispose {
            onZoomChanged(false)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(item.path, item.contentStamp) {
                detectTapGestures(
                    onTap = { onContentClick() },
                    // Double tap is the instant "like"; zooming stays on pinch and the options menu.
                    onDoubleTap = { onBookmark() },
                )
            }
            .pointerInput(item.path, item.contentStamp) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pressedCount = event.changes.count { it.pressed }
                        if (pressedCount == 0) break

                        val zoomChange = if (pressedCount > 1) event.calculateZoom() else MIN_IMAGE_ZOOM
                        val panChange = if (imageScale > MIN_IMAGE_ZOOM || pressedCount > 1) {
                            event.calculatePan()
                        } else {
                            Offset.Zero
                        }
                        val shouldHandle = imageScale > MIN_IMAGE_ZOOM || pressedCount > 1
                        if (shouldHandle) {
                            val previousScale = imageScale
                            val nextScale = (imageScale * zoomChange).coerceIn(MIN_IMAGE_ZOOM, MAX_IMAGE_ZOOM)
                            val nextOffset = if (nextScale <= MIN_IMAGE_ZOOM) {
                                Offset.Zero
                            } else {
                                val centroid = event.calculateCentroid(useCurrent = true)
                                val center = Offset(size.width / 2f, size.height / 2f)
                                val scaleChange = nextScale / previousScale
                                ((Offset(imageOffsetX, imageOffsetY) + centroid - center) * scaleChange) -
                                    (centroid - center) + panChange
                            }
                            val constrainedOffset = constrainImageOffset(
                                offset = nextOffset,
                                width = size.width.toFloat(),
                                height = size.height.toFloat(),
                                scale = nextScale,
                            )
                            imageScale = nextScale
                            imageOffsetX = constrainedOffset.x
                            imageOffsetY = constrainedOffset.y
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val previewResult = preview
        if (previewResult == null) {
            CircularProgressIndicator(color = Color.White)
        } else if (previewResult.bitmap == null) {
            Icon(
                Icons.Default.ImageNotSupported,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.7f),
            )
            previewResult.error?.let {
                Text(it, color = Color.White, modifier = Modifier.padding(top = 64.dp, start = 24.dp, end = 24.dp))
            }
        } else {
            Image(
                bitmap = previewResult.bitmap!!.asImageBitmap(),
                contentDescription = item.name,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        rotationZ = rotation.toFloat(),
                        scaleX = imageScale * if (flipped) -1f else 1f,
                        scaleY = imageScale,
                        translationX = imageOffsetX,
                        translationY = imageOffsetY,
                        clip = true,
                    ),
                contentScale = if (fillFrame) ContentScale.Crop else ContentScale.Fit,
            )
        }
    }
}

private val MediaViewerItem.readinessKey: String
    get() = "$path:$contentStamp"

/**
 * Version of the file a page loads and caches its picture by. A server file's time only arrives
 * once a sort asks for it, which would reload a page already showing, so server files use none.
 */
private val MediaViewerItem.contentStamp: Long
    get() = if (MediaPaths.isRemote(path)) 0L else lastModifiedMillis

/**
 * Whether loading this page downloads from a server. Entries of a server archive are read from its
 * downloaded copy, and server video pages without frames load nothing.
 */
private fun MediaViewerItem.downloadsFromServer(loadVideoFrames: Boolean): Boolean =
    MediaPaths.isRemote(path) &&
        MediaPaths.archiveEntryName(path) == null &&
        (type == MediaViewerType.Image || loadVideoFrames)

// Server downloads running at once beyond the page on screen, and how far a slow server preloads.
private const val SERVER_PRELOAD_PARALLEL = 3
private const val SLOW_SERVER_PRELOAD_DISTANCE = 2

private fun adjustedPseudoStayMillis(baseStayMillis: Long, horizontalFraction: Float): Long {
    val speedMultiplier = 4.0.pow(horizontalFraction.toDouble())
    return (baseStayMillis / speedMultiplier)
        .toLong()
        .coerceIn(MIN_PSEUDO_STAY_MILLIS, MAX_PSEUDO_STAY_MILLIS)
}

private const val MIN_PSEUDO_STAY_MILLIS = 1L
private const val MAX_PSEUDO_STAY_MILLIS = 3_600_000L
private const val HIDDEN_NAVIGATION_LONG_PRESS_MILLIS = 500L
private const val MIN_IMAGE_ZOOM = 1f
private const val DOUBLE_TAP_IMAGE_ZOOM = 2.5f
private const val MAX_IMAGE_ZOOM = 5f

private data class ZoomCommand(
    val mediaKey: String,
    val id: Int,
)

private fun constrainImageOffset(
    offset: Offset,
    width: Float,
    height: Float,
    scale: Float,
): Offset {
    if (scale <= MIN_IMAGE_ZOOM) return Offset.Zero
    val maxX = width * (scale - MIN_IMAGE_ZOOM) / 2f
    val maxY = height * (scale - MIN_IMAGE_ZOOM) / 2f
    return Offset(
        x = offset.x.coerceIn(-maxX, maxX),
        y = offset.y.coerceIn(-maxY, maxY),
    )
}

@Composable
private fun MediaDetailsDialog(item: MediaViewerItem, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.media_details)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(item.name)
                Text(item.path, style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.media_type, item.type.name))
                Text(stringResource(R.string.media_size, formatMediaBytes(item.sizeBytes)))
                Text(
                    stringResource(
                        R.string.media_modified,
                        // HTTP servers only report times when a sort order asked for them.
                        if (item.lastModifiedMillis > 0L) {
                            java.text.DateFormat.getDateTimeInstance().format(java.util.Date(item.lastModifiedMillis))
                        } else {
                            stringResource(R.string.unknown)
                        },
                    ),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

private fun formatMediaBytes(bytes: Long): String {
    if (bytes < 0) return "Unknown"
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) { value /= 1024; unit++ }
    return "%.1f %s".format(value, units[unit])
}

/** Shown over the pages while the list waits for the file details its sort order compares. */
@Composable
private fun SortingIndicator(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = Color.White)
        Text(
            text = stringResource(R.string.sorting_media),
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

/** Spinner while media loads; while a server archive downloads, how much of it has arrived. */
@Composable
private fun MediaLoadingIndicator(downloadProgress: DownloadProgress?, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val fraction = downloadProgress?.fraction
        if (fraction != null) {
            CircularProgressIndicator(progress = { fraction })
        } else {
            CircularProgressIndicator()
        }
        if (downloadProgress != null) {
            Text(
                text = if (downloadProgress.totalBytes > 0L) {
                    stringResource(
                        R.string.downloading_archive_progress,
                        formatMediaBytes(downloadProgress.downloadedBytes),
                        formatMediaBytes(downloadProgress.totalBytes),
                    )
                } else {
                    stringResource(R.string.downloading_archive, formatMediaBytes(downloadProgress.downloadedBytes))
                },
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/**
 * What lies past the first or last item. While [isSearching], the neighbouring source is still
 * being looked for: the dialog says so with Move disabled, and fills in once the answer arrives.
 */
@Composable
private fun SourceEdgeDialog(
    direction: SourceDirection,
    source: MediaViewerSource?,
    isSearching: Boolean,
    isArchiveSource: Boolean,
    style: MediaEdgeDialogStyle,
    onDismiss: () -> Unit,
    onMove: () -> Unit,
) {
    // Archives lead to archives and folders to folders, so the kind is known before the answer.
    val isArchive = source?.isArchive ?: isArchiveSource
    val offersMove = isSearching || source != null
    val title = if (offersMove) {
        stringResource(direction.titleStringRes(isArchive))
    } else {
        stringResource(direction.edgeReachedTitleStringRes())
    }
    val message = when {
        isSearching -> stringResource(R.string.finding_source)
        source != null -> source.name
        else -> stringResource(direction.edgeReachedMessageStringRes())
    }
    val detail = source?.path
    val moveLabel = if (offersMove) stringResource(direction.moveStringRes(isArchive)) else null

    if (style == MediaEdgeDialogStyle.Bottom) {
        BottomSourceEdgeDialog(
            title = title,
            message = message,
            detail = null,
            moveLabel = moveLabel,
            isSearching = isSearching,
            onDismiss = onDismiss,
            onMove = onMove,
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (isSearching) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(message)
                }
            } else if (source != null) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(message)
                    Text(detail.orEmpty(), style = MaterialTheme.typography.bodySmall)
                }
            } else {
                Text(message)
            }
        },
        confirmButton = {
            if (offersMove) {
                Button(onClick = onMove, enabled = !isSearching) {
                    Text(moveLabel.orEmpty())
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.close))
                }
            }
        },
        dismissButton = {
            if (offersMove) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        },
    )
}

@Composable
private fun BottomSourceEdgeDialog(
    title: String,
    message: String,
    detail: String?,
    moveLabel: String?,
    isSearching: Boolean,
    onDismiss: () -> Unit,
    onMove: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                tonalElevation = 6.dp,
                color = MaterialTheme.colorScheme.surface,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, top = 10.dp, end = 8.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isSearching) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(1.dp),
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        detail?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.MiddleEllipsis,
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(if (moveLabel == null) R.string.close else R.string.cancel))
                    }
                    moveLabel?.let {
                        Button(onClick = onMove, enabled = !isSearching) {
                            Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

private enum class SourceDirection {
    Previous,
    Next,
}

private fun SourceDirection.titleStringRes(isArchive: Boolean): Int = when (this) {
    SourceDirection.Previous -> if (isArchive) R.string.previous_archive else R.string.previous_directory
    SourceDirection.Next -> if (isArchive) R.string.next_archive else R.string.next_directory
}

private fun SourceDirection.moveStringRes(isArchive: Boolean): Int = when (this) {
    SourceDirection.Previous -> if (isArchive) R.string.move_previous_archive else R.string.move_previous_directory
    SourceDirection.Next -> if (isArchive) R.string.move_next_archive else R.string.move_next_directory
}

private fun SourceDirection.edgeReachedTitleStringRes(): Int = when (this) {
    SourceDirection.Previous -> R.string.reached_first_media
    SourceDirection.Next -> R.string.reached_last_media
}

private fun SourceDirection.edgeReachedMessageStringRes(): Int = when (this) {
    SourceDirection.Previous -> R.string.no_previous_source
    SourceDirection.Next -> R.string.no_next_source
}

@Composable
private fun SortDialog(
    currentOrder: DirectorySortOrder,
    onOrderSelect: (DirectorySortOrder) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sort_order)) },
        text = {
            Column {
                DirectorySortOrder.entries.forEach { order ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOrderSelect(order) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = order == currentOrder,
                            onClick = { onOrderSelect(order) }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = when (order) {
                                DirectorySortOrder.NameAscending -> stringResource(R.string.sort_name_ascending)
                                DirectorySortOrder.NameDescending -> stringResource(R.string.sort_name_descending)
                                DirectorySortOrder.NewestFirst -> stringResource(R.string.sort_newest)
                                DirectorySortOrder.OldestFirst -> stringResource(R.string.sort_oldest)
                                DirectorySortOrder.LargestFirst -> stringResource(R.string.sort_largest)
                                DirectorySortOrder.SmallestFirst -> stringResource(R.string.sort_smallest)
                            },
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        }
    )
}

@Composable
private fun BoxScope.HiddenNavigationButtons(
    currentPage: Int,
    pageCount: Int,
    onPreviousClick: () -> Unit,
    onNextClick: () -> Unit,
    onPreviousLongPressStart: () -> Unit,
    onNextLongPressStart: () -> Unit,
    onLongPressEnd: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.15f)
            .align(Alignment.BottomCenter)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HiddenNavigationButton(
                text = "-",
                contentDescription = stringResource(R.string.previous_media),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                onClick = onPreviousClick,
                onLongPressStart = onPreviousLongPressStart,
                onLongPressEnd = onLongPressEnd,
            )

            HiddenNavigationButton(
                text = "+",
                contentDescription = stringResource(R.string.next_media),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                onClick = onNextClick,
                onLongPressStart = onNextLongPressStart,
                onLongPressEnd = onLongPressEnd,
            )
        }

        //todo add fetched date here.

        Text(
            text = "%d/%d".format(currentPage + 1, pageCount),
            color = Color.White.copy(alpha = 0.5f),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp)
        )
    }
}

@Composable
private fun HiddenNavigationButton(
    text: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongPressStart: () -> Unit,
    onLongPressEnd: () -> Unit,
) {
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnLongPressStart by rememberUpdatedState(onLongPressStart)
    val currentOnLongPressEnd by rememberUpdatedState(onLongPressEnd)
    Box(
        modifier = modifier
            .alpha(0.11f)
            .background(MaterialTheme.colorScheme.surface)
            .pointerInput(contentDescription) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val releasedBeforeLongPress = withTimeoutOrNull(HIDDEN_NAVIGATION_LONG_PRESS_MILLIS) {
                        var released = false
                        while (!released) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                                ?: return@withTimeoutOrNull true
                            change.consume()
                            released = !change.pressed
                        }
                        true
                    } == true

                    if (releasedBeforeLongPress) {
                        currentOnClick()
                        return@awaitEachGesture
                    }

                    currentOnLongPressStart()
                    try {
                        do {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                                ?: break
                            change.consume()
                        } while (change.pressed)
                    } finally {
                        currentOnLongPressEnd()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            style = MaterialTheme.typography.headlineLarge,
        )
    }
}

@Composable
private fun EmptyViewerMessage() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(stringResource(R.string.no_media_selected), color = Color.White.copy(alpha = 0.7f))
    }
}

@Composable
private fun ViewerTimeline(
    currentPage: Int,
    pageCount: Int,
    backPage: Int?,
    forwardPage: Int?,
    onPageSelected: (Int) -> Unit,
    onSeekFinished: (fromPage: Int, toPage: Int) -> Unit,
    onJumpBack: () -> Unit,
    onJumpForward: () -> Unit,
) {
    // The page a drag or a tap on the track started from; one gesture is recorded as one jump.
    var seekStartPage by remember { mutableStateOf<Int?>(null) }
    var seekPage by remember { mutableIntStateOf(currentPage) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF303030))
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (pageCount == 0) "00 /00" else "%02d /%02d".format(currentPage + 1, pageCount),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Both buttons always take their space, so the slider never resizes under a finger.
            JumpHistoryButton(
                icon = Icons.AutoMirrored.Filled.Undo,
                contentDescription = backPage
                    ?.let { stringResource(R.string.jump_back_to, it + 1) }
                    ?: stringResource(R.string.jump_back),
                enabled = backPage != null,
                onClick = onJumpBack,
            )
            Slider(
                value = currentPage.toFloat(),
                onValueChange = { value ->
                    if (seekStartPage == null) seekStartPage = currentPage
                    seekPage = value.toInt()
                    onPageSelected(seekPage)
                },
                onValueChangeFinished = {
                    seekStartPage?.let { onSeekFinished(it, seekPage) }
                    seekStartPage = null
                },
                valueRange = 0f..(pageCount - 1).coerceAtLeast(0).toFloat(),
                steps = (pageCount - 2).coerceAtLeast(0),
                enabled = pageCount > 1,
                modifier = Modifier
                    .weight(1f)
                    .height(28.dp)
                    .padding(horizontal = 4.dp),
            )
            JumpHistoryButton(
                icon = Icons.AutoMirrored.Filled.Redo,
                contentDescription = forwardPage
                    ?.let { stringResource(R.string.jump_forward_to, it + 1) }
                    ?: stringResource(R.string.jump_forward),
                enabled = forwardPage != null,
                onClick = onJumpForward,
            )
        }
    }
}

@Composable
private fun JumpHistoryButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(32.dp),
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = Color.White.copy(alpha = if (enabled) 1f else 0.38f),
        )
    }
}

//@Preview(showBackground = true)
//@Composable
//private fun HiddenNavigationButtonPreview() {
//    SCRTVWRTheme {
//        HiddenNavigationButton(
//            text = "+",
//            contentDescription = "Previous media",
//            modifier = Modifier.width(64.dp),
//            onClick = {}
//        )
//    }
//}

@Preview(showBackground = true)
@Composable
private fun MediaViewerScreenPreview() {
    TimeUtil.initialize(LocalContext.current)
    val sampleItem = MediaViewerItem(
        name = "image.jpg",
        path = "/storage/emulated/0/Pictures/image.jpg",
        type = MediaViewerType.Image,
        lastModifiedMillis = System.currentTimeMillis(),
        sizeBytes = 1024 * 1024
    )
    val sampleUiState = MediaViewerUiState(
        sourceDirectoryPath = "/storage/emulated/0/Pictures",
        selectedPath = "/storage/emulated/0/Pictures/image.jpg",
        items = listOf(sampleItem),
        previousSource = null,
        nextSource = null,
        initialPage = 0,
        selectedPage = 0,
        fillFrame = false,
        rotation = 0,
        flipped = false,
        bookmarked = false,
        isChromeHidden = false,
        isSlideShowRunning = false,
        slideShowStayMillis = 100L,
        slideShowDirection = SlideShowDirection.Next,
        holdSlideShowEnabled = true,
        imagePreloadDistance = 1,
        edgeDialogStyle = MediaEdgeDialogStyle.Bottom,
        isLoading = false,
        errorMessage = null
    )
    SCRTVWRTheme {
        MediaViewerScreen(
            uiState = sampleUiState,
            onPageChanged = {},
            onFitClick = {},
            onRotateClick = {},
            onFlipClick = {},
            onBookmarkClick = {},
            onBookmarkAdd = {},
            onChromeToggle = {},
            onFindSources = {},
            onPreviousSourceClick = {},
            onNextSourceClick = {},
            onSlideShowStart = { _, _ -> },
            onSlideShowStop = {},
            onSlideShowSkipsVideosToggle = {},
            onHoldSlideShowSkipsVideosToggle = {},
            onSortOrderChange = {},
            onJumpRecorded = { _, _ -> },
            onJumpBack = {},
            onJumpForward = {},
            onBack = {}
        )
    }
}
