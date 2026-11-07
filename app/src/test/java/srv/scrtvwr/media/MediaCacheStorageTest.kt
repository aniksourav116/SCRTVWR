package srv.scrtvwr.media

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MediaCacheStorageTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun usageSeparatesDownloadsFromCompletedCachesAndExcludesUnrelatedFiles() {
        val root = temporaryFolder.newFolder("cache")
        fun write(path: String, size: Int) {
            File(root, path).apply { parentFile!!.mkdirs(); writeBytes(ByteArray(size)) }
        }
        write("media_thumbnails/a.webp", 10)
        write("remote_media/image", 20)
        write("remote_archives/book.zip", 30)
        write("remote_archives/download.part", 40)
        write("remote_images/copy.part", 50)
        write("unrelated/file", 100)

        val usage = mediaCacheUsage(root)
        assertEquals(10L, usage.thumbnailsBytes)
        assertEquals(20L, usage.viewerBytes)
        assertEquals(30L, usage.archivesBytes)
        assertEquals(90L, usage.temporaryBytes)
        assertEquals(150L, usage.totalBytes)
    }
}
