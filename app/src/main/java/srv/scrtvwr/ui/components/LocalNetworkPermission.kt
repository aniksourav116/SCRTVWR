package srv.scrtvwr.ui.components

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Android 17 blocks connections to devices on the local network, such as a computer running
 * `python -m http.server`, for apps targeting SDK 37 until ACCESS_LOCAL_NETWORK is granted.
 * Earlier releases allow that traffic with the INTERNET permission alone.
 */
fun Context.hasLocalNetworkAccess(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_LOCAL_NETWORK) ==
        PackageManager.PERMISSION_GRANTED
