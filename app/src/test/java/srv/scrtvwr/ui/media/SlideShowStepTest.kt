package srv.scrtvwr.ui.media

import org.junit.Assert.assertEquals
import org.junit.Test

class SlideShowStepTest {
    @Test
    fun goesOnToTheNextPageOnceItHasLoaded() {
        val step = step(upcoming = 6..10, settled = { it == 6 })

        assertEquals(SlideShowStep.Show(6), step)
    }

    @Test
    fun waitsOnThePageOnScreenWhileTheNextOneLoads() {
        val step = step(upcoming = 6..10, settled = { it == 7 })

        assertEquals(SlideShowStep.Wait, step)
    }

    @Test
    fun passesOverPagesThatFailed() {
        val step = step(upcoming = 6..10, settled = { it <= 8 }, failed = { it <= 7 })

        assertEquals(SlideShowStep.Show(8), step)
    }

    @Test
    fun aPageStillLoadingAfterAWhileGivesWayToALaterOneThatLoaded() {
        val step = step(upcoming = 6..10, settled = { it == 8 }, passLoading = true)

        assertEquals(SlideShowStep.Show(8), step)
    }

    @Test
    fun keepsWaitingWhenNoLaterPageHasLoadedEither() {
        val step = step(upcoming = 6..10, passLoading = true)

        assertEquals(SlideShowStep.Wait, step)
    }

    @Test
    fun goesOnToAPageThatLoadsOnlyOnScreen() {
        val step = step(upcoming = 6..10, loadsBeside = { false })

        assertEquals(SlideShowStep.Show(6), step)
    }

    @Test
    fun goesOnToAPageThatLoadsOnlyOnScreenOncePagesBeforeItFailed() {
        val step = step(upcoming = 6..10, loadsBeside = { it <= 7 }, settled = { it <= 7 }, failed = { it <= 7 })

        assertEquals(SlideShowStep.Show(8), step)
    }

    @Test
    fun waitsForAStalledPageRatherThanGoOnToOneThatLoadsOnlyOnScreen() {
        val step = step(upcoming = 6..10, loadsBeside = { it == 6 }, passLoading = true)

        assertEquals(SlideShowStep.Wait, step)
    }

    @Test
    fun endsWhenEveryPageLeftFailed() {
        val step = step(upcoming = 6..8, settled = { true }, failed = { true })

        assertEquals(SlideShowStep.End, step)
    }

    @Test
    fun endsWhenNoPageIsLeft() {
        val step = step(upcoming = IntRange.EMPTY)

        assertEquals(SlideShowStep.End, step)
    }

    private fun step(
        upcoming: IntProgression,
        loadsBeside: (Int) -> Boolean = { true },
        settled: (Int) -> Boolean = { false },
        failed: (Int) -> Boolean = { false },
        passLoading: Boolean = false,
    ): SlideShowStep = nextSlideShowStep(
        upcoming = upcoming.asSequence(),
        loadsBeside = loadsBeside,
        hasSettled = settled,
        hasFailed = failed,
        passLoading = passLoading,
    )
}
