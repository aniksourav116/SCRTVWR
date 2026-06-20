package srv.scrtvwr

import android.app.Application
import srv.scrtvwr.media.MediaPreviewLoader
import srv.scrtvwr.util.logs.CustomLogger
import timber.log.Timber

class ScrtvwrApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(CustomLogger())
        }
        MediaPreviewLoader.initialize(cacheDir)
        Timber.d("SCRTVWR application started")
    }
}
