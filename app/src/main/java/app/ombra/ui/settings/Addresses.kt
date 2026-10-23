package app.ombra.ui.settings

/** Why a custom DNS server is refused. */
enum class DnsEntryProblem {
    /** Not an IPv4 or IPv6 literal. Names are never resolved, so a host name cannot be a DNS server. */
    NotAnAddress,

    /** 127.0.0.0/8 or ::1: this phone, not a DNS server. */
    Loopback,

    /** 0.0.0.0 or ::. */
    Unspecified,

    /** 224.0.0.0/4 or ff00::/8: a group address, never a DNS server. */
    Multicast,
    Duplicate,
}

/**
 * Null when [text] can be added to the custom DNS servers [current]. The :vpn process checks the
 * entries again (loopback and unspecified) before it builds the TUN; this check is stricter.
 */
fun dnsEntryProblem(text: String, current: List<String>): DnsEntryProblem? {
    val bytes = parseIpLiteral(text.trim()) ?: return DnsEntryProblem.NotAnAddress
    val v4 = bytes.ipv4OrNull()
    return when {
        v4?.get(0) == 127.toByte() || bytes.contentEquals(IPV6_LOOPBACK) -> DnsEntryProblem.Loopback
        v4?.all { it == 0.toByte() } == true || bytes.all { it == 0.toByte() } -> DnsEntryProblem.Unspecified
        if (v4 != null) (v4[0].toInt() and 0xF0) == 0xE0 else bytes[0] == FF -> DnsEntryProblem.Multicast
        current.any { parseIpLiteral(it)?.contentEquals(bytes) == true } -> DnsEntryProblem.Duplicate
        else -> null
    }
}

/** The 4 or 16 bytes of an IPv4 or IPv6 literal (RFC 4291 text forms, no zone), or null. */
fun parseIpLiteral(text: String): ByteArray? = if (':' in text) parseIpv6(text) else parseIpv4(text)

/** Dotted quad; leading zeros are refused because some resolvers read them as octal. */
private fun parseIpv4(text: String): ByteArray? {
    val parts = text.split('.')
    if (parts.size != 4) return null
    val bytes = ByteArray(4)
    parts.forEachIndexed { i, part ->
        if (part.isEmpty() || part.length > 3 || !part.all { it in '0'..'9' }) return null
        if (part.length > 1 && part[0] == '0') return null
        val value = part.toInt()
        if (value > 255) return null
        bytes[i] = value.toByte()
    }
    return bytes
}

private fun parseIpv6(text: String): ByteArray? {
    val halves = text.split("::")
    if (halves.size > 2) return null
    // A dotted IPv4 part may only end the address.
    val head = groups(halves[0], ipv4Last = halves.size == 1) ?: return null
    val tail = if (halves.size == 2) groups(halves[1], ipv4Last = true) ?: return null else emptyList()
    val explicit = head.size + tail.size
    if (halves.size == 1 && explicit != 8) return null
    if (halves.size == 2 && explicit > 7) return null
    val all = head + List(8 - explicit) { 0 } + tail
    return ByteArray(16) { i -> (all[i / 2] shr (if (i % 2 == 0) 8 else 0)).toByte() }
}

/** 16-bit groups of one side of "::"; with [ipv4Last], a final IPv4 part counts as two groups. */
private fun groups(text: String, ipv4Last: Boolean): List<Int>? {
    if (text.isEmpty()) return emptyList()
    val parts = text.split(':')
    val result = mutableListOf<Int>()
    parts.forEachIndexed { i, part ->
        if (ipv4Last && i == parts.lastIndex && '.' in part) {
            val v4 = parseIpv4(part) ?: return null
            result += (v4[0].toInt() and 0xFF shl 8) or (v4[1].toInt() and 0xFF)
            result += (v4[2].toInt() and 0xFF shl 8) or (v4[3].toInt() and 0xFF)
        } else {
            // Hex digits only: toInt would also take a sign.
            if (part.isEmpty() || part.length > 4 || !part.all { it.isHexDigit() }) return null
            result += part.toInt(16)
        }
    }
    return result
}

/** The IPv4 bytes of an IPv4 address or an IPv4-mapped IPv6 address (::ffff:a.b.c.d). */
private fun ByteArray.ipv4OrNull(): ByteArray? = when {
    size == 4 -> this
    copyOfRange(0, 10).all { it == 0.toByte() } && this[10] == FF && this[11] == FF -> copyOfRange(12, 16)
    else -> null
}

private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

private const val FF = 0xFF.toByte()
private val IPV6_LOOPBACK = ByteArray(16).also { it[15] = 1 }

/**
 * A public host name for the relay SNI or the backup API host: dot-separated labels of letters,
 * digits and hyphens (RFC 1123), 253 characters at most, and not an IP address.
 */
fun isHostName(text: String): Boolean {
    if (text.isEmpty() || text.length > 253) return false
    val labels = text.removeSuffix(".").split('.')
    if (labels.size < 2 || labels.last().all { it in '0'..'9' }) return false
    return labels.all { label ->
        label.length in 1..63 &&
            label.first() != '-' && label.last() != '-' &&
            label.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }
    }
}
