package srv.scrtvwr.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import srv.scrtvwr.R

/** Puts [url] on the clipboard so it can be pasted into a browser or another device. */
fun Context.copyLink(url: String) {
    copyToClipboard(getString(R.string.link), url, getString(R.string.link_copied))
}

/** Puts a shell [command] on the clipboard, to be pasted on the computer that runs it. */
fun Context.copyCommand(command: String) {
    copyToClipboard(getString(R.string.command), command, getString(R.string.command_copied))
}

private fun Context.copyToClipboard(label: String, text: String, confirmation: String) {
    val clipboard = getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    // Android 13 and later confirm clipboard writes themselves.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(this, confirmation, Toast.LENGTH_SHORT).show()
    }
}
