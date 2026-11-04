package srv.scrtvwr.ui.media

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import srv.scrtvwr.media.PrefetchKind
import srv.scrtvwr.media.PrefetchTuner

class SlideShowPrefetcherTest {
    private val copies = MutableStateFlow(emptySet<String>())
    private val started = mutableListOf<String>()

    @Test
    fun holdsFetchesWhileThePageOnScreenDownloadsByItselfAndGoesOnOnceItsCopyIsIn() = runBlocking {
        val prefetcher = prefetcher { item ->
            started += item.url
            awaitCancellation()
        }
        val show = launch { prefetcher.runShow(onPage(0, settled = false), isSettled = { false }) }

        delay(100)
        assertEquals(emptyList<String>(), started)

        // Its own download stored the copy; it still has to decode, but needs the server no more.
        copies.value = setOf("u0")
        waitFor { started.size == 2 }
        assertEquals(listOf("u1", "u2"), started)
        show.cancel()
    }

    @Test
    fun fetchesWhileThePageOnScreenOnlyDecodesItsCopy() = runBlocking {
        copies.value = setOf("u0")
        val prefetcher = prefetcher { item ->
            started += item.url
            awaitCancellation()
        }
        val show = launch { prefetcher.runShow(onPage(0, settled = false), isSettled = { false }) }

        waitFor { started.size == 2 }
        assertEquals(listOf("u1", "u2"), started)
        show.cancel()
    }

    @Test
    fun fetchesWhileThePageOnScreenWaitsForAFetchAlreadyRunning() = runBlocking {
        val finish = HashMap<String, CompletableDeferred<Long>>()
        val prefetcher = prefetcher { item ->
            started += item.url
            finish.getOrPut(item.url) { CompletableDeferred() }.await().also { copies.update { stored -> stored + item.url } }
        }
        val snapshots = onPage(0, settled = true)
        val show = launch { prefetcher.runShow(snapshots, isSettled = { it == 0 }) }
        waitFor { started.size == 2 }

        // The show reaches page 1, whose fetch is still running, and the fetch of page 2 ends.
        snapshots.value = PrefetchSnapshot(currentPage = 1, pageOffset = 1, isCurrentSettled = false, stayMillis = 25)
        finish.getOrPut("u2") { CompletableDeferred() }.complete(100L)

        waitFor { started.size == 3 }
        assertEquals(listOf("u1", "u2", "u3"), started)
        show.cancel()
    }

    private fun prefetcher(fetch: suspend (PrefetchItem) -> Long?): SlideShowPrefetcher =
        SlideShowPrefetcher(
            // Three pages ahead, two fetches at once, whatever the fetches report.
            tuner = PrefetchTuner(initialDepth = 3, minDepth = 1, maxDepth = 3, initialParallel = 2, maxParallel = 2),
            fetch = fetch,
            hasCopy = { it.url in copies.value },
            clock = { 0L },
            copyChanges = copies,
        )

    private fun onPage(page: Int, settled: Boolean): MutableStateFlow<PrefetchSnapshot> =
        MutableStateFlow(PrefetchSnapshot(currentPage = page, pageOffset = 1, isCurrentSettled = settled, stayMillis = 25))

    private suspend fun SlideShowPrefetcher.runShow(
        snapshots: MutableStateFlow<PrefetchSnapshot>,
        isSettled: (Int) -> Boolean,
    ) = run(
        snapshots = snapshots,
        pageCount = 10,
        itemFor = { page -> PrefetchItem("u$page", PrefetchKind.Image) },
        isSettled = isSettled,
        isSkipped = { false },
    )

    private suspend fun waitFor(condition: () -> Boolean) {
        withTimeout(2_000) {
            while (!condition()) delay(5)
        }
    }
}
