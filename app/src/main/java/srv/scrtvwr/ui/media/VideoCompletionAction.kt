package srv.scrtvwr.ui.media

/**
 * What the video player does once the playing video reaches its end.
 *
 * Looping and auto-advancing are values of a single setting rather than independent toggles, so
 * the player can never be told to repeat and to move on at the same time: exactly one action is
 * in effect, and [RepeatAll] is simply [PlayNext] that wraps instead of stopping.
 */
enum class VideoCompletionAction {
    /** Hold on the last frame and wait for the viewer. */
    Stop,

    /** Play the finished video again. */
    RepeatOne,

    /** Move to the next video, stopping once the last one in the directory finishes. */
    PlayNext,

    /** Move to the next video, wrapping back to the first one after the last. */
    RepeatAll,
}

/** True when the player loops the video itself; every other action is carried out by the pager. */
val VideoCompletionAction.loopsCurrentVideo: Boolean
    get() = when (this) {
        VideoCompletionAction.RepeatOne -> true
        VideoCompletionAction.Stop,
        VideoCompletionAction.PlayNext,
        VideoCompletionAction.RepeatAll -> false
    }

/** True when finishing a video hands playback over to the one after it. */
val VideoCompletionAction.advancesToNextVideo: Boolean
    get() = when (this) {
        VideoCompletionAction.PlayNext,
        VideoCompletionAction.RepeatAll -> true
        VideoCompletionAction.Stop,
        VideoCompletionAction.RepeatOne -> false
    }

/** True when advancing past the last video wraps around to the first one. */
val VideoCompletionAction.wrapsAtDirectoryEnd: Boolean
    get() = when (this) {
        VideoCompletionAction.RepeatAll -> true
        VideoCompletionAction.Stop,
        VideoCompletionAction.RepeatOne,
        VideoCompletionAction.PlayNext -> false
    }

/**
 * Index of the video to play once the one at [videoIndex] finishes, or null when playback should
 * stay on the finished video.
 *
 * [VideoCompletionAction.RepeatOne] returns null because the player repeats that video on its own
 * and so never reports the video as ended.
 */
fun VideoCompletionAction.nextVideoIndex(videoIndex: Int, videoCount: Int): Int? {
    if (!advancesToNextVideo || videoIndex !in 0 until videoCount) return null
    val nextIndex = videoIndex + 1
    return when {
        nextIndex < videoCount -> nextIndex
        wrapsAtDirectoryEnd -> 0
        else -> null
    }
}
