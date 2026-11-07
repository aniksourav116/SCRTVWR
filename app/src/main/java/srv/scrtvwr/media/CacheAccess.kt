package srv.scrtvwr.media

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/** Readers may suspend; maintenance waits for them without blocking a UI thread. */
internal class CacheAccess {
    private val permits = Semaphore(MAX_READERS)
    private val maintenance = Mutex()

    suspend fun <T> read(block: suspend () -> T): T = permits.withPermit { block() }

    suspend fun <T> exclusive(block: suspend () -> T): T = maintenance.withLock {
        var acquired = 0
        try {
            repeat(MAX_READERS) {
                permits.acquire()
                acquired++
            }
            block()
        } finally {
            repeat(acquired) { permits.release() }
        }
    }

    private companion object {
        const val MAX_READERS = 64
    }
}
