package srv.scrtvwr.tagging

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Persists tagging state under the app's private files directory, in three parts:
 *
 *  - `characters.json` - the characters and their exemplar vectors. Small, rewritten whole.
 *  - `images.jsonl` - one line per tagged image. A whole-device scan makes a lot of these, so they
 *    are line-delimited and neither side of the round trip has to hold the document in memory.
 *  - `faces.jsonl` - one line per detected face, with the vector that identifies it. Append-only
 *    while tagging runs, rewritten when the groups are rebuilt.
 *
 * Vectors are stored as int8 with a per-vector scale rather than as JSON numbers: a 192-float vector
 * costs about 260 bytes this way instead of about 2 KB of text, and the quantisation error is far
 * below the margin any threshold here cares about.
 */
class CharacterTagStore(private val directory: File) {
    private val charactersFile = File(directory, "characters.json")
    private val imagesFile = File(directory, "images.jsonl")
    private val facesFile = File(directory, "faces.jsonl")

    fun read(): CharacterTagSnapshot = runCatching {
        val root = charactersFile.takeIf(File::isFile)?.let { JSONObject(it.readText()) }
        if (root != null && root.optInt(KEY_VERSION) != VERSION) return CharacterTagSnapshot()
        CharacterTagSnapshot(
            descriptorId = root?.optString(KEY_DESCRIPTOR).orEmpty(),
            characters = root?.let(::readCharacters).orEmpty(),
            images = readImages(),
        )
    }.getOrElse { error ->
        Timber.w(error, "Unable to read character tags; starting empty")
        CharacterTagSnapshot()
    }

    fun write(snapshot: CharacterTagSnapshot) {
        runCatching {
            directory.mkdirs()
            writeAtomically(charactersFile) { writer -> writer.write(snapshot.toJson().toString()) }
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

    /** Appends newly embedded faces. Cheap enough to call on every save. */
    fun appendFaces(descriptorId: String, faces: List<FaceVector>) {
        if (faces.isEmpty()) return
        runCatching {
            directory.mkdirs()
            val needsHeader = !facesFile.isFile || facesFile.length() == 0L
            FileWriter(facesFile, true).buffered().use { writer ->
                if (needsHeader) {
                    writer.write(JSONObject().put(KEY_VERSION, VERSION).put(KEY_DESCRIPTOR, descriptorId).toString())
                    writer.write("\n")
                }
                faces.forEach { face ->
                    writer.write(face.toJson().toString())
                    writer.write("\n")
                }
            }
        }.onFailure { error -> Timber.w(error, "Unable to append face vectors") }
    }

    /** Reads every stored face vector. Only the rebuild needs this, so the cost is paid rarely. */
    fun readFaces(descriptorId: String): List<FaceVector> = runCatching {
        if (!facesFile.isFile) return emptyList()
        val faces = mutableListOf<FaceVector>()
        facesFile.bufferedReader().useLines { lines ->
            lines.forEachIndexed { index, line ->
                if (line.isBlank()) return@forEachIndexed
                val json = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachIndexed
                if (index == 0 && json.has(KEY_VERSION)) {
                    // Vectors from another embedder cannot be compared with the current ones.
                    if (json.optInt(KEY_VERSION) != VERSION) return emptyList()
                    if (json.optString(KEY_DESCRIPTOR) != descriptorId) return emptyList()
                    return@forEachIndexed
                }
                faces += json.toFaceVector() ?: return@forEachIndexed
            }
        }
        faces
    }.getOrElse { error ->
        Timber.w(error, "Unable to read face vectors")
        emptyList()
    }

    /** Replaces the face log, dropping rows for images that are gone and re-stating assignments. */
    fun writeFaces(descriptorId: String, faces: List<FaceVector>) {
        runCatching {
            directory.mkdirs()
            writeAtomically(facesFile) { writer ->
                writer.write(JSONObject().put(KEY_VERSION, VERSION).put(KEY_DESCRIPTOR, descriptorId).toString())
                writer.write("\n")
                faces.forEach { face ->
                    writer.write(face.toJson().toString())
                    writer.write("\n")
                }
            }
        }.onFailure { error -> Timber.w(error, "Unable to rewrite face vectors") }
    }

    fun clear() {
        charactersFile.delete()
        imagesFile.delete()
        facesFile.delete()
    }

    private fun readCharacters(root: JSONObject): List<StoredCharacter> {
        val array = root.optJSONArray(KEY_CHARACTERS) ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val json = array.optJSONObject(index) ?: return@mapNotNull null
            val id = json.optString(KEY_ID).takeIf(String::isNotEmpty) ?: return@mapNotNull null
            val exemplarsJson = json.optJSONArray(KEY_EXEMPLARS) ?: JSONArray()
            StoredCharacter(
                id = id,
                ordinal = json.optInt(KEY_ORDINAL, index + 1),
                label = json.optString(KEY_LABEL).takeIf(String::isNotEmpty),
                note = json.optString(KEY_NOTE).takeIf(String::isNotEmpty),
                exemplars = (0 until exemplarsJson.length()).mapNotNull { position ->
                    exemplarsJson.optJSONObject(position)?.toVector()
                },
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

    private fun CharacterTagSnapshot.toJson(): JSONObject {
        val array = JSONArray()
        characters.forEach { character ->
            // Optional values are omitted rather than stored as JSON null: org.json hands a stored
            // null back as the literal string "null", which would become a character named "null".
            val json = JSONObject()
                .put(KEY_ID, character.id)
                .put(KEY_ORDINAL, character.ordinal)
                .put(KEY_SAMPLES, character.sampleCount)
                .put(KEY_COVER_MODIFIED, character.coverModifiedMillis)
                .put(KEY_EXEMPLARS, JSONArray().apply { character.exemplars.forEach { put(it.toJson()) } })
            character.label?.let { json.put(KEY_LABEL, it) }
            character.note?.let { json.put(KEY_NOTE, it) }
            character.coverPath?.let { json.put(KEY_COVER, it) }
            array.put(json)
        }
        return JSONObject()
            .put(KEY_VERSION, VERSION)
            .put(KEY_DESCRIPTOR, descriptorId)
            .put(KEY_CHARACTERS, array)
    }

    private fun ImageTags.toJson(): JSONObject = JSONObject()
        .put(KEY_PATH, path)
        .put(KEY_MODIFIED, modifiedMillis)
        .put(KEY_FACES, faceCount)
        .put(KEY_TAGGED_AT, taggedAtMillis)
        .put(KEY_CHARACTERS, JSONArray().apply { characterIds.forEach(::put) })

    private fun FaceVector.toJson(): JSONObject {
        val json = JSONObject()
            .put(KEY_PATH, path)
            .put(KEY_MODIFIED, modifiedMillis)
            .put(KEY_INDEX, index)
            .put(KEY_VECTOR, embedding.toJson())
        characterId?.let { json.put(KEY_CHARACTER, it) }
        return json
    }

    private fun JSONObject.toFaceVector(): FaceVector? {
        val path = optString(KEY_PATH).takeIf(String::isNotEmpty) ?: return null
        val embedding = optJSONObject(KEY_VECTOR)?.toVector() ?: return null
        return FaceVector(
            path = path,
            modifiedMillis = optLong(KEY_MODIFIED),
            index = optInt(KEY_INDEX),
            embedding = embedding,
            characterId = optString(KEY_CHARACTER).takeIf(String::isNotEmpty),
        )
    }

    /** int8 with a per-vector scale, which keeps both unit-length embeddings and raw geometry exact enough. */
    private fun FloatArray.toJson(): JSONObject {
        val peak = maxOfOrNull { abs(it) } ?: 0f
        val scale = if (peak <= 0f) 1f else peak / Byte.MAX_VALUE
        val bytes = ByteArray(size) { index -> (this[index] / scale).roundToInt().coerceIn(-127, 127).toByte() }
        return JSONObject()
            .put(KEY_SCALE, scale.toDouble())
            .put(KEY_DATA, Base64.encodeToString(bytes, Base64.NO_WRAP))
    }

    private fun JSONObject.toVector(): FloatArray? = runCatching {
        val scale = optDouble(KEY_SCALE, 1.0).toFloat()
        val bytes = Base64.decode(optString(KEY_DATA), Base64.NO_WRAP)
        FloatArray(bytes.size) { index -> bytes[index] * scale }
    }.getOrNull()

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
        /** Bumped from 1: version 1 stored landmark centroids, which the new matching cannot use. */
        const val VERSION = 2
        const val KEY_VERSION = "version"
        const val KEY_DESCRIPTOR = "descriptor"
        const val KEY_CHARACTERS = "characters"
        const val KEY_ID = "id"
        const val KEY_ORDINAL = "ordinal"
        const val KEY_LABEL = "label"
        const val KEY_NOTE = "note"
        const val KEY_SAMPLES = "samples"
        const val KEY_COVER = "cover"
        const val KEY_COVER_MODIFIED = "coverModified"
        const val KEY_EXEMPLARS = "exemplars"
        const val KEY_PATH = "path"
        const val KEY_MODIFIED = "modified"
        const val KEY_FACES = "faces"
        const val KEY_TAGGED_AT = "taggedAt"
        const val KEY_INDEX = "index"
        const val KEY_CHARACTER = "character"
        const val KEY_VECTOR = "vector"
        const val KEY_SCALE = "scale"
        const val KEY_DATA = "data"
    }
}
