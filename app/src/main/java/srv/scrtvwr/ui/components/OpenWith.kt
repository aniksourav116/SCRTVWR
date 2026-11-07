package srv.scrtvwr.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import srv.scrtvwr.R
import srv.scrtvwr.media.MediaPaths
import java.io.File
import srv.scrtvwr.media.DocumentMedia
import srv.scrtvwr.media.DownloadSafety
import srv.scrtvwr.media.TransportSafety
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import java.util.UUID

/** Whether another app can be handed [path]: an entry inside an archive has no file of its own. */
fun canOpenWithOtherApp(path: String?): Boolean = path != null && !path.contains("::")

/** Lets the user pick another app to open [path], a device file or a server file, named [name]. */
fun Context.openWithOtherApp(path: String, name: String) {
    if (!canOpenWithOtherApp(path)) return
    val activity = this as? ComponentActivity ?: return
    activity.lifecycleScope.launch {
        val uri = try {
            withContext(Dispatchers.IO) {
                when {
                    MediaPaths.isRemote(path) -> {
                        TransportSafety.requireAllowed(path)
                        Uri.parse(path)
                    }
                    DocumentMedia.isDocument(path) -> DocumentMedia.uri(path)
                    else -> {
                        val source = File(path).canonicalFile
                        val privateRoot = File(applicationInfo.dataDir).canonicalFile
                        require(source.path != privateRoot.path && !source.path.startsWith(privateRoot.path + File.separator))
                        val directory = File(cacheDir, "shared_media").apply { mkdirs() }
                        directory.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000L }
                            ?.forEach { old ->
                                revokeUriPermission(FileProvider.getUriForFile(this@openWithOtherApp,
                                    "$packageName.fileprovider", old), Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                old.delete()
                            }
                        val target = File(directory, "${UUID.randomUUID()}.${source.extension.filter(Char::isLetterOrDigit)}")
                        try {
                            DownloadSafety.check(target, remainingBytes = source.length())
                            source.inputStream().use { input -> target.outputStream().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                var copied = 0L
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    copied += count
                                    DownloadSafety.check(target, copied, writeBytes = count.toLong())
                                    output.write(buffer, 0, count)
                                }
                            } }
                            FileProvider.getUriForFile(this@openWithOtherApp, "$packageName.fileprovider", target)
                        } catch (error: Exception) {
                            target.delete()
                            throw error
                        }
                    }
                }
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            Toast.makeText(this@openWithOtherApp, R.string.cannot_open_file, Toast.LENGTH_SHORT).show()
            return@launch
        }
        openSharedUri(uri, path, name)
    }
}

private fun Context.openSharedUri(uri: Uri, path: String, name: String) {
    val mimeType = MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(MediaPaths.extension(path))
        ?: "*/*"

    try {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeType).apply {
            if (uri.scheme == "content") {
                clipData = ClipData.newRawUri(name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }

        startActivity(Intent.createChooser(intent, name))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, getString(R.string.no_app_found_to_open_file), Toast.LENGTH_SHORT).show()
    } catch (_: IllegalArgumentException) {
        Toast.makeText(this, getString(R.string.cannot_open_file), Toast.LENGTH_SHORT).show()
    } catch (_: SecurityException) {
        Toast.makeText(this, getString(R.string.cannot_open_file), Toast.LENGTH_SHORT).show()
    }
}
