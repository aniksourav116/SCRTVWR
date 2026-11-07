package srv.scrtvwr.util.logs

import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics
import timber.log.Timber

/**
 * Release logger that turns warnings into Crashlytics breadcrumbs and reports errors
 * carrying a throwable as non-fatal events, so a crash report arrives with the log
 * trail that led up to it.
 */
class CrashlyticsTree : Timber.Tree() {

    override fun isLoggable(tag: String?, priority: Int): Boolean = priority >= Log.WARN

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val crashlytics = FirebaseCrashlytics.getInstance()
        crashlytics.log(if (tag == null) message else "$tag: $message")
        if (t != null && priority >= Log.ERROR) {
            crashlytics.recordException(t)
        }
    }
}
