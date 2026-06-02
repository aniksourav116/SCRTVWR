package srv.scrt.vwr

import android.app.Application
import srv.scrt.vwr.util.logs.CustomDebugTree
import timber.log.Timber

class SCRTVWRApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(CustomDebugTree())
        }
        Timber.d("SCRTVWRApplication onCreate called")
    }
}
