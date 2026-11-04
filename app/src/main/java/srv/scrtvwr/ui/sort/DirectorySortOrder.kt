package srv.scrtvwr.ui.sort

import java.io.File

enum class DirectorySortOrder(val label: String) {
    NameAscending("Name A-Z"),
    NameDescending("Name Z-A"),
    NewestFirst("Newest first"),
    OldestFirst("Oldest first"),
    LargestFirst("Largest first"),
    SmallestFirst("Smallest first"),
}

/** Whether this order compares sizes or times, which HTTP listings only provide on request. */
val DirectorySortOrder.needsFileMetadata: Boolean
    get() = this != DirectorySortOrder.NameAscending && this != DirectorySortOrder.NameDescending

/**
 * This order for a listing on an HTTP server. Sizes and times there cost a request per file, so a
 * date or size order reads as name A-Z unless [detailsAllowed].
 */
fun DirectorySortOrder.onServer(detailsAllowed: Boolean): DirectorySortOrder =
    if (needsFileMetadata && !detailsAllowed) DirectorySortOrder.NameAscending else this

/** The orders a sort menu offers: on a server, only those [onServer] keeps. */
fun sortOrdersFor(isRemote: Boolean, detailsAllowed: Boolean): List<DirectorySortOrder> =
    DirectorySortOrder.entries.filter { order -> !isRemote || order.onServer(detailsAllowed) == order }

fun DirectorySortOrder.fileComparator(directoriesFirst: Boolean = true): Comparator<File> =
    entryComparator(
        directoriesFirst = directoriesFirst,
        isDirectory = File::isDirectory,
        name = File::getName,
        lastModified = File::lastModified,
        size = File::sortSize,
        path = File::getAbsolutePath,
    )

/** The ordering behind [fileComparator], for listings that are not local files. */
fun <T> DirectorySortOrder.entryComparator(
    directoriesFirst: Boolean,
    isDirectory: (T) -> Boolean,
    name: (T) -> String,
    lastModified: (T) -> Long,
    size: (T) -> Long,
    path: (T) -> String,
): Comparator<T> =
    Comparator { left, right ->
        if (directoriesFirst && isDirectory(left) != isDirectory(right)) {
            return@Comparator if (isDirectory(left)) -1 else 1
        }

        val primary = when (this) {
            DirectorySortOrder.NameAscending -> name(left).compareTo(name(right), ignoreCase = true)
            DirectorySortOrder.NameDescending -> name(right).compareTo(name(left), ignoreCase = true)
            DirectorySortOrder.NewestFirst -> lastModified(right).compareTo(lastModified(left))
            DirectorySortOrder.OldestFirst -> lastModified(left).compareTo(lastModified(right))
            DirectorySortOrder.LargestFirst -> size(right).compareTo(size(left))
            DirectorySortOrder.SmallestFirst -> size(left).compareTo(size(right))
        }

        primary
            .takeIf { it != 0 }
            ?: name(left).compareTo(name(right), ignoreCase = true).takeIf { it != 0 }
            ?: path(left).compareTo(path(right), ignoreCase = true)
    }

private fun File.sortSize(): Long =
    if (isFile) length() else 0L
