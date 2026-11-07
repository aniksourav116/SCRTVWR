package srv.scrtvwr.media.remote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class RemoteMediaStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val url = "http://host:8000/Photos/a.jpg"

    @Test
    fun file_fetchesOnceAndThenServesTheCopy() = runBlocking {
        val store = store()

        val copy = store.file(url, IMAGE) { it.writeText("picture") }
        val again = store.file(url, IMAGE) { fail("A stored copy is fetched again") }

        assertEquals(copy, again)
        assertEquals("picture", again.readText())
        assertTrue(store.contains(url, IMAGE))
        assertTrue(store.name(url, IMAGE) in store.names.value)
    }

    @Test
    fun file_callersAskingAtOnceShareOneFetch() = runBlocking {
        val store = store()
        val gate = CompletableDeferred<Unit>()
        val fetches = AtomicInteger()

        val first = async(Dispatchers.Default) {
            store.file(url, IMAGE) {
                fetches.incrementAndGet()
                gate.await()
                it.writeText("first")
            }
        }
        while (fetches.get() == 0) delay(1)
        val second = async(Dispatchers.Default) {
            store.file(url, IMAGE) {
                fetches.incrementAndGet()
                it.writeText("second")
            }
        }
        delay(50)
        gate.complete(Unit)

        assertEquals(first.await(), second.await())
        assertEquals("first", second.await().readText())
        assertEquals(1, fetches.get())
    }

    @Test
    fun file_aWaiterFetchesItselfWhenTheFetchItWaitedForFails() = runBlocking {
        val store = store()
        val gate = CompletableDeferred<Unit>()
        val fetches = AtomicInteger()

        val first = async(Dispatchers.Default) {
            runCatching {
                store.file(url, IMAGE) {
                    fetches.incrementAndGet()
                    gate.await()
                    throw IOException("Server went away")
                }
            }
        }
        while (fetches.get() == 0) delay(1)
        val second = async(Dispatchers.Default) {
            store.file(url, IMAGE) {
                fetches.incrementAndGet()
                it.writeText("second")
            }
        }
        delay(50)
        gate.complete(Unit)

        assertTrue(first.await().exceptionOrNull() is IOException)
        assertEquals("second", second.await().readText())
        assertEquals(2, fetches.get())
    }

    @Test
    fun file_aFailedFetchLeavesNoCopyAndNoPartialFile() = runBlocking {
        val store = store()

        val result = runCatching {
            store.file(url, IMAGE) {
                it.writeText("half")
                throw IOException("Download ended early")
            }
        }

        assertTrue(result.exceptionOrNull() is IOException)
        assertFalse(store.contains(url, IMAGE))
        assertEquals(0, partials.listFiles().orEmpty().size)
        assertEquals(0, copies.listFiles().orEmpty().size)
    }

    @Test
    fun existing_isNullWhenNothingIsStoredOrFetching() = runBlocking {
        assertNull(store().existing(url, IMAGE))
    }

    @Test
    fun existing_returnsTheStoredCopy() = runBlocking {
        val store = store()
        val copy = store.file(url, FRAME) { it.writeText("frame") }

        assertEquals(copy, store.existing(url, FRAME))
    }

    @Test
    fun forget_dropsEveryVariantOfTheUrlsOnly() = runBlocking {
        val store = store()
        val other = "http://host:8000/Photos/b.jpg"
        store.file(url, IMAGE) { it.writeText("image") }
        store.file(url, FRAME) { it.writeText("frame") }
        store.file(other, IMAGE) { it.writeText("other") }

        store.forget(listOf(url))

        assertFalse(store.contains(url, IMAGE))
        assertFalse(store.contains(url, FRAME))
        assertTrue(store.contains(other, IMAGE))
        assertEquals(1, copies.listFiles().orEmpty().size)
    }

    @Test
    fun file_dropsTheLeastRecentlyUsedCopiesBeyondTheCap() = runBlocking {
        val store = store(maxBytes = 10)
        val other = "http://host:8000/Photos/b.jpg"
        store.file(url, IMAGE) { it.writeText("123456") }

        store.file(other, IMAGE) { it.writeText("abcdef") }

        assertFalse(store.contains(url, IMAGE))
        assertTrue(store.contains(other, IMAGE))
    }

    @Test
    fun cachedFile_forgetsACopyRemovedFromTheDisk() = runBlocking {
        val store = store()
        store.file(url, IMAGE) { it.writeText("image") }.delete()

        assertNull(store.cachedFile(url, IMAGE))
        assertFalse(store.contains(url, IMAGE))
    }

    @Test
    fun remove_dropsOneCopy() = runBlocking {
        val store = store()
        store.file(url, IMAGE) { it.writeText("image") }
        store.file(url, FRAME) { it.writeText("frame") }

        store.remove(url, IMAGE)

        assertFalse(store.contains(url, IMAGE))
        assertTrue(store.contains(url, FRAME))
    }

    @Test
    fun cachedFile_aGlanceDoesNotMarkTheCopyUsed() = runBlocking {
        val store = store()
        val copy = store.file(url, IMAGE) { it.writeText("image") }
        copy.setLastModified(1_000_000L)

        store.cachedFile(url, IMAGE, touch = false)
        assertEquals(1_000_000L, copy.lastModified())

        store.cachedFile(url, IMAGE)
        assertTrue(copy.lastModified() > 1_000_000L)
    }

    @Test
    fun name_startsWithTheUrlsHashSoEveryVariantSharesIt() {
        val store = store()

        val image = store.name(url, IMAGE)
        val frame = store.name(url, FRAME)

        assertTrue(image, image.matches(Regex("[0-9a-f]{16}-[0-9a-f]{32}")))
        assertEquals(image.substringBefore('-'), frame.substringBefore('-'))
        assertFalse(image == frame)
    }

    @Test
    fun reducingLimitEvictsOlderCopiesAndKeepsTheMostRecentOversizedFile() = runBlocking {
        val store = store()
        val older = store.file(url, IMAGE) { it.writeText("123456") }
        older.setLastModified(1_000_000L)
        val other = "http://host:8000/Photos/b.jpg"
        val newest = store.file(other, IMAGE) { it.writeText("abcdef") }

        store.setMaxBytes(4)

        assertFalse(older.exists())
        assertFalse(store.contains(url, IMAGE))
        assertTrue(newest.exists())
        assertTrue(store.contains(other, IMAGE))
    }

    @Test
    fun clearRemovesCompletedCopiesAndUpdatesTheirAvailability() = runBlocking {
        val store = store()
        store.file(url, IMAGE) { it.writeText("image") }
        store.file(url, FRAME) { it.writeText("frame") }

        assertTrue(store.clear())

        assertTrue(store.names.value.isEmpty())
        assertTrue(copies.listFiles().orEmpty().isEmpty())
    }

    private val copies: File
        get() = File(temporaryFolder.root, "copies")

    private val partials: File
        get() = File(temporaryFolder.root, "partials")

    private fun store(maxBytes: Long = 1_000_000): RemoteMediaStore =
        RemoteMediaStore(maxBytes).apply { initialize(copies, partialDirectory = partials) }

    private companion object {
        const val IMAGE = "image"
        const val FRAME = "frame-1600"
    }
}
