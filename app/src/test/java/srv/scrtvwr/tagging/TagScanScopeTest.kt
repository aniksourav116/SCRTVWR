package srv.scrtvwr.tagging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TagScanScopeTest {
    @Test
    fun `all of storage tags anywhere by default`() {
        val scope = TagScanScope()

        assertTrue(scope.allowsDirectory("$STORAGE/DCIM"))
        assertTrue(scope.allowsFile("$STORAGE/Download/holiday.jpg"))
        assertFalse(scope.isEmpty)
    }

    @Test
    fun `chosen folders tag there and nowhere else`() {
        val scope = TagScanScope(
            mode = TagScopeMode.SelectedFolders,
            rules = listOf(TagFolderRule("$STORAGE/DCIM", includeSubfolders = true)),
        )

        assertTrue(scope.allowsFile("$STORAGE/DCIM/photo.jpg"))
        assertTrue(scope.allowsFile("$STORAGE/DCIM/Camera/photo.jpg"))
        assertFalse(scope.allowsFile("$STORAGE/Download/photo.jpg"))
        assertFalse(scope.allowsFile("$STORAGE/DCIMBackup/photo.jpg"))
    }

    @Test
    fun `a folder chosen without its subfolders keeps them out`() {
        val scope = TagScanScope(
            mode = TagScopeMode.SelectedFolders,
            rules = listOf(TagFolderRule("$STORAGE/DCIM", includeSubfolders = false)),
        )

        assertTrue(scope.allowsFile("$STORAGE/DCIM/photo.jpg"))
        assertFalse(scope.allowsFile("$STORAGE/DCIM/Camera/photo.jpg"))
    }

    @Test
    fun `the most specific rule wins, so a skipped subfolder survives a tagged parent`() {
        val scope = TagScanScope(
            mode = TagScopeMode.SelectedFolders,
            rules = listOf(
                TagFolderRule("$STORAGE/DCIM", includeSubfolders = true),
                TagFolderRule("$STORAGE/DCIM/Screenshots", includeSubfolders = true, included = false),
            ),
        )

        assertTrue(scope.allowsFile("$STORAGE/DCIM/Camera/photo.jpg"))
        assertFalse(scope.allowsFile("$STORAGE/DCIM/Screenshots/shot.png"))
        assertFalse(scope.allowsFile("$STORAGE/DCIM/Screenshots/Old/shot.png"))
    }

    @Test
    fun `all of storage still honours a folder ruled out`() {
        val scope = TagScanScope(
            rules = listOf(TagFolderRule("$STORAGE/Private", includeSubfolders = true, included = false)),
        )

        assertTrue(scope.allowsFile("$STORAGE/DCIM/photo.jpg"))
        assertFalse(scope.allowsFile("$STORAGE/Private/photo.jpg"))
        assertFalse(scope.allowsFile("$STORAGE/Private/Deeper/photo.jpg"))
    }

    @Test
    fun `an archive entry answers for the archive holding it`() {
        val scope = TagScanScope(
            mode = TagScopeMode.SelectedFolders,
            rules = listOf(TagFolderRule("$STORAGE/Comics", includeSubfolders = true)),
        )

        assertTrue(scope.allowsFile("$STORAGE/Comics/Issue 001.cbz::Pages/001.jpg"))
        assertFalse(scope.allowsFile("$STORAGE/Other/Issue 001.cbz::Pages/001.jpg"))
    }

    @Test
    fun `a walk descends through folders out of scope to reach one in it`() {
        val scope = TagScanScope(
            mode = TagScopeMode.SelectedFolders,
            rules = listOf(TagFolderRule("$STORAGE/Pictures/Trips/2024", includeSubfolders = true)),
        )

        assertTrue(scope.allowsDescent("$STORAGE/Pictures"))
        assertTrue(scope.allowsDescent("$STORAGE/Pictures/Trips"))
        assertFalse(scope.allowsDirectory("$STORAGE/Pictures/Trips"))
        assertFalse(scope.allowsDescent("$STORAGE/Music"))
    }

    @Test
    fun `a walk stops at a folder ruled out unless something chosen sits under it`() {
        val scope = TagScanScope(
            rules = listOf(
                TagFolderRule("$STORAGE/Android", includeSubfolders = true, included = false),
                TagFolderRule("$STORAGE/Android/media/keep", includeSubfolders = true),
            ),
        )

        assertFalse(scope.allowsDirectory("$STORAGE/Android"))
        assertTrue(scope.allowsDescent("$STORAGE/Android"))
        assertTrue(scope.allowsFile("$STORAGE/Android/media/keep/photo.jpg"))
    }

    @Test
    fun `scan roots leave out folders reachable from a folder above them`() {
        val scope = TagScanScope(
            mode = TagScopeMode.SelectedFolders,
            rules = listOf(
                TagFolderRule("$STORAGE/DCIM", includeSubfolders = true),
                TagFolderRule("$STORAGE/DCIM/Camera", includeSubfolders = true),
                TagFolderRule("$STORAGE/Pictures", includeSubfolders = true),
                TagFolderRule("$STORAGE/Private", includeSubfolders = true, included = false),
            ),
        )

        assertEquals(listOf("$STORAGE/DCIM", "$STORAGE/Pictures"), scope.scanRootPaths(STORAGE))
    }

    @Test
    fun `all of storage scans from the storage root`() {
        assertEquals(listOf(STORAGE), TagScanScope().scanRootPaths(STORAGE))
    }

    @Test
    fun `choosing a folder is what lets a walk into a hidden one, or past a skipped one`() {
        val hidden = TagScanScope(
            rules = listOf(TagFolderRule("$STORAGE/.private", includeSubfolders = true)),
        )
        val sandboxed = TagScanScope(
            mode = TagScopeMode.SelectedFolders,
            rules = listOf(TagFolderRule("$STORAGE/Android/media/com.example/Images", includeSubfolders = true)),
        )

        assertTrue(hidden.reachesInto("$STORAGE/.private"))
        assertFalse(hidden.reachesInto("$STORAGE/.thumbnails"))
        // The chosen folder is several levels inside a folder that is otherwise walked past.
        assertTrue(sandboxed.reachesInto("$STORAGE/Android"))
        assertTrue(sandboxed.allowsFile("$STORAGE/Android/media/com.example/Images/photo.jpg"))
    }

    @Test
    fun `a trailing slash names the same folder`() {
        val scope = TagScanScope(
            mode = TagScopeMode.SelectedFolders,
            rules = listOf(TagFolderRule("$STORAGE/DCIM/", includeSubfolders = true)),
        )

        assertTrue(scope.allowsDirectory("$STORAGE/DCIM"))
        assertTrue(scope.allowsDirectory("$STORAGE/DCIM/Camera/"))
        assertEquals("$STORAGE/DCIM", scope.ruleAt("$STORAGE/DCIM")?.path)
    }

    @Test
    fun `a new rule replaces what was said about the same folder`() {
        val scope = TagScanScope(
            rules = listOf(TagFolderRule("$STORAGE/DCIM", includeSubfolders = true)),
        ).withRule(TagFolderRule("$STORAGE/DCIM", includeSubfolders = false, included = false))

        assertEquals(1, scope.rules.size)
        assertFalse(scope.allowsDirectory("$STORAGE/DCIM"))
        assertTrue(scope.allowsDirectory("$STORAGE/DCIM/Camera"))
        assertEquals(0, scope.withoutRule("$STORAGE/DCIM").rules.size)
    }

    @Test
    fun `chosen folders with nothing chosen has nowhere to look`() {
        val empty = TagScanScope(
            mode = TagScopeMode.SelectedFolders,
            rules = listOf(TagFolderRule("$STORAGE/Private", includeSubfolders = true, included = false)),
        )

        assertTrue(empty.isEmpty)
        assertFalse(empty.allowsFile("$STORAGE/DCIM/photo.jpg"))
        assertTrue(empty.scanRootPaths(STORAGE).isEmpty())
    }

    private companion object {
        const val STORAGE = "/storage/emulated/0"
    }
}
