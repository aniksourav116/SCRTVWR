package srv.scrtvwr.media.remote

/**
 * A server address typed as four IPv4 octets and a port, each in its own box. Parts are kept as
 * typed so a half-filled box survives until the address is submitted.
 */
data class Ipv4ServerAddress(
    val octets: List<String>,
    val port: String,
) {
    /** `a.b.c.d:port`, or null unless every octet is 0-255 and the port is 1-65535. */
    fun toAddress(): String? {
        if (octets.size != OCTET_COUNT) return null
        val numbers = octets.map { octet -> octet.trim().toIntOrNull()?.takeIf { it in 0..255 } ?: return null }
        val portNumber = port.trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        return "${numbers.joinToString(".")}:$portNumber"
    }

    companion object {
        const val OCTET_COUNT = 4

        /** Common home network prefix, leaving only the last octet and the port to check. */
        val Default = Ipv4ServerAddress(listOf("192", "168", "0", "2"), port = "")

        private val ADDRESS = Regex(
            """(?:https?://)?(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})(?::(\d{1,5}))?/?""",
            RegexOption.IGNORE_CASE,
        )

        /**
         * Parts of an `a.b.c.d` or `a.b.c.d:port` address, optionally with `http://`, or null for
         * anything the boxes cannot show, such as a host name or a path.
         */
        fun parse(address: String): Ipv4ServerAddress? {
            val match = ADDRESS.matchEntire(address.trim()) ?: return null
            val groups = match.groupValues
            return Ipv4ServerAddress(octets = groups.subList(1, 1 + OCTET_COUNT), port = groups[5])
        }
    }
}
