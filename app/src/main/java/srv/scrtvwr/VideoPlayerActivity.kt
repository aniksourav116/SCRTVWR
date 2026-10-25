package srv.scrtvwr

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import srv.scrtvwr.media.DirectoryTraversal
import srv.scrtvwr.media.remote.RemoteVideoPlayback
import srv.scrtvwr.ui.components.BookmarkFlash
import srv.scrtvwr.ui.components.TapeTransportButton
import srv.scrtvwr.ui.components.VideoSideGestures
import srv.scrtvwr.ui.components.copyLink
import srv.scrtvwr.ui.theme.SCRTVWRTheme
import srv.scrtvwr.media.MediaCatalog
import srv.scrtvwr.media.MediaPaths
import srv.scrtvwr.media.MediaTypes
import srv.scrtvwr.media.remote.RemoteDirectoryTree
import srv.scrtvwr.media.remote.RemotePaths
import srv.scrtvwr.media.remote.serverFor
import srv.scrtvwr.preferences.ScrtvwrPreferences
import srv.scrtvwr.ui.media.MediaViewerItem
import srv.scrtvwr.ui.media.MediaViewerType
import srv.scrtvwr.ui.media.VideoCompletionAction
import srv.scrtvwr.ui.media.loopsCurrentVideo
import srv.scrtvwr.ui.media.nextVideoIndex
import srv.scrtvwr.ui.media.wrapsAtDirectoryEnd
import srv.scrtvwr.ui.sort.DirectorySortOrder
import srv.scrtvwr.ui.sort.fileComparator
import java.io.File
import java.io.IOException
import java.util.Locale

class VideoPlayerActivity : ComponentActivity() {
    private var pendingImageDirectory: String? = null
    private val imageViewerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val directoryPath = result.data
            ?.getStringExtra(MediaViewerActivity.RESULT_DIRECTORY_PATH)
            ?: pendingImageDirectory
        if (directoryPath != null) {
            setResult(
                Activity.RESULT_OK,
                Intent().putExtra(RESULT_DIRECTORY_PATH, directoryPath),
            )
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setContent {
            SCRTVWRTheme {
                VideoPagerScreen(
                    directoryPath = intent.getStringExtra(EXTRA_DIRECTORY_PATH),
                    selectedPath = intent.getStringExtra(EXTRA_VIDEO_PATH),
                    onBack = ::finish,
                    onDirectoryChanged = { path ->
                        setResult(
                            Activity.RESULT_OK,
                            Intent().putExtra(RESULT_DIRECTORY_PATH, path),
                        )
                    },
                    onOpenImageDirectory = { directory, imagePath ->
                        pendingImageDirectory = directory
                        setResult(
                            Activity.RESULT_OK,
                            Intent().putExtra(RESULT_DIRECTORY_PATH, directory),
                        )
                        imageViewerLauncher.launch(
                            Intent(this, MediaViewerActivity::class.java).apply {
                                putExtra(MediaViewerActivity.EXTRA_DIRECTORY_PATH, directory)
                                putExtra(MediaViewerActivity.EXTRA_MEDIA_PATH, imagePath)
                            },
                        )
                    },
                )
            }
        }
    }

    companion object {
        const val EXTRA_DIRECTORY_PATH = "srv.scrtvwr.extra.VIDEO_DIRECTORY_PATH"
        const val EXTRA_VIDEO_PATH = "srv.scrtvwr.extra.VIDEO_PATH"
        const val RESULT_DIRECTORY_PATH = MediaViewerActivity.RESULT_DIRECTORY_PATH
    }
}

@Composable
private fun VideoPagerScreen(
    directoryPath: String?,
    selectedPath: String?,
    onBack: () -> Unit,
    onDirectoryChanged: (String) -> Unit,
    onOpenImageDirectory: (String, String) -> Unit,
) {
    val context = LocalContext.current
    val preferences = remember { ScrtvwrPreferences(context) }
    val sortOrder = remember { preferences.getDirectorySortOrder() }
    val videoPreloadDistance = remember { preferences.getVideoPreloadDistance() }
    val traversalRoot = remember { runCatching { Environment.getExternalStorageDirectory() }.getOrNull() }
    var currentDirectoryPath by rememberSaveable(directoryPath, selectedPath) {
        mutableStateOf(directoryPath ?: selectedPath?.let(MediaPaths::parent))
    }
    var selectedVideoPath by rememberSaveable(directoryPath, selectedPath) {
        mutableStateOf(selectedPath)
    }
    val loadedDirectory by produceState<VideoDirectory?>(initialValue = null, currentDirectoryPath, selectedPath) {
        // The state outlives its keys. Clear it, or the pager would scroll and play within the
        // previous folder's videos, and record them as the selection, while the next one loads.
        value = null
        value = withContext(Dispatchers.IO) {
            val requestedSelection = selectedPath.takeIf { currentDirectoryPath == directoryPath }
            if (MediaPaths.isRemote(currentDirectoryPath ?: requestedSelection)) {
                loadRemoteVideoDirectory(
                    directoryPath = currentDirectoryPath,
                    selectedPath = requestedSelection,
                    sortOrder = sortOrder,
                    preferences = preferences,
                )
            } else {
                loadVideoDirectory(
                    directoryPath = currentDirectoryPath,
                    selectedPath = requestedSelection,
                    sortOrder = sortOrder,
                    traversalRoot = traversalRoot,
                )
            }
        }
    }
    val videos = loadedDirectory?.videos.orEmpty()
    val previousDirectory = loadedDirectory?.previousDirectory
    val nextDirectory = loadedDirectory?.nextDirectory
    val firstVideoPageIndex = if (previousDirectory != null) 1 else 0
    var completionAction by rememberSaveable { mutableStateOf(preferences.getVideoCompletionAction()) }
    var controlsLocked by rememberSaveable { mutableStateOf(false) }
    val initialPage = remember(videos, selectedVideoPath, firstVideoPageIndex) {
        videos.indexOfFirst { it.path == selectedVideoPath }.coerceAtLeast(0) + firstVideoPageIndex
    }
    val pagerState = rememberPagerState(
        initialPage = initialPage,
        pageCount = {
            videos.size +
                (if (previousDirectory != null) 1 else 0) +
                (if (nextDirectory != null) 1 else 0)
        },
    )
    val scope = rememberCoroutineScope()

    LaunchedEffect(loadedDirectory, selectedVideoPath) {
        if (videos.isNotEmpty() && pagerState.currentPage != initialPage) {
            pagerState.scrollToPage(initialPage)
        }
    }

    LaunchedEffect(pagerState, videos) {
        snapshotFlow { pagerState.currentPage }
            .collect { page ->
                videos.getOrNull(page - firstVideoPageIndex)?.let { video ->
                    selectedVideoPath = video.path
                    currentDirectoryPath?.let { preferences.saveLastMediaSession(it, video.path) }
                }
            }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        when {
            loadedDirectory?.videos?.isEmpty() == true -> {
                Text(
                    stringResource(R.string.no_videos_found),
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            loadedDirectory == null -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            else -> VerticalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = minOf(videoPreloadDistance, pagerState.pageCount - 1),
                userScrollEnabled = !controlsLocked,
            ) { page ->
                val videoIndex = page - firstVideoPageIndex
                val item = videos.getOrNull(videoIndex)
                if (item != null) {
                    VideoPage(
                        item = item,
                        page = videoIndex,
                        pageCount = videos.size,
                        isActive = page == pagerState.currentPage,
                        completionAction = completionAction,
                        onCompletionActionChange = { action ->
                            completionAction = action
                            preferences.saveVideoCompletionAction(action)
                        },
                        controlsLocked = controlsLocked,
                        onControlsLockedChange = { controlsLocked = it },
                        onBack = onBack,
                        onMovePrevious = {
                            scope.launch {
                                pagerState.animateScrollToPage(page - 1)
                            }
                        },
                        onMoveNext = {
                            scope.launch {
                                pagerState.animateScrollToPage(page + 1)
                            }
                        },
                        onMoveToFirst = {
                            scope.launch {
                                pagerState.animateScrollToPage(firstVideoPageIndex)
                            }
                        },
                        onMoveToLast = {
                            scope.launch {
                                pagerState.animateScrollToPage(firstVideoPageIndex + videos.lastIndex)
                            }
                        },
                        onEnded = {
                            completionAction.nextVideoIndex(videoIndex, videos.size)?.let { nextIndex ->
                                scope.launch {
                                    pagerState.animateScrollToPage(firstVideoPageIndex + nextIndex)
                                }
                            }
                        },
                    )
                } else if (page == 0 && previousDirectory != null) {
                    VideoDirectoryPage(
                        directory = previousDirectory,
                        direction = VideoDirectoryDirection.Previous,
                        onMove = {
                            if (previousDirectory.type == VideoDirectoryType.Video) {
                                currentDirectoryPath = previousDirectory.path
                                selectedVideoPath = previousDirectory.selectedMediaPath
                                onDirectoryChanged(previousDirectory.path)
                            } else {
                                onOpenImageDirectory(previousDirectory.path, previousDirectory.selectedMediaPath)
                            }
                        },
                    )
                } else if (nextDirectory != null) {
                    VideoDirectoryPage(
                        directory = nextDirectory,
                        direction = VideoDirectoryDirection.Next,
                        onMove = {
                            if (nextDirectory.type == VideoDirectoryType.Video) {
                                currentDirectoryPath = nextDirectory.path
                                selectedVideoPath = nextDirectory.selectedMediaPath
                                onDirectoryChanged(nextDirectory.path)
                            } else {
                                onOpenImageDirectory(nextDirectory.path, nextDirectory.selectedMediaPath)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun VideoPage(
    item: VideoItem,
    page: Int,
    pageCount: Int,
    isActive: Boolean,
    completionAction: VideoCompletionAction,
    onCompletionActionChange: (VideoCompletionAction) -> Unit,
    controlsLocked: Boolean,
    onControlsLockedChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onMovePrevious: () -> Unit,
    onMoveNext: () -> Unit,
    onMoveToFirst: () -> Unit,
    onMoveToLast: () -> Unit,
    onEnded: () -> Unit,
) {
    val context = LocalContext.current
    val preferences = remember { ScrtvwrPreferences(context) }
    var savedPlaybackPosition by rememberSaveable(item.path) { mutableLongStateOf(0L) }
    val mediaItem = remember(item.path) {
        // Server files stream through ExoPlayer's HTTP data source straight from their URL.
        MediaItem.fromUri(if (MediaPaths.isRemote(item.path)) Uri.parse(item.path) else Uri.fromFile(File(item.path)))
    }
    val player = remember(item.path) {
        val playback = if (MediaPaths.isRemote(item.path)) {
            RemoteVideoPlayback.player(context)
        } else {
            ExoPlayer.Builder(context).build()
        }
        playback.apply {
            setMediaItem(mediaItem)
        }
    }
    var controlsVisible by rememberSaveable(item.path) { mutableStateOf(true) }
    var isPlaying by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var dragStartPosition by remember { mutableLongStateOf(0L) }
    var dragTargetPosition by remember { mutableStateOf<Long?>(null) }
    var horizontalDrag by remember { mutableFloatStateOf(0f) }
    var speed by rememberSaveable { mutableFloatStateOf(1f) }
    var resizeMode by rememberSaveable { mutableStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var optionsExpanded by rememberSaveable(item.path) { mutableStateOf(false) }
    var speedMenuOpen by remember { mutableStateOf(false) }
    var bookmarked by remember(item.path) { mutableStateOf(preferences.isMediaBookmarked(item.path)) }
    var bookmarkFlashId by remember(item.path) { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var prepareRequested by remember { mutableStateOf(false) }
    var reloadWhenActive by remember { mutableStateOf(false) }
    var activeReloadAttempts by remember(item.path) { mutableStateOf(0) }
    val latestIsActive by rememberUpdatedState(isActive)

    fun saveCurrentPosition() {
        if (prepareRequested || player.playbackState != Player.STATE_IDLE) {
            val currentPosition = player.currentPosition.coerceAtLeast(0L)
            // A finished video resumes from the top rather than from its last frame, so coming
            // back to it plays it again instead of instantly re-running the completion action.
            savedPlaybackPosition = if (player.playbackState == Player.STATE_ENDED) 0L else currentPosition
            position = currentPosition
        }
    }

    fun preparePlayerForActivePlayback() {
        error = null
        reloadWhenActive = false
        player.setMediaItem(mediaItem)
        if (savedPlaybackPosition > 0L) player.seekTo(savedPlaybackPosition)
        player.prepare()
        prepareRequested = true
    }

    fun setBookmarked(value: Boolean) {
        preferences.setMediaBookmarked(item.path, value)
        bookmarked = value
        // Replay the heart even when it was already bookmarked, so a double tap always confirms.
        if (value) bookmarkFlashId += 1
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(value: Boolean) {
                isPlaying = value
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    prepareRequested = true
                    duration = player.duration.coerceAtLeast(0L)
                    error = null
                    reloadWhenActive = false
                    activeReloadAttempts = 0
                }
                if (state == Player.STATE_ENDED) {
                    savedPlaybackPosition = 0L
                    if (MediaPaths.isRemote(item.path)) {
                        // Free the streaming buffers immediately, while keeping the media item
                        // available for replay. Looping videos never enter STATE_ENDED.
                        player.stop()
                        player.seekTo(0L)
                        prepareRequested = false
                    }
                    onEnded()
                }
            }

            override fun onPlayerError(playbackError: PlaybackException) {
                prepareRequested = false
                if (latestIsActive && activeReloadAttempts < ACTIVE_ERROR_RELOAD_ATTEMPTS) {
                    error = null
                    reloadWhenActive = true
                    activeReloadAttempts += 1
                } else if (latestIsActive) {
                    error = playbackError.localizedMessage ?: playbackError.errorCodeName
                } else {
                    error = null
                    reloadWhenActive = true
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            saveCurrentPosition()
            player.release()
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        saveCurrentPosition()
        player.pause()
        player.stop()
        prepareRequested = false
    }

    LaunchedEffect(isActive, reloadWhenActive, player) {
        if (isActive) {
            player.playWhenReady = true
            if (reloadWhenActive || !prepareRequested || player.playbackState == Player.STATE_IDLE) {
                if (reloadWhenActive) delay(ACTIVE_ERROR_RELOAD_DELAY_MILLIS)
                preparePlayerForActivePlayback()
            }
            if (player.playbackState != Player.STATE_ENDED) {
                player.play()
            }
        } else {
            saveCurrentPosition()
            player.pause()
            if (prepareRequested || player.playbackState != Player.STATE_IDLE) {
                player.stop()
                prepareRequested = false
            }
        }
    }

    LaunchedEffect(player, isActive) {
        while (isActive) {
            position = player.currentPosition.coerceAtLeast(0L)
            if (player.playbackState != Player.STATE_ENDED) savedPlaybackPosition = position
            if (player.duration > 0) duration = player.duration
            delay(250)
        }
    }

    LaunchedEffect(completionAction, pageCount) {
        // Repeating a whole directory of one video has nowhere to advance to, so it loops in place.
        val loopsInPlayer = completionAction.loopsCurrentVideo ||
            (completionAction.wrapsAtDirectoryEnd && pageCount <= 1)
        player.repeatMode = if (loopsInPlayer) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    LaunchedEffect(speed) { player.setPlaybackSpeed(speed) }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    useController = false
                    this.player = player
                    this.resizeMode = resizeMode
                }
            },
            update = {
                it.player = player
                it.resizeMode = resizeMode
            },
            modifier = Modifier.fillMaxSize(),
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(player, duration, controlsLocked) {
                    detectTapGestures(
                        onTap = { controlsVisible = !controlsVisible },
                        // Outer thirds keep the seek gesture; the middle third is the instant "like".
                        onDoubleTap = { offset ->
                            if (controlsLocked) return@detectTapGestures
                            val zoneWidth = size.width / 3f
                            val delta = when {
                                offset.x < zoneWidth -> -DOUBLE_TAP_SEEK_MILLIS
                                offset.x > size.width - zoneWidth -> DOUBLE_TAP_SEEK_MILLIS
                                else -> null
                            }
                            if (delta == null) {
                                setBookmarked(true)
                            } else {
                                player.seekTo((player.currentPosition + delta).coerceIn(0L, duration.coerceAtLeast(0L)))
                            }
                        },
                    )
                }
                .then(
                    if (controlsLocked) {
                        Modifier
                    } else {
                        Modifier.pointerInput(player, duration) {
                            detectHorizontalDragGestures(
                                onDragStart = {
                                    dragStartPosition = player.currentPosition
                                    horizontalDrag = 0f
                                    dragTargetPosition = dragStartPosition
                                },
                                onHorizontalDrag = { change, amount ->
                                    change.consume()
                                    horizontalDrag += amount
                                    val seekWindow = duration.coerceAtMost(MAX_SWIPE_SEEK_MILLIS)
                                    val delta = (horizontalDrag / size.width.coerceAtLeast(1) * seekWindow).toLong()
                                    dragTargetPosition =
                                        (dragStartPosition + delta).coerceIn(0L, duration.coerceAtLeast(0L))
                                },
                                onDragEnd = {
                                    dragTargetPosition?.let(player::seekTo)
                                    dragTargetPosition = null
                                },
                                onDragCancel = { dragTargetPosition = null },
                            )
                        }
                    },
                ),
        ) {
            // Nested inside the gesture layer so taps still reach the handlers above.
            VideoSideGestures(enabled = isActive && !controlsLocked)
        }

        dragTargetPosition?.let { target ->
            Text(
                stringResource(R.string.seek_preview, formatVideoTime(target)),
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.7f), MaterialTheme.shapes.medium)
                    .padding(16.dp),
            )
        }

        error?.let { message ->
            Surface(
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF212121),
                tonalElevation = 4.dp,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(24.dp),
                ) {
                    Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = Color.White.copy(alpha = 0.85f))
                    Text(
                        stringResource(R.string.video_playback_error, message),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        onClick = {
                            error = null
                            activeReloadAttempts = 0
                            reloadWhenActive = true
                        },
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.retry), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }

        if (isActive && controlsVisible) {
            if (controlsLocked) {
                // Locked keeps the frame clear: one button, and every other gesture is inert.
                IconButton(
                    onClick = { onControlsLockedChange(false) },
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 16.dp)
                        .size(48.dp)
                        .background(Color.Black.copy(alpha = 0.55f), CircleShape),
                ) {
                    Icon(Icons.Default.Lock, stringResource(R.string.unlock_controls), tint = Color.White)
                }
            } else {
                VideoControls(
                    item = item,
                    page = page,
                    pageCount = pageCount,
                    player = player,
                    isPlaying = isPlaying,
                    position = position,
                    duration = duration,
                    completionAction = completionAction,
                    onCompletionActionChange = onCompletionActionChange,
                    speed = speed,
                    onSpeedChange = { speed = it },
                    speedMenuOpen = speedMenuOpen,
                    onSpeedMenuChange = { speedMenuOpen = it },
                    resizeMode = resizeMode,
                    onResizeModeChange = { resizeMode = it },
                    optionsExpanded = optionsExpanded,
                    onOptionsExpandedChange = { optionsExpanded = it },
                    bookmarked = bookmarked,
                    onBookmarkToggle = { setBookmarked(!bookmarked) },
                    onScrubPreview = { dragTargetPosition = it },
                    onBack = onBack,
                    onLock = { onControlsLockedChange(true) },
                    onMovePrevious = onMovePrevious,
                    onMoveNext = onMoveNext,
                    onMoveToFirst = onMoveToFirst,
                    onMoveToLast = onMoveToLast,
                )
            }
        }

        BookmarkFlash(
            flashId = bookmarkFlashId,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

@Composable
private fun BoxScope.VideoControls(
    item: VideoItem,
    page: Int,
    pageCount: Int,
    player: ExoPlayer,
    isPlaying: Boolean,
    position: Long,
    duration: Long,
    completionAction: VideoCompletionAction,
    onCompletionActionChange: (VideoCompletionAction) -> Unit,
    speed: Float,
    onSpeedChange: (Float) -> Unit,
    speedMenuOpen: Boolean,
    onSpeedMenuChange: (Boolean) -> Unit,
    resizeMode: Int,
    onResizeModeChange: (Int) -> Unit,
    optionsExpanded: Boolean,
    onOptionsExpandedChange: (Boolean) -> Unit,
    bookmarked: Boolean,
    onBookmarkToggle: () -> Unit,
    onScrubPreview: (Long?) -> Unit,
    onBack: () -> Unit,
    onLock: () -> Unit,
    onMovePrevious: () -> Unit,
    onMoveNext: () -> Unit,
    onMoveToFirst: () -> Unit,
    onMoveToLast: () -> Unit,
) {
    val context = LocalContext.current
    // Whether the tape transport interrupted playback, so releasing it can pick up where it left off.
    var resumeAfterSpool by remember { mutableStateOf(false) }

    // Top: compact chrome over a scrim, with the options tray unfolding directly beneath it so the
    // options read as belonging to the title bar that opened them.
    Column(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = 0.85f),
                        Color.Black.copy(alpha = 0.6f),
                        Color.Transparent,
                    ),
                ),
            )
            .padding(top = 18.dp, bottom = 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = Color.White)
            }
            Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                Text(
                    item.name,
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(R.string.video_count, page + 1, pageCount),
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            IconButton(
                onClick = { onOptionsExpandedChange(!optionsExpanded) },
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    if (optionsExpanded) Icons.Default.ExpandLess else Icons.Default.MoreVert,
                    stringResource(if (optionsExpanded) R.string.hide_player_options else R.string.player_options),
                    tint = Color.White,
                )
            }
        }

        AnimatedVisibility(
            visible = optionsExpanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            PlayerOptionsRow(
                speed = speed,
                onSpeedChange = onSpeedChange,
                speedMenuOpen = speedMenuOpen,
                onSpeedMenuChange = onSpeedMenuChange,
                bookmarked = bookmarked,
                onBookmarkToggle = onBookmarkToggle,
                canMoveToFirst = pageCount > 0 && page != 0,
                onMoveToFirst = onMoveToFirst,
                canMoveToLast = pageCount > 0 && page != pageCount - 1,
                onMoveToLast = onMoveToLast,
                onCopyLink = MediaPaths.link(item.path)?.let { link -> { context.copyLink(link) } },
            )
        }
    }

    // Bottom: scrim-gradient overlay with a compact scrubber, then the transport row - lock on the
    // far left, playback centred, and the two cycling toggles on the far right.
    Column(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)),
                ),
            )
            .padding(top = 24.dp, bottom = 14.dp, start = 12.dp, end = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                formatVideoTime(position),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(end = 8.dp),
            )
            Slider(
                value = position.coerceAtMost(duration).toFloat(),
                onValueChange = { player.seekTo(it.toLong()) },
                valueRange = 0f..duration.coerceAtLeast(1L).toFloat(),
                enabled = duration > 0,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                ),
                modifier = Modifier.weight(1f),
            )
            Text(
                formatVideoTime(duration),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onLock, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.LockOpen, stringResource(R.string.lock_controls), tint = Color.White)
            }
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val canMovePrevious = page > 0
                val canMoveNext = page < pageCount - 1
                IconButton(
                    onClick = onMovePrevious,
                    enabled = canMovePrevious,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        Icons.Default.SkipPrevious,
                        stringResource(R.string.previous_video),
                        tint = transportTint(canMovePrevious),
                    )
                }
                TapeTransportButton(
                    icon = Icons.Default.FastRewind,
                    contentDescription = stringResource(R.string.rewind),
                    forward = false,
                    positionMillis = { player.currentPosition },
                    durationMillis = duration,
                    nudgeMillis = SHORT_SEEK_MILLIS,
                    onSeek = { player.seekTo(it) },
                    onSpoolPreview = onScrubPreview,
                    onSpoolingChange = { spooling ->
                        if (spooling) {
                            resumeAfterSpool = player.isPlaying
                            player.pause()
                        } else if (resumeAfterSpool) {
                            player.play()
                        }
                    },
                    modifier = Modifier.padding(start = 8.dp).size(40.dp),
                )
                IconButton(
                    onClick = {
                        if (player.isPlaying) player.pause() else {
                            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                            if (player.playbackState == Player.STATE_IDLE) player.prepare()
                            player.play()
                        }
                    },
                    modifier = Modifier
                        .padding(horizontal = 20.dp)
                        .size(56.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                ) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        stringResource(if (isPlaying) R.string.pause_video else R.string.play_video),
                        // The rest of the chrome is white on video, but this icon sits on a primary
                        // fill, and dark schemes make primary a pale tone - white would vanish.
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(30.dp),
                    )
                }
                TapeTransportButton(
                    icon = Icons.Default.FastForward,
                    contentDescription = stringResource(R.string.fast_forward),
                    forward = true,
                    positionMillis = { player.currentPosition },
                    durationMillis = duration,
                    nudgeMillis = SHORT_SEEK_MILLIS,
                    onSeek = { player.seekTo(it) },
                    onSpoolPreview = onScrubPreview,
                    onSpoolingChange = { spooling ->
                        if (spooling) {
                            resumeAfterSpool = player.isPlaying
                            player.pause()
                        } else if (resumeAfterSpool) {
                            player.play()
                        }
                    },
                    modifier = Modifier.padding(end = 8.dp).size(40.dp),
                )
                IconButton(
                    onClick = onMoveNext,
                    enabled = canMoveNext,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        Icons.Default.SkipNext,
                        stringResource(R.string.next_video),
                        tint = transportTint(canMoveNext),
                    )
                }
            }
            IconButton(
                onClick = { onCompletionActionChange(completionAction.next()) },
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    completionAction.icon(),
                    stringResource(R.string.video_on_complete_state, completionAction.label()),
                    tint = if (completionAction == VideoCompletionAction.Stop) {
                        Color.White
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
            IconButton(
                onClick = { onResizeModeChange(resizeMode.next()) },
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    resizeMode.icon(),
                    stringResource(R.string.aspect_ratio),
                    tint = if (resizeMode == AspectRatioFrameLayout.RESIZE_MODE_FIT) {
                        Color.White
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
        }
    }
}

/**
 * The options that are not frequent enough to earn a slot in the transport bar, as one scrollable
 * tray of icon-over-label entries. Each entry is labelled with the value it currently holds rather
 * than only its name, so the tray doubles as a status readout.
 */
@Composable
private fun PlayerOptionsRow(
    speed: Float,
    onSpeedChange: (Float) -> Unit,
    speedMenuOpen: Boolean,
    onSpeedMenuChange: (Boolean) -> Unit,
    bookmarked: Boolean,
    onBookmarkToggle: () -> Unit,
    canMoveToFirst: Boolean,
    onMoveToFirst: () -> Unit,
    canMoveToLast: Boolean,
    onMoveToLast: () -> Unit,
    onCopyLink: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(top = 10.dp, start = 8.dp, end = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box {
            PlayerOption(
                icon = Icons.Default.Speed,
                label = formatSpeed(speed),
                active = speed != 1f,
                onClick = { onSpeedMenuChange(true) },
                contentDescription = stringResource(R.string.playback_speed),
            )
            DropdownMenu(expanded = speedMenuOpen, onDismissRequest = { onSpeedMenuChange(false) }) {
                PLAYBACK_SPEEDS.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(formatSpeed(option)) },
                        onClick = {
                            onSpeedChange(option)
                            onSpeedMenuChange(false)
                        },
                    )
                }
            }
        }
        PlayerOption(
            icon = if (bookmarked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
            label = stringResource(if (bookmarked) R.string.bookmarked else R.string.bookmark),
            active = bookmarked,
            onClick = onBookmarkToggle,
        )
        PlayerOption(
            icon = Icons.Default.VerticalAlignTop,
            label = stringResource(R.string.first_video),
            active = false,
            enabled = canMoveToFirst,
            onClick = onMoveToFirst,
            contentDescription = stringResource(R.string.move_first_item),
        )
        PlayerOption(
            icon = Icons.Default.VerticalAlignBottom,
            label = stringResource(R.string.last_video),
            active = false,
            enabled = canMoveToLast,
            onClick = onMoveToLast,
            contentDescription = stringResource(R.string.move_last_item),
        )
        onCopyLink?.let {
            PlayerOption(
                icon = Icons.Default.Link,
                label = stringResource(R.string.copy_link),
                active = false,
                onClick = it,
            )
        }
    }
}

@Composable
private fun PlayerOption(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
    contentDescription: String? = null,
) {
    val tint = when {
        !enabled -> Color.White.copy(alpha = 0.35f)
        active -> MaterialTheme.colorScheme.primary
        else -> Color.White
    }
    Column(
        modifier = Modifier
            .width(PLAYER_OPTION_WIDTH)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            icon,
            contentDescription = contentDescription ?: label,
            tint = tint,
            modifier = Modifier.size(24.dp),
        )
        Text(
            label,
            color = tint,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun transportTint(enabled: Boolean): Color =
    if (enabled) Color.White else Color.White.copy(alpha = 0.35f)

private fun Int.next(): Int = when (this) {
    AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_FILL
    AspectRatioFrameLayout.RESIZE_MODE_FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
}

private fun Int.icon(): ImageVector = when (this) {
    AspectRatioFrameLayout.RESIZE_MODE_FILL -> Icons.Default.AspectRatio
    AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> Icons.Default.Fullscreen
    else -> Icons.Default.FitScreen
}

private fun Int.labelRes(): Int = when (this) {
    AspectRatioFrameLayout.RESIZE_MODE_FILL -> R.string.video_resize_fill
    AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> R.string.video_resize_zoom
    else -> R.string.video_resize_fit
}

@Composable
private fun VideoCompletionAction.label(): String = stringResource(
    when (this) {
        VideoCompletionAction.Stop -> R.string.video_on_complete_stop
        VideoCompletionAction.RepeatOne -> R.string.video_on_complete_repeat_one
        VideoCompletionAction.PlayNext -> R.string.video_on_complete_play_next
        VideoCompletionAction.RepeatAll -> R.string.video_on_complete_repeat_all
    },
)

private fun VideoCompletionAction.icon(): ImageVector = when (this) {
    // Stop is the familiar repeat-off state: the same glyph as Repeat All, only shown inactive.
    // A stop square would read as "stop playing" this close to the play button, and Play Next
    // borrows the playlist glyph for the same reason - Skip Next already sits two slots away.
    VideoCompletionAction.Stop -> Icons.Default.Repeat
    VideoCompletionAction.RepeatOne -> Icons.Default.RepeatOne
    VideoCompletionAction.PlayNext -> Icons.AutoMirrored.Filled.PlaylistPlay
    VideoCompletionAction.RepeatAll -> Icons.Default.Repeat
}

/** Chip cycle: do nothing, loop this video, move on once, then move on forever. */
private fun VideoCompletionAction.next(): VideoCompletionAction = when (this) {
    VideoCompletionAction.Stop -> VideoCompletionAction.RepeatOne
    VideoCompletionAction.RepeatOne -> VideoCompletionAction.PlayNext
    VideoCompletionAction.PlayNext -> VideoCompletionAction.RepeatAll
    VideoCompletionAction.RepeatAll -> VideoCompletionAction.Stop
}

@Composable
private fun VideoDirectoryPage(
    directory: VideoDirectoryTarget,
    direction: VideoDirectoryDirection,
    onMove: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .background(Color.White.copy(alpha = 0.08f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Folder,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(36.dp),
                )
            }
            Text(
                stringResource(direction.titleStringRes(isArchive = false)),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                directory.name,
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Button(onClick = onMove) {
                Text(stringResource(direction.moveStringRes(isArchive = false)))
            }
        }
    }
}

private fun loadVideoDirectory(
    directoryPath: String?,
    selectedPath: String?,
    sortOrder: DirectorySortOrder,
    traversalRoot: File?,
): VideoDirectory {
    val directory = directoryPath?.let(::File)?.takeIf(File::isDirectory)
        ?: selectedPath?.let(::File)?.parentFile
    val videos = directory.listVideos(sortOrder)
    val fallbackVideos = if (videos.isEmpty()) {
        selectedPath?.let(::File)?.takeIf(File::isFile)?.let { listOf(VideoItem(it.name, it.absolutePath)) }.orEmpty()
    } else {
        videos
    }
    return VideoDirectory(
        videos = fallbackVideos,
        previousDirectory = directory?.findPreviousMediaDirectory(sortOrder, traversalRoot),
        nextDirectory = directory?.findNextMediaDirectory(sortOrder, traversalRoot),
    )
}

private fun File?.listVideos(sortOrder: DirectorySortOrder): List<VideoItem> = this?.listFiles()
        ?.asSequence()
        ?.filter(File::isFile)
        ?.filter { MediaTypes.isVideo(it.name) }
        ?.sortedWith(sortOrder.fileComparator(directoriesFirst = false))
        ?.map { VideoItem(it.name.ifBlank { it.absolutePath }, it.absolutePath) }
        ?.toList()
        .orEmpty()

private fun File.findNextMediaDirectory(
    sortOrder: DirectorySortOrder,
    traversalRoot: File?,
): VideoDirectoryTarget? = firstMediaDirectory(sortOrder, traversalRoot) { root ->
    DirectoryTraversal.forward(this, root, sortOrder)
}

private fun File.findPreviousMediaDirectory(
    sortOrder: DirectorySortOrder,
    traversalRoot: File?,
): VideoDirectoryTarget? = firstMediaDirectory(sortOrder, traversalRoot) { root ->
    DirectoryTraversal.backward(this, root, sortOrder)
}

/**
 * First directory holding playable media along [candidates], the depth-first walk starting here:
 * subfolders first, then following siblings, then the siblings of each ancestor up to the root.
 */
private fun File.firstMediaDirectory(
    sortOrder: DirectorySortOrder,
    traversalRoot: File?,
    candidates: (root: File) -> Sequence<File>,
): VideoDirectoryTarget? {
    val root = DirectoryTraversal.resolveRoot(this, traversalRoot)
    return candidates(root)
        .mapNotNull { candidate ->
            val name = DirectoryTraversal.displayName(this, candidate)
            val videos = candidate.listVideos(sortOrder)
            if (videos.isNotEmpty()) {
                VideoDirectoryTarget(
                    name = name,
                    path = candidate.absolutePath,
                    type = VideoDirectoryType.Video,
                    selectedMediaPath = videos.first().path,
                )
            } else {
                candidate.listImages(sortOrder).firstOrNull()?.let { image ->
                    VideoDirectoryTarget(
                        name = name,
                        path = candidate.absolutePath,
                        type = VideoDirectoryType.Image,
                        selectedMediaPath = image.absolutePath,
                    )
                }
            }
        }
        .firstOrNull()
}

/** [loadVideoDirectory] for a folder on an HTTP server, hopping only within that server. */
private suspend fun loadRemoteVideoDirectory(
    directoryPath: String?,
    selectedPath: String?,
    sortOrder: DirectorySortOrder,
    preferences: ScrtvwrPreferences,
): VideoDirectory {
    val directory = directoryPath?.takeIf(MediaPaths::isRemote)?.let(RemotePaths::directoryUrl)
        ?: selectedPath?.let(RemotePaths::parent)
        ?: return VideoDirectory(videos = emptyList(), previousDirectory = null, nextDirectory = null)
    val videos = remoteMedia(directory, sortOrder).videos()
    val fallbackVideos = videos.ifEmpty {
        selectedPath?.let { listOf(VideoItem(RemotePaths.name(it), it)) }.orEmpty()
    }
    val root = RemotePaths.resolveRoot(directory, preferences.getHttpServers().serverFor(directory)?.baseUrl)
    return VideoDirectory(
        videos = fallbackVideos,
        previousDirectory = firstRemoteMediaDirectory(
            from = directory,
            candidates = DirectoryTraversal.backward(RemoteDirectoryTree, directory, root, sortOrder),
            sortOrder = sortOrder,
        ),
        nextDirectory = firstRemoteMediaDirectory(
            from = directory,
            candidates = DirectoryTraversal.forward(RemoteDirectoryTree, directory, root, sortOrder),
            sortOrder = sortOrder,
        ),
    )
}

/** Remote counterpart of [firstMediaDirectory]: videos win, otherwise the first image opens the viewer. */
private suspend fun firstRemoteMediaDirectory(
    from: String,
    candidates: Sequence<String>,
    sortOrder: DirectorySortOrder,
): VideoDirectoryTarget? {
    for (candidate in candidates) {
        currentCoroutineContext().ensureActive()
        val media = remoteMedia(candidate, sortOrder)
        val name = DirectoryTraversal.displayName(RemoteDirectoryTree, from, candidate)
        media.videos().firstOrNull()?.let { video ->
            return VideoDirectoryTarget(name, candidate, VideoDirectoryType.Video, video.path)
        }
        media.firstOrNull { it.type == MediaViewerType.Image }?.let { image ->
            return VideoDirectoryTarget(name, candidate, VideoDirectoryType.Image, image.path)
        }
    }
    return null
}

private suspend fun remoteMedia(directoryUrl: String, sortOrder: DirectorySortOrder): List<MediaViewerItem> =
    try {
        MediaCatalog.listRemoteDirectory(directoryUrl, sortOrder)
    } catch (_: IOException) {
        emptyList()
    }

private fun List<MediaViewerItem>.videos(): List<VideoItem> =
    filter { it.type == MediaViewerType.Video }.map { VideoItem(it.name, it.path) }

private fun File.listImages(sortOrder: DirectorySortOrder): List<File> = listFiles()
    ?.asSequence()
    ?.filter(File::isFile)
    ?.filter { MediaTypes.isImage(it.name) }
    ?.sortedWith(sortOrder.fileComparator(directoriesFirst = false))
    ?.toList()
    .orEmpty()

private fun formatVideoTime(millis: Long): String {
    val totalSeconds = millis.coerceAtLeast(0L) / 1_000
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    else String.format(Locale.US, "%d:%02d", minutes, seconds)
}

private fun formatSpeed(speed: Float): String = "${speed}x"

private data class VideoItem(val name: String, val path: String)
private data class VideoDirectory(
    val videos: List<VideoItem>,
    val previousDirectory: VideoDirectoryTarget?,
    val nextDirectory: VideoDirectoryTarget?,
)
private data class VideoDirectoryTarget(
    val name: String,
    val path: String,
    val type: VideoDirectoryType,
    val selectedMediaPath: String,
)

private enum class VideoDirectoryDirection {
    Previous,
    Next,
}

private fun VideoDirectoryDirection.titleStringRes(isArchive: Boolean): Int = when (this) {
    VideoDirectoryDirection.Previous -> if (isArchive) R.string.previous_archive else R.string.previous_directory
    VideoDirectoryDirection.Next -> if (isArchive) R.string.next_archive else R.string.next_directory
}

private fun VideoDirectoryDirection.moveStringRes(isArchive: Boolean): Int = when (this) {
    VideoDirectoryDirection.Previous -> if (isArchive) R.string.move_previous_archive else R.string.move_previous_directory
    VideoDirectoryDirection.Next -> if (isArchive) R.string.move_next_archive else R.string.move_next_directory
}

private enum class VideoDirectoryType { Video, Image }

private val PLAYBACK_SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
private val PLAYER_OPTION_WIDTH = 76.dp
private const val SHORT_SEEK_MILLIS = 2_000L
private const val DOUBLE_TAP_SEEK_MILLIS = 10_000L
private const val MAX_SWIPE_SEEK_MILLIS = 300_000L
private const val ACTIVE_ERROR_RELOAD_ATTEMPTS = 2
private const val ACTIVE_ERROR_RELOAD_DELAY_MILLIS = 150L
