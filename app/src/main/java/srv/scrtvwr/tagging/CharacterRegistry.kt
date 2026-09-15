package srv.scrtvwr.tagging

/**
 * Keeps the character clusters and the per-image tags in memory.
 *
 * Pure Kotlin on purpose - the clustering rules are the part worth testing, and they have no reason to
 * know about Android. Callers are responsible for confining access to a single thread or guarding it
 * with a lock; [CharacterTagRepository] does the latter.
 */
class CharacterRegistry(
    private val matchThreshold: Float = FaceGeometry.MATCH_THRESHOLD,
    private val maxImages: Int = MAX_IMAGES,
) {
    private class Cluster(
        val id: String,
        val ordinal: Int,
        var label: String?,
        var centroid: FloatArray,
        var sampleCount: Int,
        var coverPath: String?,
        var coverModifiedMillis: Long,
    )

    private val clusters = linkedMapOf<String, Cluster>()
    private val imageCounts = mutableMapOf<String, Int>()
    /** Access-ordered so the oldest untouched image is the one evicted once [maxImages] is reached. */
    private val images = LinkedHashMap<String, ImageTags>(0, 0.75f, true)
    private var nextOrdinal = 1

    fun tagsFor(path: String): ImageTags? = images[path]

    fun tagsFor(paths: Collection<String>): Map<String, ImageTags> =
        paths.mapNotNull { path -> images[path]?.let { path to it } }.toMap()

    /** True when the image has never been tagged, or has been edited since it was. */
    fun needsTagging(path: String, modifiedMillis: Long): Boolean =
        images[path]?.modifiedMillis != modifiedMillis

    /**
     * Records the result of detecting faces in one image. Every usable [signatures] entry joins the
     * closest character within [matchThreshold], or starts a new one.
     */
    fun record(
        path: String,
        modifiedMillis: Long,
        faceCount: Int,
        signatures: List<FloatArray>,
        taggedAtMillis: Long,
    ): ImageTags {
        forget(path)
        val characterIds = mutableListOf<String>()
        signatures.forEach { signature ->
            val cluster = nearestCluster(signature) ?: newCluster(signature)
            if (cluster.sampleCount > 0) {
                cluster.centroid = FaceGeometry.mergeIntoCentroid(cluster.centroid, cluster.sampleCount, signature)
            }
            cluster.sampleCount++
            if (cluster.coverPath == null) {
                cluster.coverPath = path
                cluster.coverModifiedMillis = modifiedMillis
            }
            if (cluster.id !in characterIds) characterIds += cluster.id
        }
        characterIds.forEach { id -> imageCounts[id] = (imageCounts[id] ?: 0) + 1 }

        val tags = ImageTags(
            path = path,
            modifiedMillis = modifiedMillis,
            faceCount = faceCount,
            characterIds = characterIds,
            taggedAtMillis = taggedAtMillis,
        )
        images[path] = tags
        trimImages()
        return tags
    }

    fun rename(characterId: String, label: String?): Boolean {
        val cluster = clusters[characterId] ?: return false
        cluster.label = label?.trim()?.takeIf(String::isNotEmpty)
        return true
    }

    /** Drops a character and the references to it, for example when the user deletes a bad cluster. */
    fun remove(characterId: String): Boolean {
        if (clusters.remove(characterId) == null) return false
        imageCounts.remove(characterId)
        images.entries.forEach { entry ->
            val tags = entry.value
            if (characterId in tags.characterIds) {
                entry.setValue(tags.copy(characterIds = tags.characterIds - characterId))
            }
        }
        return true
    }

    fun forget(path: String) {
        val previous = images.remove(path) ?: return
        previous.characterIds.forEach { id ->
            val remaining = (imageCounts[id] ?: 0) - 1
            if (remaining > 0) imageCounts[id] = remaining else imageCounts.remove(id)
        }
    }

    fun clear() {
        clusters.clear()
        imageCounts.clear()
        images.clear()
        nextOrdinal = 1
    }

    fun profiles(): List<CharacterProfile> = clusters.values
        .map { cluster ->
            CharacterProfile(
                id = cluster.id,
                ordinal = cluster.ordinal,
                label = cluster.label,
                sampleCount = cluster.sampleCount,
                imageCount = imageCounts[cluster.id] ?: 0,
                coverPath = cluster.coverPath,
                coverModifiedMillis = cluster.coverModifiedMillis,
            )
        }
        .sortedWith(compareByDescending<CharacterProfile> { it.imageCount }.thenBy { it.ordinal })

    fun taggedImageCount(): Int = images.size

    fun pathsFor(characterId: String): List<String> =
        images.values.filter { characterId in it.characterIds }.map(ImageTags::path)

    fun snapshot(): CharacterTagSnapshot = CharacterTagSnapshot(
        characters = clusters.values.map { cluster ->
            StoredCharacter(
                id = cluster.id,
                ordinal = cluster.ordinal,
                label = cluster.label,
                centroid = cluster.centroid.copyOf(),
                sampleCount = cluster.sampleCount,
                coverPath = cluster.coverPath,
                coverModifiedMillis = cluster.coverModifiedMillis,
            )
        },
        images = images.values.toList(),
    )

    fun restore(snapshot: CharacterTagSnapshot) {
        clear()
        snapshot.characters.forEach { stored ->
            clusters[stored.id] = Cluster(
                id = stored.id,
                ordinal = stored.ordinal,
                label = stored.label,
                centroid = stored.centroid.copyOf(),
                sampleCount = stored.sampleCount,
                coverPath = stored.coverPath,
                coverModifiedMillis = stored.coverModifiedMillis,
            )
            nextOrdinal = maxOf(nextOrdinal, stored.ordinal + 1)
        }
        snapshot.images.takeLast(maxImages).forEach { tags ->
            val knownIds = tags.characterIds.filter(clusters::containsKey)
            images[tags.path] = if (knownIds.size == tags.characterIds.size) tags else tags.copy(characterIds = knownIds)
            knownIds.forEach { id -> imageCounts[id] = (imageCounts[id] ?: 0) + 1 }
        }
    }

    private fun nearestCluster(signature: FloatArray): Cluster? {
        var best: Cluster? = null
        var bestDistance = matchThreshold
        clusters.values.forEach { cluster ->
            val distance = FaceGeometry.distance(cluster.centroid, signature)
            if (distance < bestDistance) {
                bestDistance = distance
                best = cluster
            }
        }
        return best
    }

    private fun newCluster(signature: FloatArray): Cluster {
        val ordinal = nextOrdinal++
        val cluster = Cluster(
            id = "c$ordinal",
            ordinal = ordinal,
            label = null,
            centroid = signature.copyOf(),
            sampleCount = 0,
            coverPath = null,
            coverModifiedMillis = 0L,
        )
        clusters[cluster.id] = cluster
        return cluster
    }

    private fun trimImages() {
        while (images.size > maxImages) {
            val oldest = images.keys.firstOrNull() ?: return
            forget(oldest)
        }
    }

    companion object {
        /** Tagging a whole device can reach a lot of files; past this the least recently seen images are dropped. */
        const val MAX_IMAGES = 60_000
    }
}
