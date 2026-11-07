package srv.scrtvwr.media

import srv.scrtvwr.BuildConfig
import java.io.IOException
import java.net.URI

object TransportSafety {
    fun isAllowed(url: String, authenticated: Boolean = false): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (uri.rawUserInfo != null || uri.host.isNullOrBlank() || uri.port !in -1..65535 || uri.port == 0) return false
        if (uri.scheme.equals("https", true)) return true
        if (!BuildConfig.PERSONAL_EDITION || authenticated || !uri.scheme.equals("http", true)) return false
        // Literal addresses only: a hostname's DNS answer must not widen the HTTP exception.
        val bytes = uri.host.split('.').map { it.toIntOrNull()?.takeIf { n -> n in 0..255 } ?: return false }
        if (bytes.size != 4) return false
        if (bytes.joinToString(".") != uri.host) return false
        return bytes[0] == 10 || bytes[0] == 127 ||
            (bytes[0] == 192 && bytes[1] == 168) || (bytes[0] == 172 && bytes[1] in 16..31) ||
            (bytes[0] == 169 && bytes[1] == 254)
    }

    fun requireAllowed(url: String, authenticated: Boolean = false) {
        if (!isAllowed(url, authenticated)) throw IOException(
            "Use HTTPS for servers and sign-in. Personal HTTP access supports private IPv4 addresses without credentials only.")
    }
}
