package srv.scrtvwr.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import srv.scrtvwr.ui.sort.DirectorySortOrder

class DirectoryTraversalTest {
    private val sortOrder = DirectorySortOrder.NameAscending

    @Test
    fun traversal_preservesDepthFirstOrderInBothDirections() {
        val tree = FakeTree(
            mapOf(
                "root" to listOf("a", "b", "c"),
                "a" to listOf("a1", "a2"),
                "a2" to listOf("a21"),
                "b" to listOf("b1"),
            ),
        )

        assertEquals(
            listOf("a1", "a2", "a21", "b", "b1", "c"),
            DirectoryTraversal.forward(tree, "a", "root", sortOrder).toList(),
        )
        assertEquals(
            listOf("a21", "a2", "a1", "a", "root"),
            DirectoryTraversal.backward(tree, "b", "root", sortOrder).toList(),
        )
    }

    @Test
    fun backward_limitsExpansionsBeforeTheFirstCandidate() {
        var requests = 0
        val tree = object : DirectoryTraversal.Tree<Int> {
            override fun parent(directory: Int): Int? = if (directory == 0) null else 0
            override fun subDirectories(directory: Int, sortOrder: DirectorySortOrder): List<Int> {
                requests++
                return if (directory == 0) listOf(2, 1) else listOf(directory + 1)
            }
            override fun key(directory: Int): String = directory.toString()
            override fun pathSegments(directory: Int): List<String> = listOf(key(directory))
            override fun name(directory: Int): String = key(directory)
        }

        assertNull(DirectoryTraversal.backward(tree, 1, 0, sortOrder).firstOrNull())
        assertEquals(DirectoryTraversal.MAX_SCANNED_DIRECTORIES, requests)
    }

    @Test
    fun traversal_skipsRepeatedDirectoryIdentities() {
        val tree = FakeTree(
            mapOf(
                "root" to listOf("a", "b"),
                "a" to listOf("a1"),
                "a1" to listOf("a"),
            ),
        )

        assertEquals(listOf("a1", "b"), DirectoryTraversal.forward(tree, "a", "root", sortOrder).toList())
        assertEquals(listOf("a1", "a", "root"), DirectoryTraversal.backward(tree, "b", "root", sortOrder).toList())
        assertTrue(tree.requests < 20)
    }

    private class FakeTree(private val children: Map<String, List<String>>) : DirectoryTraversal.Tree<String> {
        var requests = 0
        override fun parent(directory: String): String? = children.entries.firstOrNull { directory in it.value }?.key
        override fun subDirectories(directory: String, sortOrder: DirectorySortOrder): List<String> {
            requests++
            return children[directory].orEmpty()
        }
        override fun key(directory: String): String = directory
        override fun pathSegments(directory: String): List<String> = listOf(directory)
        override fun name(directory: String): String = directory
    }
}
