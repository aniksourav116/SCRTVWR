package srv.scrtvwr

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.automirrored.filled.FormatIndentDecrease
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.ImageNotSupported
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Rotate90DegreesCcw
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import srv.scrtvwr.media.MediaPreviewLoader
import srv.scrtvwr.media.PreviewResult
import srv.scrtvwr.ui.media.MediaViewerItem
import srv.scrtvwr.ui.media.MediaViewerType
import srv.scrtvwr.ui.media.MediaViewerUiState
import srv.scrtvwr.ui.media.MediaViewerViewModel
import srv.scrtvwr.ui.theme.SCRTVWRTheme
import srv.scrtvwr.util.time.TimeUtil
import timber.log.Timber
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
        viewModel.load(directoryPath, selectedPath)
    }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(uiState.sourceDirectoryPath) {
        uiState.sourceDirectoryPath?.let(onDirectoryChanged)
    }
    MediaViewerScreen(
        uiState = uiState,
        onPageChanged = viewModel::selectPage,
        onFitClick = viewModel::toggleFillFrame,
        onRotateClick = viewModel::rotateClockwise,
        onFlipClick = viewModel::toggleFlip,
        onBookmarkClick = viewModel::toggleBookmark,
        onChromeToggle = viewModel::toggleChrome,
        onNextSourceClick = viewModel::loadNextSource,
        onSlideShowStart = viewModel::startSlideShow,
        onSlideShowStop = viewModel::stopSlideShow,
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
    onChromeToggle: () -> Unit,
    onNextSourceClick: () -> Unit,
    onSlideShowStart: (Long) -> Unit,
    onSlideShowStop: () -> Unit,
    onBack: () -> Unit,
) {
    val pagerState = rememberPagerState(
        initialPage = uiState.selectedPage,
        pageCount = { uiState.pageCount.coerceAtLeast(1) },
    )
    val coroutineScope = rememberCoroutineScope()
    var menuExpanded by rememberSaveable { mutableStateOf(false) }
    var slideShowDialogVisible by rememberSaveable { mutableStateOf(false) }
    var detailsDialogVisible by rememberSaveable { mutableStateOf(false) }
    var slideShowStayText by rememberSaveable { mutableStateOf(uiState.slideShowStayMillis.toString()) }
    var verticalDrag by remember { mutableFloatStateOf(0f) }
    var isPseudoSlideShowRunning by remember { mutableStateOf(false) }
    var pseudoSlideShowStayMillis by remember { mutableStateOf(uiState.slideShowStayMillis) }
    var pseudoSlideShowHorizontalFraction by remember { mutableFloatStateOf(0f) }
    val readyMedia = remember(uiState.items) { mutableStateMapOf<String, Boolean>() }
    val isAnySlideShowRunning = uiState.isSlideShowRunning || isPseudoSlideShowRunning
    val activeSlideShowStayMillis = if (isPseudoSlideShowRunning) {
        pseudoSlideShowStayMillis
    } else {
        uiState.slideShowStayMillis
    }

    LaunchedEffect(slideShowDialogVisible, uiState.slideShowStayMillis) {
        if (slideShowDialogVisible) {
            slideShowStayText = uiState.slideShowStayMillis.toString()
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
        isPseudoSlideShowRunning,
        uiState.items,
        pagerState.currentPage,
    ) {
        if (isAnySlideShowRunning) {
            val currentPage = pagerState.currentPage
            val currentItem = uiState.items.getOrNull(currentPage)
            if (currentItem == null) {
                if (!isPseudoSlideShowRunning) onSlideShowStop()
                return@LaunchedEffect
            }

            snapshotFlow { readyMedia[currentItem.readinessKey] == true }
                .first { it }
            // Give the loaded bitmap a render frame before even a very short stay begins.
            withFrameNanos { }
            delay(activeSlideShowStayMillis.milliseconds)

            if (pagerState.currentPage == currentPage) {
                val nextPage = currentPage + 1
                if (nextPage >= uiState.items.size) {
                    if (!isPseudoSlideShowRunning) onSlideShowStop()
                } else {
                    pagerState.scrollToPage(nextPage)
                }
            }
        }
    }

    val selectedItem = uiState.items.getOrNull(pagerState.currentPage)
    Timber.d("name ${selectedItem?.name}")
    val splits = selectedItem?.name?.split("_") ?: emptyList()
    Timber.d("splits $splits")

    val selectedName =  selectedItem?.name?: stringResource(R.string.no_media_selected)
    val time = if (splits.isNotEmpty() && splits.size>=4) {
        TimeUtil.getDateAndTimeWithWeekDay(splits[(splits.size - 3).coerceAtLeast(0)].toLongOrNull())
    } else {
        TimeUtil.getDateAndTimeWithWeekDay()
    }


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
                        IconButton(onClick = onFitClick) {
                            Icon(Icons.Default.OpenInFull, contentDescription = stringResource(R.string.toggle_full_view))
                        }
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
                                    onClick = {
                                        onBookmarkClick()
                                        menuExpanded = false
                                    },
                                )
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
                    fillFrame = uiState.fillFrame,
                    flipped = uiState.flipped,
                    bookmarked = uiState.bookmarked,
                    onFitClick = onFitClick,
                    onRotateClick = onRotateClick,
                    onFlipClick = onFlipClick,
                    onBookmarkClick = onBookmarkClick,
                    onDetailsClick = { detailsDialogVisible = true },
                    onSlideShowClick = { slideShowDialogVisible = true },
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
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
                    beyondViewportPageCount = minOf(uiState.imagePreloadDistance, uiState.lastPageIndex),
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(uiState.pageCount) {
                            detectVerticalDragGestures(
                                onDragStart = { verticalDrag = 0f },
                                onVerticalDrag = { _, dragAmount -> verticalDrag += dragAmount },
                                onDragEnd = {
                                    val nextPage = when {
                                        verticalDrag < -80f -> pagerState.currentPage + 1
                                        verticalDrag > 80f -> pagerState.currentPage - 1
                                        else -> pagerState.currentPage
                                    }.coerceIn(0, uiState.lastPageIndex)

                                    if (nextPage != pagerState.currentPage) {
                                        coroutineScope.launch {
                                            pagerState.animateScrollToPage(nextPage)
                                        }
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
                        ) {
                            if (
                                !uiState.holdSlideShowEnabled ||
                                !uiState.isChromeHidden ||
                                uiState.isSlideShowRunning
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
                                pseudoSlideShowStayMillis = baseStayMillis
                                pseudoSlideShowHorizontalFraction = 0f
                                isPseudoSlideShowRunning = true

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
                                    isPseudoSlideShowRunning = false
                                    pseudoSlideShowHorizontalFraction = 0f
                                }
                            }
                        }
                        .pointerInput(uiState.pageCount) {
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

                                        val nextPage = (pagerState.currentPage + skip).coerceIn(
                                            0,
                                            uiState.lastPageIndex
                                        )

                                        if (nextPage != pagerState.currentPage) {
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
                    uiState.items.getOrNull(page)?.let { item ->
                        MediaPage(
                            item = item,
                            fillFrame = uiState.fillFrame,
                            rotation = uiState.rotation,
                            flipped = uiState.flipped,
                            onReady = { readyMedia[item.readinessKey] = true },
                            onContentClick = onChromeToggle,
                        )
                    } ?: if (page == uiState.nextSourcePageIndex) {
                        NextSourcePage(
                            sourceName = uiState.nextSource?.name.orEmpty(),
                            isArchive = uiState.nextSource?.isArchive == true,
                            onClick = onNextSourceClick,
                        )
                    } else {
                        EmptyViewerMessage()
                    }
                }

                if (uiState.isChromeHidden && uiState.pageCount > 1) {
                    HiddenNavigationButtons(
                        currentPage = pagerState.currentPage,
                        pageCount = uiState.pageCount,
                        onPreviousClick = {
                            val previousPage = (pagerState.currentPage - 1).coerceAtLeast(0)
                            coroutineScope.launch {
                                pagerState.scrollToPage(previousPage)
                            }
                        },
                        onNextClick = {
                            val nextPage = (pagerState.currentPage + 1).coerceAtMost(uiState.lastPageIndex)
                            coroutineScope.launch {
                                pagerState.scrollToPage(nextPage)
                            }
                        },
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
            }

            if (!uiState.isChromeHidden) {
                ViewerTimeline(
                    currentPage = pagerState.currentPage,
                    pageCount = uiState.pageCount,
                    onPageSelected = { page ->
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(page)
                        }
                    },
                )
            }
        }
    }

    if (slideShowDialogVisible) {
        SlideShowDialog(
            stayMillis = slideShowStayText,
            onStayMillisChange = { slideShowStayText = it.filter(Char::isDigit) },
            onDismiss = { slideShowDialogVisible = false },
            onStart = {
                onSlideShowStart(slideShowStayText.toLongOrNull() ?: 50L)
                slideShowDialogVisible = false
            },
        )
    }
    if (detailsDialogVisible && selectedItem != null) {
        MediaDetailsDialog(item = selectedItem, onDismiss = { detailsDialogVisible = false })
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
                .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(20.dp))
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
    fillFrame: Boolean,
    flipped: Boolean,
    bookmarked: Boolean,
    onFitClick: () -> Unit,
    onRotateClick: () -> Unit,
    onFlipClick: () -> Unit,
    onBookmarkClick: () -> Unit,
    onDetailsClick: () -> Unit,
    onSlideShowClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(Color(0xFF303030)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        IconButton(onClick = onFitClick) {
            Icon(
                Icons.Default.FitScreen,
                contentDescription = stringResource(if (fillFrame) R.string.fit_screen else R.string.fill_screen),
                tint = if (fillFrame) Color(0xFF64B5F6) else Color.White,
            )
        }
        IconButton(onClick = onRotateClick) {
            Icon(Icons.Default.Rotate90DegreesCcw, contentDescription = stringResource(R.string.rotate), tint = Color.White)
        }
        IconButton(onClick = onFlipClick) {
            Icon(
                Icons.Default.SwapHoriz,
                contentDescription = stringResource(R.string.flip_horizontal),
                tint = if (flipped) Color(0xFF64B5F6) else Color.White,
            )
        }
        IconButton(onClick = onBookmarkClick) {
            Icon(
                Icons.Default.BookmarkBorder,
                contentDescription = stringResource(R.string.bookmark),
                tint = if (bookmarked) Color(0xFF64B5F6) else Color.White,
            )
        }
        IconButton(onClick = onDetailsClick) {
            Icon(Icons.AutoMirrored.Filled.FormatIndentDecrease, contentDescription = stringResource(R.string.details), tint = Color.White)
        }
        IconButton(onClick = onSlideShowClick) {
            Icon(Icons.Default.Slideshow, contentDescription = stringResource(R.string.start_slideshow), tint = Color.White)
        }
    }
}

@Composable
private fun SlideShowDialog(
    stayMillis: String,
    onStayMillisChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onStart: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.slideshow)) },
        text = {
            OutlinedTextField(
                value = stayMillis,
                onValueChange = onStayMillisChange,
                label = { Text(stringResource(R.string.per_image_stay)) },
                singleLine = true,
            )
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
    onReady: () -> Unit,
    onContentClick: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    if (item.type == MediaViewerType.Video) {
        val preview by produceState<PreviewResult?>(
            initialValue = null,
            key1 = item.path,
            key2 = item.lastModifiedMillis,
        ) {
            value = MediaPreviewLoader.loadVideoFrame(item.path, item.lastModifiedMillis, 1600)
        }

        LaunchedEffect(preview, item.path, item.lastModifiedMillis) {
            if (preview != null) onReady()
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable {
                    context.startActivity(
                        Intent(context, VideoPlayerActivity::class.java).apply {
                            putExtra(VideoPlayerActivity.EXTRA_DIRECTORY_PATH, java.io.File(item.path).parent)
                            putExtra(VideoPlayerActivity.EXTRA_VIDEO_PATH, item.path)
                        },
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
            Icon(
                Icons.Default.PlayCircle,
                contentDescription = stringResource(R.string.play_video),
                tint = Color.White,
                modifier = Modifier.size(72.dp),
            )
        }
        return
    }
    val preview by produceState<PreviewResult?>(initialValue = null, key1 = item.path, key2 = item.lastModifiedMillis) {
        value = MediaPreviewLoader.loadImage(item.path, item.lastModifiedMillis, 1600)
    }

    LaunchedEffect(preview?.bitmap, item.path, item.lastModifiedMillis) {
        if (preview?.bitmap != null) onReady()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(onClick = onContentClick),
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
                    .graphicsLayer(rotationZ = rotation.toFloat())
                    .scale(scaleX = if (flipped) -1f else 1f, scaleY = 1f),
                contentScale = if (fillFrame) ContentScale.Crop else ContentScale.Fit,
            )
        }
    }
}

private val MediaViewerItem.readinessKey: String
    get() = "$path:$lastModifiedMillis"

private fun adjustedPseudoStayMillis(baseStayMillis: Long, horizontalFraction: Float): Long {
    val speedMultiplier = 4.0.pow(horizontalFraction.toDouble())
    return (baseStayMillis / speedMultiplier)
        .toLong()
        .coerceIn(MIN_PSEUDO_STAY_MILLIS, MAX_PSEUDO_STAY_MILLIS)
}

private const val MIN_PSEUDO_STAY_MILLIS = 1L
private const val MAX_PSEUDO_STAY_MILLIS = 3_600_000L

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
                Text(stringResource(R.string.media_modified, java.text.DateFormat.getDateTimeInstance().format(java.util.Date(item.lastModifiedMillis))))
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

@Composable
private fun NextSourcePage(
    sourceName: String,
    isArchive: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            Icon(
                if (isArchive) Icons.Default.Archive else Icons.Default.Folder,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.75f),
                modifier = Modifier.size(52.dp),
            )
            Text(
                text = stringResource(if (isArchive) R.string.next_archive else R.string.next_directory),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = sourceName,
                color = Color.White.copy(alpha = 0.7f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Button(onClick = onClick) {
                Text(stringResource(if (isArchive) R.string.move_next_archive else R.string.move_next_directory))
            }
        }
    }
}

@Composable
private fun BoxScope.HiddenNavigationButtons(
    currentPage: Int,
    pageCount: Int,
    onPreviousClick: () -> Unit,
    onNextClick: () -> Unit,
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
            )

            HiddenNavigationButton(
                text = "+",
                contentDescription = stringResource(R.string.next_media),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                onClick = onNextClick,
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
) {
    Box(
        modifier = modifier
            .alpha(0.11f)
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClickLabel = contentDescription, onClick = onClick),
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
    onPageSelected: (Int) -> Unit,
) {
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
        Slider(
            value = currentPage.toFloat(),
            onValueChange = { value -> onPageSelected(value.toInt()) },
            valueRange = 0f..(pageCount - 1).coerceAtLeast(0).toFloat(),
            steps = (pageCount - 2).coerceAtLeast(0),
            enabled = pageCount > 1,
            modifier = Modifier
                .height(28.dp)
                .padding(horizontal = 32.dp),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun HiddenNavigationButtonPreview() {
    SCRTVWRTheme {
        HiddenNavigationButton(
            text = "+",
            contentDescription = "Previous media",
            modifier = Modifier.width(64.dp),
            onClick = {}
        )
    }
}


