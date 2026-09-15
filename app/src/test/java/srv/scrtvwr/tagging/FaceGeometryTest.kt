package srv.scrtvwr.tagging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class FaceGeometryTest {
    @Test
    fun `signature is translation scale and rotation invariant`() {
        val original = FaceGeometry.signature(FACE)
        val moved = FaceGeometry.signature(FACE.transformed(scale = 3.5f, rotationDegrees = 37f, dx = 640f, dy = -120f))

        assertNotNull(original)
        assertNotNull(moved)
        assertTrue(
            "Same face after a rigid transform should land on the same signature",
            FaceGeometry.distance(original!!, moved!!) < 0.01f,
        )
    }

    @Test
    fun `different face proportions fall short of the fallback join threshold`() {
        val face = FaceGeometry.signature(FACE)
        val longerFace = FaceGeometry.signature(
            FACE.copy(
                noseBase = FacePoint(5f, 9f),
                mouthLeft = FacePoint(8f, 17f),
                mouthRight = FacePoint(2f, 17f),
                mouthBottom = FacePoint(5f, 20f),
            ),
        )

        assertNotNull(face)
        assertNotNull(longerFace)
        val rules = MatchingRules.forGeometry("geometry-test")
        assertTrue(rules.similarity(face!!, longerFace!!) < rules.joinSimilarity)
    }

    @Test
    fun `eyes too close together produce no signature`() {
        assertNull(
            FaceGeometry.signature(FACE.copy(leftEye = FacePoint(5f, 5f), rightEye = FacePoint(5f, 5f))),
        )
    }

    @Test
    fun `signature has the documented size`() {
        assertEquals(FaceGeometry.SIGNATURE_SIZE, FaceGeometry.signature(FACE)!!.size)
    }

    @Test
    fun `pose gate rejects heads turned too far`() {
        assertTrue(FaceGeometry.isPoseUsable(eulerY = 5f, eulerZ = -8f))
        assertFalse(FaceGeometry.isPoseUsable(eulerY = 45f, eulerZ = 0f))
        assertFalse(FaceGeometry.isPoseUsable(eulerY = 0f, eulerZ = -40f))
    }

    @Test
    fun `distance of mismatched sizes does not match anything`() {
        assertEquals(Float.MAX_VALUE, FaceGeometry.distance(floatArrayOf(1f), floatArrayOf(1f, 2f)), 0f)
    }

    @Test
    fun `the same face stays above the fallback join threshold`() {
        val rules = MatchingRules.forGeometry("geometry-test")
        val face = FaceGeometry.signature(FACE)
        val moved = FaceGeometry.signature(FACE.transformed(scale = 2f, rotationDegrees = 12f, dx = 40f, dy = 15f))

        assertTrue(rules.similarity(face!!, moved!!) >= rules.joinSimilarity)
    }

    private fun FaceLandmarks.transformed(scale: Float, rotationDegrees: Float, dx: Float, dy: Float): FaceLandmarks {
        val radians = rotationDegrees * PI.toFloat() / 180f
        val cosine = cos(radians)
        val sine = sin(radians)
        fun FacePoint.map() = FacePoint(
            x = (x * cosine - y * sine) * scale + dx,
            y = (x * sine + y * cosine) * scale + dy,
        )
        return FaceLandmarks(
            leftEye = leftEye.map(),
            rightEye = rightEye.map(),
            noseBase = noseBase.map(),
            mouthLeft = mouthLeft.map(),
            mouthRight = mouthRight.map(),
            mouthBottom = mouthBottom.map(),
        )
    }

    private companion object {
        val FACE = FaceLandmarks(
            leftEye = FacePoint(10f, 0f),
            rightEye = FacePoint(0f, 0f),
            noseBase = FacePoint(5f, 6f),
            mouthLeft = FacePoint(8f, 11f),
            mouthRight = FacePoint(2f, 11f),
            mouthBottom = FacePoint(5f, 13f),
        )
    }
}
