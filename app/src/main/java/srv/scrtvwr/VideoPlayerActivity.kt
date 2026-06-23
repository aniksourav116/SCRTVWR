package srv.scrtvwr

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Folder
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import androidx.media3.ui.PlayerView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
            )
        }
    }
    val videos = loadedDirectory?.videos.orEmpty()
    val nextDirectory = loadedDirectory?.nextDirectory
    var repeatMode by rememberSaveable { mutableStateOf(VideoRepeatMode.None) }
    val initialPage = remember(videos, selectedVideoPath) {
        videos.indexOfFirst { it.path == selectedVideoPath }.coerceAtLeast(0)
    }
    val pagerState = rememberPagerState(
        initialPage = initialPage,
        pageCount = { videos.size + if (nextDirectory != null) 1 else 0 },
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
                videos.getOrNull(page)?.let { video ->
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
                val item = videos.getOrNull(page)
                if (item != null) {
                    VideoPage(
                        item = item,
                        page = page,
                        pageCount = videos.size,
                        isActive = page == pagerState.currentPage,
                        repeatMode = repeatMode,
                        onRepeatModeChange = { repeatMode = it },
                        onBack = onBack,
                        onEnded = {
                            if (repeatMode == VideoRepeatMode.All) {
                                scope.launch {
                                    pagerState.animateScrollToPage((page + 1) % videos.size)
                                }
                            }
                        },
                    )
                } else if (item != null) {
                    Box(Modifier.fillMaxSize().background(Color.Black))
                } else if (nextDirectory != null) {
                    NextVideoDirectoryPage(
                        directory = nextDirectory,
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
    onEnded: () -> Unit,
) {
    val context = LocalContext.current
    var savedPlaybackPosition by rememberSaveable(item.path) { mutableLongStateOf(0L) }
    val player = remember(item.path) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(File(item.path))))
            if (savedPlaybackPosition > 0L) seekTo(savedPlaybackPosition)
            playWhenReady = isActive
            prepare()
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
    var speedMenuOpen by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(value: Boolean) {
                isPlaying = value
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) duration = player.duration.coerceAtLeast(0L)
                if (state == Player.STATE_ENDED) onEnded()
            }

            override fun onPlayerError(playbackError: PlaybackException) {
                error = playbackError.localizedMessage ?: playbackError.errorCodeName
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            savedPlaybackPosition = player.currentPosition.coerceAtLeast(0L)
            player.release()
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { player.pause() }

    LaunchedEffect(isActive, player) {
        if (isActive) {
            player.playWhenReady = true
            if (player.playbackState != Player.STATE_ENDED) player.play()
        } else {
            player.pause()
        }
    }

    LaunchedEffect(player) {
        while (true) {
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
                }
            },
            update = { it.player = player },
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
            Text(
                stringResource(R.string.video_playback_error, message),
                color = Color.White,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
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
                onBack = onBack,
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
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.55f)).padding(top = 24.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = Color.White)
        }
        Text(item.name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(stringResource(R.string.video_count, page + 1, pageCount), color = Color.White)
    }

    Column(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.7f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Slider(
            value = position.coerceAtMost(duration).toFloat(),
            onValueChange = { player.seekTo(it.toLong()) },
            valueRange = 0f..duration.coerceAtLeast(1L).toFloat(),
            enabled = duration > 0,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(onClick = {
                if (player.isPlaying) player.pause() else {
                    if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                    player.play()
                }
            }) {
                Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, stringResource(if (isPlaying) R.string.pause_video else R.string.play_video), tint = Color.White)
            }
            IconButton(onClick = { player.seekTo((player.currentPosition - SHORT_SEEK_MILLIS).coerceAtLeast(0L)) }) {
                Icon(Icons.Default.Replay, stringResource(R.string.seek_back_2_seconds), tint = Color.White)
            }
            IconButton(onClick = { player.seekTo((player.currentPosition + SHORT_SEEK_MILLIS).coerceAtMost(duration)) }) {
                Icon(Icons.AutoMirrored.Filled.Forward, stringResource(R.string.seek_forward_2_seconds), tint = Color.White)
            }
            IconButton(onClick = { onRepeatModeChange(repeatMode.next()) }) {
                Icon(Icons.Default.Repeat, repeatMode.label(), tint = if (repeatMode == VideoRepeatMode.None) Color.White else MaterialTheme.colorScheme.primary)
            }
            Text("${formatVideoTime(position)} / ${formatVideoTime(duration)}", color = Color.White, style = MaterialTheme.typography.labelMedium)
            Box {
                TextButton(onClick = { onSpeedMenuChange(true) }) {
                    Icon(Icons.Default.Speed, stringResource(R.string.playback_speed), tint = Color.White)
                    Text(" ${formatSpeed(speed)}", color = Color.White)
                }
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
        }
        Text(repeatMode.label(), color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
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
private fun NextVideoDirectoryPage(
    directory: VideoDirectoryTarget,
    onMove: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
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
            )
            Text(
                stringResource(R.string.next_directory),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                directory.name,
                color = Color.White.copy(alpha = 0.7f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Button(onClick = onMove) {
                Text(stringResource(R.string.move_next_directory))
            }
        }
    }
}

private fun loadVideoDirectory(
    directoryPath: String?,
    selectedPath: String?,
    sortOrder: DirectorySortOrder,
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
        nextDirectory = directory?.findNextMediaDirectory(sortOrder),
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

private fun File.findNextMediaDirectory(sortOrder: DirectorySortOrder): VideoDirectoryTarget? {
    val currentPath = runCatching { canonicalPath }.getOrElse { absolutePath }
    return parentFile
        ?.listFiles()
        ?.asSequence()
        ?.filter(File::isDirectory)
        ?.sortedWith(sortOrder.fileComparator(directoriesFirst = false))
        ?.dropWhile { candidate ->
            runCatching { candidate.canonicalPath }.getOrElse { candidate.absolutePath } != currentPath
        }
        ?.drop(1)
        ?.mapNotNull { candidate ->
            val videos = candidate.listVideos(sortOrder)
            if (videos.isNotEmpty()) {
                VideoDirectoryTarget(
                    name = candidate.name.ifBlank { candidate.absolutePath },
                    path = candidate.absolutePath,
                    type = VideoDirectoryType.Video,
                    selectedMediaPath = videos.first().path,
                )
            } else {
                candidate.listImages(sortOrder).firstOrNull()?.let { image ->
                    VideoDirectoryTarget(
                        name = candidate.name.ifBlank { candidate.absolutePath },
                        path = candidate.absolutePath,
                        type = VideoDirectoryType.Image,
                        selectedMediaPath = image.absolutePath,
                    )
                }
            }
        }
        ?.firstOrNull()
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
private data class VideoDirectory(val videos: List<VideoItem>, val nextDirectory: VideoDirectoryTarget?)
private data class VideoDirectoryTarget(
    val name: String,
    val path: String,
    val type: VideoDirectoryType,
    val selectedMediaPath: String,
)

private enum class VideoRepeatMode { None, One, All }
private enum class VideoDirectoryType { Video, Image }

private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "webm", "3gp", "mov", "avi", "m4v")
private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")
private val PLAYBACK_SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
private const val SHORT_SEEK_MILLIS = 2_000L
private const val DOUBLE_TAP_SEEK_MILLIS = 10_000L
private const val MAX_SWIPE_SEEK_MILLIS = 300_000L
