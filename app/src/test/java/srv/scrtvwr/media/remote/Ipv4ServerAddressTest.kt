package srv.scrtvwr.media.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Ipv4ServerAddressTest {
    @Test
    fun toAddress_joinsValidParts() {
        assertEquals("192.168.0.2:8000", Ipv4ServerAddress.Default.copy(port = "8000").toAddress())
        assertEquals("10.0.0.1:80", Ipv4ServerAddress(listOf("010", " 0", "0 ", "1"), "080").toAddress())
    }

    @Test
    fun toAddress_rejectsMissingOrOutOfRangeParts() {
        assertNull(Ipv4ServerAddress.Default.toAddress())
        assertNull(Ipv4ServerAddress(listOf("192", "168", "0", "256"), "8000").toAddress())
        assertNull(Ipv4ServerAddress(listOf("192", "168", "", "2"), "8000").toAddress())
        assertNull(Ipv4ServerAddress(listOf("192", "168", "0", "2"), "0").toAddress())
        assertNull(Ipv4ServerAddress(listOf("192", "168", "0", "2"), "65536").toAddress())
        assertNull(Ipv4ServerAddress(listOf("192", "168", "2"), "8000").toAddress())
    }

    @Test
    fun parse_splitsIpv4Addresses() {
        assertEquals(
            Ipv4ServerAddress(listOf("192", "168", "1", "20"), "8000"),
            Ipv4ServerAddress.parse(" 192.168.1.20:8000 "),
        )
        assertEquals(
            Ipv4ServerAddress(listOf("192", "168", "1", "20"), "8000"),
            Ipv4ServerAddress.parse("HTTP://192.168.1.20:8000/"),
        )
        assertEquals(Ipv4ServerAddress(listOf("10", "0", "0", "5"), ""), Ipv4ServerAddress.parse("10.0.0.5"))
    }

    @Test
    fun parse_rejectsAddressesTheBoxesCannotShow() {
        assertNull(Ipv4ServerAddress.parse(""))
        assertNull(Ipv4ServerAddress.parse("nas.local:8000"))
        assertNull(Ipv4ServerAddress.parse("192.168.1:8000"))
        assertNull(Ipv4ServerAddress.parse("192.168.1.20:8000/media/"))
        assertNull(Ipv4ServerAddress.parse("ftp://192.168.1.20:8000"))
    }
}
