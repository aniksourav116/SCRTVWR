package srv.scrtvwr.media.remote

import srv.scrtvwr.media.DirectoryTraversal
import srv.scrtvwr.ui.sort.DirectorySortOrder
import srv.scrtvwr.ui.sort.entryComparator
import java.io.IOException

/** The folders an HTTP server lists, walked by directory URL. Unreachable folders read as empty. */
object RemoteDirectoryTree : DirectoryTraversal.Tree<String> {
    override fun parent(directory: String): String? = RemotePaths.parent(directory)

    override fun subDirectories(directory: String, sortOrder: DirectorySortOrder): List<String> =
        try {
            HttpDirectoryClient.list(directory)
                .filter(RemoteEntry::isDirectory)
                .sortedWith(sortOrder.remoteEntryComparator(directoriesFirst = false))
                .map(RemoteEntry::url)
        } catch (_: IOException) {
            emptyList()
        }

    override fun key(directory: String): String = RemotePaths.directoryUrl(directory)

    override fun pathSegments(directory: String): List<String> = RemotePaths.pathSegments(directory)

    override fun name(directory: String): String = RemotePaths.name(directory)
}

fun DirectorySortOrder.remoteEntryComparator(directoriesFirst: Boolean = true): Comparator<RemoteEntry> =
    entryComparator(
        directoriesFirst = directoriesFirst,
        isDirectory = RemoteEntry::isDirectory,
        name = RemoteEntry::name,
        lastModified = RemoteEntry::lastModifiedMillis,
        size = { entry -> if (entry.isDirectory) 0L else entry.sizeBytes },
        path = RemoteEntry::url,
    )
