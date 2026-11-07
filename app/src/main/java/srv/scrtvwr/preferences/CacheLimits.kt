package srv.scrtvwr.preferences

const val MEBIBYTE = 1024L * 1024L

data class CacheLimits(
    val thumbnailsMiB: Long = 64,
    val viewerMiB: Long = 512,
    val archivesMiB: Long = 1024,
) {
    fun bounded(): CacheLimits = copy(
        thumbnailsMiB = thumbnailsMiB.coerceIn(16L, 4096L),
        viewerMiB = viewerMiB.coerceIn(16L, 8192L),
        archivesMiB = archivesMiB.coerceIn(64L, 16384L),
    )
}
