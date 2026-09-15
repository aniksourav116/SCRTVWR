package srv.scrtvwr.tagging

import kotlin.math.sqrt

/**
 * How two face vectors are compared, and how confident a match has to be before it counts.
 *
 * The numbers depend on which embedder produced the vectors, so they travel together with a
 * [descriptorId]: vectors from different embedders live in different spaces and must never be
 * compared. Two thresholds rather than one gives a deliberate no-man's-land - a face that is neither
 * clearly the same person nor clearly someone new is left unassigned instead of guessed at, because a
 * wrong merge corrupts a character for good while an unassigned face costs nothing but a later pass.
 */
data class MatchingRules(
    val descriptorId: String,
    val joinSimilarity: Float,
    val newIdentitySimilarity: Float,
    val mergeSimilarity: Float,
    private val metric: (FloatArray, FloatArray) -> Float,
) {
    fun similarity(first: FloatArray, second: FloatArray): Float =
        if (first.size != second.size) LOWEST_SIMILARITY else metric(first, second)

    companion object {
        const val LOWEST_SIMILARITY = -1f

        /**
         * Cosine thresholds for an ArcFace-trained model. 0.62 to join is the usual operating point
         * for MobileFaceNet; below 0.45 a face is treated as somebody new.
         */
        fun forModel(descriptorId: String): MatchingRules = MatchingRules(
            descriptorId = descriptorId,
            joinSimilarity = 0.62f,
            newIdentitySimilarity = 0.45f,
            mergeSimilarity = 0.70f,
            metric = { first, second -> cosine(first, second) },
        )

        /**
         * The landmark fallback measures distance, not angle, so it gets its own mapping onto the
         * same scale. It stays deliberately strict: this descriptor cannot tell people apart well,
         * so it is better for it to leave faces unassigned than to invent groups.
         */
        fun forGeometry(descriptorId: String): MatchingRules = MatchingRules(
            descriptorId = descriptorId,
            joinSimilarity = 0.88f,
            newIdentitySimilarity = 0.80f,
            mergeSimilarity = 0.92f,
            metric = { first, second ->
                1f - (FaceGeometry.distance(first, second) / GEOMETRY_DISTANCE_SCALE)
            },
        )

        fun forEmbedder(embedder: FaceEmbedder): MatchingRules = when (embedder) {
            is ModelFaceEmbedder -> forModel(embedder.descriptorId)
            else -> forGeometry(embedder.descriptorId)
        }

        /** Both vectors are unit length, so cosine similarity is just their dot product. */
        fun cosine(first: FloatArray, second: FloatArray): Float {
            var dot = 0f
            var firstLength = 0f
            var secondLength = 0f
            for (index in first.indices) {
                dot += first[index] * second[index]
                firstLength += first[index] * first[index]
                secondLength += second[index] * second[index]
            }
            val denominator = sqrt(firstLength) * sqrt(secondLength)
            return if (denominator <= 0f) LOWEST_SIMILARITY else dot / denominator
        }

        /** Distance at which two landmark signatures are considered completely unrelated. */
        private const val GEOMETRY_DISTANCE_SCALE = 1.5f
    }
}

/**
 * Groups face vectors into identities without being told how many identities to expect.
 *
 * The number of people varies from one library to the next, so nothing here takes a target count:
 * clusters appear when a face resembles nothing already seen. Three stages, each fixing a weakness of
 * the one before:
 *
 *  1. **Leader pass** - walk the faces once, joining the closest identity above the threshold or
 *     starting a new one. Fast, but the result depends on the order faces arrive in.
 *  2. **Refinement passes** - reassign every face to its best identity and rebuild the exemplars,
 *     repeatedly. This is what washes the ordering out, so a late good photo can pull a face into
 *     the right group.
 *  3. **Merge pass** - identities that turn out to be the same person (the usual outcome when
 *     someone was first seen in a bad photo) are combined.
 */
object FaceClustering {
    const val UNASSIGNED = -1
    private const val REFINEMENT_PASSES = 3

    /** Up to this many vectors represent one identity, so one person can look several ways. */
    const val MAX_EXEMPLARS = 12

    /**
     * Returns the identity index for each entry of [faces], or [UNASSIGNED] for faces that never
     * settled anywhere. Identity indices are dense and start at zero.
     */
    fun cluster(faces: List<FloatArray>, rules: MatchingRules): IntArray {
        if (faces.isEmpty()) return IntArray(0)
        val assignments = IntArray(faces.size) { UNASSIGNED }
        var clusters = mutableListOf<MutableList<Int>>()

        faces.forEachIndexed { index, face ->
            val best = bestCluster(face, clusters, faces, rules)
            when {
                best.similarity >= rules.joinSimilarity && best.cluster >= 0 -> {
                    assignments[index] = best.cluster
                    clusters[best.cluster].add(index)
                }
                best.similarity < rules.newIdentitySimilarity -> {
                    clusters.add(mutableListOf(index))
                    assignments[index] = clusters.size - 1
                }
                // Between the two thresholds: too alike to be sure it is someone new, too unlike to
                // join. Left out of this pass; refinement gets another chance at it.
                else -> assignments[index] = UNASSIGNED
            }
        }

        repeat(REFINEMENT_PASSES) {
            val exemplars = clusters.map { members -> members.exemplars(faces) }
            var changed = false
            faces.forEachIndexed { index, face ->
                val best = bestOfExemplars(face, exemplars, rules)
                val target = if (best.similarity >= rules.joinSimilarity) best.cluster else UNASSIGNED
                if (target != assignments[index]) {
                    assignments[index] = target
                    changed = true
                }
            }
            clusters = rebuildClusters(assignments, clusters.size)
            if (!changed) return@repeat
        }

        clusters = mergeSimilarClusters(clusters, faces, rules, assignments)
        return compact(assignments, clusters.size)
    }

    /** The best identity for one face, given the current exemplars of each cluster. */
    fun bestMatch(face: FloatArray, exemplarsByCluster: List<List<FloatArray>>, rules: MatchingRules): Match =
        bestOfExemplars(face, exemplarsByCluster, rules)

    /**
     * Picks the vectors that best represent a cluster: the most central one first, then the ones
     * least like those already chosen, so an identity keeps a spread of how the person can look
     * rather than a dozen near-copies of the same photo.
     */
    fun selectExemplars(members: List<FloatArray>, rules: MatchingRules, limit: Int = MAX_EXEMPLARS): List<FloatArray> {
        if (members.size <= limit) return members
        val centrality = members.map { candidate ->
            members.sumOf { other -> rules.similarity(candidate, other).toDouble() }
        }
        val chosen = mutableListOf(members[centrality.indexOf(centrality.max())])
        while (chosen.size < limit) {
            val next = members
                .filter { candidate -> chosen.none { it === candidate } }
                .minByOrNull { candidate -> chosen.maxOf { rules.similarity(candidate, it) } }
                ?: break
            chosen.add(next)
        }
        return chosen
    }

    data class Match(val cluster: Int, val similarity: Float)

    private fun bestCluster(
        face: FloatArray,
        clusters: List<List<Int>>,
        faces: List<FloatArray>,
        rules: MatchingRules,
    ): Match {
        var bestCluster = UNASSIGNED
        var bestSimilarity = MatchingRules.LOWEST_SIMILARITY
        clusters.forEachIndexed { clusterIndex, members ->
            members.forEach { memberIndex ->
                val similarity = rules.similarity(face, faces[memberIndex])
                if (similarity > bestSimilarity) {
                    bestSimilarity = similarity
                    bestCluster = clusterIndex
                }
            }
        }
        return Match(bestCluster, bestSimilarity)
    }

    private fun bestOfExemplars(
        face: FloatArray,
        exemplars: List<List<FloatArray>>,
        rules: MatchingRules,
    ): Match {
        var bestCluster = UNASSIGNED
        var bestSimilarity = MatchingRules.LOWEST_SIMILARITY
        exemplars.forEachIndexed { clusterIndex, clusterExemplars ->
            clusterExemplars.forEach { exemplar ->
                val similarity = rules.similarity(face, exemplar)
                if (similarity > bestSimilarity) {
                    bestSimilarity = similarity
                    bestCluster = clusterIndex
                }
            }
        }
        return Match(bestCluster, bestSimilarity)
    }

    private fun List<Int>.exemplars(faces: List<FloatArray>): List<FloatArray> =
        map { index -> faces[index] }.take(MAX_EXEMPLARS)

    private fun rebuildClusters(assignments: IntArray, clusterCount: Int): MutableList<MutableList<Int>> {
        val clusters = MutableList(clusterCount) { mutableListOf<Int>() }
        assignments.forEachIndexed { index, cluster ->
            if (cluster in 0 until clusterCount) clusters[cluster].add(index)
        }
        return clusters
    }

    private fun mergeSimilarClusters(
        clusters: MutableList<MutableList<Int>>,
        faces: List<FloatArray>,
        rules: MatchingRules,
        assignments: IntArray,
    ): MutableList<MutableList<Int>> {
        var merged = true
        while (merged) {
            merged = false
            outer@ for (first in clusters.indices) {
                if (clusters[first].isEmpty()) continue
                for (second in first + 1 until clusters.size) {
                    if (clusters[second].isEmpty()) continue
                    val similarity = clusterSimilarity(clusters[first], clusters[second], faces, rules)
                    if (similarity >= rules.mergeSimilarity) {
                        clusters[second].forEach { index -> assignments[index] = first }
                        clusters[first].addAll(clusters[second])
                        clusters[second].clear()
                        merged = true
                        break@outer
                    }
                }
            }
        }
        return clusters
    }

    /** Average linkage: the mean similarity between the two sides, which resists single outliers. */
    private fun clusterSimilarity(
        first: List<Int>,
        second: List<Int>,
        faces: List<FloatArray>,
        rules: MatchingRules,
    ): Float {
        if (first.isEmpty() || second.isEmpty()) return MatchingRules.LOWEST_SIMILARITY
        var total = 0f
        first.forEach { firstIndex ->
            second.forEach { secondIndex ->
                total += rules.similarity(faces[firstIndex], faces[secondIndex])
            }
        }
        return total / (first.size * second.size)
    }

    /** Drops emptied clusters and renumbers the rest so identity indices stay dense. */
    private fun compact(assignments: IntArray, clusterCount: Int): IntArray {
        val remapped = IntArray(clusterCount) { UNASSIGNED }
        var next = 0
        assignments.forEach { cluster ->
            if (cluster in 0 until clusterCount && remapped[cluster] == UNASSIGNED) {
                remapped[cluster] = next++
            }
        }
        return IntArray(assignments.size) { index ->
            val cluster = assignments[index]
            if (cluster in 0 until clusterCount) remapped[cluster] else UNASSIGNED
        }
    }
}
