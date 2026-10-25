package srv.scrtvwr.media

import androidx.annotation.OptIn
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator

/**
 * [delegate], except that while [preBuffering] it stops loading once [preBufferUs] of media are
 * buffered, so a video prepared ahead of being shown costs only its first seconds. Clearing the
 * flag lets the player load as usual on its next check.
 */
@OptIn(UnstableApi::class)
class PreBufferLoadControl(
    private val delegate: LoadControl,
    private val preBufferUs: Long = DEFAULT_PRE_BUFFER_US,
) : LoadControl {
    @Volatile var preBuffering: Boolean = false

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean =
        if (preBuffering && parameters.bufferedDurationUs >= preBufferUs) {
            false
        } else {
            delegate.shouldContinueLoading(parameters)
        }

    override fun onPrepared(playerId: PlayerId) = delegate.onPrepared(playerId)

    override fun onTracksSelected(
        parameters: LoadControl.Parameters,
        trackGroups: TrackGroupArray,
        trackSelections: Array<ExoTrackSelection?>,
    ) = delegate.onTracksSelected(parameters, trackGroups, trackSelections)

    override fun onStopped(playerId: PlayerId) = delegate.onStopped(playerId)

    override fun onReleased(playerId: PlayerId) = delegate.onReleased(playerId)

    override fun getAllocator(playerId: PlayerId): Allocator = delegate.getAllocator(playerId)

    override fun getBackBufferDurationUs(playerId: PlayerId): Long = delegate.getBackBufferDurationUs(playerId)

    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean =
        delegate.retainBackBufferFromKeyframe(playerId)

    override fun shouldContinuePreloading(
        playerId: PlayerId,
        timeline: Timeline,
        mediaPeriodId: MediaSource.MediaPeriodId,
        bufferedDurationUs: Long,
    ): Boolean = delegate.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs)

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean =
        delegate.shouldStartPlayback(parameters)

    companion object {
        /** A few seconds: enough to show the first frame and start without waiting. */
        const val DEFAULT_PRE_BUFFER_US = 3_000_000L
    }
}
