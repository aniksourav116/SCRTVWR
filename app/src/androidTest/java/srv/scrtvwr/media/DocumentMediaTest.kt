package srv.scrtvwr.media

import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DocumentMediaTest {
    private fun document(root: String, vararg children: String): String {
        val tree = DocumentsContract.buildTreeDocumentUri("com.example.documents", root)
        val chain = JSONArray().put(JSONArray().put(root).put("Media"))
        children.forEach { chain.put(JSONArray().put(it).put(it.substringAfterLast(':'))) }
        return DocumentsContract.buildDocumentUriUsingTree(tree, children.lastOrNull() ?: root)
            .buildUpon().fragment(chain.toString()).build().toString()
    }

    @Test fun traversalCannotEscapeTheSelectedTree() {
        val root = document("primary:Media")
        val child = document("primary:Media", "primary:Media/Photos")
        assertNull(DocumentMedia.parent(root))
        assertEquals(root, DocumentMedia.parent(child))
        assertTrue(DocumentMedia.isSameOrChildOf(child, root))
        assertFalse(DocumentMedia.isSameOrChildOf(document("primary:Other", "primary:Media/Photos"), root))
        assertFalse(DocumentMedia.isSameOrChildOf("/storage/emulated/0/Media", root))
    }

    @Test fun playbackAndSharingReceiveOnlyTheProviderUri() {
        val child = document("primary:Media", "primary:Media/photo.jpg")
        assertNull(DocumentMedia.uri(child).fragment)
        assertEquals("primary:Media/photo.jpg", DocumentsContract.getDocumentId(DocumentMedia.uri(child)))
        assertEquals("jpg", MediaPaths.extension(child))
        assertTrue(MediaTypes.isImage(child))
    }
}
