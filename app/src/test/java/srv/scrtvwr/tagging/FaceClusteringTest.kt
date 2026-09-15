package srv.scrtvwr.tagging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class FaceClusteringTest {
    private val rules = MatchingRules.forModel("test-model")

    @Test
    fun `groups are discovered without being told how many people there are`() {
        val faces = listOf(0, 6, 12, 90, 96, 180, 186, 192).map(::face)

        val partition = FaceClustering.cluster(faces, rules).partition()

        assertEquals(
            setOf(setOf(0, 1, 2), setOf(3, 4), setOf(5, 6, 7)),
            partition,
        )
    }

    @Test
    fun `the same faces in a different order produce the same groups`() {
        val angles = listOf(0, 6, 12, 90, 96, 180, 186, 192)
        val shuffled = angles.shuffled(Random(20250915))

        val first = FaceClustering.cluster(angles.map(::face), rules)
            .partition()
            .map { group -> group.map { angles[it] }.toSet() }
            .toSet()
        val second = FaceClustering.cluster(shuffled.map(::face), rules)
            .partition()
            .map { group -> group.map { shuffled[it] }.toSet() }
            .toSet()

        assertEquals(first, second)
    }

    @Test
    fun `a person split across two groups is merged back together`() {
        // Joining is per face and merging is per group, so by default merging is the stricter of the
        // two and rarely fires. These rules invert that to exercise the merge pass on its own: two
        // tight groups form separately, then merge because every member of one resembles every
        // member of the other on average.
        val mergeable = rules.copy(
            joinSimilarity = 0.99f,
            newIdentitySimilarity = 0.97f,
            mergeSimilarity = 0.85f,
        )
        val faces = listOf(0, 3, 24, 27).map(::face)

        val partition = FaceClustering.cluster(faces, mergeable).partition()

        assertEquals(setOf(setOf(0, 1, 2, 3)), partition)
    }

    @Test
    fun `an empty library clusters into nothing`() {
        assertTrue(FaceClustering.cluster(emptyList(), rules).isEmpty())
    }

    @Test
    fun `one face is one person`() {
        assertEquals(setOf(setOf(0)), FaceClustering.cluster(listOf(face(0)), rules).partition())
    }

    @Test
    fun `exemplars stay within the limit and keep the spread`() {
        val members = (0..40).map { step -> face(step * 2) }

        val exemplars = FaceClustering.selectExemplars(members, rules, limit = 5)

        assertEquals(5, exemplars.size)
        // A naive "first five" would cover 8 degrees; picking for spread has to do better than that.
        val spread = exemplars.minOf { first -> exemplars.minOf { second -> rules.similarity(first, second) } }
        assertTrue("exemplars should not all be near-copies of each other", spread < 0.99f)
    }

    @Test
    fun `everything below the new-identity threshold becomes its own person`() {
        val faces = listOf(0, 90, 180, 270).map(::face)

        assertEquals(4, FaceClustering.cluster(faces, rules).partition().size)
    }

    @Test
    fun `a face in the gap between the thresholds joins nobody`() {
        // 60 degrees is 0.5 similar: too alike to call it a new person, not alike enough to join.
        val faces = listOf(0, 60, 180).map(::face)

        val assignments = FaceClustering.cluster(faces, rules)

        assertEquals(FaceClustering.UNASSIGNED, assignments[1])
        assertEquals(2, assignments.partition().size)
    }

    @Test
    fun `cosine similarity behaves as the thresholds assume`() {
        assertEquals(1f, MatchingRules.cosine(face(0), face(0)), 0.0001f)
        assertEquals(0f, MatchingRules.cosine(face(0), face(90)), 0.0001f)
        assertEquals(-1f, MatchingRules.cosine(face(0), face(180)), 0.0001f)
        assertEquals(MatchingRules.LOWEST_SIMILARITY, rules.similarity(face(0), floatArrayOf(1f)), 0.0001f)
    }

    /** Unit vectors on a circle: the similarity between two faces is the cosine of the angle. */
    private fun face(degrees: Int): FloatArray {
        val radians = degrees * PI / 180.0
        return floatArrayOf(cos(radians).toFloat(), sin(radians).toFloat())
    }

    private fun IntArray.partition(): Set<Set<Int>> = indices
        .filter { index -> this[index] != FaceClustering.UNASSIGNED }
        .groupBy { index -> this[index] }
        .values
        .map(List<Int>::toSet)
        .toSet()
}
