package srv.scrtvwr.ui.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoCompletionActionTest {
    @Test
    fun nextVideoIndex_stopAndRepeatOneStayOnTheFinishedVideo() {
        assertNull(VideoCompletionAction.Stop.nextVideoIndex(videoIndex = 0, videoCount = 3))
        assertNull(VideoCompletionAction.RepeatOne.nextVideoIndex(videoIndex = 0, videoCount = 3))
    }

    @Test
    fun nextVideoIndex_playNextAdvancesUntilTheLastVideo() {
        assertEquals(1, VideoCompletionAction.PlayNext.nextVideoIndex(videoIndex = 0, videoCount = 3))
        assertEquals(2, VideoCompletionAction.PlayNext.nextVideoIndex(videoIndex = 1, videoCount = 3))
        assertNull(VideoCompletionAction.PlayNext.nextVideoIndex(videoIndex = 2, videoCount = 3))
    }

    @Test
    fun nextVideoIndex_repeatAllWrapsAfterTheLastVideo() {
        assertEquals(1, VideoCompletionAction.RepeatAll.nextVideoIndex(videoIndex = 0, videoCount = 3))
        assertEquals(0, VideoCompletionAction.RepeatAll.nextVideoIndex(videoIndex = 2, videoCount = 3))
    }

    @Test
    fun nextVideoIndex_ignoresIndexesOutsideTheDirectory() {
        assertNull(VideoCompletionAction.RepeatAll.nextVideoIndex(videoIndex = 0, videoCount = 0))
        assertNull(VideoCompletionAction.PlayNext.nextVideoIndex(videoIndex = -1, videoCount = 3))
        assertNull(VideoCompletionAction.PlayNext.nextVideoIndex(videoIndex = 3, videoCount = 3))
    }

    @Test
    fun exactlyOneBehaviourAppliesPerAction() {
        VideoCompletionAction.entries.forEach { action ->
            assertEquals(
                "$action must not loop and advance at the same time",
                false,
                action.loopsCurrentVideo && action.advancesToNextVideo,
            )
        }
        assertEquals(
            listOf(false, true, false, false),
            VideoCompletionAction.entries.map { it.loopsCurrentVideo },
        )
        assertEquals(
            listOf(false, false, true, true),
            VideoCompletionAction.entries.map { it.advancesToNextVideo },
        )
        assertEquals(
            listOf(false, false, false, true),
            VideoCompletionAction.entries.map { it.wrapsAtDirectoryEnd },
        )
    }
}
