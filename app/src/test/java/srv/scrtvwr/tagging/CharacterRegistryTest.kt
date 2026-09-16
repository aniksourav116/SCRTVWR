package srv.scrtvwr.tagging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class CharacterRegistryTest {
    private val rules = MatchingRules.forModel(DESCRIPTOR)
    private val registry = CharacterRegistry(rules)

    @Test
    fun `two photos of the same face become one character`() {
        registry.record("/a.jpg", 1L, faceCount = 1, faces = listOf(face(0)), taggedAtMillis = 10L)
        registry.record("/b.jpg", 2L, faceCount = 1, faces = listOf(face(18)), taggedAtMillis = 20L)

        val profiles = registry.profiles()
        assertEquals(1, profiles.size)
        assertEquals(2, profiles.single().imageCount)
        assertEquals(listOf("/a.jpg", "/b.jpg"), registry.imagesFor(profiles.single().id).map(ImageTags::path))
    }

    @Test
    fun `a clearly different face starts a new character`() {
        registry.record("/a.jpg", 1L, faceCount = 1, faces = listOf(face(0)), taggedAtMillis = 10L)
        registry.record("/b.jpg", 1L, faceCount = 1, faces = listOf(face(90)), taggedAtMillis = 10L)

        assertEquals(2, registry.profiles().size)
    }

    @Test
    fun `a face between the thresholds is left unassigned rather than guessed at`() {
        registry.record("/a.jpg", 1L, faceCount = 1, faces = listOf(face(0)), taggedAtMillis = 10L)

        // Similar enough to be suspicious, not similar enough to be sure. Joining would risk welding
        // two people together for good, so the face is counted but belongs to nobody yet.
        val tags = registry.record("/b.jpg", 1L, faceCount = 1, faces = listOf(face(55)), taggedAtMillis = 10L)

        assertEquals(1, registry.profiles().size)
        assertEquals(1, tags.faceCount)
        assertTrue(tags.characterIds.isEmpty())
    }

    @Test
    fun `two faces of the same character in one image count the image once`() {
        val tags = registry.record(
            path = "/a.jpg",
            modifiedMillis = 1L,
            faceCount = 2,
            faces = listOf(face(0), face(10)),
            taggedAtMillis = 10L,
        )

        assertEquals(2, tags.faceCount)
        assertEquals(1, tags.characterIds.size)
        assertEquals(1, registry.profiles().single().imageCount)
    }

    @Test
    fun `an image whose faces were all rejected is still tagged with its face count`() {
        val tags = registry.record("/a.jpg", 1L, faceCount = 3, faces = emptyList(), taggedAtMillis = 10L)

        assertTrue(tags.hasFaces)
        assertEquals(3, tags.faceCount)
        assertTrue(tags.characterIds.isEmpty())
        assertTrue(registry.profiles().isEmpty())
    }

    @Test
    fun `retagging an edited image replaces its previous tags`() {
        registry.record("/a.jpg", 1L, faceCount = 1, faces = listOf(face(0)), taggedAtMillis = 10L)
        val characterId = registry.profiles().single().id

        registry.record("/a.jpg", 2L, faceCount = 0, faces = emptyList(), taggedAtMillis = 20L)

        assertEquals(1, registry.taggedImageCount())
        assertEquals(0, registry.profiles().single().imageCount)
        assertTrue(registry.imagesFor(characterId).isEmpty())
    }

    @Test
    fun `tagging is only needed when the file is new or changed`() {
        assertTrue(registry.needsTagging("/a.jpg", 1L))

        registry.record("/a.jpg", 1L, faceCount = 0, faces = emptyList(), taggedAtMillis = 10L)

        assertFalse(registry.needsTagging("/a.jpg", 1L))
        assertTrue(registry.needsTagging("/a.jpg", 2L))
    }

    @Test
    fun `every embedded face is queued for the face log exactly once`() {
        registry.record("/a.jpg", 1L, faceCount = 2, faces = listOf(face(0), face(90)), taggedAtMillis = 10L)

        val drained = registry.drainPendingFaces()

        assertEquals(2, drained.size)
        assertEquals(listOf(0, 1), drained.map(FaceVector::index))
        assertTrue(drained.all { it.characterId != null })
        assertTrue(registry.drainPendingFaces().isEmpty())
    }

    @Test
    fun `renaming keeps the character and clearing the name restores the default`() {
        registry.record("/a.jpg", 1L, faceCount = 1, faces = listOf(face(0)), taggedAtMillis = 10L)
        val characterId = registry.profiles().single().id

        assertTrue(registry.rename(characterId, "  Mina  "))
        assertEquals("Mina", registry.profiles().single().label)

        assertTrue(registry.rename(characterId, "   "))
        assertNull(registry.profiles().single().label)
        assertFalse(registry.rename("missing", "Nobody"))
    }

    @Test
    fun `a note and a chosen cover survive a snapshot`() {
        registry.record("/a.jpg", 1L, faceCount = 1, faces = listOf(face(0)), taggedAtMillis = 10L)
        registry.record(
            path = "/b.jpg",
            modifiedMillis = 2L,
            faceCount = 1,
            faces = listOf(face(10, RIGHT_OF_FRAME)),
            taggedAtMillis = 20L,
        )
        val characterId = registry.profiles().single().id

        assertTrue(registry.setNote(characterId, "  Wears the red scarf  "))
        assertTrue(registry.setCover(characterId, "/b.jpg", 2L))

        val restored = CharacterRegistry(rules)
        assertTrue(restored.restore(registry.snapshot()))
        val profile = restored.profiles().single()

        assertEquals("Wears the red scarf", profile.note)
        assertEquals("/b.jpg", profile.coverPath)
        assertEquals(2L, profile.coverModifiedMillis)
        assertEquals(RIGHT_OF_FRAME, profile.coverBounds)
        assertEquals(2, restored.taggedImageCount())
    }

    @Test
    fun `a cover is boxed on that character's face rather than on the whole photo`() {
        registry.record(
            path = "/group.jpg",
            modifiedMillis = 1L,
            faceCount = 2,
            faces = listOf(face(0, LEFT_OF_FRAME), face(90, RIGHT_OF_FRAME)),
            taggedAtMillis = 10L,
        )

        val profiles = registry.profiles().sortedBy(CharacterProfile::ordinal)
        assertEquals(2, profiles.size)
        assertEquals("/group.jpg", profiles.first().coverPath)
        assertEquals(LEFT_OF_FRAME, profiles.first().coverBounds)
        assertEquals(RIGHT_OF_FRAME, profiles.last().coverBounds)
    }

    @Test
    fun `choosing another photo as the cover takes the box from that photo`() {
        registry.record("/a.jpg", 1L, faceCount = 1, faces = listOf(face(0, LEFT_OF_FRAME)), taggedAtMillis = 10L)
        registry.record("/b.jpg", 2L, faceCount = 1, faces = listOf(face(10, RIGHT_OF_FRAME)), taggedAtMillis = 20L)
        val characterId = registry.profiles().single().id

        assertTrue(registry.setCover(characterId, "/b.jpg", 2L))

        assertEquals(RIGHT_OF_FRAME, registry.profiles().single().coverBounds)
    }

    @Test
    fun `tags from a different embedder are refused instead of compared`() {
        registry.record("/a.jpg", 1L, faceCount = 1, faces = listOf(face(0)), taggedAtMillis = 10L)
        val snapshot = registry.snapshot()
        assertEquals(DESCRIPTOR, snapshot.descriptorId)

        val other = CharacterRegistry(MatchingRules.forModel("some-other-model"))

        assertFalse(other.restore(snapshot))
        assertTrue(other.profiles().isEmpty())
        assertEquals(0, other.taggedImageCount())
    }

    @Test
    fun `merging folds one character into another`() {
        registry.record("/a.jpg", 1L, faceCount = 1, faces = listOf(face(0)), taggedAtMillis = 10L)
        registry.record("/b.jpg", 1L, faceCount = 1, faces = listOf(face(90)), taggedAtMillis = 10L)
        val ordered = registry.profiles().sortedBy(CharacterProfile::ordinal)
        val first = ordered.first()
        val second = ordered.last()
        assertTrue(registry.rename(second.id, "Mina"))

        assertTrue(registry.merge(first.id, second.id))

        val merged = registry.profiles().single()
        assertEquals(first.id, merged.id)
        assertEquals("Mina", merged.label)
        assertEquals(2, merged.imageCount)
        assertEquals(listOf("/a.jpg", "/b.jpg"), registry.imagesFor(merged.id).map(ImageTags::path))
        assertFalse(registry.merge(first.id, first.id))
    }

    @Test
    fun `unassigning drops one image and moves the cover off it`() {
        registry.record("/a.jpg", 1L, faceCount = 1, faces = listOf(face(0)), taggedAtMillis = 10L)
        registry.record("/b.jpg", 1L, faceCount = 1, faces = listOf(face(10)), taggedAtMillis = 10L)
        val characterId = registry.profiles().single().id

        assertTrue(registry.unassign(characterId, "/a.jpg"))

        assertEquals(1, registry.profiles().single().imageCount)
        assertEquals(listOf("/b.jpg"), registry.imagesFor(characterId).map(ImageTags::path))
        assertEquals("/b.jpg", registry.profiles().single().coverPath)
        assertFalse(registry.unassign(characterId, "/a.jpg"))
    }

    @Test
    fun `rebuilding regroups every face and keeps the names that were given`() {
        registry.record("/a.jpg", 1L, faceCount = 1, faces = listOf(face(0)), taggedAtMillis = 10L)
        registry.record("/b.jpg", 1L, faceCount = 1, faces = listOf(face(8)), taggedAtMillis = 10L)
        registry.record("/c.jpg", 1L, faceCount = 1, faces = listOf(face(90)), taggedAtMillis = 10L)
        val named = registry.profiles().maxByOrNull(CharacterProfile::imageCount)!!
        assertTrue(registry.rename(named.id, "Mina"))

        val regrouped = registry.rebuild(
            listOf(
                FaceVector("/a.jpg", 1L, 0, vector(0), named.id),
                FaceVector("/b.jpg", 1L, 0, vector(8), named.id),
                FaceVector("/c.jpg", 1L, 0, vector(90), null),
            ),
        )

        assertEquals(3, regrouped.size)
        val mina = registry.profiles().single { it.label == "Mina" }
        assertEquals(listOf("/a.jpg", "/b.jpg"), registry.imagesFor(mina.id).map(ImageTags::path))
        assertEquals(3, registry.taggedImageCount())

        // The boxes are indexed by face, so regrouping has to re-point them along with the tags, or
        // the cover would know which photo it is but not where in it to look.
        assertEquals(LEFT_OF_FRAME, mina.coverBounds)

        // No image may be left pointing at a character the rebuild dissolved.
        val knownIds = registry.profiles().map(CharacterProfile::id).toSet()
        listOf("/a.jpg", "/b.jpg", "/c.jpg").forEach { path ->
            assertTrue(registry.tagsFor(path)?.characterIds.orEmpty().all { it in knownIds })
        }
    }

    @Test
    fun `rebuilding ignores faces whose image has since changed`() {
        registry.record("/a.jpg", 2L, faceCount = 1, faces = listOf(face(0)), taggedAtMillis = 10L)

        val regrouped = registry.rebuild(listOf(FaceVector("/a.jpg", 1L, 0, vector(0), null)))

        assertTrue(regrouped.isEmpty())
    }

    @Test
    fun `removing a character drops it from the images that referenced it`() {
        registry.record("/a.jpg", 1L, faceCount = 1, faces = listOf(face(0)), taggedAtMillis = 10L)
        val characterId = registry.profiles().single().id

        assertTrue(registry.remove(characterId))

        assertTrue(registry.profiles().isEmpty())
        assertEquals(emptyList<String>(), registry.tagsFor("/a.jpg")?.characterIds)
        assertEquals(1, registry.taggedImageCount())
    }

    @Test
    fun `restore drops references to characters that are gone`() {
        val snapshot = CharacterTagSnapshot(
            descriptorId = DESCRIPTOR,
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

        assertTrue(registry.restore(snapshot))

        assertEquals(emptyList<String>(), registry.tagsFor("/a.jpg")?.characterIds)
    }

    @Test
    fun `the oldest images are dropped once the cap is reached`() {
        val small = CharacterRegistry(rules, maxImages = 2)
        small.record("/a.jpg", 1L, faceCount = 0, faces = emptyList(), taggedAtMillis = 10L)
        small.record("/b.jpg", 1L, faceCount = 0, faces = emptyList(), taggedAtMillis = 20L)
        small.record("/c.jpg", 1L, faceCount = 0, faces = emptyList(), taggedAtMillis = 30L)

        assertEquals(2, small.taggedImageCount())
        assertNull(small.tagsFor("/a.jpg"))
        assertTrue(small.needsTagging("/a.jpg", 1L))
    }

    /** A face at [degrees], standing somewhere in the frame. */
    private fun face(degrees: Int, bounds: FaceBounds = LEFT_OF_FRAME): DetectedFace =
        DetectedFace(vector(degrees), bounds)

    /**
     * A unit vector at [degrees], so cosine similarity between two faces is the cosine of the angle
     * between them: 0 and 18 degrees are plainly the same person, 90 plainly is not, and 55 lands in
     * the deliberate gap between the two thresholds.
     */
    private fun vector(degrees: Int): FloatArray {
        val radians = degrees * PI / 180.0
        return floatArrayOf(cos(radians).toFloat(), sin(radians).toFloat(), 0f, 0f)
    }

    private companion object {
        const val DESCRIPTOR = "test-model"

        /** Two people standing apart, so a cover cropped to the wrong one is obvious. */
        val LEFT_OF_FRAME = FaceBounds(left = 0.05f, top = 0.1f, right = 0.25f, bottom = 0.45f)
        val RIGHT_OF_FRAME = FaceBounds(left = 0.7f, top = 0.1f, right = 0.9f, bottom = 0.45f)
    }
}
