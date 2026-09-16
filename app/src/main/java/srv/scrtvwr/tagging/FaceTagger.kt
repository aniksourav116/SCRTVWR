package srv.scrtvwr.tagging

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * What one image yielded: how many faces were in it, and the faces that were good enough to identify.
 * A face can be counted but skipped for identity - too small, too turned, too blurred - which keeps
 * poor detections from inventing or polluting characters.
 */
data class FaceDetectionResult(
    val faceCount: Int,
    val faces: List<DetectedFace>,
) {
    companion object {
        val Empty = FaceDetectionResult(faceCount = 0, faces = emptyList())
    }
}

/**
 * One identified face: the vector it is recognised by, and where it sits in the frame. The box is
 * what lets a character's cover be cropped to them rather than to the middle of whatever photo they
 * were found in.
 */
class DetectedFace(val embedding: FloatArray, val bounds: FaceBounds)

/**
 * Turns one detected face into a comparable vector, or null when the face is unusable.
 * [descriptorId] identifies the vector space: stored vectors from a different one cannot be compared
 * against new ones, so changing embedder invalidates the tags rather than silently mixing them.
 */
interface FaceEmbedder {
    val descriptorId: String
    fun embed(bitmap: Bitmap, face: Face): FloatArray?
}

/**
 * Landmark proportions, used only when no recognition model is installed. It can group obviously
 * similar faces but confuses people who merely have similar proportions - see [FaceGeometry].
 */
class GeometricFaceEmbedder : FaceEmbedder {
    override val descriptorId: String = "geometry-v1-${FaceGeometry.SIGNATURE_SIZE}"

    override fun embed(bitmap: Bitmap, face: Face): FloatArray? {
        if (!FaceGeometry.isPoseUsable(face.headEulerAngleY, face.headEulerAngleZ)) return null
        val landmarks = face.landmarks() ?: return null
        return FaceGeometry.signature(landmarks)
    }
}

/** The real thing: an aligned crop through a face recognition model. See [FaceEmbeddingModel]. */
class ModelFaceEmbedder(private val model: FaceEmbeddingModel) : FaceEmbedder {
    companion object {
        /** 2: the alignment template was corrected to the reference ArcFace five-point positions. */
        const val PIPELINE_VERSION = 2
    }

    /**
     * [PIPELINE_VERSION] is part of the identity of the vector space, not decoration: how a face is
     * cropped and scaled decides what the vectors mean just as much as which model produced them.
     * Bumping it retires every stored vector, which is what has to happen when the preprocessing
     * changes - otherwise old and new vectors get compared as though they were the same thing.
     */
    override val descriptorId: String =
        "tflite-v$PIPELINE_VERSION-${model.inputSize}-${model.embeddingSize}-${model.fingerprint}"

    override fun embed(bitmap: Bitmap, face: Face): FloatArray? {
        val leftEye = face.point(FaceLandmark.LEFT_EYE)
        val rightEye = face.point(FaceLandmark.RIGHT_EYE)
        if (leftEye == null || rightEye == null) {
            Timber.d("Face skipped: no eye landmarks")
            return null
        }
        val aligned = FaceAligner.align(bitmap, leftEye, rightEye, model.inputSize)
        if (aligned == null) {
            Timber.d("Face skipped: could not align (eyes %s / %s)", leftEye, rightEye)
            return null
        }
        FaceCropDebug.dump(aligned)
        return try {
            val sharpness = FaceQuality.sharpness(aligned)
            if (sharpness < FaceQuality.MIN_LAPLACIAN_VARIANCE) {
                Timber.d("Face skipped: too blurred (sharpness %.1f)", sharpness)
                null
            } else {
                model.embed(aligned).also { if (it == null) Timber.d("Face skipped: the model returned nothing") }
            }
        } finally {
            aligned.recycle()
        }
    }
}

/** Thin wrapper around ML Kit face detection: bitmap in, face count and identity vectors out. */
class FaceTagger(val embedder: FaceEmbedder = GeometricFaceEmbedder()) {
    private val detector: FaceDetector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                // Identity depends on landmark positions being right, so this pays for itself even
                // though it is slower than FAST - a misplaced eye skews the whole aligned crop.
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .setMinFaceSize(MIN_FACE_SIZE)
                .build(),
        )
    }

    suspend fun detect(bitmap: Bitmap, rotationDegrees: Int = 0): FaceDetectionResult {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return FaceDetectionResult.Empty
        val faces = detector.process(InputImage.fromBitmap(bitmap, rotationDegrees)).await()
        if (faces.isEmpty()) return FaceDetectionResult.Empty

        val usable = faces.filter { face ->
            when {
                !face.isBigEnoughToIdentify() -> {
                    Timber.d(
                        "Face skipped: %dx%d px, smaller than the %d px identity floor",
                        face.boundingBox.width(),
                        face.boundingBox.height(),
                        MIN_IDENTITY_FACE_PIXELS,
                    )
                    false
                }
                !face.isFacingCameraEnough() -> {
                    Timber.d(
                        "Face skipped: turned too far (yaw %.0f, roll %.0f)",
                        face.headEulerAngleY,
                        face.headEulerAngleZ,
                    )
                    false
                }
                else -> true
            }
        }
        val identified = usable.mapNotNull { face ->
            embedder.embed(bitmap, face)?.let { embedding ->
                DetectedFace(embedding, face.boundingBox.asFractionsOf(bitmap.width, bitmap.height))
            }
        }
        Timber.d(
            "Faces: %d detected, %d past the gates, %d embedded (image %dx%d)",
            faces.size,
            usable.size,
            identified.size,
            bitmap.width,
            bitmap.height,
        )
        return FaceDetectionResult(faceCount = faces.size, faces = identified)
    }

    fun close() {
        detector.close()
    }

    /**
     * A face has to occupy real pixels before its identity means anything. Below this the landmarks
     * are guesses, the aligned crop is mostly interpolation, and the embedding lands anywhere - which
     * is how unrelated people end up merged.
     */
    private fun Face.isBigEnoughToIdentify(): Boolean = boundingBox.shortestSide() >= MIN_IDENTITY_FACE_PIXELS

    private fun Face.isFacingCameraEnough(): Boolean =
        FaceGeometry.isPoseUsable(headEulerAngleY, headEulerAngleZ)

    private fun Rect.shortestSide(): Int = minOf(width(), height())

    /**
     * ML Kit reports pixels on the bitmap it was handed, which is one particular decode of the image.
     * Tags keep fractions instead, so the box still lands on the face in the far smaller decode a
     * cover is drawn from.
     */
    private fun Rect.asFractionsOf(imageWidth: Int, imageHeight: Int): FaceBounds = FaceBounds(
        left = (left.toFloat() / imageWidth).coerceIn(0f, 1f),
        top = (top.toFloat() / imageHeight).coerceIn(0f, 1f),
        right = (right.toFloat() / imageWidth).coerceIn(0f, 1f),
        bottom = (bottom.toFloat() / imageHeight).coerceIn(0f, 1f),
    )

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
        addOnCompleteListener { task ->
            val error = task.exception
            when {
                task.isCanceled -> continuation.cancel()
                error != null -> continuation.resumeWithException(error)
                else -> continuation.resume(task.result)
            }
        }
    }

    private companion object {
        /** Low enough to still count faces in group shots and comic panels for the badge. */
        const val MIN_FACE_SIZE = 0.06f

        /**
         * Measured on the analysis bitmap. The model resamples every face to its own input size, so
         * this is about how much real detail exists rather than about matching that size: a little
         * under half of it still carries a usable identity, while the avatar-sized faces in chat
         * screenshots - routinely ten pixels across - carry none and would poison the clusters.
         */
        const val MIN_IDENTITY_FACE_PIXELS = 48
    }
}

private fun Face.landmarks(): FaceLandmarks? = FaceLandmarks(
    leftEye = point(FaceLandmark.LEFT_EYE) ?: return null,
    rightEye = point(FaceLandmark.RIGHT_EYE) ?: return null,
    noseBase = point(FaceLandmark.NOSE_BASE) ?: return null,
    mouthLeft = point(FaceLandmark.MOUTH_LEFT) ?: return null,
    mouthRight = point(FaceLandmark.MOUTH_RIGHT) ?: return null,
    mouthBottom = point(FaceLandmark.MOUTH_BOTTOM) ?: return null,
)

private fun Face.point(type: Int): FacePoint? =
    getLandmark(type)?.position?.let { FacePoint(it.x, it.y) }
