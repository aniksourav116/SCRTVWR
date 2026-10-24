package srv.scrtvwr.media

import com.github.junrar.Archive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import srv.scrtvwr.media.remote.DownloadProgress
import srv.scrtvwr.media.remote.HttpDirectoryClient
import srv.scrtvwr.media.remote.RemoteArchiveCache
import srv.scrtvwr.media.remote.RemoteDirectoryTree
import srv.scrtvwr.media.remote.RemoteEntry
import srv.scrtvwr.media.remote.RemotePaths
import srv.scrtvwr.media.remote.remoteEntryComparator
import srv.scrtvwr.ui.media.MediaViewerSource
import srv.scrtvwr.ui.media.MediaViewerItem
import srv.scrtvwr.ui.media.MediaViewerType
import srv.scrtvwr.ui.sort.DirectorySortOrder
import srv.scrtvwr.ui.sort.fileComparator
import srv.scrtvwr.ui.sort.needsFileMetadata
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

object MediaCatalog {
    private const val MAX_ARCHIVE_ITEMS = 5_000

    suspend fun listDirectory(directory: File, sortOrder: DirectorySortOrder): List<MediaViewerItem> =
        runCatching {
            val context = currentCoroutineContext()
            directory.listFiles()
                ?.asSequence()
                ?.onEach { context.ensureActive() }
                ?.filter(File::isFile)
                ?.filter { toItem(it) != null }
                ?.sortedWith(sortOrder.fileComparator(directoriesFirst = false))
                ?.mapNotNull(::toItem)
                ?.toList()
        }.getOrNull().orEmpty()

    private fun viewerType(name: String): MediaViewerType? = when {
        MediaTypes.isImage(name) -> MediaViewerType.Image
        MediaTypes.isVideo(name) -> MediaViewerType.Video
        else -> null
    }

    fun toItem(file: File): MediaViewerItem? {
        val type = viewerType(file.name) ?: return null
        return MediaViewerItem(
            name = file.name.ifBlank { file.absolutePath },
            path = file.absolutePath,
            type = type,
            lastModifiedMillis = file.lastModified(),
            sizeBytes = file.length(),
        )
    }

    /**
     * Images and videos an HTTP server lists in [directoryUrl], in [sortOrder]. Unlike
     * [listDirectory], an unreachable server throws so the viewer can say why nothing loaded.
     */
    @Throws(IOException::class)
    suspend fun listRemoteDirectory(directoryUrl: String, sortOrder: DirectorySortOrder): List<MediaViewerItem> =
        listRemoteEntries(directoryUrl, sortOrder) { toRemoteItem(it) != null }
            .sortedWith(sortOrder.remoteEntryComparator(directoriesFirst = false))
            .mapNotNull(::toRemoteItem)

    /**
     * Entries of [directoryUrl] that [include] keeps, with sizes and times only when [sortOrder]
     * needs them. Those cost a request per file, so only the kept entries ask for them.
     */
    @Throws(IOException::class)
    private suspend fun listRemoteEntries(
        directoryUrl: String,
        sortOrder: DirectorySortOrder,
        include: (RemoteEntry) -> Boolean,
    ): List<RemoteEntry> {
        val entries = HttpDirectoryClient.listForDisplay(directoryUrl).filter(include)
        return if (sortOrder.needsFileMetadata) HttpDirectoryClient.withViewerMetadata(entries) else entries
    }

    fun toRemoteItem(entry: RemoteEntry): MediaViewerItem? {
        if (entry.isDirectory) return null
        val type = viewerType(entry.name) ?: return null
        return MediaViewerItem(
            name = entry.name,
            path = entry.url,
            type = type,
            lastModifiedMillis = entry.lastModifiedMillis,
            sizeBytes = entry.sizeBytes,
        )
    }

    /** Viewer item for a single file URL, used when its directory cannot be listed. */
    fun remoteItem(url: String): MediaViewerItem? =
        toRemoteItem(RemoteEntry(name = RemotePaths.name(url), url = url, isDirectory = false))

    /** [nextDirectory] for a folder on an HTTP server, never leaving [root]. */
    suspend fun nextRemoteDirectory(
        directoryUrl: String,
        sortOrder: DirectorySortOrder,
        root: String?,
    ): MediaViewerSource? = RemoteDirectoryTree.search { tree ->
        firstRemoteDirectoryWithMedia(
            from = directoryUrl,
            candidates = DirectoryTraversal.forward(
                tree = tree,
                start = directoryUrl,
                root = RemotePaths.resolveRoot(directoryUrl, root),
                sortOrder = sortOrder,
            ),
            tree = tree,
        )
    }

    /** [previousDirectory] for a folder on an HTTP server, never leaving [root]. */
    suspend fun previousRemoteDirectory(
        directoryUrl: String,
        sortOrder: DirectorySortOrder,
        root: String?,
    ): MediaViewerSource? = RemoteDirectoryTree.search { tree ->
        firstRemoteDirectoryWithMedia(
            from = directoryUrl,
            candidates = DirectoryTraversal.backward(
                tree = tree,
                start = directoryUrl,
                root = RemotePaths.resolveRoot(directoryUrl, root),
                sortOrder = sortOrder,
            ),
            tree = tree,
        )
    }

    private suspend fun firstRemoteDirectoryWithMedia(
        from: String,
        candidates: Sequence<String>,
        tree: RemoteDirectoryTree.Search,
    ): MediaViewerSource? {
        for (candidate in candidates) {
            currentCoroutineContext().ensureActive()
            val hasMedia = tree.list(candidate).any { toRemoteItem(it) != null }
            if (hasMedia) {
                return MediaViewerSource(DirectoryTraversal.displayName(RemoteDirectoryTree, from, candidate), candidate)
            }
        }
        return null
    }

    /**
     * Next directory holding media in depth-first order: the current directory's subfolders first,
     * then the following siblings, then the siblings of each ancestor up to [root].
     */
    suspend fun nextDirectory(
        directory: File,
        sortOrder: DirectorySortOrder,
        root: File? = null,
    ): MediaViewerSource? = firstDirectoryWithMedia(
        from = directory,
        candidates = DirectoryTraversal.forward(
            start = directory,
            root = DirectoryTraversal.resolveRoot(directory, root),
            sortOrder = sortOrder,
        ),
        sortOrder = sortOrder,
    )

    /** Previous directory holding media, walking [nextDirectory]'s depth-first order backwards. */
    suspend fun previousDirectory(
        directory: File,
        sortOrder: DirectorySortOrder,
        root: File? = null,
    ): MediaViewerSource? = firstDirectoryWithMedia(
        from = directory,
        candidates = DirectoryTraversal.backward(
            start = directory,
            root = DirectoryTraversal.resolveRoot(directory, root),
            sortOrder = sortOrder,
        ),
        sortOrder = sortOrder,
    )

    private suspend fun firstDirectoryWithMedia(
        from: File,
        candidates: Sequence<File>,
        sortOrder: DirectorySortOrder,
    ): MediaViewerSource? {
        for (candidate in candidates) {
            currentCoroutineContext().ensureActive()
            if (listDirectory(candidate, sortOrder).isNotEmpty()) {
                return MediaViewerSource(DirectoryTraversal.displayName(from, candidate), candidate.absolutePath)
            }
        }
        return null
    }

    /** [nextArchive] for a local archive path or an archive URL. */
    suspend fun nextArchive(path: String, sortOrder: DirectorySortOrder): MediaViewerSource? =
        if (MediaPaths.isRemote(path)) adjacentRemoteArchive(path, sortOrder, step = 1) else nextArchive(File(path), sortOrder)

    /** [previousArchive] for a local archive path or an archive URL. */
    suspend fun previousArchive(path: String, sortOrder: DirectorySortOrder): MediaViewerSource? =
        if (MediaPaths.isRemote(path)) adjacentRemoteArchive(path, sortOrder, step = -1) else previousArchive(File(path), sortOrder)

    /**
     * The archive [step] places away from [archiveUrl] in its server folder. Unlike local archives,
     * neighbours are not checked for images first, since that would mean downloading each one.
     */
    private suspend fun adjacentRemoteArchive(
        archiveUrl: String,
        sortOrder: DirectorySortOrder,
        step: Int,
    ): MediaViewerSource? {
        val directoryUrl = RemotePaths.parent(archiveUrl) ?: return null
        val entries = try {
            listRemoteEntries(directoryUrl, sortOrder) { !it.isDirectory && MediaTypes.isArchive(it.name) }
        } catch (_: IOException) {
            return null
        }
        val archives = entries.sortedWith(sortOrder.remoteEntryComparator(directoriesFirst = false))
        val index = archives.indexOfFirst { it.url == archiveUrl }
        if (index < 0) return null
        val candidate = archives.getOrNull(index + step) ?: return null
        return MediaViewerSource(candidate.name, candidate.url, isArchive = true)
    }

    suspend fun nextArchive(file: File, sortOrder: DirectorySortOrder): MediaViewerSource? {
        return adjacentArchive(
            file = file,
            sortOrder = sortOrder,
            candidateSelector = { archives, currentPath ->
                archives
                    .dropWhile { it.canonicalPathOrAbsolute() != currentPath }
                    .drop(1)
            },
        )
    }

    suspend fun previousArchive(file: File, sortOrder: DirectorySortOrder): MediaViewerSource? {
        return adjacentArchive(
            file = file,
            sortOrder = sortOrder,
            candidateSelector = { archives, currentPath ->
                archives
                    .takeWhile { it.canonicalPathOrAbsolute() != currentPath }
                    .asReversed()
            },
        )
    }

    private suspend fun adjacentArchive(
        file: File,
        sortOrder: DirectorySortOrder,
        candidateSelector: (List<File>, String) -> List<File>,
    ): MediaViewerSource? {
        val currentPath = file.canonicalPathOrAbsolute()
        val archives = file.parentFile
            ?.listFiles()
            ?.asSequence()
            ?.filter(File::isFile)
            ?.filter { MediaTypes.isArchive(it.path) }
            ?.sortedWith(sortOrder.fileComparator(directoriesFirst = false))
            ?.toList()
            .orEmpty()
        val candidates = candidateSelector(archives, currentPath)
        for (candidate in candidates) {
            currentCoroutineContext().ensureActive()
            val hasImages = runCatching { listArchive(candidate).isNotEmpty() }.getOrDefault(false)
            if (hasImages) {
                return MediaViewerSource(candidate.name.ifBlank { candidate.absolutePath }, candidate.absolutePath, true)
            }
        }
        return null
    }

    /**
     * Images in a local archive or an archive URL. An archive on a server is downloaded into
     * [RemoteArchiveCache] first, reporting [onDownloadProgress]; its items still point into the
     * archive URL rather than the cached copy.
     */
    suspend fun listArchive(
        path: String,
        onDownloadProgress: (DownloadProgress) -> Unit = {},
    ): List<MediaViewerItem> =
        if (MediaPaths.isRemote(path)) {
            // Server files have no local timestamp; the copy's time only says when it was last read.
            readArchive(RemoteArchiveCache.file(path, onDownloadProgress), archivePath = path, modifiedMillis = 0L)
        } else {
            listArchive(File(path))
        }

    suspend fun listArchive(file: File): List<MediaViewerItem> =
        readArchive(file, archivePath = file.absolutePath, modifiedMillis = file.lastModified())

    /** Images in [file], addressed as entries of [archivePath]. */
    private suspend fun readArchive(file: File, archivePath: String, modifiedMillis: Long): List<MediaViewerItem> {
        val context = currentCoroutineContext()
        return when (MediaTypes.archiveFormat(archivePath)) {
        MediaTypes.ArchiveFormat.Zip -> ZipFile(file).use { zip ->
            zip.entries().asSequence()
                .filter { !it.isDirectory && MediaTypes.isImage(it.name) }
                .onEach { context.ensureActive() }
                .take(MAX_ARCHIVE_ITEMS)
                .map { entry ->
                    MediaViewerItem(
                        name = entry.name.substringAfterLast('/'),
                        path = MediaPaths.archiveEntryPath(archivePath, entry.name),
                        type = MediaViewerType.Image,
                        lastModifiedMillis = entry.time,
                        sizeBytes = entry.size,
                    )
                }
                .sortedBy(MediaViewerItem::name)
                .toList()
        }
        MediaTypes.ArchiveFormat.Rar -> Archive(file).use { archive ->
            archive.fileHeaders.asSequence()
                .filter { !it.isDirectory && MediaTypes.isImage(it.fileName) }
                .onEach { context.ensureActive() }
                .take(MAX_ARCHIVE_ITEMS)
                .map { header ->
                    MediaViewerItem(
                        name = header.fileName.substringAfterLast('\\').substringAfterLast('/'),
                        path = MediaPaths.archiveEntryPath(archivePath, header.fileName),
                        type = MediaViewerType.Image,
                        lastModifiedMillis = header.mTime?.time ?: modifiedMillis,
                        sizeBytes = header.fullUnpackSize,
                    )
                }
                .sortedBy(MediaViewerItem::name)
                .toList()
        }
            null -> error("Unsupported archive type")
        }
    }
}
