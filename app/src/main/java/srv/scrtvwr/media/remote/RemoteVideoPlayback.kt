package srv.scrtvwr.media.remote

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

/** Direct HTTP playback: no downloaded video file or persistent video cache. */
@OptIn(UnstableApi::class)
object RemoteVideoPlayback {
    fun player(context: Context): ExoPlayer {
        val http = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(HttpDirectoryClient.CONNECT_TIMEOUT_MILLIS)
            .setReadTimeoutMs(HttpDirectoryClient.READ_TIMEOUT_MILLIS)
            .setDefaultRequestProperties(mapOf("Cache-Control" to "no-store"))
        // Looked up per request, since a playlist can move between servers.
        val authenticated = ResolvingDataSource.Factory(http) { dataSpec ->
            HttpServerAuth.authorizationFor(dataSpec.uri.toString())
                ?.let { dataSpec.withAdditionalHeaders(mapOf("Authorization" to it)) }
                ?: dataSpec
        }
        val sources = DefaultMediaSourceFactory(DefaultDataSource.Factory(context, authenticated))
        // The target bounds buffering independently of the video's total size. Consumed data is
        // discarded; stop/release frees the buffers when leaving playback. No disk cache is used.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(5_000, 30_000, 1_000, 2_000)
            .setTargetBufferBytes(32 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .setBackBuffer(0, false)
            .build()
        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(sources)
            .setLoadControl(loadControl)
            .build()
    }
}
