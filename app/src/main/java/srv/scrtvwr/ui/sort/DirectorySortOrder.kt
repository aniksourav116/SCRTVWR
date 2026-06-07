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

fun DirectorySortOrder.fileComparator(directoriesFirst: Boolean = true): Comparator<File> =
    Comparator { left, right ->
        if (directoriesFirst && left.isDirectory != right.isDirectory) {
            return@Comparator if (left.isDirectory) -1 else 1
        }

        val primary = when (this) {
            DirectorySortOrder.NameAscending -> left.name.compareTo(right.name, ignoreCase = true)
            DirectorySortOrder.NameDescending -> right.name.compareTo(left.name, ignoreCase = true)
            DirectorySortOrder.NewestFirst -> right.lastModified().compareTo(left.lastModified())
            DirectorySortOrder.OldestFirst -> left.lastModified().compareTo(right.lastModified())
            DirectorySortOrder.LargestFirst -> right.sortSize().compareTo(left.sortSize())
            DirectorySortOrder.SmallestFirst -> left.sortSize().compareTo(right.sortSize())
        }

        primary
            .takeIf { it != 0 }
            ?: left.name.compareTo(right.name, ignoreCase = true).takeIf { it != 0 }
            ?: left.absolutePath.compareTo(right.absolutePath, ignoreCase = true)
    }

private fun File.sortSize(): Long =
    if (isFile) length() else 0L
