package srv.scrtvwr.media.remote

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import okhttp3.OkHttpClient

/**
 * ExoPlayer sources that reach saved servers the way [HttpDirectoryClient] does: signed in with the
 * server's credentials, and trusting only its pinned certificate when it has one. ExoPlayer's
 * built-in HTTP source cannot take a per-server certificate, so server media streams over OkHttp.
 */
@OptIn(UnstableApi::class)
object RemoteMediaSources {
    // Shared so every player reuses the same connection pool and threads.
    private val baseClient by lazy { OkHttpClient() }

    /** Player for [path], which may be a server URL or a local file. */
    fun player(context: Context, path: String): ExoPlayer =
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(factoryFor(context, path))
            .build()

    private fun factoryFor(context: Context, path: String): MediaSource.Factory {
        if (!RemotePaths.isRemote(path)) return DefaultMediaSourceFactory(context)
        val client = HttpServerAccess.pinFor(path)?.let(PinnedCertificates::pinning)?.let { pinning ->
            baseClient.newBuilder()
                .sslSocketFactory(pinning.socketFactory, pinning.trustManager)
                .hostnameVerifier(pinning.hostnameVerifier)
                .build()
        } ?: baseClient
        // A player only ever plays one URL, so its server's header can apply to every request.
        // OkHttp drops it if the server redirects to another host.
        val httpFactory = OkHttpDataSource.Factory(client)
        HttpServerAccess.authorizationFor(path)?.let { authorization ->
            httpFactory.setDefaultRequestProperties(mapOf("Authorization" to authorization))
        }
        return DefaultMediaSourceFactory(DefaultDataSource.Factory(context, httpFactory))
    }
}
