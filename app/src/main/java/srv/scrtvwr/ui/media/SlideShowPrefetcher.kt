package srv.scrtvwr.ui.media

import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import srv.scrtvwr.media.MediaPreviewLoader
import srv.scrtvwr.media.PrefetchKind
import srv.scrtvwr.media.PrefetchTuner
import srv.scrtvwr.media.remote.HttpAuthenticationException
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** The server file a viewer page shows, which a slide show can fetch before reaching the page. */
internal data class PrefetchItem(val url: String, val kind: PrefetchKind)

/** A running slide show, as the prefetcher plans from it. */
internal data class PrefetchSnapshot(
    val currentPage: Int,
    /** 1 for a show moving to later pages, -1 for one moving to earlier pages. */
    val pageOffset: Int,
    /** Whether the page on screen has finished loading; nothing is prefetched until it has. */
    val isCurrentSettled: Boolean,
    val stayMillis: Long,
)

/** Pages to prefetch, most urgent first, and the pages a fetch may keep running for. */
internal class PrefetchPlan(val order: List<Int>, val window: IntRange)

/**
 * Pages a slide show on [currentPage] moving by [pageOffset] should fetch, most urgent first.
 *
 * The window is the next [ahead] pages the show visits, jumping over those [isSkipped] says it
 * skips, and [behind] pages the other way in case it turns back. A page is due when the show gets
 * there, [paceMillis] per page visited, and has to start [expectedFetchMillis] before that. Pages
 * already too late to be ready in time come first, soonest due first, as the show will wait on
 * them; then the others, least time to spare first, so a video frame that takes long to read may
 * start before a nearer image. Each step behind counts as [behindWeight] steps ahead. Only pages
 * [needsFetch] are listed, though the others count as visited.
 */
internal fun planPrefetch(
    currentPage: Int,
    pageCount: Int,
    pageOffset: Int,
    ahead: Int,
    behind: Int,
    paceMillis: Long,
    expectedFetchMillis: (page: Int) -> Long,
    needsFetch: (page: Int) -> Boolean,
    isSkipped: (page: Int) -> Boolean = { false },
    behindWeight: Int = 6,
): PrefetchPlan {
    class Candidate(val page: Int, val dueMillis: Long, val spareMillis: Long)

    val step = if (pageOffset < 0) -1 else 1
    val candidates = ArrayList<Candidate>()
    var first = currentPage
    var last = currentPage
    fun visit(direction: Int, count: Int, weight: Int) {
        var page = currentPage
        var visits = 0
        while (visits < count) {
            page += direction
            if (page !in 0 until pageCount) return
            if (isSkipped(page)) continue
            visits++
            first = minOf(first, page)
            last = maxOf(last, page)
            if (!needsFetch(page)) continue
            val dueMillis = visits.toLong() * weight * paceMillis
            candidates += Candidate(page, dueMillis, dueMillis - expectedFetchMillis(page))
        }
    }
    visit(step, ahead, weight = 1)
    visit(-step, behind, weight = behindWeight)
    val order = candidates
        .sortedWith(compareBy<Candidate> { it.spareMillis > 0 }.thenBy { if (it.spareMillis > 0) it.spareMillis else it.dueMillis })
        .map { it.page }
    return PrefetchPlan(order, first..last)
}

/**
 * Fetches server pages ahead of a running slide show, in the direction it moves, so the show
 * finds them on the device rather than waiting on downloads. The page on screen stays first: no
 * fetch starts while it is still loading, and it downloads through slots of its own. How far
 * ahead, and how many fetches at once, [tuner] decides from how fetches go. One prefetcher
 * serves a viewer for as long as it is open, so what it learned carries over to the next show.
 */
internal class SlideShowPrefetcher(
    private val tuner: PrefetchTuner,
    private val fetch: suspend (PrefetchItem) -> Long?,
    private val hasCopy: (PrefetchItem) -> Boolean,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    /** A prefetcher for viewer pages decoded at [maxDimension], fetching into the viewer's copies. */
    constructor(maxDimension: Int) : this(
        tuner = PrefetchTuner(budgetBytes = MediaPreviewLoader.VIEWER_PREFETCH_BUDGET_BYTES),
        fetch = { item ->
            when (item.kind) {
                PrefetchKind.Image -> MediaPreviewLoader.prefetchImage(item.url)
                // Server pages load with no modification time; see the viewer's contentStamp.
                PrefetchKind.VideoFrame -> MediaPreviewLoader.prefetchVideoFrame(item.url, modified = 0L, maxDimension)
            }
        },
        hasCopy = { item ->
            MediaPreviewLoader.isViewerCopy(
                MediaPreviewLoader.viewerCopies.value,
                item.url,
                video = item.kind == PrefetchKind.VideoFrame,
                maxDimension = maxDimension,
            )
        },
    )

    /**
     * Prefetches for a show until cancelled, planning again whenever [snapshots] changes and
     * whenever a fetch ends. [itemFor] gives the server file a page fetches, or null when it needs
     * none; [isSettled] tells pages that already finished loading. Fetches still running when
     * the show ends are cancelled with it.
     */
    suspend fun run(
        snapshots: Flow<PrefetchSnapshot>,
        pageCount: Int,
        itemFor: (page: Int) -> PrefetchItem?,
        isSettled: (page: Int) -> Boolean,
        isSkipped: (page: Int) -> Boolean,
    ): Unit = coroutineScope {
        val running = ConcurrentHashMap<Int, Job>()
        // Files fetched, or being fetched, during this show. One that failed is tried again a
        // little later, a few times, before it is left to load once on screen. Used on this
        // coroutine's thread only, as are the counts below.
        val tried = HashSet<String>()
        val failures = HashMap<String, Int>()
        val retryAt = HashMap<String, Long>()
        val refused = AtomicBoolean(false)
        val wake = Channel<Unit>(Channel.CONFLATED)
        val stats = ShowStats(clock())
        var snapshot: PrefetchSnapshot? = null
        var arrivedPage: Int? = null
        var arrivedAt = 0L
        // Whether the page the show just moved on to has yet to finish loading, for its wait.
        var awaitingSettle = false

        fun isReady(page: Int): Boolean {
            val item = itemFor(page) ?: return true
            return hasCopy(item) || isSettled(page)
        }

        // How many pages in a row ahead of [page] the show will find ready.
        fun readyAhead(page: Int, offset: Int, limit: Int): Int {
            var next = page
            var ready = 0
            while (ready < limit) {
                next += offset
                if (next !in 0 until pageCount) break
                if (isSkipped(next)) continue
                if (!isReady(next)) break
                ready++
            }
            return ready
        }

        launch {
            snapshots.collect {
                snapshot = it
                wake.trySend(Unit)
            }
        }

        try {
            while (true) {
                wake.receive()
                val show = snapshot ?: continue
                val now = clock()
                tuner.recordStay(show.stayMillis)
                val previousPage = arrivedPage
                if (show.currentPage != previousPage) {
                    awaitingSettle = false
                    // Only pages the show moved on to count: not the one it began on, one it jumps
                    // over, or one the user went back to.
                    val movedOn = previousPage != null && (show.currentPage - previousPage) * show.pageOffset > 0
                    if (movedOn && !isSkipped(show.currentPage)) {
                        itemFor(show.currentPage)?.let { item ->
                            val ready = hasCopy(item) || isSettled(show.currentPage)
                            tuner.recordArrival(ready)
                            stats.arrival(ready)
                            awaitingSettle = true
                            log(
                                "arrive page=${show.currentPage} ready=$ready " +
                                    "readyAhead=${readyAhead(show.currentPage, show.pageOffset, tuner.depth)} " +
                                    "depth=${tuner.depth} parallel=${tuner.parallel} running=${running.size} " +
                                    "stay=${show.stayMillis}",
                            )
                        }
                    } else if (previousPage == null) {
                        log(
                            "start page=${show.currentPage} offset=${show.pageOffset} stay=${show.stayMillis} " +
                                "depth=${tuner.depth} parallel=${tuner.parallel}",
                        )
                    }
                    arrivedPage = show.currentPage
                    arrivedAt = now
                }
                if (show.isCurrentSettled && awaitingSettle) {
                    awaitingSettle = false
                    val waitMillis = now - arrivedAt
                    stats.settled(waitMillis)
                    log("settled page=${show.currentPage} waitMs=$waitMillis")
                }

                val plan = planPrefetch(
                    currentPage = show.currentPage,
                    pageCount = pageCount,
                    pageOffset = show.pageOffset,
                    ahead = tuner.depth,
                    behind = BEHIND_PAGES,
                    paceMillis = tuner.paceMillis,
                    expectedFetchMillis = { page -> itemFor(page)?.let { tuner.expectedMillis(it.kind) } ?: 0L },
                    needsFetch = { page ->
                        val item = itemFor(page)
                        item != null && item.url !in tried && (retryAt[item.url] ?: 0L) <= now &&
                            !isSettled(page) && !hasCopy(item)
                    },
                    isSkipped = isSkipped,
                )

                // A show that turned back, or jumped, no longer needs what it was heading for.
                running.forEach { (page, job) ->
                    if (page !in plan.window && job.isActive) {
                        job.cancel()
                        itemFor(page)?.let { tried -= it.url }
                    }
                }

                // The page on screen has the server to itself until it has loaded.
                if (!show.isCurrentSettled || refused.get()) continue
                for (page in plan.order) {
                    if (running.size >= tuner.parallel) break
                    val item = itemFor(page) ?: continue
                    tried += item.url
                    val job = launch(start = CoroutineStart.LAZY) {
                        val startedAt = clock()
                        try {
                            val bytes = fetch(item)
                            val elapsed = clock() - startedAt
                            if (bytes == null) {
                                stats.joined()
                                log("fetch page=$page kind=${item.kind} ms=$elapsed result=none")
                                return@launch
                            }
                            tuner.recordFetch(item.kind, elapsed, bytes, failed = false)
                            stats.fetched(elapsed, bytes)
                            log("fetch page=$page kind=${item.kind} ms=$elapsed bytes=$bytes result=ok")
                        } catch (error: CancellationException) {
                            stats.cancelled()
                            throw error
                        } catch (error: HttpAuthenticationException) {
                            // Every other fetch would be refused too; the page on screen shows why.
                            refused.set(true)
                            log("fetch page=$page refused; prefetching stops for this show")
                        } catch (error: Exception) {
                            val elapsed = clock() - startedAt
                            tuner.recordFetch(item.kind, elapsed, bytes = -1L, failed = true)
                            stats.failed()
                            val attempts = (failures[item.url] ?: 0) + 1
                            failures[item.url] = attempts
                            if (attempts < MAX_ATTEMPTS) {
                                tried -= item.url
                                retryAt[item.url] = clock() + RETRY_DELAY_MILLIS * attempts
                            }
                            log("fetch page=$page kind=${item.kind} ms=$elapsed result=failed attempt=$attempts error=${error.message}")
                        }
                    }
                    running[page] = job
                    job.invokeOnCompletion {
                        running.remove(page, job)
                        wake.trySend(Unit)
                    }
                    job.start()
                }
            }
        } finally {
            log(stats.summary(clock(), tuner.depth, tuner.parallel))
        }
    }

    /** Counts of one show, for the debug log, to judge how well prefetching kept up. */
    private class ShowStats(private val startedAt: Long) {
        private var arrivals = 0
        private var readyArrivals = 0
        private val waits = ArrayList<Long>()
        private var fetches = 0
        private var fetchedBytes = 0L
        private var fetchMillis = 0L
        private var failures = 0
        private var cancels = 0
        private var joins = 0

        fun arrival(ready: Boolean) {
            arrivals++
            if (ready) readyArrivals++
        }

        fun settled(waitMillis: Long) {
            waits += waitMillis
        }

        fun fetched(elapsedMillis: Long, bytes: Long) {
            fetches++
            fetchMillis += elapsedMillis
            fetchedBytes += bytes
        }

        fun failed() {
            failures++
        }

        fun cancelled() {
            cancels++
        }

        fun joined() {
            joins++
        }

        fun summary(now: Long, depth: Int, parallel: Int): String {
            val seconds = (now - startedAt).coerceAtLeast(1L) / 1_000.0
            val sorted = waits.sorted()
            fun percentile(fraction: Double): Long =
                if (sorted.isEmpty()) 0L else sorted[((sorted.size - 1) * fraction).toInt()]
            return "summary seconds=${"%.1f".format(seconds)} arrivals=$arrivals ready=$readyArrivals " +
                "pagesPerSecond=${"%.2f".format(arrivals / seconds)} " +
                "waitMs(p50=${percentile(0.5)} p90=${percentile(0.9)} max=${sorted.lastOrNull() ?: 0}) " +
                "fetches=$fetches failed=$failures cancelled=$cancels none=$joins " +
                "avgFetchMs=${if (fetches > 0) fetchMillis / fetches else 0} " +
                "fetchedMB=${"%.1f".format(fetchedBytes / 1_048_576.0)} depth=$depth parallel=$parallel"
        }
    }

    private fun log(message: String) {
        Timber.tag(LOG_TAG).d(message)
    }

    private companion object {
        // Pages behind the show worth fetching, in case it turns back.
        const val BEHIND_PAGES = 2
        const val MAX_ATTEMPTS = 3
        const val RETRY_DELAY_MILLIS = 1_000L
        const val LOG_TAG = "SlideShowPrefetch"
    }
}
