package srv.scrtvwr.media

import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.ExifInterface

/**
 * BitmapFactory ignores EXIF, so a photo taken in portrait arrives sideways. Anything that has to
 * agree with the rest of tagging about which way is up goes through here: faces are detected on the
 * upright picture and their boxes recorded against it, so a crop drawn from those boxes has to be
 * taken from the upright picture too.
 */
object ImageOrientation {
    fun rotationDegrees(path: String): Int {
        // An archive entry is not a file EXIF can be read from, so it is taken as it decodes.
        if (path.contains("::")) return 0
        return runCatching {
            val orientation = ExifInterface(path)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        }.getOrDefault(0)
    }

    /** Returns the bitmap itself when there is nothing to turn, so callers can tell a copy from the original. */
    fun turnedUpright(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrDefault(bitmap)
    }
}
