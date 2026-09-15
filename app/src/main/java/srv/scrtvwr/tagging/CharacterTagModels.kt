package srv.scrtvwr.tagging

/** A character discovered by clustering the faces ML Kit found across images. */
data class CharacterProfile(
    val id: String,
    /** 1-based discovery order, used for the default "Character 3" label. */
    val ordinal: Int,
    /** User-assigned name, or null while the character still uses its default label. */
    val label: String?,
    /** Free-text detail the user attached to this character. */
    val note: String? = null,
    val sampleCount: Int,
    val imageCount: Int,
    val coverPath: String? = null,
    val coverModifiedMillis: Long = 0L,
)

/** Everything tagging knows about one image. [modifiedMillis] invalidates the entry when it changes. */
data class ImageTags(
    val path: String,
    val modifiedMillis: Long,
    val faceCount: Int,
    val characterIds: List<String>,
    val taggedAtMillis: Long,
) {
    val hasFaces: Boolean
        get() = faceCount > 0
}

/**
 * One face and the vector that identifies it. These are kept on disk rather than in memory: they are
 * what makes it possible to regroup everyone, retune the thresholds, or split a character apart
 * without decoding a single image again.
 */
data class FaceVector(
    val path: String,
    val modifiedMillis: Long,
    val index: Int,
    val embedding: FloatArray,
    val characterId: String?,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is FaceVector && path == other.path && modifiedMillis == other.modifiedMillis &&
            index == other.index && characterId == other.characterId && embedding.contentEquals(other.embedding))

    override fun hashCode(): Int {
        var result = path.hashCode()
        result = 31 * result + modifiedMillis.hashCode()
        result = 31 * result + index
        result = 31 * result + (characterId?.hashCode() ?: 0)
        result = 31 * result + embedding.contentHashCode()
        return result
    }
}

/** One character on disk, including the exemplar vectors new faces are matched against. */
data class StoredCharacter(
    val id: String,
    val ordinal: Int,
    val label: String?,
    val note: String?,
    val exemplars: List<FloatArray>,
    val sampleCount: Int,
    val coverPath: String?,
    val coverModifiedMillis: Long,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is StoredCharacter && id == other.id && ordinal == other.ordinal &&
            label == other.label && note == other.note && sampleCount == other.sampleCount &&
            coverPath == other.coverPath && coverModifiedMillis == other.coverModifiedMillis &&
            exemplars.size == other.exemplars.size &&
            exemplars.indices.all { index -> exemplars[index].contentEquals(other.exemplars[index]) })

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + ordinal
        result = 31 * result + (label?.hashCode() ?: 0)
        result = 31 * result + (note?.hashCode() ?: 0)
        result = 31 * result + sampleCount
        result = 31 * result + (coverPath?.hashCode() ?: 0)
        result = 31 * result + coverModifiedMillis.hashCode()
        exemplars.forEach { exemplar -> result = 31 * result + exemplar.contentHashCode() }
        return result
    }
}

/**
 * The tagging state as written to and read back from disk. [descriptorId] names the vector space the
 * exemplars live in; state from a different embedder is discarded rather than compared against.
 */
data class CharacterTagSnapshot(
    val descriptorId: String = "",
    val characters: List<StoredCharacter> = emptyList(),
    val images: List<ImageTags> = emptyList(),
)

/** Tagging can be switched off entirely, or paused and picked up again where it stopped. */
enum class TaggingState {
    Off,
    Paused,
    Running,
}

data class TagScanProgress(
    val isRunning: Boolean = false,
    val processed: Int = 0,
    val total: Int = 0,
    val currentName: String? = null,
) {
    val fraction: Float
        get() = if (total <= 0) 0f else (processed.toFloat() / total).coerceIn(0f, 1f)
}

/** Whether the user has answered the "tag every image in storage" prompt. */
enum class StorageScanConsent {
    Unknown,
    Granted,
    Declined,
}
