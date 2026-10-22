package srv.scrtvwr.media

/**
 * Decides when reading video frames from servers has become a burden worth telling the user
 * about: when [heavyReads] of the last [window] reads were slow, ran out of time, or needed more
 * bytes than allowed. On a home network a frame normally arrives in well under a second.
 */
internal class VideoFrameLoadMonitor(
    private val window: Int = 6,
    private val heavyReads: Int = 3,
    val slowMillis: Long = 4_000L,
) {
    private val recent = ArrayDeque<Boolean>()

    /**
     * Records a read that took [elapsedMillis]; [exhausted] means it hit the time or byte limit.
     * Returns whether the recent reads now count as too heavy.
     */
    @Synchronized
    fun record(elapsedMillis: Long, exhausted: Boolean): Boolean {
        recent.addLast(exhausted || elapsedMillis >= slowMillis)
        if (recent.size > window) recent.removeFirst()
        return recent.count { it } >= heavyReads
    }

    @Synchronized
    fun reset() {
        recent.clear()
    }
}
