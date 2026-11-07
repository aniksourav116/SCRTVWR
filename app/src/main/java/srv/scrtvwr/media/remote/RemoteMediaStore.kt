package srv.scrtvwr.media.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Local copies of server files the viewer shows, so a page coming up decodes a file on the device
 * rather than waiting on the server: whole images, and video frames already read and compressed.
 * A copy is addressed by the server URL it came from and a variant, such as the frame size.
 *
 * One fetch per copy runs at a time. A caller asking for a copy another caller is fetching waits
 * for that fetch, and fetches it itself if that one fails or is cancelled. Finished copies stay
 * until the store holds more than [maxBytes], least recently used dropped first.
 */
internal class RemoteMediaStore(@Volatile private var maxBytes: Long) {
    @Volatile private var directory: File? = null
    @Volatile private var partialDirectory: File? = null
    private val running = ConcurrentHashMap<String, CompletableDeferred<File>>()
    private val _names = MutableStateFlow<Set<String>>(emptySet())

    /**
     * File names of the finished copies, for telling which pages need no download without
     * reading the disk. See [name].
     */
    val names: StateFlow<Set<String>> = _names.asStateFlow()

    // Viewers ask for the same few names many times a second.
    private val nameCache = object : LinkedHashMap<String, String>(NAME_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > NAME_CACHE_SIZE
    }

    /**
     * Keeps copies in [directory], which should start empty, writing them first in
     * [partialDirectory], which must be on the same file system and is expected to be emptied of
     * leftovers when the process starts. Touches no disk.
     */
    fun initialize(directory: File, partialDirectory: File) {
        this.directory = directory
        this.partialDirectory = partialDirectory
    }

    /** File name of the copy of [url] in [variant]: the URL's hash first, so [forget] finds every variant. */
    fun name(url: String, variant: String): String {
        val key = "$variant:$url"
        synchronized(nameCache) { nameCache[key] }?.let { return it }
        val name = "${hex(sha256(url)).take(URL_HASH_CHARS)}-${hex(sha256(key)).take(COPY_HASH_CHARS)}"
        synchronized(nameCache) { nameCache[key] = name }
        return name
    }

    /** Whether a finished copy of [url] in [variant] is stored. Needs no disk access. */
    fun contains(url: String, variant: String): Boolean = name(url, variant) in _names.value

    /**
     * The copy of [url] in [variant], or null when none is stored. Marked as just used when [touch]
     * is true; a glance that should not keep the copy longer, such as a small thumbnail, passes
     * false. Reads the disk.
     */
    fun cachedFile(url: String, variant: String, touch: Boolean = true): File? {
        val file = File(directory ?: return null, name(url, variant))
        if (!file.isFile) {
            if (file.name in _names.value) _names.update { it - file.name }
            return null
        }
        if (touch) file.setLastModified(System.currentTimeMillis())
        if (file.name !in _names.value) _names.update { it + file.name }
        return file
    }

    /**
     * The copy of [url] in [variant], or, while another caller is fetching it, that fetch's result.
     * Null when there is neither, or that fetch failed. Never fetches.
     */
    suspend fun existing(url: String, variant: String): File? = withContext(Dispatchers.IO) {
        cachedFile(url, variant)?.let { return@withContext it }
        val fetch = running[name(url, variant)] ?: return@withContext null
        try {
            fetch.await()
        } catch (error: CancellationException) {
            ensureActive()
            null
        } catch (error: Exception) {
            null
        }
    }

    /**
     * The copy of [url] in [variant], fetched first by [fetch] into the file it is given when none
     * is stored. Throws what [fetch] threw, and leaves no partial copy behind.
     */
    suspend fun file(url: String, variant: String, fetch: suspend (target: File) -> Unit): File =
        withContext(Dispatchers.IO) { awaitOrFetch(url, variant, fetch) }

    private suspend fun awaitOrFetch(url: String, variant: String, fetch: suspend (target: File) -> Unit): File {
        val name = name(url, variant)
        while (true) {
            cachedFile(url, variant)?.let { return it }
            val mine = CompletableDeferred<File>()
            val other = running.putIfAbsent(name, mine)
            if (other == null) {
                val stored = try {
                    store(name, fetch)
                } catch (error: Throwable) {
                    // Removed first, so a waiter trying again does not find this failed fetch.
                    running.remove(name, mine)
                    mine.completeExceptionally(error)
                    throw error
                }
                running.remove(name, mine)
                mine.complete(stored)
                return stored
            }
            // Another caller's fetch failing, or being cancelled, says nothing for this one, which tries itself.
            try {
                return other.await()
            } catch (error: CancellationException) {
                currentCoroutineContext().ensureActive()
            } catch (error: Exception) {
                // Tries again below.
            }
        }
    }

    private suspend fun store(name: String, fetch: suspend (target: File) -> Unit): File {
        val directory = directory ?: throw IOException("Media store is not ready")
        val partials = partialDirectory ?: throw IOException("Media store is not ready")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Unable to create the media store")
        if (!partials.isDirectory && !partials.mkdirs()) throw IOException("Unable to create the media store")
        val target = File(directory, name)
        val temporary = File.createTempFile("copy-", PARTIAL_SUFFIX, partials)
        try {
            fetch(temporary)
            currentCoroutineContext().ensureActive()
            if (!temporary.renameTo(target)) throw IOException("Unable to store the downloaded file")
        } finally {
            temporary.delete()
        }
        target.setLastModified(System.currentTimeMillis())
        _names.update { it + name }
        trim(keep = target)
        return target
    }

    /** Drops the copy of [url] in [variant], such as one that turned out not to decode. */
    fun remove(url: String, variant: String) {
        val file = File(directory ?: return, name(url, variant))
        if (file.delete() || !file.exists()) _names.update { it - file.name }
    }

    /** Drops every copy of [urls], in every variant. Reads the disk. */
    fun forget(urls: Collection<String>) {
        if (urls.isEmpty()) return
        val prefixes = urls.mapTo(HashSet()) { hex(sha256(it)).take(URL_HASH_CHARS) }
        val dropped = directory?.listFiles()
            ?.filter { file -> file.name.substringBefore('-', missingDelimiterValue = "") in prefixes }
            ?.filter(File::delete)
            ?.mapTo(HashSet(), File::getName)
            ?: return
        if (dropped.isNotEmpty()) _names.update { it - dropped }
    }

    fun setMaxBytes(bytes: Long) {
        maxBytes = bytes.coerceAtLeast(0L)
        trim(directory?.listFiles()?.filter(File::isFile)?.maxByOrNull(File::lastModified))
    }

    /** Called while the preview loader has suspended new readers and waited for existing ones. */
    fun clear(): Boolean {
        val files = directory?.listFiles()?.filter(File::isFile).orEmpty()
        val dropped = files.filter { it.delete() || !it.exists() }.mapTo(HashSet(), File::getName)
        _names.update { it - dropped }
        return files.all { it.name in dropped }
    }

    private fun trim(keep: File?) {
        val files = directory?.listFiles()?.filter(File::isFile) ?: return
        val dropped = RemoteArchiveCache.filesToEvict(files, maxBytes, keep)
            .filter(File::delete)
            .mapTo(HashSet(), File::getName)
        if (dropped.isNotEmpty()) _names.update { it - dropped }
    }

    private fun sha256(value: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))

    // Hex by table, as String.format per byte is slow for the many names a viewer asks about.
    private fun hex(bytes: ByteArray): String {
        val chars = CharArray(bytes.size * 2)
        bytes.forEachIndexed { index, byte ->
            chars[index * 2] = HEX_DIGITS[(byte.toInt() shr 4) and 0xF]
            chars[index * 2 + 1] = HEX_DIGITS[byte.toInt() and 0xF]
        }
        return String(chars)
    }

    private companion object {
        const val PARTIAL_SUFFIX = ".part"
        const val URL_HASH_CHARS = 16
        const val COPY_HASH_CHARS = 32
        const val HEX_DIGITS = "0123456789abcdef"
        const val NAME_CACHE_SIZE = 4096
    }
}
