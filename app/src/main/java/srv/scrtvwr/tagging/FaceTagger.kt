package srv.scrtvwr.tagging

import android.graphics.Bitmap
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class FaceDetectionResult(
    val faceCount: Int,
    val signatures: List<FloatArray>,
) {
    companion object {
        val Empty = FaceDetectionResult(faceCount = 0, signatures = emptyList())
    }
}

/**
 * Turns one detected face into a comparable signature, or null when the face is unusable.
 * Swapping in a TFLite embedding model later means implementing this and nothing else.
 */
fun interface FaceEmbedder {
    fun embed(bitmap: Bitmap, face: Face): FloatArray?
}

/** Default embedder: landmark proportions only, no extra model to ship. See [FaceGeometry]. */
class GeometricFaceEmbedder : FaceEmbedder {
    override fun embed(bitmap: Bitmap, face: Face): FloatArray? {
        if (!FaceGeometry.isPoseUsable(face.headEulerAngleY, face.headEulerAngleZ)) return null
        val landmarks = FaceLandmarks(
            leftEye = face.point(FaceLandmark.LEFT_EYE) ?: return null,
            rightEye = face.point(FaceLandmark.RIGHT_EYE) ?: return null,
            noseBase = face.point(FaceLandmark.NOSE_BASE) ?: return null,
            mouthLeft = face.point(FaceLandmark.MOUTH_LEFT) ?: return null,
            mouthRight = face.point(FaceLandmark.MOUTH_RIGHT) ?: return null,
            mouthBottom = face.point(FaceLandmark.MOUTH_BOTTOM) ?: return null,
        )
        return FaceGeometry.signature(landmarks)
    }

    private fun Face.point(type: Int): FacePoint? =
        getLandmark(type)?.position?.let { FacePoint(it.x, it.y) }
}

/** Thin wrapper around ML Kit face detection: bitmap in, face count and character signatures out. */
class FaceTagger(private val embedder: FaceEmbedder = GeometricFaceEmbedder()) {
    private val detector: FaceDetector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                // FAST still reports landmarks and is what makes a whole-device pass finish in
                // reasonable time; ACCURATE mainly helps with small or heavily rotated faces.
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
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
        return FaceDetectionResult(
            faceCount = faces.size,
            signatures = faces.mapNotNull { face -> embedder.embed(bitmap, face) },
        )
    }

    fun close() {
        detector.close()
    }

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
        /** Fraction of the image width a face must cover. Low enough for group shots and comic panels. */
        const val MIN_FACE_SIZE = 0.08f
    }
}
