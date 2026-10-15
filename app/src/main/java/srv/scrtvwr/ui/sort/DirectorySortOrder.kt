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
