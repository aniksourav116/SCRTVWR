package srv.scrtvwr.tagging

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import kotlin.math.hypot

/**
 * Warps a detected face into the canonical square a recognition model expects.
 *
 * Embedding models are trained on aligned crops: eyes on a fixed horizontal line, at a fixed
 * separation, with the face at a fixed scale. Feeding them raw crops costs a lot of accuracy, because
 * head tilt and framing then show up in the embedding as if they were identity.
 *
 * The transform is a similarity transform - rotate, scale, translate, no shear - derived from the two
 * eye positions, which is the same frame [FaceGeometry] uses for its fallback signature.
 */
object FaceAligner {
    /** Where the eyes land in a unit square. Matches the usual ArcFace/MobileFaceNet template. */
    private const val EYE_Y = 0.36f
    private const val LEFT_EYE_X = 0.65f
    private const val RIGHT_EYE_X = 0.35f
    private const val MIN_EYE_DISTANCE_PIXELS = 8f

    /**
     * Returns a [size] x [size] aligned crop, or null when the eyes are too close together for the
     * transform to mean anything - which is also the signal that the face is too small to identify.
     */
    fun align(source: Bitmap, leftEye: FacePoint, rightEye: FacePoint, size: Int): Bitmap? {
        if (source.isRecycled || size <= 0) return null
        val eyeDistance = hypot(leftEye.x - rightEye.x, leftEye.y - rightEye.y)
        if (eyeDistance < MIN_EYE_DISTANCE_PIXELS) return null

        val matrix = Matrix()
        val sourcePoints = floatArrayOf(leftEye.x, leftEye.y, rightEye.x, rightEye.y)
        val targetPoints = floatArrayOf(
            LEFT_EYE_X * size, EYE_Y * size,
            RIGHT_EYE_X * size, EYE_Y * size,
        )
        // Two point pairs define exactly one similarity transform, which is what we want here:
        // a four-point fit would let perspective and shear back in.
        if (!matrix.setPolyToPoly(sourcePoints, 0, targetPoints, 0, 2)) return null

        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(output).drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return output
    }
}
