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

    /** How the walk reads one kind of directory tree, such as device storage or an HTTP server. */
    interface Tree<T> {
        fun parent(directory: T): T?

        fun subDirectories(directory: T, sortOrder: DirectorySortOrder): List<T>

        /** Identity used to tell directories apart, such as a canonical path. */
        fun key(directory: T): String

        fun pathSegments(directory: T): List<String>

        fun name(directory: T): String
    }

    /**
     * Highest directory a hop starting at [directory] may reach. Falls back to the directory's own
     * parent when [preferredRoot] is not an ancestor, which keeps the walk local.
     */
    fun resolveRoot(directory: File, preferredRoot: File?): File =
        preferredRoot?.takeIf { directory.isSameOrChildOf(it) } ?: directory.parentFile ?: directory

    /** Directories after [start] in depth-first order, nearest first. */
    fun forward(start: File, root: File, sortOrder: DirectorySortOrder): Sequence<File> =
        forward(LocalTree, start, root, sortOrder)

    /** Directories before [start] in depth-first order, nearest first. */
    fun backward(start: File, root: File, sortOrder: DirectorySortOrder): Sequence<File> =
        backward(LocalTree, start, root, sortOrder)

    /**
     * Label for a hop from [from] to [to]: the folder name alone for a sibling or a child, and the
     * trailing path from the closest shared ancestor once the hop crosses into another subtree.
     */
    fun displayName(from: File, to: File): String = displayName(LocalTree, from, to)

    fun <T> forward(tree: Tree<T>, start: T, root: T, sortOrder: DirectorySortOrder): Sequence<T> = sequence {
        val pending = ArrayDeque<T>()
        pending.addAll(tree.subDirectories(start, sortOrder))
        var node = start
        while (!tree.isSame(node, root)) {
            val parent = tree.parent(node) ?: break
            pending.addAll(tree.subDirectories(parent, sortOrder).after(tree, node))
            node = parent
        }
        while (pending.isNotEmpty()) {
            val directory = pending.removeFirst()
            yield(directory)
            pending.addAll(0, tree.subDirectories(directory, sortOrder))
        }
    }.take(MAX_SCANNED_DIRECTORIES)

    fun <T> backward(tree: Tree<T>, start: T, root: T, sortOrder: DirectorySortOrder): Sequence<T> = sequence {
        var node = start
        while (!tree.isSame(node, root)) {
            val parent = tree.parent(node) ?: break
            for (sibling in tree.subDirectories(parent, sortOrder).before(tree, node).asReversed()) {
                yieldAll(subtreeReversed(tree, sibling, sortOrder))
            }
            yield(parent)
            node = parent
        }
    }.take(MAX_SCANNED_DIRECTORIES)

    fun <T> displayName(tree: Tree<T>, from: T, to: T): String {
        val fromSegments = tree.pathSegments(from)
        val toSegments = tree.pathSegments(to)
        val shared = fromSegments.zip(toSegments).takeWhile { (left, right) -> left == right }.count()
        return toSegments.drop(shared)
            .joinToString("/")
            .ifBlank { tree.name(to) }
    }

    /** [directory] and its subtree in reverse depth-first order, so the directory itself comes last. */
    private fun <T> subtreeReversed(tree: Tree<T>, directory: T, sortOrder: DirectorySortOrder): Sequence<T> = sequence {
        for (child in tree.subDirectories(directory, sortOrder).asReversed()) {
            yieldAll(subtreeReversed(tree, child, sortOrder))
        }
        yield(directory)
    }

    private fun <T> Tree<T>.isSame(left: T, right: T): Boolean = key(left) == key(right)

    private fun <T> List<T>.after(tree: Tree<T>, node: T): List<T> =
        indexOfKey(tree, node).takeIf { it >= 0 }?.let { drop(it + 1) }.orEmpty()

    private fun <T> List<T>.before(tree: Tree<T>, node: T): List<T> =
        indexOfKey(tree, node).takeIf { it >= 0 }?.let { take(it) } ?: this

    private fun <T> List<T>.indexOfKey(tree: Tree<T>, node: T): Int {
        val key = tree.key(node)
        return indexOfFirst { tree.key(it) == key }
    }

    private object LocalTree : Tree<File> {
        override fun parent(directory: File): File? = directory.parentFile

        override fun subDirectories(directory: File, sortOrder: DirectorySortOrder): List<File> = directory.listFiles()
            ?.asSequence()
            ?.filter(File::isDirectory)
            ?.sortedWith(sortOrder.fileComparator(directoriesFirst = false))
            ?.toList()
            .orEmpty()

        override fun key(directory: File): String = directory.canonicalPathOrAbsolute()

        override fun pathSegments(directory: File): List<String> =
            directory.canonicalPathOrAbsolute().split(File.separatorChar).filter(String::isNotEmpty)

        override fun name(directory: File): String = directory.name.ifBlank { directory.absolutePath }
    }

    private fun File.isSameOrChildOf(root: File): Boolean {
        val rootPath = root.canonicalPathOrAbsolute()
        val path = canonicalPathOrAbsolute()
        return path == rootPath || path.startsWith("$rootPath${File.separator}")
    }
}

internal fun File.canonicalPathOrAbsolute(): String =
    runCatching { canonicalPath }.getOrElse { absolutePath }
