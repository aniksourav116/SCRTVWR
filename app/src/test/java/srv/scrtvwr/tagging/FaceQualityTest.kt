package srv.scrtvwr.tagging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceQualityTest {
    @Test
    fun `a flat crop has no detail at all`() {
        val flat = IntArray(WIDTH * HEIGHT) { 128 }

        assertEquals(0.0, FaceQuality.laplacianVariance(flat, WIDTH, HEIGHT), 0.0001)
    }

    @Test
    fun `a sharp crop scores far above the rejection threshold`() {
        val checkerboard = IntArray(WIDTH * HEIGHT) { index ->
            if ((index % WIDTH + index / WIDTH) % 2 == 0) 0 else 255
        }

        assertTrue(FaceQuality.laplacianVariance(checkerboard, WIDTH, HEIGHT) > FaceQuality.MIN_LAPLACIAN_VARIANCE)
    }

    @Test
    fun `a soft gradient scores below a hard edge`() {
        val gradient = IntArray(WIDTH * HEIGHT) { index -> (index % WIDTH) * 255 / WIDTH }
        val edge = IntArray(WIDTH * HEIGHT) { index -> if (index % WIDTH < WIDTH / 2) 0 else 255 }

        assertTrue(
            FaceQuality.laplacianVariance(gradient, WIDTH, HEIGHT) <
                FaceQuality.laplacianVariance(edge, WIDTH, HEIGHT),
        )
    }

    @Test
    fun `an image too small to measure scores nothing rather than failing`() {
        assertEquals(0.0, FaceQuality.laplacianVariance(IntArray(4) { 200 }, 2, 2), 0.0001)
        assertEquals(0.0, FaceQuality.laplacianVariance(IntArray(0), 16, 16), 0.0001)
    }

    private companion object {
        const val WIDTH = 16
        const val HEIGHT = 16
    }
}
