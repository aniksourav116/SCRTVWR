package srv.scrtvwr.media.remote

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class HttpRangeReaderTest {
    private val video = ByteArray(10_000) { (it * 31 % 251).toByte() }

    @Test
    fun readAt_fetchesOnlyTheBlocksItIsAskedFor() {
        TestHttpServer(mapOf("clip.mp4" to video), supportsRanges = true).use { server ->
            val reader = HttpRangeReader(server.url("clip.mp4"), maxBytes = 1_000_000, blockBytes = 4096)
            val buffer = ByteArray(200)

            assertEquals(10_000L, reader.size())
            assertEquals(100, reader.readAt(5_000, buffer, 0, 100))
            assertArrayEquals(video.copyOfRange(5_000, 5_100), buffer.copyOf(100))
            // A read crossing a block boundary stops there; callers ask again for the rest.
            assertEquals(96, reader.readAt(4_000, buffer, 10, 200))
            assertArrayEquals(video.copyOfRange(4_000, 4_096), buffer.copyOfRange(10, 106))
            assertEquals(100, reader.readAt(9_900, buffer, 0, 200))
            assertArrayEquals(video.copyOfRange(9_900, 10_000), buffer.copyOf(100))
            assertEquals(HttpRangeReader.END_OF_FILE, reader.readAt(10_000, buffer, 0, 100))

            // Block 0 for the size, then blocks 1 and 2; the reread of block 0 came from memory.
            assertEquals(3, server.fileRequests)
            assertNull(reader.stopReason)
        }
    }

    @Test
    fun readAt_givesUpOnServersThatIgnoreRanges() {
        TestHttpServer(mapOf("clip.mp4" to video)).use { server ->
            val url = server.url("clip.mp4")
            val reader = HttpRangeReader(url, maxBytes = 1_000_000)

            assertTrue(HttpRangeReader.supportsRanges(url))
            assertThrows(IOException::class.java) { reader.readAt(0, ByteArray(16), 0, 16) }
            assertEquals(HttpRangeReader.StopReason.RangesUnsupported, reader.stopReason)
            assertFalse(HttpRangeReader.supportsRanges(url))
            assertFalse(HttpRangeReader.supportsRanges(server.url("other.mp4")))
        }
    }

    @Test
    fun readAt_stopsOnceTheByteBudgetIsSpent() {
        TestHttpServer(mapOf("clip.mp4" to video), supportsRanges = true).use { server ->
            val reader = HttpRangeReader(server.url("clip.mp4"), maxBytes = 6_000, blockBytes = 4096)

            assertEquals(10_000L, reader.size())
            assertThrows(IOException::class.java) { reader.readAt(5_000, ByteArray(16), 0, 16) }
            assertEquals(HttpRangeReader.StopReason.BudgetExceeded, reader.stopReason)
        }
    }

    @Test
    fun readAt_failsWithoutARequestOnceCancelled() {
        TestHttpServer(mapOf("clip.mp4" to video), supportsRanges = true).use { server ->
            val reader = HttpRangeReader(server.url("clip.mp4"), maxBytes = 1_000_000)

            reader.cancel()

            assertThrows(IOException::class.java) { reader.readAt(0, ByteArray(16), 0, 16) }
            assertEquals(HttpRangeReader.StopReason.Cancelled, reader.stopReason)
            assertEquals(0, server.fileRequests)
        }
    }

    @Test
    fun contentRangeTotal_readsTheLengthAfterTheSlash() {
        assertEquals(1234L, HttpRangeReader.contentRangeTotal("bytes 0-99/1234"))
        assertEquals(0L, HttpRangeReader.contentRangeTotal("bytes */0"))
        assertNull(HttpRangeReader.contentRangeTotal("bytes 0-99/*"))
        assertNull(HttpRangeReader.contentRangeTotal(null))
    }
}
