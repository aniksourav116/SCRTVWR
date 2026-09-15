package srv.scrtvwr.tagging

/**
 * Keeps the characters and the per-image tags in memory, and decides which character a new face
 * belongs to.
 *
 * Pure Kotlin on purpose - the matching rules are the part worth testing, and they have no reason to
 * know about Android. Callers confine access to one thread or guard it with a lock;
 * [CharacterTagRepository] does the latter.
 *
 * Only each character's exemplars are held here. The full face vectors live on disk and are read back
 * only when the groups are rebuilt, so memory stays proportional to the number of people rather than
 * the number of photos - which matters because both vary enormously between users.
 */
class CharacterRegistry(
    private var rules: MatchingRules = MatchingRules.forGeometry("geometry-v1-${FaceGeometry.SIGNATURE_SIZE}"),
    private val maxImages: Int = MAX_IMAGES,
) {
    private class Identity(
        val id: String,
        val ordinal: Int,
        var label: String?,
        var note: String?,
        val exemplars: MutableList<FloatArray>,
        var sampleCount: Int,
        var coverPath: String?,
        var coverModifiedMillis: Long,
    )

    private val identities = linkedMapOf<String, Identity>()
    private val imageCounts = mutableMapOf<String, Int>()

    /** Access-ordered so the least recently touched image is the one evicted at [maxImages]. */
    private val images = LinkedHashMap<String, ImageTags>(0, 0.75f, true)

    /** Face vectors produced since the last drain, waiting to be appended to the face log. */
    private val pendingFaces = mutableListOf<FaceVector>()
    private var nextOrdinal = 1

    val descriptorId: String
        get() = rules.descriptorId

    fun tagsFor(path: String): ImageTags? = images[path]

    fun tagsFor(paths: Collection<String>): Map<String, ImageTags> =
        paths.mapNotNull { path -> images[path]?.let { path to it } }.toMap()

    fun needsTagging(path: String, modifiedMillis: Long): Boolean =
        images[path]?.modifiedMillis != modifiedMillis

    /**
     * Records the faces found in one image. Each embedding joins the character it clearly belongs to,
     * starts a new one if it resembles nobody, and is otherwise left unassigned rather than guessed
     * at - a wrong merge is permanent, an unassigned face only waits for the next rebuild.
     */
    fun record(
        path: String,
        modifiedMillis: Long,
        faceCount: Int,
        embeddings: List<FloatArray>,
        taggedAtMillis: Long,
    ): ImageTags {
        forget(path)
        val characterIds = mutableListOf<String>()
        embeddings.forEachIndexed { faceIndex, embedding ->
            val identity = assign(embedding)
            identity?.let { matched ->
                matched.absorb(embedding, rules)
                matched.sampleCount++
                if (matched.coverPath == null) {
                    matched.coverPath = path
                    matched.coverModifiedMillis = modifiedMillis
                }
                if (matched.id !in characterIds) characterIds += matched.id
            }
            pendingFaces += FaceVector(
                path = path,
                modifiedMillis = modifiedMillis,
                index = faceIndex,
                embedding = embedding,
                characterId = identity?.id,
            )
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

    fun drainPendingFaces(): List<FaceVector> {
        val drained = pendingFaces.toList()
        pendingFaces.clear()
        return drained
    }

    /**
     * Regroups every face from scratch. Ordering effects, early bad merges and threshold changes all
     * wash out here, which is why it is worth running after a big scan. Names, notes and covers
     * follow whichever new group inherited most of the old one's faces.
     */
    fun rebuild(faces: List<FaceVector>): List<FaceVector> {
        if (faces.isEmpty()) return emptyList()
        val current = faces
            .filter { face -> images[face.path]?.modifiedMillis == face.modifiedMillis }
            // The face log is append-only, so an image tagged twice leaves two rows for the same
            // face. Keep the newest, or that face would vote twice in the clustering.
            .asReversed()
            .distinctBy { face -> face.path to face.index }
            .asReversed()
        if (current.isEmpty()) return emptyList()

        val assignments = FaceClustering.cluster(current.map(FaceVector::embedding), rules)
        val previous = identities.values.associateBy(Identity::id)
        val groups = assignments.indices.groupBy { index -> assignments[index] }
            .filterKeys { it != FaceClustering.UNASSIGNED }

        identities.clear()
        imageCounts.clear()
        val claimed = mutableSetOf<String>()
        val regrouped = mutableListOf<FaceVector>()

        groups.values
            .sortedByDescending(List<Int>::size)
            .forEach { members ->
                val inherited = members
                    .mapNotNull { index -> current[index].characterId }
                    .filter { id -> id !in claimed && previous.containsKey(id) }
                    .groupingBy { it }
                    .eachCount()
                    .maxByOrNull { entry -> entry.value }
                    ?.key
                    ?.also(claimed::add)
                val identity = inherited?.let(previous::get)?.let { old ->
                    Identity(
                        id = old.id,
                        ordinal = old.ordinal,
                        label = old.label,
                        note = old.note,
                        exemplars = mutableListOf(),
                        sampleCount = 0,
                        coverPath = old.coverPath,
                        coverModifiedMillis = old.coverModifiedMillis,
                    )
                } ?: newIdentity()
                identities[identity.id] = identity

                val memberEmbeddings = members.map { index -> current[index].embedding }
                identity.exemplars.addAll(FaceClustering.selectExemplars(memberEmbeddings, rules))
                identity.sampleCount = members.size
                members.forEach { index ->
                    regrouped += current[index].copy(characterId = identity.id)
                }
            }

        assignments.indices
            .filter { index -> assignments[index] == FaceClustering.UNASSIGNED }
            .forEach { index -> regrouped += current[index].copy(characterId = null) }

        reindexImages(regrouped)
        nextOrdinal = maxOf(nextOrdinal, (identities.values.maxOfOrNull(Identity::ordinal) ?: 0) + 1)
        return regrouped
    }

    /** Folds [otherId] into [characterId], for when one person was split across two characters. */
    fun merge(characterId: String, otherId: String): Boolean {
        if (characterId == otherId) return false
        val target = identities[characterId] ?: return false
        val source = identities.remove(otherId) ?: return false

        source.exemplars.forEach { exemplar -> target.absorb(exemplar, rules) }
        target.sampleCount += source.sampleCount
        target.label = target.label ?: source.label
        target.note = target.note ?: source.note
        if (target.coverPath == null) {
            target.coverPath = source.coverPath
            target.coverModifiedMillis = source.coverModifiedMillis
        }
        imageCounts.remove(otherId)
        images.entries.forEach { entry ->
            val tags = entry.value
            if (otherId in tags.characterIds) {
                val ids = (tags.characterIds - otherId).let { if (characterId in it) it else it + characterId }
                entry.setValue(tags.copy(characterIds = ids))
            }
        }
        imageCounts[characterId] = images.values.count { characterId in it.characterIds }
        return true
    }

    /** "That is not them": drops one image from a character without touching the image itself. */
    fun unassign(characterId: String, path: String): Boolean {
        val tags = images[path] ?: return false
        if (characterId !in tags.characterIds) return false
        images[path] = tags.copy(characterIds = tags.characterIds - characterId)
        val remaining = (imageCounts[characterId] ?: 0) - 1
        if (remaining > 0) imageCounts[characterId] = remaining else imageCounts.remove(characterId)
        identities[characterId]?.let { identity ->
            if (identity.coverPath == path) {
                identity.coverPath = images.values.firstOrNull { characterId in it.characterIds }?.path
                identity.coverModifiedMillis = identity.coverPath?.let { images[it]?.modifiedMillis } ?: 0L
            }
        }
        return true
    }

    fun rename(characterId: String, label: String?): Boolean {
        val identity = identities[characterId] ?: return false
        identity.label = label?.trim()?.takeIf(String::isNotEmpty)
        return true
    }

    fun setNote(characterId: String, note: String?): Boolean {
        val identity = identities[characterId] ?: return false
        identity.note = note?.trim()?.takeIf(String::isNotEmpty)
        return true
    }

    fun setCover(characterId: String, path: String, modifiedMillis: Long): Boolean {
        val identity = identities[characterId] ?: return false
        identity.coverPath = path
        identity.coverModifiedMillis = modifiedMillis
        return true
    }

    fun remove(characterId: String): Boolean {
        if (identities.remove(characterId) == null) return false
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
        identities.clear()
        imageCounts.clear()
        images.clear()
        pendingFaces.clear()
        nextOrdinal = 1
    }

    fun profiles(): List<CharacterProfile> = identities.values
        .map { identity ->
            CharacterProfile(
                id = identity.id,
                ordinal = identity.ordinal,
                label = identity.label,
                note = identity.note,
                sampleCount = identity.sampleCount,
                imageCount = imageCounts[identity.id] ?: 0,
                coverPath = identity.coverPath,
                coverModifiedMillis = identity.coverModifiedMillis,
            )
        }
        .sortedWith(compareByDescending<CharacterProfile> { it.imageCount }.thenBy { it.ordinal })

    fun taggedImageCount(): Int = images.size

    /** Every image a character appears in, oldest tag first, for their album. */
    fun imagesFor(characterId: String): List<ImageTags> =
        images.values.filter { characterId in it.characterIds }.sortedBy(ImageTags::path)

    fun snapshot(): CharacterTagSnapshot = CharacterTagSnapshot(
        descriptorId = rules.descriptorId,
        characters = identities.values.map { identity ->
            StoredCharacter(
                id = identity.id,
                ordinal = identity.ordinal,
                label = identity.label,
                note = identity.note,
                exemplars = identity.exemplars.map(FloatArray::copyOf),
                sampleCount = identity.sampleCount,
                coverPath = identity.coverPath,
                coverModifiedMillis = identity.coverModifiedMillis,
            )
        },
        images = images.values.toList(),
    )

    /**
     * Restores a snapshot, but only if it came from the same embedder: vectors from a different one
     * are not comparable, so stale tags are dropped and the images are tagged again.
     */
    fun restore(snapshot: CharacterTagSnapshot): Boolean {
        clear()
        if (snapshot.characters.isEmpty() && snapshot.images.isEmpty()) return true
        if (snapshot.descriptorId != rules.descriptorId) return false

        snapshot.characters.forEach { stored ->
            identities[stored.id] = Identity(
                id = stored.id,
                ordinal = stored.ordinal,
                label = stored.label,
                note = stored.note,
                exemplars = stored.exemplars.map(FloatArray::copyOf).toMutableList(),
                sampleCount = stored.sampleCount,
                coverPath = stored.coverPath,
                coverModifiedMillis = stored.coverModifiedMillis,
            )
            nextOrdinal = maxOf(nextOrdinal, stored.ordinal + 1)
        }
        snapshot.images.takeLast(maxImages).forEach { tags ->
            val knownIds = tags.characterIds.filter(identities::containsKey)
            images[tags.path] = if (knownIds.size == tags.characterIds.size) tags else tags.copy(characterIds = knownIds)
            knownIds.forEach { id -> imageCounts[id] = (imageCounts[id] ?: 0) + 1 }
        }
        return true
    }

    /**
     * Swaps the matching rules, for example when a recognition model is installed. Returns false when
     * the new rules describe a different vector space, in which case everything held here is dropped:
     * old vectors cannot be compared with new ones, so keeping them would produce nonsense groups.
     */
    fun useRules(newRules: MatchingRules): Boolean {
        val compatible = newRules.descriptorId == rules.descriptorId
        rules = newRules
        if (!compatible) clear()
        return compatible
    }

    private fun assign(embedding: FloatArray): Identity? {
        val order = identities.values.toList()
        val match = FaceClustering.bestMatch(embedding, order.map { it.exemplars }, rules)
        return when {
            match.similarity >= rules.joinSimilarity && match.cluster >= 0 -> order[match.cluster]
            match.similarity < rules.newIdentitySimilarity -> newIdentity().also { identities[it.id] = it }
            else -> null
        }
    }

    private fun newIdentity(): Identity {
        val ordinal = nextOrdinal++
        return Identity(
            id = "c$ordinal",
            ordinal = ordinal,
            label = null,
            note = null,
            exemplars = mutableListOf(),
            sampleCount = 0,
            coverPath = null,
            coverModifiedMillis = 0L,
        )
    }

    /**
     * Keeps a spread of exemplars rather than a dozen copies of the same shot: once full, the most
     * redundant of the candidates is dropped, so angles and lighting the character has not seen
     * before displace ones it already has.
     */
    private fun Identity.absorb(embedding: FloatArray, rules: MatchingRules) {
        if (exemplars.size < FaceClustering.MAX_EXEMPLARS) {
            exemplars.add(embedding.copyOf())
            return
        }
        val candidates = exemplars + embedding
        val redundancy = candidates.map { candidate ->
            candidates.filter { it !== candidate }.maxOfOrNull { other -> rules.similarity(candidate, other) }
                ?: MatchingRules.LOWEST_SIMILARITY
        }
        val dropIndex = redundancy.indexOf(redundancy.max())
        if (dropIndex == candidates.lastIndex) return
        exemplars[dropIndex] = embedding.copyOf()
    }

    private fun reindexImages(faces: List<FaceVector>) {
        val byPath = faces.groupBy(FaceVector::path)
        images.entries.forEach { entry ->
            val ids = byPath[entry.key]?.mapNotNull(FaceVector::characterId)?.distinct().orEmpty()
            entry.setValue(entry.value.copy(characterIds = ids))
        }
        imageCounts.clear()
        images.values.forEach { tags ->
            tags.characterIds.forEach { id -> imageCounts[id] = (imageCounts[id] ?: 0) + 1 }
        }
        identities.values.forEach { identity ->
            if (identity.coverPath?.let { path -> images[path]?.characterIds?.contains(identity.id) } != true) {
                val cover = images.values.firstOrNull { identity.id in it.characterIds }
                identity.coverPath = cover?.path
                identity.coverModifiedMillis = cover?.modifiedMillis ?: 0L
            }
        }
    }

    private fun trimImages() {
        while (images.size > maxImages) {
            val oldest = images.keys.firstOrNull() ?: return
            forget(oldest)
        }
    }

    companion object {
        /** Tagging a whole device can reach a lot of files; past this the least recent images go. */
        const val MAX_IMAGES = 60_000
    }
}
