package srv.scrtvwr

import android.app.Application
import android.util.Log

class ScrtvwrApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "SCRTVWR application started")
        }
    }

    private companion object {
        const val TAG = "SCRTVWR"
    }
}
