package srv.scrtvwr.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemotePreloadPaceTest {
    @Test
    fun record_throttlesOnceEnoughRecentDownloadsAreSlowOrFail() {
        val pace = RemotePreloadPace(slowMillis = 2_000, window = 4, slowToThrottle = 2, fastToRecover = 4)

        assertFalse(pace.record(elapsedMillis = 3_000, failed = false))
        assertFalse(pace.record(elapsedMillis = 300, failed = false))
        assertTrue(pace.record(elapsedMillis = 100, failed = true))
    }

    @Test
    fun record_recoversAfterEnoughFastDownloadsInARow() {
        val pace = RemotePreloadPace(slowMillis = 2_000, window = 4, slowToThrottle = 2, fastToRecover = 3)
        pace.record(elapsedMillis = 5_000, failed = false)
        pace.record(elapsedMillis = 5_000, failed = false)

        assertTrue(pace.record(elapsedMillis = 100, failed = false))
        assertTrue(pace.record(elapsedMillis = 100, failed = false))
        assertFalse(pace.record(elapsedMillis = 100, failed = false))
    }

    @Test
    fun record_aSlowDownloadRestartsTheRecoveryCount() {
        val pace = RemotePreloadPace(slowMillis = 2_000, window = 4, slowToThrottle = 2, fastToRecover = 2)
        pace.record(elapsedMillis = 5_000, failed = false)
        pace.record(elapsedMillis = 5_000, failed = false)

        assertTrue(pace.record(elapsedMillis = 100, failed = false))
        assertTrue(pace.record(elapsedMillis = 5_000, failed = false))
        assertTrue(pace.record(elapsedMillis = 100, failed = false))
        assertFalse(pace.record(elapsedMillis = 100, failed = false))
    }

    @Test
    fun record_forgetsSlowDownloadsThatLeftTheWindow() {
        val pace = RemotePreloadPace(slowMillis = 2_000, window = 3, slowToThrottle = 2, fastToRecover = 4)

        assertFalse(pace.record(elapsedMillis = 5_000, failed = false))
        assertFalse(pace.record(elapsedMillis = 100, failed = false))
        assertFalse(pace.record(elapsedMillis = 100, failed = false))
        // The first slow download has dropped out, so one more is not enough.
        assertFalse(pace.record(elapsedMillis = 5_000, failed = false))
    }
}
