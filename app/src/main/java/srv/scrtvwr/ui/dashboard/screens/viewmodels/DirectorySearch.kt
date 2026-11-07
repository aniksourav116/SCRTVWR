package srv.scrtvwr.ui.dashboard.screens.viewmodels

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import srv.scrtvwr.media.MediaTypes
import srv.scrtvwr.media.remote.HttpDirectoryClient
import srv.scrtvwr.media.remote.RemoteEntry
import srv.scrtvwr.media.remote.RemotePaths
import srv.scrtvwr.ui.sort.entryComparator
import srv.scrtvwr.ui.sort.needsFileMetadata
import java.io.File
import java.io.IOException

private const val BYTES_PER_MIB = 1024L * 1024L

/** Search is scoped to the open folder; archives are returned as files, not expanded. */
data class DirectorySearchOptions(
    val includeSubfolders: Boolean = true,
    val minimumSizeMiB: Long? = null,
    val maximumSizeMiB: Long? = null,
    val modifiedWithinDays: Int? = null,
) {
    val hasMetadataFilters: Boolean
        get() = minimumSizeMiB != null || maximumSizeMiB != null || modifiedWithinDays != null
}

internal val DirectoryUiState.isSearching: Boolean
    get() = searchQuery.isNotBlank() || searchOptions.hasMetadataFilters

internal suspend fun searchDirectory(
    request: DirectoryUiState,
    reload: Boolean,
    onProgress: (Int) -> Unit,
): DirectoryListing {
    val context = currentCoroutineContext()
    val options = request.searchOptions
    val query = request.searchQuery.trim()
    val modifiedAfter = options.modifiedWithinDays?.let { System.currentTimeMillis() - it * 86_400_000L }
    val pending = ArrayDeque<String>().apply { add(request.currentPath) }
    val visited = HashSet<String>()
    val results = ArrayList<DirectoryEntry>()
    val localRoot = if (request.isRemote) null else File(request.currentPath).canonicalFile
    var skipped = 0
    var readableDirectories = 0

    while (pending.isNotEmpty()) {
        context.ensureActive()
        val path = pending.removeLast()
        val identity = if (request.isRemote) path else {
            try { File(path).canonicalPath } catch (_: IOException) { skipped++; continue }
        }
        // Do not follow local symbolic links out of the selected tree, or visit a loop twice.
        if (localRoot != null && identity != localRoot.path &&
            !identity.startsWith(localRoot.path.trimEnd(File.separatorChar) + File.separator)) continue
        if (!visited.add(identity)) continue
        onProgress(visited.size)

        val entries = try {
            if (request.isRemote) {
                HttpDirectoryClient.list(path, refresh = reload)
                    .filter { RemotePaths.isSameOrChildOf(it.url, path) && it.url != path }
                    .filter { request.showHiddenFiles || !it.isHidden }
                    .map { entry ->
                        DirectoryEntry(entry.name, entry.url, entry.isDirectory, entry.sizeBytes,
                            entry.lastModifiedMillis, searchMediaType(entry.url, entry.isDirectory))
                    }
            } else {
                val children = File(path).listFiles()
                if (children == null) { skipped++; continue }
                children.asSequence().filter { request.showHiddenFiles || !it.isHidden }.map { file ->
                    context.ensureActive()
                    DirectoryEntry(file.name, file.absolutePath, file.isDirectory,
                        if (file.isFile) file.length() else 0L, file.lastModified(),
                        searchMediaType(file.path, file.isDirectory))
                }.toList()
            }
        } catch (error: IOException) {
            if (path == request.currentPath) throw error
            skipped++
            continue
        } catch (_: SecurityException) {
            skipped++
            continue
        }
        readableDirectories++
        for (candidate in entries) {
            context.ensureActive()
            if (candidate.isDirectory && options.includeSubfolders) pending.add(candidate.path)
            if (!candidate.name.contains(query, ignoreCase = true) || !candidate.matches(request.mediaFilter)) continue
            // A size is meaningful only for files; HTTP listings generally lack folder dates too.
            if (candidate.isDirectory && options.hasMetadataFilters) continue
            val entry = if (request.isRemote && !candidate.isDirectory &&
                (options.hasMetadataFilters || request.effectiveSortOrder.needsFileMetadata)) {
                val remote = HttpDirectoryClient.withMetadata(
                    RemoteEntry(candidate.name, candidate.path, false),
                )
                candidate.copy(sizeBytes = remote.sizeBytes, lastModifiedMillis = remote.lastModifiedMillis)
            } else candidate
            if (options.minimumSizeMiB != null && entry.sizeBytes < options.minimumSizeMiB * BYTES_PER_MIB) continue
            if (options.maximumSizeMiB != null && (entry.sizeBytes < 0 || entry.sizeBytes > options.maximumSizeMiB * BYTES_PER_MIB)) continue
            if (modifiedAfter != null && entry.lastModifiedMillis < modifiedAfter) continue
            results.add(entry)
        }
    }
    context.ensureActive()
    return DirectoryListing(
        entries = results.sortedWith(request.effectiveSortOrder.entryComparator(
            directoriesFirst = true, isDirectory = DirectoryEntry::isDirectory, name = DirectoryEntry::name,
            lastModified = DirectoryEntry::lastModifiedMillis, size = DirectoryEntry::sizeBytes, path = DirectoryEntry::path,
        )),
        canRead = readableDirectories > 0,
        skippedDirectories = skipped,
    )
}

private fun searchMediaType(path: String, isDirectory: Boolean): DirectoryMediaType = when {
    isDirectory -> DirectoryMediaType.Other
    MediaTypes.isImage(path) -> DirectoryMediaType.Image
    MediaTypes.isVideo(path) -> DirectoryMediaType.Video
    MediaTypes.isArchive(path) -> DirectoryMediaType.Archive
    else -> DirectoryMediaType.Other
}

private fun DirectoryEntry.matches(filter: DirectoryMediaFilter): Boolean = when (filter) {
    DirectoryMediaFilter.All -> true
    DirectoryMediaFilter.Folders -> isDirectory
    DirectoryMediaFilter.Images -> mediaType == DirectoryMediaType.Image
    DirectoryMediaFilter.Videos -> mediaType == DirectoryMediaType.Video
    DirectoryMediaFilter.Archives -> mediaType == DirectoryMediaType.Archive
}
