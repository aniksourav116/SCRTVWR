package srv.scrtvwr.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import srv.scrtvwr.R

/** Puts [url] on the clipboard so it can be pasted into a browser or another device. */
fun Context.copyLink(url: String) {
    val clipboard = getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.link), url))
    // Android 13 and later confirm clipboard writes themselves.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(this, getString(R.string.link_copied), Toast.LENGTH_SHORT).show()
    }
}
