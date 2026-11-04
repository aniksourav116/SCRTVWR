package srv.scrtvwr.ui.media

/** What a slide show does once the page on screen has had its stay. */
sealed interface SlideShowStep {
    /** Go on to [page]. */
    data class Show(val page: Int) : SlideShowStep

    /** Keep the page on screen while the next one loads. */
    data object Wait : SlideShowStep

    /** Nothing is left to show. */
    data object End : SlideShowStep
}

/**
 * The slide show's next step among [upcoming], the pages it would show next, in order. It goes on
 * only to a page that has loaded, so the screen keeps the picture it has rather than turning to an
 * empty page, and passes over pages that failed. While the nearest page left still loads it waits,
 * or, when [passLoading], goes on to a later page that has loaded if there is one. A page that does
 * not load while the show stays where it is, by [loadsBeside], loads only once on screen, so the
 * show goes on to it unless a page before it may still arrive.
 */
internal fun nextSlideShowStep(
    upcoming: Sequence<Int>,
    loadsBeside: (page: Int) -> Boolean,
    hasSettled: (page: Int) -> Boolean,
    hasFailed: (page: Int) -> Boolean,
    passLoading: Boolean,
): SlideShowStep {
    var anyLoading = false
    for (page in upcoming) {
        when {
            hasFailed(page) -> continue
            hasSettled(page) -> return SlideShowStep.Show(page)
            !loadsBeside(page) -> return if (anyLoading) SlideShowStep.Wait else SlideShowStep.Show(page)
            !passLoading -> return SlideShowStep.Wait
            else -> anyLoading = true
        }
    }
    return if (anyLoading) SlideShowStep.Wait else SlideShowStep.End
}
