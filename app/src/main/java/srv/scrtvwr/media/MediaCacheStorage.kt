package srv.scrtvwr.media

import java.io.File

data class MediaCacheUsage(
    val thumbnailsBytes: Long = 0,
    val viewerBytes: Long = 0,
    val archivesBytes: Long = 0,
    val temporaryBytes: Long = 0,
    val availableBytes: Long = 0,
) {
    val totalBytes: Long
        get() = thumbnailsBytes + viewerBytes + archivesBytes + temporaryBytes
}

/** Counts only SCRTVWR media caches, never user media or preferences. Run off the UI thread. */
fun mediaCacheUsage(cacheDirectory: File): MediaCacheUsage {
    fun bytes(name: String): Long = File(cacheDirectory, name).listFiles()
        ?.filter(File::isFile)?.sumOf(File::length) ?: 0L
    return MediaCacheUsage(
        thumbnailsBytes = bytes("media_thumbnails"),
        viewerBytes = bytes("remote_media"),
        archivesBytes = File(cacheDirectory, "remote_archives").listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".part") }?.sumOf(File::length) ?: 0L,
        temporaryBytes = bytes("remote_images") + bytes("shared_media") + (File(cacheDirectory, "remote_archives").listFiles()
            ?.filter { it.isFile && it.name.endsWith(".part") }?.sumOf(File::length) ?: 0L),
        availableBytes = cacheDirectory.usableSpace,
    )
}
