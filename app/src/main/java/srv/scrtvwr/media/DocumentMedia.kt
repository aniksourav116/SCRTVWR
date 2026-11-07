package srv.scrtvwr.media

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import srv.scrtvwr.media.remote.RemoteEntry
import srv.scrtvwr.ui.media.MediaViewerItem
import srv.scrtvwr.ui.media.MediaViewerSource
import srv.scrtvwr.ui.sort.DirectorySortOrder
import srv.scrtvwr.media.remote.remoteEntryComparator
import java.io.IOException

/** Persisted, user-selected document trees. Never resolves a document URI to a filesystem path. */
object DocumentMedia : DirectoryTraversal.Tree<String> {
    private lateinit var context: Context
    private val projection = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)

    fun initialize(context: Context) { this.context = context.applicationContext }

    fun isDocument(path: String?): Boolean = path?.startsWith("content://") == true

    /** Fragments store navigation ancestry; they are removed before talking to a provider/player. */
    fun uri(path: String): Uri = Uri.parse(MediaPaths.archivePath(path)).buildUpon().fragment(null).build()

    fun rememberTree(tree: Uri): String {
        context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return rootPath(tree).also { selectedTrees().edit().putString(tree.toString(), it).apply() }
    }

    // Listing granted roots must not query remote document providers on the UI thread.
    fun roots(): List<String> = context.contentResolver.persistedUriPermissions
        .filter { it.isReadPermission && DocumentsContract.isTreeUri(it.uri) }
        .mapNotNull { permission -> runCatching {
            selectedTrees().getString(permission.uri.toString(), null) ?: run {
                val id = DocumentsContract.getTreeDocumentId(permission.uri)
                path(permission.uri, JSONArray().put(JSONArray().put(id).put(id.substringAfterLast('/').substringAfter(':').ifBlank { "Selected folder" })))
            }
        }.getOrNull() }

    private fun selectedTrees() = context.getSharedPreferences("selected_document_trees", Context.MODE_PRIVATE)

    fun hasAccess(path: String): Boolean = runCatching {
        val tree = treeUri(path)
        context.contentResolver.persistedUriPermissions.any { it.isReadPermission && it.uri == tree }
    }.getOrDefault(false)

    fun forgetTree(path: String) {
        val tree = treeUri(path)
        context.contentResolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        selectedTrees().edit().remove(tree.toString()).apply()
    }

    private fun rootPath(tree: Uri): String {
        val document = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val displayName = context.contentResolver.query(document, projection, null, null, null)?.use {
            if (it.moveToFirst()) it.getString(1) else null
        } ?: throw IOException("This folder is unavailable. Choose it again from Storage.")
        return path(tree, JSONArray().put(JSONArray().put(DocumentsContract.getTreeDocumentId(tree)).put(displayName)))
    }

    private fun treeUri(path: String): Uri {
        val document = uri(path)
        return DocumentsContract.buildTreeDocumentUri(document.authority, DocumentsContract.getTreeDocumentId(document))
    }

    private fun ancestry(path: String): JSONArray = JSONArray(Uri.parse(MediaPaths.archivePath(path)).fragment ?: "[]")

    private fun path(tree: Uri, ancestry: JSONArray): String {
        val id = ancestry.getJSONArray(ancestry.length() - 1).getString(0)
        return DocumentsContract.buildDocumentUriUsingTree(tree, id).buildUpon().fragment(ancestry.toString()).build().toString()
    }

    override fun name(directory: String): String = runCatching {
        val chain = ancestry(directory)
        chain.getJSONArray(chain.length() - 1).getString(1)
    }.getOrDefault("Selected folder")

    override fun parent(directory: String): String? {
        val chain = ancestry(directory)
        if (chain.length() <= 1) return null
        val parent = JSONArray()
        for (index in 0 until chain.length() - 1) parent.put(chain.getJSONArray(index))
        return path(treeUri(directory), parent)
    }

    fun root(directory: String): String = path(treeUri(directory), JSONArray().put(ancestry(directory).getJSONArray(0)))

    fun isSameOrChildOf(document: String, root: String): Boolean {
        if (!isDocument(document) || !isDocument(root) || treeUri(document) != treeUri(root)) return false
        val child = ancestry(document)
        val ancestor = ancestry(root)
        return child.length() >= ancestor.length() && (0 until ancestor.length()).all {
            child.getJSONArray(it).getString(0) == ancestor.getJSONArray(it).getString(0)
        }
    }

    fun isDirectory(path: String): Boolean = runCatching {
        context.contentResolver.query(uri(path), arrayOf(Document.COLUMN_MIME_TYPE), null, null, null)?.use {
            it.moveToFirst() && it.getString(0) == Document.MIME_TYPE_DIR
        } ?: false
    }.getOrDefault(false)

    fun exists(path: String): Boolean = runCatching {
        context.contentResolver.query(uri(path), arrayOf(Document.COLUMN_DOCUMENT_ID), null, null, null)?.use {
            it.moveToFirst()
        } ?: false
    }.getOrDefault(false)

    @Throws(IOException::class)
    fun list(directory: String): List<RemoteEntry> {
        val parent = uri(directory)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent))
        val tree = treeUri(directory)
        val ancestors = ancestry(directory)
        try {
            return context.contentResolver.query(children, projection, null, null, null)?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val chain = JSONArray(ancestors.toString())
                            .put(JSONArray().put(cursor.getString(0)).put(cursor.getString(1)))
                        add(RemoteEntry(cursor.getString(1), path(tree, chain),
                            cursor.getString(2) == Document.MIME_TYPE_DIR,
                            cursor.longOrUnknown(3), cursor.longOrUnknown(4).coerceAtLeast(0L)))
                    }
                }
            } ?: throw IOException("This folder is unavailable. Choose it again from Storage.")
        } catch (error: SecurityException) {
            throw IOException("Folder access was revoked. Choose it again from Storage.", error)
        } catch (error: Exception) {
            throw if (error is IOException) error else IOException("This folder is unavailable. Choose it again from Storage.", error)
        }
    }

    private fun Cursor.longOrUnknown(column: Int): Long = if (isNull(column)) -1L else getLong(column)

    fun open(path: String) = context.contentResolver.openInputStream(uri(path))
        ?: throw IOException("Unable to open this document.")

    fun applicationContext(): Context = context

    fun mediaItems(directory: String, order: DirectorySortOrder): List<MediaViewerItem> =
        MediaCatalog.sorted(list(directory).mapNotNull(MediaCatalog::toRemoteItem), order)

    override fun subDirectories(directory: String, sortOrder: DirectorySortOrder): List<String> =
        try { list(directory).filter { it.isDirectory }.sortedWith(sortOrder.remoteEntryComparator(false)).map { it.url } }
        catch (_: IOException) { emptyList() }

    override fun key(directory: String): String = uri(directory).toString()
    override fun pathSegments(directory: String): List<String> = ancestry(directory).let { chain ->
        (0 until chain.length()).map { chain.getJSONArray(it).getString(1) }
    }

    suspend fun neighbour(directory: String, next: Boolean, order: DirectorySortOrder): MediaViewerSource? {
        val candidates = if (next) DirectoryTraversal.forward(this, directory, root(directory), order)
            else DirectoryTraversal.backward(this, directory, root(directory), order)
        for (candidate in candidates) {
            currentCoroutineContext().ensureActive()
            val items = try { mediaItems(candidate, order) } catch (_: IOException) { continue }
            if (items.isNotEmpty()) return MediaViewerSource(
                DirectoryTraversal.displayName(this, directory, candidate), candidate)
        }
        return null
    }
}
