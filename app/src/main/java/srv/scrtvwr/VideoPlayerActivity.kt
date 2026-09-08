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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Speed
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import srv.scrtvwr.media.DirectoryTraversal
import srv.scrtvwr.ui.theme.SCRTVWRTheme
import srv.scrtvwr.preferences.ScrtvwrPreferences
import srv.scrtvwr.ui.sort.DirectorySortOrder
import srv.scrtvwr.ui.sort.fileComparator
import java.io.File
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
        mutableStateOf(directoryPath ?: selectedPath?.let(::File)?.parent)
    }
    var selectedVideoPath by rememberSaveable(directoryPath, selectedPath) {
        mutableStateOf(selectedPath)
    }
    val loadedDirectory by produceState<VideoDirectory?>(initialValue = null, currentDirectoryPath, selectedPath) {
        value = withContext(Dispatchers.IO) {
            loadVideoDirectory(
                directoryPath = currentDirectoryPath,
                selectedPath = selectedPath.takeIf { currentDirectoryPath == directoryPath },
                sortOrder = sortOrder,
                traversalRoot = traversalRoot,
            )
        }
    }
    val videos = loadedDirectory?.videos.orEmpty()
    val previousDirectory = loadedDirectory?.previousDirectory
    val nextDirectory = loadedDirectory?.nextDirectory
    val firstVideoPageIndex = if (previousDirectory != null) 1 else 0
    var repeatMode by rememberSaveable { mutableStateOf(VideoRepeatMode.None) }
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
            ) { page ->
                val videoIndex = page - firstVideoPageIndex
                val item = videos.getOrNull(videoIndex)
                if (item != null) {
                    VideoPage(
                        item = item,
                        page = videoIndex,
                        pageCount = videos.size,
                        isActive = page == pagerState.currentPage,
                        repeatMode = repeatMode,
                        onRepeatModeChange = { repeatMode = it },
                        onBack = onBack,
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
                            if (repeatMode == VideoRepeatMode.All) {
                                scope.launch {
                                    val nextVideoPage = if (videoIndex == videos.lastIndex) {
                                        firstVideoPageIndex
                                    } else {
                                        page + 1
                                    }
                                    pagerState.animateScrollToPage(nextVideoPage)
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
    repeatMode: VideoRepeatMode,
    onRepeatModeChange: (VideoRepeatMode) -> Unit,
    onBack: () -> Unit,
    onMoveToFirst: () -> Unit,
    onMoveToLast: () -> Unit,
    onEnded: () -> Unit,
) {
    val context = LocalContext.current
    var savedPlaybackPosition by rememberSaveable(item.path) { mutableLongStateOf(0L) }
    val mediaItem = remember(item.path) { MediaItem.fromUri(Uri.fromFile(File(item.path))) }
    val player = remember(item.path) {
        ExoPlayer.Builder(context).build().apply {
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
    var controlsExpanded by rememberSaveable(item.path) { mutableStateOf(false) }
    var speedMenuOpen by remember { mutableStateOf(false) }
    var optionsMenuOpen by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var prepareRequested by remember { mutableStateOf(false) }
    var reloadWhenActive by remember { mutableStateOf(false) }
    var activeReloadAttempts by remember(item.path) { mutableStateOf(0) }
    val latestIsActive by rememberUpdatedState(isActive)

    fun saveCurrentPosition() {
        if (prepareRequested || player.playbackState != Player.STATE_IDLE) {
            val currentPosition = player.currentPosition.coerceAtLeast(0L)
            savedPlaybackPosition = currentPosition
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

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(value: Boolean) {
                isPlaying = value
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    duration = player.duration.coerceAtLeast(0L)
                    error = null
                    reloadWhenActive = false
                    activeReloadAttempts = 0
                }
                if (state == Player.STATE_ENDED) onEnded()
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
            savedPlaybackPosition = position
            if (player.duration > 0) duration = player.duration
            delay(250)
        }
    }

    LaunchedEffect(repeatMode) {
        player.repeatMode = if (repeatMode == VideoRepeatMode.One) {
            Player.REPEAT_MODE_ONE
        } else {
            Player.REPEAT_MODE_OFF
        }
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
                .pointerInput(player, duration) {
                    detectTapGestures(
                        onTap = { controlsVisible = !controlsVisible },
                        onDoubleTap = { offset ->
                            val delta = if (offset.x < size.width / 2f) -DOUBLE_TAP_SEEK_MILLIS else DOUBLE_TAP_SEEK_MILLIS
                            player.seekTo((player.currentPosition + delta).coerceIn(0L, duration.coerceAtLeast(0L)))
                        },
                    )
                }
                .pointerInput(player, duration) {
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
                            dragTargetPosition = (dragStartPosition + delta).coerceIn(0L, duration.coerceAtLeast(0L))
                        },
                        onDragEnd = {
                            dragTargetPosition?.let(player::seekTo)
                            dragTargetPosition = null
                        },
                        onDragCancel = { dragTargetPosition = null },
                    )
                },
        )

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
            VideoControls(
                item = item,
                page = page,
                pageCount = pageCount,
                player = player,
                isPlaying = isPlaying,
                position = position,
                duration = duration,
                repeatMode = repeatMode,
                onRepeatModeChange = onRepeatModeChange,
                speed = speed,
                onSpeedChange = { speed = it },
                speedMenuOpen = speedMenuOpen,
                onSpeedMenuChange = { speedMenuOpen = it },
                resizeMode = resizeMode,
                onResizeModeChange = { resizeMode = it },
                controlsExpanded = controlsExpanded,
                onControlsExpandedChange = { controlsExpanded = it },
                optionsMenuOpen = optionsMenuOpen,
                onOptionsMenuChange = { optionsMenuOpen = it },
                onBack = onBack,
                onMoveToFirst = onMoveToFirst,
                onMoveToLast = onMoveToLast,
            )
        }
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
    repeatMode: VideoRepeatMode,
    onRepeatModeChange: (VideoRepeatMode) -> Unit,
    speed: Float,
    onSpeedChange: (Float) -> Unit,
    speedMenuOpen: Boolean,
    onSpeedMenuChange: (Boolean) -> Unit,
    resizeMode: Int,
    onResizeModeChange: (Int) -> Unit,
    controlsExpanded: Boolean,
    onControlsExpandedChange: (Boolean) -> Unit,
    optionsMenuOpen: Boolean,
    onOptionsMenuChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onMoveToFirst: () -> Unit,
    onMoveToLast: () -> Unit,
) {
    // Top bar: compact, scrim-gradient chrome so it reads as an overlay rather than a solid panel.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent),
                ),
            )
            .padding(top = 18.dp, bottom = 14.dp, start = 4.dp, end = 8.dp),
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
        Box {
            IconButton(onClick = { onOptionsMenuChange(true) }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.MoreVert, stringResource(R.string.more_options), tint = Color.White)
            }
            DropdownMenu(expanded = optionsMenuOpen, onDismissRequest = { onOptionsMenuChange(false) }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.move_first_item)) },
                    enabled = pageCount > 0 && page != 0,
                    onClick = {
                        onMoveToFirst()
                        onOptionsMenuChange(false)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.move_last_item)) },
                    enabled = pageCount > 0 && page != pageCount - 1,
                    onClick = {
                        onMoveToLast()
                        onOptionsMenuChange(false)
                    },
                )
            }
        }
    }

    // Bottom bar: scrim-gradient overlay with a compact scrubber, primary transport controls,
    // and a collapsible panel for secondary functions (repeat, speed, aspect ratio).
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
        AnimatedVisibility(
            visible = controlsExpanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ControlChip(
                    icon = Icons.Default.Repeat,
                    label = repeatMode.label(),
                    active = repeatMode != VideoRepeatMode.None,
                    onClick = { onRepeatModeChange(repeatMode.next()) },
                )
                Box {
                    ControlChip(
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
                ControlChip(
                    icon = resizeMode.icon(),
                    label = stringResource(resizeMode.labelRes()),
                    active = resizeMode != AspectRatioFrameLayout.RESIZE_MODE_FIT,
                    onClick = { onResizeModeChange(resizeMode.next()) },
                )
            }
        }

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
            Spacer(modifier = Modifier.width(40.dp))
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { player.seekTo((player.currentPosition - SHORT_SEEK_MILLIS).coerceAtLeast(0L)) },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(Icons.Default.Replay, stringResource(R.string.seek_back_2_seconds), tint = Color.White)
                }
                IconButton(
                    onClick = {
                        if (player.isPlaying) player.pause() else {
                            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
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
                        tint = Color.White,
                        modifier = Modifier.size(30.dp),
                    )
                }
                IconButton(
                    onClick = { player.seekTo((player.currentPosition + SHORT_SEEK_MILLIS).coerceAtMost(duration)) },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Forward, stringResource(R.string.seek_forward_2_seconds), tint = Color.White)
                }
            }
            IconButton(
                onClick = { onControlsExpandedChange(!controlsExpanded) },
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    if (controlsExpanded) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                    stringResource(if (controlsExpanded) R.string.hide_more_controls else R.string.more_controls),
                    tint = Color.White,
                )
            }
        }
    }
}

@Composable
private fun ControlChip(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    contentDescription: String? = null,
) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        modifier = Modifier.background(
            if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.12f),
            RoundedCornerShape(50),
        ),
    ) {
        Icon(
            icon,
            contentDescription = contentDescription ?: label,
            tint = if (active) MaterialTheme.colorScheme.primary else Color.White,
            modifier = Modifier.size(18.dp),
        )
        Text(
            label,
            color = if (active) MaterialTheme.colorScheme.primary else Color.White,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

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
private fun VideoRepeatMode.label(): String = stringResource(
    when (this) {
        VideoRepeatMode.None -> R.string.repeat_none
        VideoRepeatMode.One -> R.string.repeat_one
        VideoRepeatMode.All -> R.string.repeat_all
    },
)

private fun VideoRepeatMode.next(): VideoRepeatMode = when (this) {
    VideoRepeatMode.None -> VideoRepeatMode.One
    VideoRepeatMode.One -> VideoRepeatMode.All
    VideoRepeatMode.All -> VideoRepeatMode.None
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
        ?.filter { it.extension.lowercase() in VIDEO_EXTENSIONS }
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

private fun File.listImages(sortOrder: DirectorySortOrder): List<File> = listFiles()
    ?.asSequence()
    ?.filter(File::isFile)
    ?.filter { it.extension.lowercase() in IMAGE_EXTENSIONS }
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

private enum class VideoRepeatMode { None, One, All }
private enum class VideoDirectoryType { Video, Image }

private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "webm", "3gp", "mov", "avi", "m4v")
private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")
private val PLAYBACK_SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
private const val SHORT_SEEK_MILLIS = 2_000L
private const val DOUBLE_TAP_SEEK_MILLIS = 10_000L
private const val MAX_SWIPE_SEEK_MILLIS = 300_000L
private const val ACTIVE_ERROR_RELOAD_ATTEMPTS = 2
private const val ACTIVE_ERROR_RELOAD_DELAY_MILLIS = 150L
