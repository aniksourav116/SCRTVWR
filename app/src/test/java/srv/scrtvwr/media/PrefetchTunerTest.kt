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
    fun recordArrival_aPageNotReadyAfterFetchingRanOutOfPagesRaisesDepthByAQuarterAndAtLeastTwo() {
        val deep = PrefetchTuner(initialDepth = 16)
        deep.recordArrival(ready = false, ranOutOfPages = true)
        assertEquals(20, deep.depth)

        val shallow = PrefetchTuner(initialDepth = 4, minDepth = 4)
        shallow.recordArrival(ready = false, ranOutOfPages = true)
        assertEquals(6, shallow.depth)
    }

    @Test
    fun recordArrival_aPageNotReadyWhileEveryFetchWasBusyKeepsDepth() {
        val tuner = PrefetchTuner(initialDepth = 16)

        repeat(3) { tuner.recordArrival(ready = false, ranOutOfPages = false) }

        assertEquals(16, tuner.depth)
    }

    @Test
    fun recordArrival_depthNeverPassesItsMaximum() {
        val tuner = PrefetchTuner(initialDepth = 60, maxDepth = 64)

        repeat(3) { tuner.recordArrival(ready = false, ranOutOfPages = true) }

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
    fun recordFetch_triesOneMoreFetchAtOnceAfterMeasuringTheCurrentCount() {
        val tuner = probing()

        tuner.fetches(2, concurrent = 2, millis = 100)
        assertEquals(2, tuner.parallel)

        tuner.fetches(1, concurrent = 2, millis = 100)
        assertEquals(3, tuner.parallel)
    }

    @Test
    fun recordFetch_keepsOneMoreFetchWhenAllTogetherBringMoreAndTriesAnother() {
        val tuner = probing()
        tuner.fetches(3, concurrent = 2, millis = 100)

        // Each fetch is as quick with three running, so together they bring half as much again.
        tuner.fetches(3, concurrent = 3, millis = 100)
        assertEquals(3, tuner.parallel)

        tuner.fetches(3, concurrent = 3, millis = 100)
        assertEquals(4, tuner.parallel)
    }

    @Test
    fun recordFetch_undoesOneMoreFetchThatBringsNoMore() {
        val tuner = probing()
        tuner.fetches(3, concurrent = 2, millis = 100)

        // Three share what two brought: each takes half as long again.
        tuner.fetches(3, concurrent = 3, millis = 150)

        assertEquals(2, tuner.parallel)
    }

    @Test
    fun recordFetch_afterAStepThatBroughtNothingLaterTriesOneFewerAndKeepsItWhenAsFast() {
        val tuner = probing()
        tuner.fetches(3, concurrent = 2, millis = 100)
        tuner.fetches(3, concurrent = 3, millis = 150)

        tuner.fetches(4, concurrent = 2, millis = 100)
        assertEquals(2, tuner.parallel)
        tuner.fetches(1, concurrent = 2, millis = 100)
        assertEquals(1, tuner.parallel)

        // One fetch alone brings what two did.
        tuner.fetches(3, concurrent = 1, millis = 50)
        assertEquals(1, tuner.parallel)
    }

    @Test
    fun recordFetch_undoesOneFewerFetchThatBringsLess() {
        val tuner = probing(initialParallel = 3, maxParallel = 3)
        tuner.fetches(3, concurrent = 3, millis = 100)
        assertEquals(2, tuner.parallel)

        // Each fetch is no quicker with two running, so together they bring a third less.
        tuner.fetches(3, concurrent = 2, millis = 100)

        assertEquals(3, tuner.parallel)
    }

    @Test
    fun recordFetch_undoesAStepTheShowNeverKeepsBusy() {
        val tuner = probing()
        tuner.fetches(3, concurrent = 2, millis = 100)
        assertEquals(3, tuner.parallel)

        tuner.fetches(8, concurrent = 2, millis = 100)
        assertEquals(3, tuner.parallel)

        tuner.fetches(1, concurrent = 2, millis = 100)
        assertEquals(2, tuner.parallel)
    }

    @Test
    fun recordFetch_videoFramesDoNotCountTowardThroughput() {
        val tuner = probing()

        repeat(10) { tuner.recordFetch(PrefetchKind.VideoFrame, elapsedMillis = 100, bytes = 100_000, failed = false, concurrent = 2) }

        assertEquals(2, tuner.parallel)
    }

    private fun probing(initialParallel: Int = 2, maxParallel: Int = 4): PrefetchTuner =
        PrefetchTuner(initialParallel = initialParallel, maxParallel = maxParallel, samplesToJudge = 3, restFetches = 5)

    private fun PrefetchTuner.fetches(count: Int, concurrent: Int, millis: Long) {
        repeat(count) {
            recordFetch(PrefetchKind.Image, elapsedMillis = millis, bytes = 100_000, failed = false, concurrent = concurrent)
        }
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
