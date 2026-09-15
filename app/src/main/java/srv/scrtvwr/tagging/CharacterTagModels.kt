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

/** Everything tagging knows about one image. [modifiedMillis] invalidates the entry when the file changes. */
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

/** One character stored on disk, including the centroid new faces are matched against. */
data class StoredCharacter(
    val id: String,
    val ordinal: Int,
    val label: String?,
    val note: String?,
    val centroid: FloatArray,
    val sampleCount: Int,
    val coverPath: String?,
    val coverModifiedMillis: Long,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is StoredCharacter && id == other.id && ordinal == other.ordinal &&
            label == other.label && note == other.note && centroid.contentEquals(other.centroid) &&
            sampleCount == other.sampleCount && coverPath == other.coverPath &&
            coverModifiedMillis == other.coverModifiedMillis)

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + ordinal
        result = 31 * result + (label?.hashCode() ?: 0)
        result = 31 * result + (note?.hashCode() ?: 0)
        result = 31 * result + centroid.contentHashCode()
        result = 31 * result + sampleCount
        result = 31 * result + (coverPath?.hashCode() ?: 0)
        result = 31 * result + coverModifiedMillis.hashCode()
        return result
    }
}

/** The complete tagging state, as written to and read back from disk. */
data class CharacterTagSnapshot(
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
