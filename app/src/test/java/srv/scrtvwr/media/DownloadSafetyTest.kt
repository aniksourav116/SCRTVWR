package srv.scrtvwr.media

import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class DownloadSafetyTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun rejectsDownloadsThatWouldConsumeTheFreeSpaceReserve() {
        val target = temporaryFolder.newFile()
        assertThrows(IOException::class.java) {
            DownloadSafety.check(target, remainingBytes = target.parentFile!!.usableSpace)
        }
    }

    @Test fun streamingCannotExceedTheEditionLimitWithoutContentLength() {
        org.junit.Assume.assumeTrue(DownloadSafety.maximumBytes < Long.MAX_VALUE)
        assertThrows(IOException::class.java) {
            DownloadSafety.check(temporaryFolder.newFile(), bytesReceived = DownloadSafety.maximumBytes + 1)
        }
    }
}
