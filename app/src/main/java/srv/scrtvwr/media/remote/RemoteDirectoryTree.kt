package srv.scrtvwr.media.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import srv.scrtvwr.media.DirectoryTraversal
import srv.scrtvwr.ui.sort.DirectorySortOrder
import srv.scrtvwr.ui.sort.entryComparator
import java.io.IOException
import kotlin.coroutines.CoroutineContext

/** The folders an HTTP server lists, walked by directory URL. Unreachable folders read as empty. */
object RemoteDirectoryTree : DirectoryTraversal.Tree<String> {
    private const val SEARCH_TIMEOUT_MILLIS = 15_000L
    private const val MAX_SEARCH_LISTINGS = 128

    /** Bound network work even before a depth-first iterator yields its first candidate. */
    suspend fun <T> search(block: suspend (Search) -> T?): T? = withContext(Dispatchers.IO) {
        try {
            withTimeoutOrNull(SEARCH_TIMEOUT_MILLIS) { block(Search(currentCoroutineContext())) }
        } catch (_: SearchLimitReached) {
            null
        }
    }

    class Search internal constructor(private val context: CoroutineContext) : DirectoryTraversal.Tree<String> by RemoteDirectoryTree {
        private val entries = HashMap<String, List<RemoteEntry>>()

        fun list(directory: String): List<RemoteEntry> {
            context.ensureActive()
            val key = RemotePaths.directoryUrl(directory)
            entries[key]?.let { return it }
            if (entries.size >= MAX_SEARCH_LISTINGS) throw SearchLimitReached()
            return try {
                HttpDirectoryClient.list(key, requestContext = context)
            } catch (_: IOException) {
                context.ensureActive()
                emptyList()
            }.also { entries[key] = it }
        }

        override fun subDirectories(directory: String, sortOrder: DirectorySortOrder): List<String> =
            list(directory)
                .filter(RemoteEntry::isDirectory)
                .sortedWith(sortOrder.remoteEntryComparator(directoriesFirst = false))
                .map(RemoteEntry::url)
    }

    private class SearchLimitReached : RuntimeException()

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
