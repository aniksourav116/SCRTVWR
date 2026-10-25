package srv.scrtvwr.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import srv.scrtvwr.R
import srv.scrtvwr.media.MediaPaths
import java.io.File

private const val FILE_PROVIDER_AUTHORITY = "srv.scrtvwr.fileprovider"

/** Whether another app can be handed [path]: an entry inside an archive has no file of its own. */
fun canOpenWithOtherApp(path: String?): Boolean = path != null && !path.contains("::")

/** Lets the user pick another app to open [path], a device file or a server file, named [name]. */
fun Context.openWithOtherApp(path: String, name: String) {
    val mimeType = MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(MediaPaths.extension(path))
        ?: "*/*"

    val intent = if (MediaPaths.isRemote(path)) {
        // Other apps can stream or download a server file from its URL directly.
        Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(path), mimeType)
    } else {
        val uri = FileProvider.getUriForFile(this, FILE_PROVIDER_AUTHORITY, File(path))
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    try {
        startActivity(Intent.createChooser(intent, name))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, getString(R.string.no_app_found_to_open_file), Toast.LENGTH_SHORT).show()
    }
}
