package srv.scrtvwr.media.remote

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

/** Direct HTTP playback: no downloaded video file or persistent video cache. */
@OptIn(UnstableApi::class)
object RemoteVideoPlayback {
    fun player(context: Context, loadControl: LoadControl = loadControl()): ExoPlayer {
        val http = DataSource.Factory { SafeMediaDataSource() }
        val sources = DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http))
        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(sources)
            .setLoadControl(loadControl)
            .build()
    }

    /**
     * Buffering for server playback. The target bounds buffering independently of the video's
     * total size. Consumed data is discarded; stop/release frees the buffers when leaving playback.
     * No disk cache is used.
     */
    fun loadControl(): LoadControl =
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(5_000, 30_000, 1_000, 2_000)
            .setTargetBufferBytes(32 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .setBackBuffer(0, false)
            .build()
}
