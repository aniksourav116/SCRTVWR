package srv.scrtvwr.ui.media

import org.junit.Assert.assertEquals
import org.junit.Test

class PageLoadPlanTest {
    private val roomy = RemotePreloadLimits(parallel = 3, distance = 5)

    @Test
    fun onlyThePageOnScreenLoadsUntilItSettles() {
        val allowed = plan(currentPage = 5, isCurrentSettled = false, server = { true })

        assertEquals(setOf(5), allowed)
    }

    @Test
    fun localPagesAllLoadOnceThePageOnScreenSettled() {
        val allowed = plan(currentPage = 5, preloadDistance = 2, server = { false })

        assertEquals(setOf(3, 4, 5, 6, 7), allowed)
    }

    @Test
    fun serverPagesStartNearestFirstAheadBeforeBehindUpToTheParallelCount() {
        val allowed = plan(
            currentPage = 5,
            preloadDistance = 3,
            remote = RemotePreloadLimits(parallel = 3, distance = 3),
            server = { true },
        )

        assertEquals(setOf(5, 6, 4, 7), allowed)
    }

    @Test
    fun runningServerDownloadsUseUpTheParallelCount() {
        val allowed = plan(
            currentPage = 5,
            preloadDistance = 3,
            remote = RemotePreloadLimits(parallel = 2, distance = 3),
            server = { true },
            started = { it == 7 || it == 3 },
        )

        // 7 and 3 are running and stay allowed; no slot is left for 6 or 4.
        assertEquals(setOf(5, 7, 3), allowed)
    }

    @Test
    fun settledServerPagesFreeTheirSlot() {
        val allowed = plan(
            currentPage = 5,
            preloadDistance = 2,
            remote = RemotePreloadLimits(parallel = 1, distance = 2),
            server = { true },
            started = { it == 6 },
            settled = { it == 6 },
        )

        assertEquals(setOf(5, 6, 4), allowed)
    }

    @Test
    fun aSlowServerPreloadsOnlyWithinItsDistance() {
        val allowed = plan(
            currentPage = 5,
            preloadDistance = 4,
            remote = RemotePreloadLimits(parallel = 10, distance = 1),
            server = { it != 8 },
        )

        // 8 is local, so the server distance does not hold it back.
        assertEquals(setOf(5, 6, 4, 8), allowed)
    }

    @Test
    fun pagesOutsideTheListAreSkipped() {
        val allowed = plan(currentPage = 0, pageCount = 2, preloadDistance = 3, server = { false })

        assertEquals(setOf(0, 1), allowed)
    }

    private fun plan(
        currentPage: Int,
        pageCount: Int = 20,
        preloadDistance: Int = 5,
        isCurrentSettled: Boolean = true,
        remote: RemotePreloadLimits = roomy,
        server: (Int) -> Boolean,
        started: (Int) -> Boolean = { false },
        settled: (Int) -> Boolean = { false },
    ): Set<Int> = pagesAllowedToLoad(
        currentPage = currentPage,
        pageCount = pageCount,
        preloadDistance = preloadDistance,
        isCurrentSettled = isCurrentSettled,
        remote = remote,
        downloadsFromServer = server,
        hasStarted = started,
        hasSettled = settled,
    )
}
