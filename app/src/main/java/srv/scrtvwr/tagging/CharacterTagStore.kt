package srv.scrtvwr.tagging

import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.BufferedWriter
import java.io.File

/**
 * Persists tagging state under the app's private files directory.
 *
 * Characters live in one small JSON document. Image tags are written one JSON object per line instead,
 * because a whole-device scan can produce tens of thousands of them and neither side of the round trip
 * should have to hold the entire document in memory. Both files are written to a temporary file and
 * renamed, so a kill mid-write leaves the previous version intact.
 */
class CharacterTagStore(private val directory: File) {
    private val charactersFile = File(directory, "characters.json")
    private val imagesFile = File(directory, "images.jsonl")

    fun read(): CharacterTagSnapshot = runCatching {
        CharacterTagSnapshot(
            characters = readCharacters(),
            images = readImages(),
        )
    }.getOrElse { error ->
        Timber.w(error, "Unable to read character tags; starting empty")
        CharacterTagSnapshot()
    }

    fun write(snapshot: CharacterTagSnapshot) {
        runCatching {
            directory.mkdirs()
            writeAtomically(charactersFile) { writer -> writer.write(snapshot.characters.toJson().toString()) }
            writeAtomically(imagesFile) { writer ->
                writer.write(JSONObject().put(KEY_VERSION, VERSION).toString())
                writer.write("\n")
                snapshot.images.forEach { tags ->
                    writer.write(tags.toJson().toString())
                    writer.write("\n")
                }
            }
        }.onFailure { error -> Timber.w(error, "Unable to save character tags") }
    }

    fun clear() {
        charactersFile.delete()
        imagesFile.delete()
    }

    private fun readCharacters(): List<StoredCharacter> {
        if (!charactersFile.isFile) return emptyList()
        val root = JSONObject(charactersFile.readText())
        if (root.optInt(KEY_VERSION) != VERSION) return emptyList()
        val array = root.optJSONArray(KEY_CHARACTERS) ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val json = array.optJSONObject(index) ?: return@mapNotNull null
            val centroidJson = json.optJSONArray(KEY_CENTROID) ?: return@mapNotNull null
            val centroid = FloatArray(centroidJson.length()) { position ->
                centroidJson.optDouble(position, 0.0).toFloat()
            }
            val id = json.optString(KEY_ID).takeIf(String::isNotEmpty) ?: return@mapNotNull null
            StoredCharacter(
                id = id,
                ordinal = json.optInt(KEY_ORDINAL, index + 1),
                label = json.optString(KEY_LABEL).takeIf(String::isNotEmpty),
                note = json.optString(KEY_NOTE).takeIf(String::isNotEmpty),
                centroid = centroid,
                sampleCount = json.optInt(KEY_SAMPLES, 1),
                coverPath = json.optString(KEY_COVER).takeIf(String::isNotEmpty),
                coverModifiedMillis = json.optLong(KEY_COVER_MODIFIED),
            )
        }
    }

    private fun readImages(): List<ImageTags> {
        if (!imagesFile.isFile) return emptyList()
        val images = mutableListOf<ImageTags>()
        imagesFile.bufferedReader().useLines { lines ->
            lines.forEachIndexed { index, line ->
                if (line.isBlank()) return@forEachIndexed
                val json = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachIndexed
                if (index == 0 && json.has(KEY_VERSION)) {
                    if (json.optInt(KEY_VERSION) != VERSION) return emptyList()
                    return@forEachIndexed
                }
                val path = json.optString(KEY_PATH).takeIf(String::isNotEmpty) ?: return@forEachIndexed
                val characters = json.optJSONArray(KEY_CHARACTERS)
                images += ImageTags(
                    path = path,
                    modifiedMillis = json.optLong(KEY_MODIFIED),
                    faceCount = json.optInt(KEY_FACES),
                    characterIds = (0 until (characters?.length() ?: 0)).mapNotNull { position ->
                        characters?.optString(position)?.takeIf(String::isNotEmpty)
                    },
                    taggedAtMillis = json.optLong(KEY_TAGGED_AT),
                )
            }
        }
        return images
    }

    private fun List<StoredCharacter>.toJson(): JSONObject {
        val array = JSONArray()
        forEach { character ->
            // Optional values are omitted rather than stored as JSON null: org.json hands a stored
            // null back as the literal string "null", which would become a character named "null".
            val json = JSONObject()
                .put(KEY_ID, character.id)
                .put(KEY_ORDINAL, character.ordinal)
                .put(KEY_SAMPLES, character.sampleCount)
                .put(KEY_COVER_MODIFIED, character.coverModifiedMillis)
                .put(KEY_CENTROID, JSONArray().apply { character.centroid.forEach { put(it.toDouble()) } })
            character.label?.let { json.put(KEY_LABEL, it) }
            character.note?.let { json.put(KEY_NOTE, it) }
            character.coverPath?.let { json.put(KEY_COVER, it) }
            array.put(json)
        }
        return JSONObject().put(KEY_VERSION, VERSION).put(KEY_CHARACTERS, array)
    }

    private fun ImageTags.toJson(): JSONObject = JSONObject()
        .put(KEY_PATH, path)
        .put(KEY_MODIFIED, modifiedMillis)
        .put(KEY_FACES, faceCount)
        .put(KEY_TAGGED_AT, taggedAtMillis)
        .put(KEY_CHARACTERS, JSONArray().apply { characterIds.forEach(::put) })

    private fun writeAtomically(target: File, block: (BufferedWriter) -> Unit) {
        val temporary = File.createTempFile(target.nameWithoutExtension, ".tmp", directory)
        try {
            temporary.bufferedWriter().use(block)
            if (!temporary.renameTo(target)) {
                target.delete()
                check(temporary.renameTo(target)) { "Unable to replace ${target.name}" }
            }
        } finally {
            temporary.delete()
        }
    }

    private companion object {
        const val VERSION = 1
        const val KEY_VERSION = "version"
        const val KEY_CHARACTERS = "characters"
        const val KEY_ID = "id"
        const val KEY_ORDINAL = "ordinal"
        const val KEY_LABEL = "label"
        const val KEY_NOTE = "note"
        const val KEY_SAMPLES = "samples"
        const val KEY_COVER = "cover"
        const val KEY_COVER_MODIFIED = "coverModified"
        const val KEY_CENTROID = "centroid"
        const val KEY_PATH = "path"
        const val KEY_MODIFIED = "modified"
        const val KEY_FACES = "faces"
        const val KEY_TAGGED_AT = "taggedAt"
    }
}
