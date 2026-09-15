package srv.scrtvwr.tagging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterRegistryTest {
    private val registry = CharacterRegistry()

    @Test
    fun `the same face in two images becomes one character`() {
        registry.record("/a.jpg", 1L, faceCount = 1, signatures = listOf(signature(0f)), taggedAtMillis = 10L)
        registry.record("/b.jpg", 2L, faceCount = 1, signatures = listOf(signature(0.01f)), taggedAtMillis = 20L)

        val profiles = registry.profiles()
        assertEquals(1, profiles.size)
        assertEquals(2, profiles.single().imageCount)
        assertEquals(2, profiles.single().sampleCount)
        assertEquals(listOf("/a.jpg", "/b.jpg"), registry.imagesFor(profiles.single().id).map(ImageTags::path))
    }

    @Test
    fun `faces further apart than the threshold become separate characters`() {
        registry.record("/a.jpg", 1L, faceCount = 1, signatures = listOf(signature(0f)), taggedAtMillis = 10L)
        registry.record("/b.jpg", 1L, faceCount = 1, signatures = listOf(signature(5f)), taggedAtMillis = 10L)

        assertEquals(2, registry.profiles().size)
    }

    @Test
    fun `two faces of the same character in one image count the image once`() {
        val tags = registry.record(
            path = "/a.jpg",
            modifiedMillis = 1L,
            faceCount = 2,
            signatures = listOf(signature(0f), signature(0.01f)),
            taggedAtMillis = 10L,
        )

        assertEquals(2, tags.faceCount)
        assertEquals(1, tags.characterIds.size)
        assertEquals(1, registry.profiles().single().imageCount)
    }

    @Test
    fun `an image without usable signatures is still tagged with its face count`() {
        val tags = registry.record("/a.jpg", 1L, faceCount = 3, signatures = emptyList(), taggedAtMillis = 10L)

        assertTrue(tags.hasFaces)
        assertEquals(3, tags.faceCount)
        assertTrue(tags.characterIds.isEmpty())
        assertTrue(registry.profiles().isEmpty())
    }

    @Test
    fun `retagging an edited image replaces its previous tags`() {
        registry.record("/a.jpg", 1L, faceCount = 1, signatures = listOf(signature(0f)), taggedAtMillis = 10L)
        val characterId = registry.profiles().single().id

        registry.record("/a.jpg", 2L, faceCount = 0, signatures = emptyList(), taggedAtMillis = 20L)

        assertEquals(1, registry.taggedImageCount())
        assertEquals(0, registry.profiles().single().imageCount)
        assertTrue(registry.imagesFor(characterId).isEmpty())
    }

    @Test
    fun `tagging is only needed when the file is new or changed`() {
        assertTrue(registry.needsTagging("/a.jpg", 1L))

        registry.record("/a.jpg", 1L, faceCount = 0, signatures = emptyList(), taggedAtMillis = 10L)

        assertFalse(registry.needsTagging("/a.jpg", 1L))
        assertTrue(registry.needsTagging("/a.jpg", 2L))
    }

    @Test
    fun `renaming keeps the character and clearing the name restores the default`() {
        registry.record("/a.jpg", 1L, faceCount = 1, signatures = listOf(signature(0f)), taggedAtMillis = 10L)
        val characterId = registry.profiles().single().id

        assertTrue(registry.rename(characterId, "  Mina  "))
        assertEquals("Mina", registry.profiles().single().label)

        assertTrue(registry.rename(characterId, "   "))
        assertNull(registry.profiles().single().label)
        assertFalse(registry.rename("missing", "Nobody"))
    }

    @Test
    fun `a note and a chosen cover survive a snapshot`() {
        registry.record("/a.jpg", 1L, faceCount = 1, signatures = listOf(signature(0f)), taggedAtMillis = 10L)
        registry.record("/b.jpg", 2L, faceCount = 1, signatures = listOf(signature(0.01f)), taggedAtMillis = 20L)
        val characterId = registry.profiles().single().id

        assertTrue(registry.setNote(characterId, "  Wears the red scarf  "))
        assertTrue(registry.setCover(characterId, "/b.jpg", 2L))

        val restored = CharacterRegistry()
        restored.restore(registry.snapshot())
        val profile = restored.profiles().single()

        assertEquals("Wears the red scarf", profile.note)
        assertEquals("/b.jpg", profile.coverPath)
        assertEquals(2L, profile.coverModifiedMillis)

        assertTrue(registry.setNote(characterId, "  "))
        assertNull(registry.profiles().single().note)
        assertFalse(registry.setNote("missing", "Nobody"))
        assertFalse(registry.setCover("missing", "/a.jpg", 1L))
    }

    @Test
    fun `the first image a character appears in becomes its cover`() {
        registry.record("/a.jpg", 7L, faceCount = 1, signatures = listOf(signature(0f)), taggedAtMillis = 10L)
        registry.record("/b.jpg", 8L, faceCount = 1, signatures = listOf(signature(0.01f)), taggedAtMillis = 20L)

        val profile = registry.profiles().single()
        assertEquals("/a.jpg", profile.coverPath)
        assertEquals(7L, profile.coverModifiedMillis)
    }

    @Test
    fun `removing a character drops it from the images that referenced it`() {
        registry.record("/a.jpg", 1L, faceCount = 1, signatures = listOf(signature(0f)), taggedAtMillis = 10L)
        val characterId = registry.profiles().single().id

        assertTrue(registry.remove(characterId))

        assertTrue(registry.profiles().isEmpty())
        assertEquals(emptyList<String>(), registry.tagsFor("/a.jpg")?.characterIds)
        assertEquals(1, registry.taggedImageCount())
    }

    @Test
    fun `a snapshot restores characters tags and the next character number`() {
        registry.record("/a.jpg", 1L, faceCount = 2, signatures = listOf(signature(0f)), taggedAtMillis = 10L)
        registry.record("/b.jpg", 2L, faceCount = 1, signatures = listOf(signature(5f)), taggedAtMillis = 20L)
        val expected = registry.profiles()

        val restored = CharacterRegistry()
        restored.restore(registry.snapshot())

        assertEquals(expected, restored.profiles())
        assertEquals(registry.tagsFor("/a.jpg"), restored.tagsFor("/a.jpg"))
        assertEquals(2, restored.taggedImageCount())

        // A new face must not reuse an ordinal that is already taken.
        restored.record("/c.jpg", 3L, faceCount = 1, signatures = listOf(signature(10f)), taggedAtMillis = 30L)
        assertEquals(listOf(1, 2, 3), restored.profiles().map(CharacterProfile::ordinal).sorted())
    }

    @Test
    fun `restore drops references to characters that are gone`() {
        val snapshot = CharacterTagSnapshot(
            characters = emptyList(),
            images = listOf(
                ImageTags(
                    path = "/a.jpg",
                    modifiedMillis = 1L,
                    faceCount = 1,
                    characterIds = listOf("c9"),
                    taggedAtMillis = 10L,
                ),
            ),
        )

        registry.restore(snapshot)

        assertEquals(emptyList<String>(), registry.tagsFor("/a.jpg")?.characterIds)
    }

    @Test
    fun `the oldest images are dropped once the cap is reached`() {
        val small = CharacterRegistry(maxImages = 2)
        small.record("/a.jpg", 1L, faceCount = 0, signatures = emptyList(), taggedAtMillis = 10L)
        small.record("/b.jpg", 1L, faceCount = 0, signatures = emptyList(), taggedAtMillis = 20L)
        small.record("/c.jpg", 1L, faceCount = 0, signatures = emptyList(), taggedAtMillis = 30L)

        assertEquals(2, small.taggedImageCount())
        assertNull(small.tagsFor("/a.jpg"))
        assertTrue(small.needsTagging("/a.jpg", 1L))
    }

    /** Signatures that sit [offset] apart, so the test controls whether they cluster together. */
    private fun signature(offset: Float) = FloatArray(FaceGeometry.SIGNATURE_SIZE) { index ->
        if (index == 0) offset else 0f
    }
}
