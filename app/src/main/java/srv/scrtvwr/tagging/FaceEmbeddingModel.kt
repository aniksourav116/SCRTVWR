package srv.scrtvwr.tagging

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import timber.log.Timber
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.sqrt

/**
 * A TensorFlow Lite face recognition model, loaded from `assets/mobilefacenet.tflite`.
 *
 * This is what makes character tagging actually work. Landmark geometry (see [FaceGeometry]) can tell
 * two faces apart only by their proportions, which barely differ between people; a recognition model
 * is trained so that the distance between two photos of one person is smaller than the distance
 * between photos of two people, which is the property clustering needs.
 *
 * Input and output shapes are read from the model rather than assumed, so any MobileFaceNet-style
 * export works: a square RGB input (usually 112x112) and a single embedding vector (usually 128 or
 * 192 floats). If the asset is missing the model simply does not load, and tagging falls back to
 * geometry - see [FaceTagger].
 */
class FaceEmbeddingModel private constructor(
    private val interpreter: Interpreter,
    val inputSize: Int,
    val embeddingSize: Int,
) {
    private val inputBuffer: ByteBuffer = ByteBuffer
        .allocateDirect(inputSize * inputSize * CHANNELS * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
    private val pixels = IntArray(inputSize * inputSize)
    private val output = Array(1) { FloatArray(embeddingSize) }
    private val lock = Any()

    /** Runs one aligned face crop through the model and returns a unit-length embedding. */
    fun embed(alignedFace: Bitmap): FloatArray? = synchronized(lock) {
        runCatching {
            val scaled = if (alignedFace.width == inputSize && alignedFace.height == inputSize) {
                alignedFace
            } else {
                Bitmap.createScaledBitmap(alignedFace, inputSize, inputSize, true)
            }
            writeInput(scaled)
            if (scaled !== alignedFace) scaled.recycle()
            interpreter.run(inputBuffer, output)
            output[0].copyOf().normalized()
        }.onFailure { error ->
            Timber.w(error, "Face embedding failed")
        }.getOrNull()
    }

    private fun writeInput(bitmap: Bitmap) {
        inputBuffer.rewind()
        bitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        pixels.forEach { pixel ->
            inputBuffer.putFloat((((pixel shr 16) and 0xFF) - MEAN) / STD)
            inputBuffer.putFloat((((pixel shr 8) and 0xFF) - MEAN) / STD)
            inputBuffer.putFloat(((pixel and 0xFF) - MEAN) / STD)
        }
        inputBuffer.rewind()
    }

    fun close() {
        synchronized(lock) { interpreter.close() }
    }

    companion object {
        const val ASSET_NAME = "mobilefacenet.tflite"
        private const val CHANNELS = 3
        private const val INFERENCE_THREADS = 2

        /**
         * Pixel scaling the model was trained with. (x - 127.5) / 128 is what MobileFaceNet and the
         * ArcFace family use; a model trained on 0..1 inputs would need MEAN 0 and STD 255.
         */
        private const val MEAN = 127.5f
        private const val STD = 128f

        /** Returns null when the asset is absent or unreadable, which is a supported state. */
        fun load(context: Context): FaceEmbeddingModel? = runCatching {
            val interpreter = Interpreter(
                context.assets.readModel(ASSET_NAME),
                Interpreter.Options().setNumThreads(INFERENCE_THREADS),
            )
            val inputShape = interpreter.getInputTensor(0).shape()
            val outputShape = interpreter.getOutputTensor(0).shape()
            val inputSize = inputShape.getOrNull(1) ?: error("Model has no input height")
            val embeddingSize = outputShape.lastOrNull() ?: error("Model has no embedding size")
            require(inputSize > 0 && embeddingSize > 0) { "Unusable model shapes" }
            Timber.i("Face model loaded: input %d, embedding %d", inputSize, embeddingSize)
            FaceEmbeddingModel(interpreter, inputSize, embeddingSize)
        }.onFailure { error ->
            Timber.w("Face recognition model unavailable (%s); falling back to landmark geometry: %s", ASSET_NAME, error.message)
        }.getOrNull()

        private fun android.content.res.AssetManager.readModel(name: String) =
            openFd(name).use { descriptor -> descriptor.mapModel() }

        private fun AssetFileDescriptor.mapModel() =
            FileInputStream(fileDescriptor).use { stream ->
                stream.channel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
            }

        private fun FloatArray.normalized(): FloatArray {
            var sum = 0f
            forEach { value -> sum += value * value }
            val length = sqrt(sum)
            if (length <= 0f || !length.isFinite()) return this
            for (index in indices) this[index] = this[index] / length
            return this
        }
    }
}
