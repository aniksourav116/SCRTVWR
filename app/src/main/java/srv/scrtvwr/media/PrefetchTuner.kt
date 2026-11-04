package srv.scrtvwr.media

import kotlin.math.ceil

/** What a prefetch reads from a server: a whole image, or a video's first frame. */
enum class PrefetchKind {
    Image,
    VideoFrame,
}

/**
 * Decides how far ahead a slide show fetches from a server, and how many fetches run at once,
 * from how fast fetches finish against how fast the show moves. Both start optimistic,
 * [initialDepth] pages ahead and [initialParallel] at once, and change as fetches finish and the
 * show reaches its pages.
 *
 * Depth follows the lead the show needs. A fetch has to start about as many pages ahead as the
 * show passes while it runs, so depth aims at [headroom] times the fetch time over the time per
 * page, plus the fetches running. Slower fetches or a faster show raise it at once. A page the
 * show reached before its fetch finished raises it by a quarter when fetching had run out of pages
 * to start; when every fetch was busy instead, the server is the limit, which a longer lead cannot
 * help, so depth stays. It falls one page at a time, and only while the show finds its pages
 * ready, so a burst of quick fetches cannot strip the cushion.
 * Depth never covers more than [budgetBytes] of fetched files, even below [minDepth], so pages
 * fetched ahead do not push each other out of storage before the show reaches them.
 *
 * Fetches at once rise by one after a run of quick fetches and halve on a slow or failed one, so
 * a struggling server gets fewer requests competing with the page on screen.
 */
internal class PrefetchTuner(
    private val initialDepth: Int = 32,
    private val minDepth: Int = 4,
    private val maxDepth: Int = 64,
    private val initialParallel: Int = 4,
    private val maxParallel: Int = 6,
    private val slowMillis: Long = 2_000L,
    // An image that takes long only counts as slow when its bytes also came in slowly, so a large
    // file over a fast network does not throttle the server.
    private val slowBytesPerSecond: Long = 1024L * 1024L,
    // Frame reads cost several requests each, so they get longer; see VideoFrameLoadMonitor.
    private val slowFrameMillis: Long = 4_000L,
    private val headroom: Double = 2.0,
    private val budgetBytes: Long = Long.MAX_VALUE,
    private val warmUpFetches: Int = 3,
) {
    private var depthValue = initialDepth.coerceIn(minDepth, maxDepth)
    private var parallelValue = initialParallel.coerceIn(1, maxParallel)
    private var finishedFetches = 0
    private var quickStreak = 0
    private var fetchMillis: Double? = null
    private val kindMillis = HashMap<PrefetchKind, Double>()
    private var fileBytes: Double? = null
    private var stayMillis = DEFAULT_STAY_MILLIS

    /** How many pages ahead of the show to fetch. */
    val depth: Int
        @Synchronized get() = depthValue.coerceAtMost(budgetDepth())

    /** How many fetches may run at once, beyond the page on screen. */
    val parallel: Int
        @Synchronized get() = parallelValue

    /** Time the show spends on a page that is ready when it gets there. */
    val paceMillis: Long
        @Synchronized get() = paceMillisValue().toLong()

    /** How long a fetch of [kind] is expected to take, for telling which page has to start first. */
    @Synchronized
    fun expectedMillis(kind: PrefetchKind): Long =
        (kindMillis[kind] ?: fetchMillis ?: DEFAULT_FETCH_MILLIS.getValue(kind)).toLong()

    /** Records how long the show stays on each page, the fastest it can go. */
    @Synchronized
    fun recordStay(stayMillis: Long) {
        if (stayMillis == this.stayMillis) return
        this.stayMillis = stayMillis.coerceAtLeast(0L)
        // A faster show needs a longer lead straight away; a slower one gives it back gradually.
        if (isWarmedUp()) depthValue = maxOf(depthValue, neededDepth())
    }

    /**
     * Records a fetch of [kind] that took [elapsedMillis] and stored [bytes], or a negative count
     * when unknown, or that [failed]. For a video frame the bytes are its stored copy, not what
     * was read, so they count toward storage only.
     */
    @Synchronized
    fun recordFetch(kind: PrefetchKind, elapsedMillis: Long, bytes: Long, failed: Boolean) {
        val elapsed = elapsedMillis.coerceAtLeast(1L)
        val slow = failed || when (kind) {
            PrefetchKind.Image -> elapsed >= slowMillis && (bytes <= 0L || bytes * 1_000L / elapsed < slowBytesPerSecond)
            PrefetchKind.VideoFrame -> elapsed >= slowFrameMillis
        }
        if (slow) {
            parallelValue = (parallelValue / 2).coerceAtLeast(1)
            quickStreak = 0
        } else if (++quickStreak >= parallelValue * 2) {
            parallelValue = (parallelValue + 1).coerceAtMost(maxParallel)
            quickStreak = 0
        }
        // A failure, often quick, says nothing about how long a fetch takes.
        if (failed) return
        finishedFetches++
        fetchMillis = smoothed(fetchMillis, elapsed.toDouble())
        kindMillis[kind] = smoothed(kindMillis[kind], elapsed.toDouble())
        if (bytes > 0L) fileBytes = smoothed(fileBytes, bytes.toDouble())
        if (isWarmedUp()) depthValue = maxOf(depthValue, neededDepth())
    }

    /**
     * Records the show reaching a page whose file was already fetched, when [ready], or was not.
     * [ranOutOfPages] says whether fetching, since the show last moved on, had every page within
     * depth fetched or under way with a fetch to spare, so depth was what held it back.
     */
    @Synchronized
    fun recordArrival(ready: Boolean, ranOutOfPages: Boolean = false) {
        depthValue = when {
            !ready && ranOutOfPages -> (depthValue + maxOf(MIN_DEPTH_STEP, depthValue / 4)).coerceAtMost(maxDepth)
            // Every fetch was busy: the server is the limit, and a longer lead would not help.
            !ready -> depthValue
            isWarmedUp() && depthValue > neededDepth() -> depthValue - 1
            else -> depthValue
        }
    }

    private fun isWarmedUp(): Boolean = finishedFetches >= warmUpFetches

    private fun neededDepth(): Int {
        val fetch = fetchMillis ?: return depthValue
        val lead = ceil(headroom * fetch / paceMillisValue()).toInt()
        return (lead + parallelValue).coerceIn(minDepth, maxDepth)
    }

    private fun paceMillisValue(): Double = (stayMillis + FRAME_MILLIS).toDouble()

    private fun budgetDepth(): Int {
        val bytes = fileBytes ?: return maxDepth
        // Below the usual minimum when files are huge: fetching past the budget would evict them.
        return (budgetBytes / bytes).toInt().coerceIn(1, maxDepth)
    }

    private fun smoothed(average: Double?, sample: Double): Double =
        average?.let { it + SMOOTHING * (sample - it) } ?: sample

    private companion object {
        const val SMOOTHING = 0.3
        const val MIN_DEPTH_STEP = 2
        // A page also needs a frame or so to render before its stay begins.
        const val FRAME_MILLIS = 16L
        const val DEFAULT_STAY_MILLIS = 100L
        val DEFAULT_FETCH_MILLIS = mapOf(PrefetchKind.Image to 800.0, PrefetchKind.VideoFrame to 1_500.0)
    }
}
