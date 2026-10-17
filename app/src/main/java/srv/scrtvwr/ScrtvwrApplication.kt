package srv.scrtvwr

import android.app.Application
import srv.scrtvwr.firebase.ScrtvwrFirebase
import srv.scrtvwr.media.MediaPreviewLoader
import srv.scrtvwr.media.remote.HttpServerAuth
import srv.scrtvwr.media.remote.RemoteArchiveCache
import srv.scrtvwr.preferences.ScrtvwrPreferences
import srv.scrtvwr.util.logs.CrashlyticsTree
import srv.scrtvwr.util.logs.CustomLogger
import srv.scrtvwr.util.time.TimeUtil
import timber.log.Timber

class ScrtvwrApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        TimeUtil.initialize(this)
        if (BuildConfig.DEBUG) {
            Timber.plant(CustomLogger())
        } else {
            Timber.plant(CrashlyticsTree())
        }
        ScrtvwrFirebase.initialize(this)
        MediaPreviewLoader.initialize(cacheDir)
        RemoteArchiveCache.initialize(cacheDir)
        // Before any screen or player requests a password-protected server.
        HttpServerAuth.update(ScrtvwrPreferences(this).getHttpServers())
        Timber.d("SCRTVWR application started")
    }
}
