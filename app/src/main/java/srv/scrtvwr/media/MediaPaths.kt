package srv.scrtvwr.media

import srv.scrtvwr.media.remote.RemotePaths
import java.io.File

/**
 * Path operations for media paths, which are either local file paths or HTTP server URLs. An
 * image inside an archive is addressed as `<archive path or URL>::<entry name>`.
 */
object MediaPaths {
    private const val ARCHIVE_ENTRY_SEPARATOR = "::"

    fun isRemote(path: String?): Boolean = RemotePaths.isRemote(path)

    /** The archive an archive entry path points into, or [path] itself for any other path. */
    fun archivePath(path: String): String = path.substringBefore(ARCHIVE_ENTRY_SEPARATOR)

    /** Name of the entry an archive entry path points to, or null for any other path. */
    fun archiveEntryName(path: String): String? =
        if (ARCHIVE_ENTRY_SEPARATOR in path) path.substringAfter(ARCHIVE_ENTRY_SEPARATOR) else null

    /**
     * Path of [entryName] inside [archivePath]. Archives on a server keep their URL here, never the
     * cached copy's path, so saved paths outlive the cache.
     */
    fun archiveEntryPath(archivePath: String, entryName: String): String =
        "$archivePath$ARCHIVE_ENTRY_SEPARATOR$entryName"

    /** Directory holding [path]: an absolute local path, or a directory URL ending with `/`. */
    fun parent(path: String): String? =
        if (isRemote(path)) RemotePaths.parent(path) else File(path).parentFile?.absolutePath

    fun name(path: String): String =
        if (isRemote(path)) RemotePaths.name(path) else File(path).name.ifBlank { path }

    fun extension(path: String): String =
        if (isRemote(path)) RemotePaths.extension(path) else File(path).extension.lowercase()
}
