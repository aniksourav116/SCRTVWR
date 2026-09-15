package srv.scrtvwr.tagging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the alignment template against drift.
 *
 * Every ArcFace and MobileFaceNet model is trained on crops warped onto the same five-point template.
 * If these numbers stop matching it, every face is cropped slightly wrong in the same direction, and
 * the damage is subtle rather than obvious: embeddings still come out, they are just worse, and
 * unrelated people start resembling each other because they share a distortion the model never saw.
 */
class FaceAlignerTest {
    @Test
    fun `eyes sit where the reference template puts them`() {
        assertEquals(REFERENCE_LEFT_EYE_X / CROP_SIZE, FaceAligner.LEFT_EYE_X, 0.0005f)
        assertEquals(REFERENCE_RIGHT_EYE_X / CROP_SIZE, FaceAligner.RIGHT_EYE_X, 0.0005f)
        assertEquals(REFERENCE_EYE_Y / CROP_SIZE, FaceAligner.EYE_Y, 0.0005f)
    }

    @Test
    fun `ML Kit's left eye maps to the left of the crop, or the face comes out upside down`() {
        // Two point pairs always yield some similarity transform, so swapping these does not fail -
        // it silently rotates every crop by 180 degrees. Verified against real crops on a device.
        assertTrue(FaceAligner.LEFT_EYE_X < FaceAligner.RIGHT_EYE_X)
    }

    @Test
    fun `the eyes sit above the middle of the crop, leaving room for the jaw`() {
        assertTrue(FaceAligner.EYE_Y < 0.5f)
        assertTrue(FaceAligner.EYE_Y > 0.4f)
    }

    @Test
    fun `the eye separation matches the template`() {
        val expected = (REFERENCE_RIGHT_EYE_X - REFERENCE_LEFT_EYE_X) / CROP_SIZE

        assertEquals(expected, FaceAligner.RIGHT_EYE_X - FaceAligner.LEFT_EYE_X, 0.0005f)
    }

    private companion object {
        /** The ArcFace/InsightFace five-point template, in pixels of a 112x112 crop. */
        const val CROP_SIZE = 112f
        /** Left and right here mean sides of the image, matching how ML Kit names landmarks. */
        const val REFERENCE_LEFT_EYE_X = 38.2946f
        const val REFERENCE_RIGHT_EYE_X = 73.5318f
        const val REFERENCE_EYE_Y = 51.6963f
    }
}
