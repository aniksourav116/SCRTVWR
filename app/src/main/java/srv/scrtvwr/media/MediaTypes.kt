package srv.scrtvwr.media

/**
 * Which files the app shows as images, videos or archives, decided by extension alone. Every
 * check takes a name or path of any kind (a local path, a server URL, an archive entry name or an
 * archive entry path) and reads the extension of its last name, ignoring case.
 */
object MediaTypes {
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")
    private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "webm", "3gp", "mov", "avi", "m4v")

    enum class ArchiveFormat {
        Zip,
        Rar,
    }

    fun isImage(path: String): Boolean = extensionOf(path) in IMAGE_EXTENSIONS

    fun isVideo(path: String): Boolean = extensionOf(path) in VIDEO_EXTENSIONS

    fun isArchive(path: String): Boolean = archiveFormat(path) != null

    /** Format of the archive [path] is, or points into when it is an archive entry path. */
    fun archiveFormat(path: String): ArchiveFormat? = when (extensionOf(MediaPaths.archivePath(path))) {
        "zip", "cbz" -> ArchiveFormat.Zip
        "rar", "cbr" -> ArchiveFormat.Rar
        else -> null
    }

    private fun extensionOf(path: String): String =
        path.substringAfterLast('/').substringAfterLast('\\').substringAfterLast('.', "").lowercase()
}
