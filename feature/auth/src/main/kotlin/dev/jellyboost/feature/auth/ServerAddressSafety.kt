package dev.jellyboost.feature.auth

/**
 * Whether [address] is a server the app would talk to **in the clear, across a network it has no
 * reason to trust**.
 *
 * `network_security_config.xml` permits cleartext globally and has to: the user types an arbitrary
 * address and a great many Jellyfin servers are plain `http://` boxes on a home LAN. That is a
 * reasonable default and a terrible silence — the one realistic remote token-theft path this app has
 * is a port-forwarded server reached over `http://` from public Wi-Fi, where the `Authorization`
 * header on every single request is readable by anyone on the path.
 *
 * So the rule is not "is this http" but "is this http *and* somewhere the packets leave the local
 * network":
 *
 * | host | verdict |
 * |---|---|
 * | `https://…` anything | safe — encrypted |
 * | `localhost`, `127.x`, `::1` | safe — never leaves the device |
 * | RFC1918 (`10/8`, `172.16/12`, `192.168/16`) | safe — home/office LAN |
 * | link-local (`169.254/16`, `fe80::/10`), ULA (`fc00::/7`) | safe — same segment |
 * | CGNAT `100.64/10` | safe — where Tailscale and friends live |
 * | a single-label name (`nas`, `jellyfin`) | safe — a LAN name; there is no public TLD to reach |
 * | `.local`, `.lan`, `.home`, `.internal`, `.home.arpa` | safe — reserved for local use |
 * | anything else over `http://` | **warn** |
 *
 * False negatives are accepted and false positives are not: a warning the user cannot act on
 * teaches them to ignore warnings. A public host behind a VPN, or a LAN with a real domain name,
 * will be warned about — that is the trade, and the warning is advisory rather than a block.
 *
 * Pure, and deliberately not built on `:core:network`'s `hostForLog`: that helper's own contract is
 * "never route, connect or *compare* on its output", and comparing is the whole of this function.
 */
internal fun isCleartextPublicAddress(address: String): Boolean {
    val trimmed = address.trim()
    if (!trimmed.startsWith(CLEARTEXT_SCHEME, ignoreCase = true)) return false
    val host = hostOf(trimmed) ?: return false
    return !isLocalHost(host)
}

/**
 * The host part of [address], lowercased, or `null` when there is nothing usable in it.
 *
 * Accepts what a user types: `http://host`, `http://host:8096/path`, `http://[::1]:8096`.
 */
internal fun hostOf(address: String): String? {
    val authority =
        address
            .trim()
            .substringAfter("://")
            .substringBefore('/')
            .substringBefore('?')
            // Nobody puts userinfo in a Jellyfin address, but `user@host` would otherwise be read
            // as the host and classified on the wrong string.
            .substringAfterLast('@')
    val host =
        if (authority.startsWith("[")) {
            authority.substringBefore(']').removePrefix("[")
        } else {
            authority.substringBefore(':')
        }
    return host.lowercase().ifBlank { null }
}

/** `true` when [host] is somewhere the packets never leave the local network. See the table above. */
private fun isLocalHost(host: String): Boolean =
    host in LOOPBACK_NAMES ||
        isSingleLabelName(host) ||
        LOCAL_SUFFIXES.any { host.endsWith(it) } ||
        isPrivateIpv4(host) ||
        isLocalIpv6(host)

/**
 * A name with no dot in it — `nas`, `jellyfin`.
 *
 * It cannot be a public DNS name; it is a NetBIOS/mDNS/hosts-file name that only resolves on the
 * network the device is already on. The colon check keeps a bare IPv6 literal out.
 */
private fun isSingleLabelName(host: String): Boolean = !host.contains('.') && !host.contains(':')

/** RFC1918, loopback, link-local and the CGNAT range, over a dotted-quad host. */
@Suppress("ReturnCount") // One early return per range reads better than a seven-clause boolean.
private fun isPrivateIpv4(host: String): Boolean {
    val octets = host.split('.')
    if (octets.size != IPV4_OCTETS) return false
    val bytes = octets.map { it.toIntOrNull() ?: return false }
    if (bytes.any { it !in 0..MAX_OCTET }) return false

    val (first, second) = bytes
    return when (first) {
        LOOPBACK_PREFIX, PRIVATE_10 -> true
        PRIVATE_172 -> second in PRIVATE_172_SECOND
        PRIVATE_192 -> second == PRIVATE_192_SECOND
        LINK_LOCAL_169 -> second == LINK_LOCAL_169_SECOND
        CGNAT_100 -> second in CGNAT_100_SECOND
        else -> false
    }
}

/** Loopback, link-local (`fe80::/10`) and unique-local (`fc00::/7`) IPv6 literals. */
private fun isLocalIpv6(host: String): Boolean {
    if (!host.contains(':')) return false
    if (host == "::1" || host == "::") return true
    val leading = host.substringBefore(':').padStart(IPV6_GROUP_DIGITS, '0')
    // fc00::/7 is fc.. and fd..; fe80::/10 is fe8. through feb.
    return leading.startsWith("fc") ||
        leading.startsWith("fd") ||
        leading.take(IPV6_LINK_LOCAL_PREFIX_DIGITS) in IPV6_LINK_LOCAL_PREFIXES
}

private const val CLEARTEXT_SCHEME = "http://"

private val LOOPBACK_NAMES = setOf("localhost", "ip6-localhost", "ip6-loopback")

private val LOCAL_SUFFIXES = listOf(".local", ".lan", ".home", ".internal", ".home.arpa", ".localdomain")

private val IPV6_LINK_LOCAL_PREFIXES = setOf("fe8", "fe9", "fea", "feb")

/** Hex digits in one IPv6 group, which `::` may have elided down to none. */
private const val IPV6_GROUP_DIGITS = 4

/** `fe80::/10` is a ten-bit prefix, i.e. the first three hex digits of the first group. */
private const val IPV6_LINK_LOCAL_PREFIX_DIGITS = 3

private const val IPV4_OCTETS = 4
private const val MAX_OCTET = 255
private const val LOOPBACK_PREFIX = 127
private const val PRIVATE_10 = 10
private const val PRIVATE_172 = 172
private val PRIVATE_172_SECOND = 16..31
private const val PRIVATE_192 = 192
private const val PRIVATE_192_SECOND = 168
private const val LINK_LOCAL_169 = 169
private const val LINK_LOCAL_169_SECOND = 254
private const val CGNAT_100 = 100
private val CGNAT_100_SECOND = 64..127
