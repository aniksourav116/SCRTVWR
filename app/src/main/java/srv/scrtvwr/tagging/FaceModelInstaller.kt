package srv.scrtvwr.tagging

import android.content.Context
import android.net.Uri
import org.tensorflow.lite.Interpreter
import timber.log.Timber
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Installs a face recognition model from a file the user picked, so a model can be added or swapped
 * without rebuilding the app.
 *
 * The installed copy lives in the app's private files directory and takes precedence over anything
 * bundled in assets - see [FaceEmbeddingModel.load]. Candidates are validated before they replace a
 * working model: a file that is not a usable TFLite embedder is rejected with a reason rather than
 * quietly breaking tagging.
 */
object FaceModelInstaller {
    private const val DIRECTORY_NAME = "face_model"

    /** Refuse anything implausible early; real embedders are one to a few MB. */
    private const val MAX_MODEL_BYTES = 64L * 1024L * 1024L

    fun installedModel(context: Context): File =
        File(File(context.filesDir, DIRECTORY_NAME), FaceEmbeddingModel.ASSET_NAME)

    fun isInstalled(context: Context): Boolean = installedModel(context).isFile

    sealed interface InstallResult {
        data class Installed(val inputSize: Int, val embeddingSize: Int) : InstallResult
        data class Rejected(val reason: String) : InstallResult
    }

    /** Copies [uri] into place after checking that TensorFlow Lite can actually run it. */
    fun install(context: Context, uri: Uri): InstallResult {
        val target = installedModel(context)
        val directory = target.parentFile ?: return InstallResult.Rejected("No writable location for the model")
        directory.mkdirs()
        val temporary = File.createTempFile("model-", ".tmp", directory)
        return try {
            val copied = context.contentResolver.openInputStream(uri)?.use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            } ?: return InstallResult.Rejected("That file could not be opened")
            if (copied > MAX_MODEL_BYTES) {
                return InstallResult.Rejected("That file is too large to be a face model")
            }

            when (val shape = validate(temporary)) {
                is InstallResult.Rejected -> shape
                is InstallResult.Installed -> {
                    if (!temporary.renameTo(target)) {
                        target.delete()
                        if (!temporary.renameTo(target)) {
                            return InstallResult.Rejected("The model could not be saved")
                        }
                    }
                    Timber.i("Installed face model: input %d, embedding %d", shape.inputSize, shape.embeddingSize)
                    shape
                }
            }
        } catch (error: Exception) {
            Timber.w(error, "Installing the face model failed")
            InstallResult.Rejected(error.message ?: "The model could not be read")
        } finally {
            temporary.delete()
        }
    }

    fun remove(context: Context): Boolean = installedModel(context).delete()

    /**
     * Loads the candidate once and checks it has the shape the tagging pipeline feeds it: one square
     * image in, one vector out.
     */
    private fun validate(candidate: File): InstallResult = runCatching {
        val bytes = candidate.readBytes()
        val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
            put(bytes)
            rewind()
        }
        Interpreter(buffer).use { interpreter ->
            val inputShape = interpreter.getInputTensor(0).shape()
            val outputShape = interpreter.getOutputTensor(0).shape()
            val height = inputShape.getOrNull(1) ?: 0
            val width = inputShape.getOrNull(2) ?: 0
            val embeddingSize = outputShape.lastOrNull() ?: 0
            when {
                height <= 0 || width <= 0 -> InstallResult.Rejected("That model does not take an image")
                height != width -> InstallResult.Rejected("That model wants a ${width}x$height input, not a square face crop")
                embeddingSize < MIN_EMBEDDING_SIZE ->
                    InstallResult.Rejected("That model returns $embeddingSize values, too few to identify a face")
                else -> InstallResult.Installed(inputSize = height, embeddingSize = embeddingSize)
            }
        }
    }.getOrElse { error ->
        InstallResult.Rejected(error.message ?: "That file is not a TensorFlow Lite model")
    }

    /** A classifier or detector head would be far smaller than any embedding worth clustering. */
    private const val MIN_EMBEDDING_SIZE = 32
}
