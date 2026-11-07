package srv.scrtvwr.media

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CacheAccessTest {
    @Test
    fun maintenanceWaitsForReadersAndBlocksNewReadsUntilItFinishes() = runBlocking {
        val access = CacheAccess()
        val releaseReader = CompletableDeferred<Unit>()
        val releaseMaintenance = CompletableDeferred<Unit>()
        val reader = async(start = CoroutineStart.UNDISPATCHED) { access.read { releaseReader.await() } }
        val maintenance = async(start = CoroutineStart.UNDISPATCHED) {
            access.exclusive { releaseMaintenance.await() }
        }
        val lateReader = async(start = CoroutineStart.UNDISPATCHED) { access.read { true } }
        assertFalse(maintenance.isCompleted)
        assertFalse(lateReader.isCompleted)
        releaseReader.complete(Unit)
        reader.await()
        assertFalse(lateReader.isCompleted)
        releaseMaintenance.complete(Unit)
        maintenance.await()
        assertTrue(lateReader.await())
    }

    @Test
    fun cancellingWaitingMaintenanceReleasesAllItsAcquiredPermits() = runBlocking {
        val access = CacheAccess()
        val releaseReader = CompletableDeferred<Unit>()
        val reader = async(start = CoroutineStart.UNDISPATCHED) { access.read { releaseReader.await() } }
        val cancelled = async(start = CoroutineStart.UNDISPATCHED) { access.exclusive { error("Should wait") } }
        cancelled.cancelAndJoin()
        releaseReader.complete(Unit)
        reader.await()
        assertTrue(access.exclusive { true })
    }
}
