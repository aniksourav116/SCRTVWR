package srv.scrtvwr.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoFrameLoadMonitorTest {
    @Test
    fun record_warnsOnceEnoughRecentReadsAreHeavy() {
        val monitor = VideoFrameLoadMonitor(window = 6, heavyReads = 3, slowMillis = 4_000)

        assertFalse(monitor.record(elapsedMillis = 5_000, exhausted = false))
        assertFalse(monitor.record(elapsedMillis = 300, exhausted = false))
        assertFalse(monitor.record(elapsedMillis = 200, exhausted = true))
        assertTrue(monitor.record(elapsedMillis = 4_000, exhausted = false))
    }

    @Test
    fun record_forgetsHeavyReadsThatLeftTheWindow() {
        val monitor = VideoFrameLoadMonitor(window = 3, heavyReads = 2, slowMillis = 4_000)

        assertFalse(monitor.record(elapsedMillis = 9_000, exhausted = false))
        assertFalse(monitor.record(elapsedMillis = 100, exhausted = false))
        assertFalse(monitor.record(elapsedMillis = 100, exhausted = false))
        // The first slow read has dropped out, so one more is not enough.
        assertFalse(monitor.record(elapsedMillis = 9_000, exhausted = false))
        assertTrue(monitor.record(elapsedMillis = 9_000, exhausted = false))
    }

    @Test
    fun reset_startsTheCountOver() {
        val monitor = VideoFrameLoadMonitor(window = 6, heavyReads = 2, slowMillis = 4_000)
        monitor.record(elapsedMillis = 9_000, exhausted = false)

        monitor.reset()

        assertFalse(monitor.record(elapsedMillis = 9_000, exhausted = false))
    }
}
