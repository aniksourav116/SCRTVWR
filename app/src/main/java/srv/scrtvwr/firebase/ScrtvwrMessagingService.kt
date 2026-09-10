package srv.scrtvwr.firebase

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import srv.scrtvwr.DashboardActivity
import srv.scrtvwr.R
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger

/**
 * Receives Cloud Messaging pushes.
 *
 * Firebase draws notification-only messages itself while the app is backgrounded; this
 * service covers the two cases it does not, namely foreground delivery and data-only
 * messages.
 */
class ScrtvwrMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // Nothing to upload yet: the app has no backend that targets individual devices.
        // Send the token to that backend here once one exists.
        Timber.d("FCM registration token refreshed: %s", token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val notification = message.notification
        val title = notification?.title
            ?: message.data[DATA_TITLE]
            ?: getString(R.string.app_name)
        val body = notification?.body ?: message.data[DATA_BODY]

        if (body.isNullOrBlank()) {
            Timber.d("Ignored FCM message %s: nothing to display", message.messageId)
            return
        }
        showNotification(title, body)
    }

    @SuppressLint("MissingPermission") // Guarded by canPostNotifications() below.
    private fun showNotification(title: String, body: String) {
        val notificationManager = NotificationManagerCompat.from(this)
        if (!canPostNotifications(notificationManager)) {
            Timber.d("Dropped FCM notification: the user has not allowed notifications")
            return
        }

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, DashboardActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat
            .Builder(this, ScrtvwrFirebase.generalChannelId(this))
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setColor(ContextCompat.getColor(this, R.color.notification_accent))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()

        notificationManager.notify(NEXT_NOTIFICATION_ID.getAndIncrement(), notification)
    }

    private fun canPostNotifications(manager: NotificationManagerCompat): Boolean {
        if (!manager.areNotificationsEnabled()) {
            return false
        }
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
    }

    private companion object {
        const val DATA_TITLE = "title"
        const val DATA_BODY = "body"
        val NEXT_NOTIFICATION_ID = AtomicInteger(1)
    }
}
