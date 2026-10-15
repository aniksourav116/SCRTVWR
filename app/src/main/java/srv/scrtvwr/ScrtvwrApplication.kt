package srv.scrtvwr

import android.app.Application
import srv.scrtvwr.firebase.ScrtvwrFirebase
import srv.scrtvwr.media.MediaPreviewLoader
import srv.scrtvwr.media.remote.HttpServerAccess
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
        ScrtvwrPreferences(this).let { preferences ->
            HttpServerAccess.update(preferences.getHttpServers(), preferences.getHttpCredentials())
        }
        Timber.d("SCRTVWR application started")
    }
}
