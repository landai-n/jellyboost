package dev.jellyboost.feature.auth

/**
 * Warns about `http://` to a host the packets *leave the local network* to reach — not about http
 * as such. `network_security_config.xml` has to permit cleartext globally (users type arbitrary
 * addresses, and most Jellyfin servers are plain http on a LAN), which leaves a port-forwarded
 * server over public Wi-Fi handing the `Authorization` header to anyone on the path.
 *
 * Local means: loopback, RFC1918, link-local, ULA, CGNAT `100.64/10`, a single-label name, and the
 * reserved suffixes below. False negatives are accepted, false positives are not — a warning the
 * user cannot act on teaches them to ignore warnings — so it stays advisory rather than a block.
 *
 * Deliberately not built on `:core:network`'s `hostForLog`, whose contract forbids comparing on its
 * output.
 */
internal fun isCleartextPublicAddress(address: String): Boolean {
    val trimmed = address.trim()
    if (!trimmed.startsWith(CLEARTEXT_SCHEME, ignoreCase = true)) return false
    val host = hostOf(trimmed) ?: return false
    return !isLocalHost(host)
}

internal fun hostOf(address: String): String? {
    val authority =
        address
            .trim()
            .substringAfter("://")
            .substringBefore('/')
            .substringBefore('?')
            // `user@host` would otherwise be read as the host and classified on the wrong string.
            .substringAfterLast('@')
    val host =
        if (authority.startsWith("[")) {
            authority.substringBefore(']').removePrefix("[")
        } else {
            authority.substringBefore(':')
        }
    return host.lowercase().ifBlank { null }
}

private fun isLocalHost(host: String): Boolean =
    host in LOOPBACK_NAMES ||
        isSingleLabelName(host) ||
        LOCAL_SUFFIXES.any { host.endsWith(it) } ||
        isPrivateIpv4(host) ||
        isLocalIpv6(host)

/** A name with no dot resolves only on the network the device is on; the colon check excludes IPv6. */
private fun isSingleLabelName(host: String): Boolean = !host.contains('.') && !host.contains(':')

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
