package srv.scrtvwr.firebase

import android.content.Context
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.messaging.FirebaseMessaging
import srv.scrtvwr.BuildConfig
import srv.scrtvwr.R
import timber.log.Timber

/**
 * Applies this app's policy to the Firebase services it uses.
 *
 * Firebase itself is started automatically from google-services.json, so all this has to
 * do is decide what gets collected, prepare the channel Cloud Messaging posts into, and
 * tag reports with enough build information to make them readable in the console.
 */
object ScrtvwrFirebase {

    /** Debug runs stay out of the console so real crash rates are not diluted. */
    private val collectionEnabled: Boolean = !BuildConfig.DEBUG

    fun initialize(context: Context) {
        val applicationContext = context.applicationContext
        createGeneralNotificationChannel(applicationContext)

        FirebaseCrashlytics.getInstance().apply {
            setCrashlyticsCollectionEnabled(collectionEnabled)
            setCustomKey(KEY_BUILD_TYPE, BuildConfig.BUILD_TYPE)
            setCustomKey(KEY_VERSION_NAME, BuildConfig.VERSION_NAME)
        }

        FirebaseAnalytics.getInstance(applicationContext)
            .setAnalyticsCollectionEnabled(collectionEnabled)

        FirebaseMessaging.getInstance().isAutoInitEnabled = true
        if (BuildConfig.DEBUG) {
            logRegistrationToken()
        }
    }

    /** Channel id shared by the manifest default and every notification we post ourselves. */
    fun generalChannelId(context: Context): String =
        context.getString(R.string.notification_channel_general_id)

    /**
     * Prints the current registration token so a debug build can be targeted from the
     * Firebase console without wiring up a backend first.
     */
    fun logRegistrationToken() {
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token -> Timber.d("FCM registration token: %s", token) }
            .addOnFailureListener { error -> Timber.w(error, "Unable to read the FCM token") }
    }

    private fun createGeneralNotificationChannel(context: Context) {
        val channel = NotificationChannelCompat
            .Builder(generalChannelId(context), NotificationManagerCompat.IMPORTANCE_DEFAULT)
            .setName(context.getString(R.string.notification_channel_general_name))
            .setDescription(context.getString(R.string.notification_channel_general_description))
            .build()
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }

    private const val KEY_BUILD_TYPE = "build_type"
    private const val KEY_VERSION_NAME = "version_name"
}
