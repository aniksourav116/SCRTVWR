package srv.scrtvwr.media

import srv.scrtvwr.BuildConfig
import java.io.File
import java.io.IOException

/** Applies during streaming too, including responses without a trustworthy Content-Length. */
object DownloadSafety {
    const val FREE_SPACE_RESERVE = 128L * 1024L * 1024L
    private const val PLAY_DOWNLOAD_LIMIT = 1024L * 1024L * 1024L
    val maximumBytes: Long get() = if (BuildConfig.PERSONAL_EDITION) Long.MAX_VALUE else PLAY_DOWNLOAD_LIMIT

    fun check(target: File, bytesReceived: Long = 0L, remainingBytes: Long = 0L, writeBytes: Long = 0L) {
        if (bytesReceived > maximumBytes || remainingBytes > maximumBytes - bytesReceived) {
            throw IOException("This download exceeds the 1 GiB safety limit.")
        }
        val available = target.parentFile?.usableSpace ?: 0L
        if (available < FREE_SPACE_RESERVE || maxOf(remainingBytes, writeBytes) > available - FREE_SPACE_RESERVE) {
            throw IOException("Not enough storage. Downloads leave at least 128 MiB free.")
        }
    }
}
