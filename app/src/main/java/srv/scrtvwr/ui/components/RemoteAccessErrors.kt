package srv.scrtvwr.ui.components

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import srv.scrtvwr.R
import srv.scrtvwr.media.remote.HttpAuthException
import java.net.HttpURLConnection
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * What to tell the user when a request to a server failed for a reason they fix on the Storage
 * screen, a rejected sign-in or an untrusted certificate, or null for any other failure. Looks
 * through causes, since ExoPlayer wraps the network error in its own exceptions.
 */
@OptIn(UnstableApi::class)
fun Throwable.remoteAccessMessage(context: Context): String? =
    generateSequence(this) { it.cause }
        .firstNotNullOfOrNull { cause ->
            when {
                cause is HttpAuthException -> R.string.http_sign_in_failed
                cause is HttpDataSource.InvalidResponseCodeException &&
                    cause.responseCode == HttpURLConnection.HTTP_UNAUTHORIZED -> R.string.http_sign_in_failed
                cause is SSLHandshakeException || cause is SSLPeerUnverifiedException -> R.string.http_certificate_untrusted
                else -> null
            }
        }
        ?.let(context::getString)
