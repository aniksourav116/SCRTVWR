package srv.scrtvwr.ui.media

import org.junit.Assert.assertEquals
import org.junit.Test

class PrefetchPlanTest {
    @Test
    fun fetchesOnlyAheadInTheDirectionTheShowMoves() {
        val plan = plan(currentPage = 5, ahead = 4)

        assertEquals(listOf(6, 7, 8, 9), plan.order)
        assertEquals(5..9, plan.window)
    }

    @Test
    fun aShowMovingBackFetchesEarlierPages() {
        val plan = plan(currentPage = 5, pageOffset = -1, ahead = 4)

        assertEquals(listOf(4, 3, 2, 1), plan.order)
        assertEquals(1..5, plan.window)
    }

    @Test
    fun pagesTheShowSkipsAreNeitherFetchedNorCounted() {
        val plan = plan(currentPage = 5, ahead = 3, skipped = { it == 6 || it == 8 })

        assertEquals(listOf(7, 9, 10), plan.order)
        assertEquals(5..10, plan.window)
    }

    @Test
    fun pagesThatNeedNoFetchStillCountAsVisited() {
        val plan = plan(currentPage = 5, ahead = 3, needsFetch = { it != 6 })

        assertEquals(listOf(7, 8), plan.order)
    }

    @Test
    fun pagesAlreadyTooLateComeFirstSoonestDueThenLeastTimeToSpare() {
        // Page 3 is a video whose frame takes a second to read; the images take 150 ms.
        val plan = plan(
            currentPage = 0,
            ahead = 5,
            paceMillis = 100,
            expected = { page -> if (page == 3) 1_000 else 150 },
        )

        // 1 and 3 cannot be ready in time; 2, 4 and 5 have 50, 250 and 350 ms to spare.
        assertEquals(listOf(1, 3, 2, 4, 5), plan.order)
    }

    @Test
    fun aSlowFetchFartherAheadStartsBeforeNearerQuickOnes() {
        val plan = plan(
            currentPage = 0,
            ahead = 6,
            paceMillis = 100,
            expected = { page -> if (page == 6) 550 else 50 },
        )

        // 6 has as little time to spare as 1.
        assertEquals(listOf(1, 6, 2, 3, 4, 5), plan.order)
    }

    @Test
    fun aPageBehindRanksWithTheSixthPageAhead() {
        val plan = plan(currentPage = 10, ahead = 8, behind = 1)

        assertEquals(listOf(11, 12, 13, 14, 15, 16, 9, 17, 18), plan.order)
        assertEquals(9..18, plan.window)
    }

    @Test
    fun stopsAtTheEndsOfTheList() {
        val plan = plan(currentPage = 18, pageCount = 20, ahead = 5, behind = 1)

        assertEquals(listOf(19, 17), plan.order)
        assertEquals(17..19, plan.window)
    }

    private fun plan(
        currentPage: Int,
        pageCount: Int = 20,
        pageOffset: Int = 1,
        ahead: Int,
        behind: Int = 0,
        paceMillis: Long = 100,
        expected: (Int) -> Long = { 0L },
        needsFetch: (Int) -> Boolean = { true },
        skipped: (Int) -> Boolean = { false },
    ): PrefetchPlan = planPrefetch(
        currentPage = currentPage,
        pageCount = pageCount,
        pageOffset = pageOffset,
        ahead = ahead,
        behind = behind,
        paceMillis = paceMillis,
        expectedFetchMillis = expected,
        needsFetch = needsFetch,
        isSkipped = skipped,
    )
}
