package srv.scrtvwr.tagging

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** A landmark position in image pixels. Deliberately not android.graphics.PointF so the math stays unit-testable. */
data class FacePoint(val x: Float, val y: Float)

/**
 * The landmarks a signature needs. ML Kit names them from the subject's point of view, so
 * [leftEye] sits on the right-hand side of the image for a face looking at the camera.
 */
data class FaceLandmarks(
    val leftEye: FacePoint,
    val rightEye: FacePoint,
    val noseBase: FacePoint,
    val mouthLeft: FacePoint,
    val mouthRight: FacePoint,
    val mouthBottom: FacePoint,
)

/**
 * Turns face landmarks into a small pose-normalised signature that can be compared across images.
 *
 * The eye line defines the frame: its midpoint is the origin, the eye-to-eye vector is the x axis,
 * and the inter-ocular distance is one unit. Whatever is left - nose base and the three mouth points -
 * becomes the signature, so translation, in-plane rotation and scale drop out and only the proportions
 * of the face remain.
 *
 * This is geometry, not face recognition: it groups faces with similar proportions and will confuse
 * genuinely similar-looking people. [FaceEmbedder] is the seam where a real embedding model
 * (FaceNet/MobileFaceNet via TFLite) can replace it without touching the rest of the pipeline.
 */
object FaceGeometry {
    const val SIGNATURE_SIZE = 8

    /**
     * Largest distance between two signatures still considered the same character. Tuned to stay on the
     * cautious side: a miss leaves a second character the user can merge by renaming, a false merge
     * silently mislabels images.
     */
    const val MATCH_THRESHOLD = 0.18f

    /** Faces turned further than this are too foreshortened for the proportions to hold up. */
    const val MAX_HEAD_ANGLE_DEGREES = 22f

    private const val MIN_EYE_DISTANCE_PIXELS = 4f

    fun signature(landmarks: FaceLandmarks): FloatArray? {
        val axisX = landmarks.leftEye.x - landmarks.rightEye.x
        val axisY = landmarks.leftEye.y - landmarks.rightEye.y
        val eyeDistance = hypot(axisX, axisY)
        if (eyeDistance < MIN_EYE_DISTANCE_PIXELS) return null

        val originX = (landmarks.leftEye.x + landmarks.rightEye.x) / 2f
        val originY = (landmarks.leftEye.y + landmarks.rightEye.y) / 2f
        val angle = atan2(axisY, axisX)
        val cosine = cos(-angle)
        val sine = sin(-angle)

        val signature = FloatArray(SIGNATURE_SIZE)
        listOf(
            landmarks.noseBase,
            landmarks.mouthLeft,
            landmarks.mouthRight,
            landmarks.mouthBottom,
        ).forEachIndexed { index, point ->
            val dx = (point.x - originX) / eyeDistance
            val dy = (point.y - originY) / eyeDistance
            signature[index * 2] = dx * cosine - dy * sine
            signature[index * 2 + 1] = dx * sine + dy * cosine
        }
        return signature.takeIf { values -> values.all { it.isFinite() } }
    }

    /** Head poses beyond [MAX_HEAD_ANGLE_DEGREES] are counted as faces but never assigned a character. */
    fun isPoseUsable(eulerY: Float, eulerZ: Float): Boolean =
        abs(eulerY) <= MAX_HEAD_ANGLE_DEGREES && abs(eulerZ) <= MAX_HEAD_ANGLE_DEGREES

    fun distance(first: FloatArray, second: FloatArray): Float {
        if (first.size != second.size) return Float.MAX_VALUE
        var sum = 0f
        for (index in first.indices) {
            val difference = first[index] - second[index]
            sum += difference * difference
        }
        return sqrt(sum)
    }

    /** Running mean of every signature assigned to a character, so a profile keeps improving as it sees more faces. */
    fun mergeIntoCentroid(centroid: FloatArray, sampleCount: Int, signature: FloatArray): FloatArray {
        if (centroid.size != signature.size || sampleCount <= 0) return signature.copyOf()
        val weight = 1f / (sampleCount + 1)
        return FloatArray(centroid.size) { index ->
            centroid[index] + (signature[index] - centroid[index]) * weight
        }
    }
}
