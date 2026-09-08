package srv.scrtvwr.media

import com.github.junrar.Archive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import srv.scrtvwr.ui.media.MediaViewerSource
import srv.scrtvwr.ui.media.MediaViewerItem
import srv.scrtvwr.ui.media.MediaViewerType
import srv.scrtvwr.ui.sort.DirectorySortOrder
import srv.scrtvwr.ui.sort.fileComparator
import java.io.File
import java.util.zip.ZipFile

object MediaCatalog {
    private const val MAX_ARCHIVE_ITEMS = 5_000
    private const val ARCHIVE_ENTRY_SEPARATOR = "::"

    enum class ArchiveFormat {
        Zip,
        Rar,
    }

    fun isArchive(path: String): Boolean = archiveFormat(path) != null

    fun archiveFormat(path: String): ArchiveFormat? = when (
        path.substringBefore(ARCHIVE_ENTRY_SEPARATOR).substringAfterLast('.', "").lowercase()
    ) {
        "zip", "cbz" -> ArchiveFormat.Zip
        "rar", "cbr" -> ArchiveFormat.Rar
        else -> null
    }

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

    fun toItem(file: File): MediaViewerItem? {
        val type = when (file.extension.lowercase()) {
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif" -> MediaViewerType.Image
            "mp4", "mkv", "webm", "3gp", "mov", "avi", "m4v" -> MediaViewerType.Video
            else -> return null
        }
        return MediaViewerItem(
            name = file.name.ifBlank { file.absolutePath },
            path = file.absolutePath,
            type = type,
            lastModifiedMillis = file.lastModified(),
            sizeBytes = file.length(),
        )
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
            ?.filter { isArchive(it.path) }
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

    suspend fun listArchive(file: File): List<MediaViewerItem> {
        val context = currentCoroutineContext()
        return when (archiveFormat(file.path)) {
        ArchiveFormat.Zip -> ZipFile(file).use { zip ->
            zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.isImageExtension() }
                .onEach { context.ensureActive() }
                .take(MAX_ARCHIVE_ITEMS)
                .map { entry ->
                    MediaViewerItem(
                        name = entry.name.substringAfterLast('/'),
                        path = "${file.absolutePath}::${entry.name}",
                        type = MediaViewerType.Image,
                        lastModifiedMillis = entry.time,
                        sizeBytes = entry.size,
                    )
                }
                .sortedBy(MediaViewerItem::name)
                .toList()
        }
        ArchiveFormat.Rar -> Archive(file).use { archive ->
            archive.fileHeaders.asSequence()
                .filter { !it.isDirectory && it.fileName.isImageExtension() }
                .onEach { context.ensureActive() }
                .take(MAX_ARCHIVE_ITEMS)
                .map { header ->
                    MediaViewerItem(
                        name = header.fileName.substringAfterLast('\\').substringAfterLast('/'),
                        path = "${file.absolutePath}::${header.fileName}",
                        type = MediaViewerType.Image,
                        lastModifiedMillis = header.mTime?.time ?: file.lastModified(),
                        sizeBytes = header.fullUnpackSize,
                    )
                }
                .sortedBy(MediaViewerItem::name)
                .toList()
        }
            null -> error("Unsupported archive type")
        }
    }

    private fun String.isImageExtension(): Boolean =
        substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")
}
