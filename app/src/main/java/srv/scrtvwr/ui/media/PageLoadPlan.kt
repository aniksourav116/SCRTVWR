package srv.scrtvwr.ui.media

/** How many server pages may download at once, beyond the one on screen, and how far from it. */
data class RemotePreloadLimits(val parallel: Int, val distance: Int)

/**
 * Pages of the viewer that may start loading now. The page on screen always may. Nothing else
 * starts until it has settled; then pages load nearest first, the one ahead before the one behind.
 * Local pages all start within [preloadDistance]. Server downloads start within [remote]'s distance
 * while fewer than its parallel count are still running. A page that has started stays allowed.
 */
internal fun pagesAllowedToLoad(
    currentPage: Int,
    pageCount: Int,
    preloadDistance: Int,
    isCurrentSettled: Boolean,
    remote: RemotePreloadLimits,
    downloadsFromServer: (page: Int) -> Boolean,
    hasStarted: (page: Int) -> Boolean,
    hasSettled: (page: Int) -> Boolean,
): Set<Int> {
    val allowed = mutableSetOf(currentPage)
    if (!isCurrentSettled) return allowed
    val window = (1..preloadDistance).flatMap { distance -> listOf(currentPage + distance, currentPage - distance) }
        .filter { it in 0 until pageCount }
    var downloading = window.count { downloadsFromServer(it) && hasStarted(it) && !hasSettled(it) }
    window.forEach { page ->
        when {
            !downloadsFromServer(page) || hasStarted(page) -> allowed += page
            kotlin.math.abs(page - currentPage) <= remote.distance && downloading < remote.parallel -> {
                allowed += page
                downloading++
            }
        }
    }
    return allowed
}
