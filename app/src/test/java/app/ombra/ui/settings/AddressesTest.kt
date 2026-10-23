package app.ombra.ui.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AddressesTest {
    @Test
    fun ipLiteralsAreAccepted() {
        listOf(
            "9.9.9.9",
            " 1.1.1.1 ",
            "2620:fe::fe",
            "2001:0DB8:0000:0000:0000:0000:0000:0001",
            "::ffff:9.9.9.9",
            "64:ff9b::1.1.1.1",
            "fe80::1",
            "1::",
        ).forEach { assertNull(dnsEntryProblem(it, emptyList()), it) }
    }

    @Test
    fun anythingButAnIpLiteralIsRefused() {
        listOf(
            "dns.quad9.net",
            "",
            "1.1.1",
            "1.1.1.1.1",
            "256.1.1.1",
            "01.1.1.1",
            "1.1.1.-1",
            "1.1.1.1/32",
            "1.1.1.1:53",
            "fe80::1%wlan0",
            "1::2::3",
            ":::",
            ":1:2:3:4:5:6:7",
            "1:2:3:4:5:6:7",
            "1:2:3:4:5:6:7:8:9",
            "12345::1",
            "-1::",
            "+1::",
            "g::1",
            "1.2.3.4::1",
            "[2620:fe::fe]",
        ).forEach { assertEquals(DnsEntryProblem.NotAnAddress, dnsEntryProblem(it, emptyList()), it) }
    }

    @Test
    fun loopbackAnyLocalAndMulticastAreRefused() {
        assertEquals(DnsEntryProblem.Loopback, dnsEntryProblem("127.0.0.53", emptyList()))
        assertEquals(DnsEntryProblem.Loopback, dnsEntryProblem("::1", emptyList()))
        assertEquals(DnsEntryProblem.Loopback, dnsEntryProblem("::ffff:127.0.0.1", emptyList()))
        assertEquals(DnsEntryProblem.Unspecified, dnsEntryProblem("0.0.0.0", emptyList()))
        assertEquals(DnsEntryProblem.Unspecified, dnsEntryProblem("::", emptyList()))
        assertEquals(DnsEntryProblem.Unspecified, dnsEntryProblem("::ffff:0.0.0.0", emptyList()))
        assertEquals(DnsEntryProblem.Multicast, dnsEntryProblem("224.0.0.251", emptyList()))
        assertEquals(DnsEntryProblem.Multicast, dnsEntryProblem("239.255.255.250", emptyList()))
        assertEquals(DnsEntryProblem.Multicast, dnsEntryProblem("ff02::fb", emptyList()))
        assertNull(dnsEntryProblem("223.255.255.255", emptyList()))
    }

    @Test
    fun theSameAddressInAnotherFormIsADuplicate() {
        val current = listOf("2001:db8::1", "9.9.9.9")
        assertEquals(DnsEntryProblem.Duplicate, dnsEntryProblem("2001:DB8:0:0::1", current))
        assertEquals(DnsEntryProblem.Duplicate, dnsEntryProblem("9.9.9.9", current))
        assertNull(dnsEntryProblem("2001:db8::2", current))
    }

    @Test
    fun parsedBytesFollowRfc4291() {
        assertEquals(listOf(9, 9, 9, 9), parseIpLiteral("9.9.9.9")?.map { it.toInt() and 0xFF })
        assertEquals(
            listOf(0x26, 0x20, 0, 0xfe, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xfe),
            parseIpLiteral("2620:fe::fe")?.map { it.toInt() and 0xFF },
        )
        assertEquals(
            listOf(0, 0x64, 0xff, 0x9b, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1),
            parseIpLiteral("64:ff9b::1.1.1.1")?.map { it.toInt() and 0xFF },
        )
    }

    @Test
    fun hostNamesFollowRfc1123() {
        listOf("example.com", "cdn.example.org", "a-b.example.co.uk", "EXAMPLE.com", "example.com.").forEach {
            assertTrue(isHostName(it), it)
        }
        listOf(
            "",
            "localhost",
            "1.1.1.1",
            "-a.example.com",
            "a-.example.com",
            "a_b.example.com",
            "example..com",
            "https://example.com",
            "example.com/path",
            "a".repeat(64) + ".com",
            ("a".repeat(60) + ".").repeat(5) + "com",
        ).forEach { assertFalse(isHostName(it), it) }
    }
}
