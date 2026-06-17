package srv.scrtvwr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatIndentDecrease
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.ImageNotSupported
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.Rotate90DegreesCcw
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import srv.scrtvwr.ui.media.MediaViewerItem
import srv.scrtvwr.ui.media.MediaViewerType
import srv.scrtvwr.ui.media.MediaViewerUiState
import srv.scrtvwr.ui.media.MediaViewerViewModel
import srv.scrtvwr.ui.theme.SCRTVWRTheme
import kotlin.time.Duration.Companion.milliseconds

class MediaViewerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val directoryPath = intent.getStringExtra(EXTRA_DIRECTORY_PATH)
        val mediaPath = intent.getStringExtra(EXTRA_MEDIA_PATH)

        setContent {
            SCRTVWRTheme {
                MediaViewerRoute(
                    directoryPath = directoryPath,
                    selectedPath = mediaPath,
                    onBack = ::finish,
                )
            }
        }
    }

    companion object {
        const val EXTRA_DIRECTORY_PATH = "srv.scrtvwr.extra.DIRECTORY_PATH"
        const val EXTRA_MEDIA_PATH = "srv.scrtvwr.extra.MEDIA_PATH"
    }
}

@Composable
private fun MediaViewerRoute(
    directoryPath: String?,
    selectedPath: String?,
    onBack: () -> Unit,
    viewModel: MediaViewerViewModel = viewModel(),
) {
    LaunchedEffect(directoryPath, selectedPath) {
        viewModel.load(directoryPath, selectedPath)
    }

    val uiState by viewModel.uiState.collectAsState()
    MediaViewerScreen(
        uiState = uiState,
        onPageChanged = viewModel::selectPage,
        onFitClick = viewModel::toggleFillFrame,
        onRotateClick = viewModel::rotateClockwise,
        onFlipClick = viewModel::toggleFlip,
        onBookmarkClick = viewModel::toggleBookmark,
        onChromeToggle = viewModel::toggleChrome,
        onNextDirectoryClick = viewModel::loadNextDirectory,
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
    onNextDirectoryClick: () -> Unit,
    onSlideShowStart: (Long) -> Unit,
    onSlideShowStop: () -> Unit,
    onBack: () -> Unit,
) {
    val pagerState = rememberPagerState(
        initialPage = uiState.initialPage,
        pageCount = { uiState.pageCount.coerceAtLeast(1) },
    )
    val coroutineScope = rememberCoroutineScope()
    var menuExpanded by remember { mutableStateOf(false) }
    var slideShowDialogVisible by remember { mutableStateOf(false) }
    var slideShowStayText by remember { mutableStateOf(uiState.slideShowStayMillis.toString()) }
    var verticalDrag by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(slideShowDialogVisible, uiState.slideShowStayMillis) {
        if (slideShowDialogVisible) {
            slideShowStayText = uiState.slideShowStayMillis.toString()
        }
    }

    LaunchedEffect(uiState.initialPage, uiState.pageCount) {
        if (uiState.items.isNotEmpty()) {
            pagerState.scrollToPage(uiState.initialPage.coerceIn(0, uiState.lastPageIndex))
        }
    }

    LaunchedEffect(pagerState, uiState.pageCount) {
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .collect(onPageChanged)
    }

    LaunchedEffect(uiState.isSlideShowRunning, uiState.slideShowStayMillis, uiState.items.size) {
        while (uiState.isSlideShowRunning && uiState.items.isNotEmpty()) {
            delay(uiState.slideShowStayMillis.milliseconds)
            val nextPage = pagerState.currentPage + 1
            if (nextPage >= uiState.items.size) {
                onSlideShowStop()
            } else {
                pagerState.scrollToPage(nextPage)
            }
        }
    }

    val selectedItem = uiState.items.getOrNull(pagerState.currentPage)
    Scaffold(
        topBar = {
            if (!uiState.isChromeHidden) {
                TopAppBar(
                    title = {
                        Column {
                            Text("Camera", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                selectedItem?.name.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = onFitClick) {
                            Icon(Icons.Default.OpenInFull, contentDescription = "Toggle full view")
                        }
                        Box {
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "More options")
                            }
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(if (uiState.fillFrame) "Fit to screen" else "Fill screen") },
                                    onClick = {
                                        onFitClick()
                                        menuExpanded = false
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Rotate 90") },
                                    onClick = {
                                        onRotateClick()
                                        menuExpanded = false
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(if (uiState.bookmarked) "Remove bookmark" else "Bookmark") },
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
                    onSlideShowClick = { slideShowDialogVisible = true },
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                HorizontalPager(
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
                        },
                ) { page ->
                    uiState.items.getOrNull(page)?.let { item ->
                        MediaPage(
                            item = item,
                            fillFrame = uiState.fillFrame,
                            rotation = uiState.rotation,
                            flipped = uiState.flipped,
                            onContentClick = onChromeToggle,
                        )
                    } ?: if (page == uiState.nextDirectoryPageIndex) {
                        NextDirectoryPage(
                            directoryName = uiState.nextDirectory?.name.orEmpty(),
                            onClick = onNextDirectoryClick,
                        )
                    } else {
                        EmptyViewerMessage()
                    }
                }

                if (uiState.isChromeHidden && uiState.pageCount > 1) {
                    HiddenNavigationButtons(
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
                contentDescription = if (fillFrame) "Show full media" else "Fill screen",
                tint = if (fillFrame) Color(0xFF64B5F6) else Color.White,
            )
        }
        IconButton(onClick = onRotateClick) {
            Icon(Icons.Default.Rotate90DegreesCcw, contentDescription = "Rotate", tint = Color.White)
        }
        IconButton(onClick = onFlipClick) {
            Icon(
                Icons.Default.SwapHoriz,
                contentDescription = "Flip horizontal",
                tint = if (flipped) Color(0xFF64B5F6) else Color.White,
            )
        }
        IconButton(onClick = onBookmarkClick) {
            Icon(
                Icons.Default.BookmarkBorder,
                contentDescription = "Bookmark",
                tint = if (bookmarked) Color(0xFF64B5F6) else Color.White,
            )
        }
        IconButton(onClick = {}) {
            Icon(Icons.AutoMirrored.Filled.FormatIndentDecrease, contentDescription = "Details", tint = Color.White)
        }
        IconButton(onClick = onSlideShowClick) {
            Icon(Icons.Default.Slideshow, contentDescription = "Start slideshow", tint = Color.White)
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
        title = { Text("Slide show") },
        text = {
            OutlinedTextField(
                value = stayMillis,
                onValueChange = onStayMillisChange,
                label = { Text("Per image stay time (ms)") },
                singleLine = true,
            )
        },
        confirmButton = {
            Button(onClick = onStart) {
                Text("Start")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
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
    onContentClick: () -> Unit,
) {
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = item.path, key2 = item.lastModifiedMillis) {
        value = when (item.type) {
            MediaViewerType.Image -> loadImagePreview(item.path)
            MediaViewerType.Video -> loadVideoPreview(item.path)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(onClick = onContentClick),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap == null) {
            Icon(
                Icons.Default.ImageNotSupported,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.7f),
            )
        } else {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = item.name,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(rotationZ = rotation.toFloat())
                    .scale(scaleX = if (flipped) -1f else 1f, scaleY = 1f),
                contentScale = if (fillFrame) ContentScale.Crop else ContentScale.Fit,
            )
            if (item.type == MediaViewerType.Video) {
                Icon(
                    Icons.Default.PlayCircle,
                    contentDescription = "Video thumbnail",
                    tint = Color.White,
                )
            }
        }
    }
}

@Composable
private fun NextDirectoryPage(
    directoryName: String,
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
                Icons.Default.Folder,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.75f),
                modifier = Modifier.size(52.dp),
            )
            Text(
                text = "Next directory",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = directoryName,
                color = Color.White.copy(alpha = 0.7f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Button(onClick = onClick) {
                Text("Move to next directory")
            }
        }
    }
}

@Composable
private fun BoxScope.HiddenNavigationButtons(
    onPreviousClick: () -> Unit,
    onNextClick: () -> Unit,
) {
    HiddenNavigationButton(
        text = "-",
        contentDescription = "Previous media",
        modifier = Modifier
            .align(Alignment.BottomStart)
            .fillMaxWidth(0.5f),
        onClick = onPreviousClick,
    )
    HiddenNavigationButton(
        text = "+",
        contentDescription = "Next media",
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .fillMaxWidth(0.5f),
        onClick = onNextClick,
    )
    Box(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .width(3.dp)
            .height(56.dp)
            .alpha(0.33f)
            .background(Color.White),
    )
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
            .fillMaxWidth()
            .wrapContentHeight()
            .padding(24.dp)
            .alpha(0.33f)
            .background(Color.Transparent)
            .clickable(onClickLabel = contentDescription, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = Color.Gray.copy(alpha = 0.5f),
            style = MaterialTheme.typography.headlineLarge,
        )
    }
}

@Composable
private fun EmptyViewerMessage() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("No image or video selected", color = Color.White.copy(alpha = 0.7f))
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
            modifier = Modifier.height(28.dp),
        )
    }
}

private suspend fun loadImagePreview(path: String): Bitmap? = withContext(Dispatchers.IO) {
    runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val sampleOptions = BitmapFactory.Options().apply {
            inSampleSize = bounds.previewSampleSize()
        }
        BitmapFactory.decodeFile(path, sampleOptions)
    }.getOrNull()
}

private suspend fun loadVideoPreview(path: String): Bitmap? = withContext(Dispatchers.IO) {
    runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(path)
            retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } finally {
            retriever.release()
        }
    }.getOrNull()
}

private fun BitmapFactory.Options.previewSampleSize(): Int {
    var sampleSize = 1
    val maxDimension = 1600
    while (outHeight / sampleSize > maxDimension || outWidth / sampleSize > maxDimension) {
        sampleSize *= 2
    }
    return sampleSize
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


