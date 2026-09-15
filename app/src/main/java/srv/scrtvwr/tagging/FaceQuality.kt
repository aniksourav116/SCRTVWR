package srv.scrtvwr.tagging

import android.graphics.Bitmap

/**
 * Rejects face crops that carry no usable detail.
 *
 * A motion-blurred or heavily upscaled face still produces an embedding, but one driven by blur
 * rather than by the person, and those embeddings drift towards each other - which merges strangers.
 * Variance of the Laplacian is the standard cheap sharpness measure: it is large when the image has
 * edges and collapses towards zero when it does not.
 */
object FaceQuality {
    /**
     * Tuned low on purpose. Dropping a sharp face only delays recognising someone, while keeping a
     * blurred one can permanently corrupt a character, but rejecting too much leaves people untagged.
     */
    const val MIN_LAPLACIAN_VARIANCE = 22.0

    fun isTooBlurred(bitmap: Bitmap): Boolean = sharpness(bitmap) < MIN_LAPLACIAN_VARIANCE

    fun sharpness(bitmap: Bitmap): Double {
        if (bitmap.isRecycled || bitmap.width < 3 || bitmap.height < 3) return 0.0
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        return laplacianVariance(IntArray(pixels.size) { index -> pixels[index].luminance() }, width, height)
    }

    /** Pure so the measure itself can be tested without a device. */
    fun laplacianVariance(luma: IntArray, width: Int, height: Int): Double {
        if (width < 3 || height < 3 || luma.size < width * height) return 0.0
        var sum = 0.0
        var sumOfSquares = 0.0
        var count = 0
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val index = y * width + x
                val response = (
                    luma[index - width] + luma[index + width] + luma[index - 1] + luma[index + 1] -
                        4 * luma[index]
                    ).toDouble()
                sum += response
                sumOfSquares += response * response
                count++
            }
        }
        if (count == 0) return 0.0
        val mean = sum / count
        return (sumOfSquares / count) - (mean * mean)
    }

    private fun Int.luminance(): Int {
        val red = (this shr 16) and 0xFF
        val green = (this shr 8) and 0xFF
        val blue = this and 0xFF
        return (red * 77 + green * 151 + blue * 28) shr 8
    }
}
