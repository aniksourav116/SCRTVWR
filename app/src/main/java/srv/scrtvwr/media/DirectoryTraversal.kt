package srv.scrtvwr.media

import srv.scrtvwr.ui.sort.DirectorySortOrder
import srv.scrtvwr.ui.sort.fileComparator
import java.io.File

/**
 * Depth-first directory ordering shared by the image viewer and the video player.
 *
 * Directories are visited pre-order: a directory first, then each of its subdirectories and their
 * subtrees in sort order. Once a subtree is exhausted the walk climbs back up and continues with
 * the next sibling of the ancestor it came from, never stepping above the traversal root.
 */
object DirectoryTraversal {
    /** Upper bound on directories inspected for a single hop, so deep trees stay responsive. */
    const val MAX_SCANNED_DIRECTORIES = 2_000

    /**
     * Highest directory a hop starting at [directory] may reach. Falls back to the directory's own
     * parent when [preferredRoot] is not an ancestor, which keeps the walk local.
     */
    fun resolveRoot(directory: File, preferredRoot: File?): File =
        preferredRoot?.takeIf { directory.isSameOrChildOf(it) } ?: directory.parentFile ?: directory

    /** Directories after [start] in depth-first order, nearest first. */
    fun forward(start: File, root: File, sortOrder: DirectorySortOrder): Sequence<File> = sequence {
        val pending = ArrayDeque<File>()
        pending.addAll(start.subDirectories(sortOrder))
        var node = start
        while (!node.isSamePath(root)) {
            val parent = node.parentFile ?: break
            pending.addAll(parent.subDirectories(sortOrder).after(node))
            node = parent
        }
        while (pending.isNotEmpty()) {
            val directory = pending.removeFirst()
            yield(directory)
            pending.addAll(0, directory.subDirectories(sortOrder))
        }
    }.take(MAX_SCANNED_DIRECTORIES)

    /** Directories before [start] in depth-first order, nearest first. */
    fun backward(start: File, root: File, sortOrder: DirectorySortOrder): Sequence<File> = sequence {
        var node = start
        while (!node.isSamePath(root)) {
            val parent = node.parentFile ?: break
            for (sibling in parent.subDirectories(sortOrder).before(node).asReversed()) {
                yieldAll(subtreeReversed(sibling, sortOrder))
            }
            yield(parent)
            node = parent
        }
    }.take(MAX_SCANNED_DIRECTORIES)

    /**
     * Label for a hop from [from] to [to]: the folder name alone for a sibling or a child, and the
     * trailing path from the closest shared ancestor once the hop crosses into another subtree.
     */
    fun displayName(from: File, to: File): String {
        val fromSegments = from.pathSegments()
        val toSegments = to.pathSegments()
        val shared = fromSegments.zip(toSegments).takeWhile { (left, right) -> left == right }.count()
        return toSegments.drop(shared)
            .joinToString("/")
            .ifBlank { to.name.ifBlank { to.absolutePath } }
    }

    /** [directory] and its subtree in reverse depth-first order, so the directory itself comes last. */
    private fun subtreeReversed(directory: File, sortOrder: DirectorySortOrder): Sequence<File> = sequence {
        for (child in directory.subDirectories(sortOrder).asReversed()) {
            yieldAll(subtreeReversed(child, sortOrder))
        }
        yield(directory)
    }

    private fun File.subDirectories(sortOrder: DirectorySortOrder): List<File> = listFiles()
        ?.asSequence()
        ?.filter(File::isDirectory)
        ?.sortedWith(sortOrder.fileComparator(directoriesFirst = false))
        ?.toList()
        .orEmpty()

    private fun List<File>.after(node: File): List<File> =
        indexOfPath(node).takeIf { it >= 0 }?.let { drop(it + 1) }.orEmpty()

    private fun List<File>.before(node: File): List<File> =
        indexOfPath(node).takeIf { it >= 0 }?.let { take(it) } ?: this

    private fun List<File>.indexOfPath(node: File): Int {
        val path = node.canonicalPathOrAbsolute()
        return indexOfFirst { it.canonicalPathOrAbsolute() == path }
    }

    private fun File.pathSegments(): List<String> =
        canonicalPathOrAbsolute().split(File.separatorChar).filter(String::isNotEmpty)

    private fun File.isSamePath(other: File): Boolean =
        canonicalPathOrAbsolute() == other.canonicalPathOrAbsolute()

    private fun File.isSameOrChildOf(root: File): Boolean {
        val rootPath = root.canonicalPathOrAbsolute()
        val path = canonicalPathOrAbsolute()
        return path == rootPath || path.startsWith("$rootPath${File.separator}")
    }
}

internal fun File.canonicalPathOrAbsolute(): String =
    runCatching { canonicalPath }.getOrElse { absolutePath }
