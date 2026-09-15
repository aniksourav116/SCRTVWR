package srv.scrtvwr.tagging

import android.content.Context
import android.graphics.Bitmap
import timber.log.Timber
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Temporary: writes the aligned crops the model is fed to disk, so what the pipeline sees can be
 * looked at rather than inferred. Remove once alignment is settled.
 */
object FaceCropDebug {
    private const val MAX_DUMPS = 16
    private val counter = AtomicInteger()
    @Volatile private var directory: File? = null

    fun initialize(context: Context) {
        directory = File(context.filesDir, "debug_faces").apply { mkdirs() }
    }

    fun dump(aligned: Bitmap) {
        val target = directory ?: return
        val index = counter.getAndIncrement()
        if (index >= MAX_DUMPS) return
        runCatching {
            File(target, "crop_%02d.png".format(index)).outputStream().use { out ->
                aligned.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        }.onFailure { error -> Timber.w(error, "Could not dump face crop") }
    }
}
