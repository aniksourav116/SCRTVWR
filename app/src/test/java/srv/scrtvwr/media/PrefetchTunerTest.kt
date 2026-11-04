package srv.scrtvwr.media

import org.junit.Assert.assertEquals
import org.junit.Test

class PrefetchTunerTest {
    @Test
    fun startsOptimistic() {
        val tuner = PrefetchTuner(initialDepth = 32, initialParallel = 4)

        assertEquals(32, tuner.depth)
        assertEquals(4, tuner.parallel)
    }

    @Test
    fun recordFetch_slowFetchesAgainstAFastShowRaiseDepthToTheLeadNeeded() {
        val tuner = PrefetchTuner(initialDepth = 8, initialParallel = 2, slowMillis = 10_000, warmUpFetches = 1)
        // 100 ms a page, with the frame it takes to render.
        tuner.recordStay(84)

        tuner.recordFetch(PrefetchKind.Image, elapsedMillis = 1_000, bytes = 1_000_000, failed = false)

        // Twice the 10 pages the show passes during one fetch, plus the 2 running.
        assertEquals(22, tuner.depth)
    }

    @Test
    fun recordStay_aFasterShowRaisesDepthAtOnceAndASlowerOneKeepsIt() {
        val tuner = PrefetchTuner(initialDepth = 4, initialParallel = 1, slowMillis = 10_000, warmUpFetches = 1)
        tuner.recordStay(184)
        tuner.recordFetch(PrefetchKind.Image, elapsedMillis = 400, bytes = 1_000, failed = false)
        assertEquals(5, tuner.depth)

        tuner.recordStay(84)
        assertEquals(9, tuner.depth)

        tuner.recordStay(984)
        assertEquals(9, tuner.depth)
    }

    @Test
    fun recordArrival_readyPagesLowerDepthOnePageAtATimeDownToTheNeed() {
        val tuner = PrefetchTuner(initialDepth = 32, initialParallel = 2, slowMillis = 10_000, warmUpFetches = 1)
        tuner.recordStay(84)
        tuner.recordFetch(PrefetchKind.Image, elapsedMillis = 100, bytes = 1_000, failed = false)

        tuner.recordArrival(ready = true)
        assertEquals(31, tuner.depth)

        repeat(100) { tuner.recordArrival(ready = true) }
        // Twice the one page passed during a fetch, plus the 2 running.
        assertEquals(4, tuner.depth)
    }

    @Test
    fun recordArrival_readyPagesKeepTheOptimisticDepthUntilFetchesHaveBeenMeasured() {
        val tuner = PrefetchTuner(initialDepth = 32, warmUpFetches = 3)

        repeat(5) { tuner.recordArrival(ready = true) }

        assertEquals(32, tuner.depth)
    }

    @Test
    fun recordArrival_aPageNotReadyRaisesDepthByAQuarterAndAtLeastTwo() {
        val deep = PrefetchTuner(initialDepth = 16)
        deep.recordArrival(ready = false)
        assertEquals(20, deep.depth)

        val shallow = PrefetchTuner(initialDepth = 4, minDepth = 4)
        shallow.recordArrival(ready = false)
        assertEquals(6, shallow.depth)
    }

    @Test
    fun recordArrival_depthNeverPassesItsMaximum() {
        val tuner = PrefetchTuner(initialDepth = 60, maxDepth = 64)

        repeat(3) { tuner.recordArrival(ready = false) }

        assertEquals(64, tuner.depth)
    }

    @Test
    fun recordFetch_aSlowOrFailedFetchHalvesFetchesAtOnce() {
        val tuner = PrefetchTuner(initialParallel = 4, slowMillis = 2_000, slowBytesPerSecond = 1024L * 1024L)

        tuner.recordFetch(PrefetchKind.Image, elapsedMillis = 3_000, bytes = 100_000, failed = false)
        assertEquals(2, tuner.parallel)

        tuner.recordFetch(PrefetchKind.Image, elapsedMillis = 10, bytes = -1, failed = true)
        assertEquals(1, tuner.parallel)

        tuner.recordFetch(PrefetchKind.Image, elapsedMillis = 10, bytes = -1, failed = true)
        assertEquals(1, tuner.parallel)
    }

    @Test
    fun recordFetch_aLargeFileThatCameInQuicklyIsNotSlow() {
        val tuner = PrefetchTuner(initialParallel = 4, slowMillis = 2_000, slowBytesPerSecond = 1024L * 1024L)

        tuner.recordFetch(PrefetchKind.Image, elapsedMillis = 3_000, bytes = 30L * 1024L * 1024L, failed = false)

        assertEquals(4, tuner.parallel)
    }

    @Test
    fun recordFetch_aRunOfQuickFetchesAddsOneFetchAtOnceUpToTheMaximum() {
        val tuner = PrefetchTuner(initialParallel = 2, maxParallel = 3)

        repeat(3) { tuner.recordFetch(PrefetchKind.Image, elapsedMillis = 100, bytes = 1_000, failed = false) }
        assertEquals(2, tuner.parallel)

        tuner.recordFetch(PrefetchKind.Image, elapsedMillis = 100, bytes = 1_000, failed = false)
        assertEquals(3, tuner.parallel)

        repeat(20) { tuner.recordFetch(PrefetchKind.Image, elapsedMillis = 100, bytes = 1_000, failed = false) }
        assertEquals(3, tuner.parallel)
    }

    @Test
    fun depth_neverCoversMoreFilesThanTheBudget() {
        val tuner = PrefetchTuner(initialDepth = 32, budgetBytes = 100_000_000, warmUpFetches = 10)

        tuner.recordFetch(PrefetchKind.Image, elapsedMillis = 100, bytes = 10_000_000, failed = false)

        assertEquals(10, tuner.depth)
    }

    @Test
    fun recordFetch_aFrameReadCountsAsSlowOnlyPastItsOwnLongerLimit() {
        val tuner = PrefetchTuner(initialParallel = 4, slowMillis = 2_000, slowFrameMillis = 4_000)

        tuner.recordFetch(PrefetchKind.VideoFrame, elapsedMillis = 3_000, bytes = 200_000, failed = false)
        assertEquals(4, tuner.parallel)

        tuner.recordFetch(PrefetchKind.VideoFrame, elapsedMillis = 4_500, bytes = 200_000, failed = false)
        assertEquals(2, tuner.parallel)
    }

    @Test
    fun depth_dropsBelowItsMinimumForFilesTooLargeForTheBudget() {
        val tuner = PrefetchTuner(initialDepth = 32, minDepth = 4, budgetBytes = 100_000_000, warmUpFetches = 10)

        tuner.recordFetch(PrefetchKind.Image, elapsedMillis = 100, bytes = 60_000_000, failed = false)

        assertEquals(1, tuner.depth)
    }

    @Test
    fun expectedMillis_followsEachKindAndFallsBackToAllFetches() {
        val tuner = PrefetchTuner()
        assertEquals(800L, tuner.expectedMillis(PrefetchKind.Image))
        assertEquals(1_500L, tuner.expectedMillis(PrefetchKind.VideoFrame))

        tuner.recordFetch(PrefetchKind.VideoFrame, elapsedMillis = 3_000, bytes = -1, failed = false)

        assertEquals(3_000L, tuner.expectedMillis(PrefetchKind.VideoFrame))
        assertEquals(3_000L, tuner.expectedMillis(PrefetchKind.Image))
    }

    @Test
    fun expectedMillis_ignoresFailedFetches() {
        val tuner = PrefetchTuner()

        tuner.recordFetch(PrefetchKind.Image, elapsedMillis = 5, bytes = -1, failed = true)

        assertEquals(800L, tuner.expectedMillis(PrefetchKind.Image))
    }
}
