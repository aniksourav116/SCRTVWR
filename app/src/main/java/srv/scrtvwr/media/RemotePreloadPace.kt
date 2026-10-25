package srv.scrtvwr.media

/**
 * Judges from recent server downloads whether the server is keeping up, so viewers can preload
 * fewer pages at a time from one that is not. It throttles once [slowToThrottle] of the last
 * [window] downloads were slow or failed, and recovers after [fastToRecover] fast ones in a row.
 */
internal class RemotePreloadPace(
    private val slowMillis: Long = 2_000L,
    private val window: Int = 4,
    private val slowToThrottle: Int = 2,
    private val fastToRecover: Int = 4,
) {
    private val recent = ArrayDeque<Boolean>()
    private var fastStreak = 0
    private var throttled = false

    /** Records a download that took [elapsedMillis], or [failed]; returns whether to throttle now. */
    @Synchronized
    fun record(elapsedMillis: Long, failed: Boolean): Boolean {
        val slow = failed || elapsedMillis >= slowMillis
        recent.addLast(slow)
        if (recent.size > window) recent.removeFirst()
        fastStreak = if (slow) 0 else fastStreak + 1
        if (!throttled && recent.count { it } >= slowToThrottle) {
            throttled = true
        } else if (throttled && fastStreak >= fastToRecover) {
            throttled = false
            recent.clear()
        }
        return throttled
    }
}
