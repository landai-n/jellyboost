package dev.jellyboost.core.network

/**
 * The host part of [address], for a log line.
 *
 * Discovery and reachability are the two paths that hold a user's server address, and they are also
 * the two whose logs get pasted into a bug report — the scrubbed git history (2026-08-01) exists
 * because that happened. What a maintainer needs from those lines is *which candidate this was*, and
 * a host alone answers that; the scheme says nothing and the port is the remaining half of a
 * ready-to-use address. So the port and scheme are dropped, consistently with the SEC-05/06
 * precedent of logging the shape of a value rather than the value.
 *
 * This is a log helper and nothing more — never route, connect or compare on its output. It accepts
 * anything a user might have typed (a bare hostname, `host:port`, a full URL, an IPv6 literal), so
 * it cannot be strict, and it answers `<none>` rather than throwing for input it cannot read.
 */
internal fun hostForLog(address: String?): String {
    val trimmed = address?.trim().orEmpty()
    if (trimmed.isEmpty()) return NO_HOST

    val authority =
        trimmed
            .substringAfter("://")
            .substringBefore('/')
            .substringBefore('?')
    val host =
        if (authority.startsWith("[")) {
            // An IPv6 literal is bracketed precisely because its own colons are not a port.
            authority.substringBefore(']').removePrefix("[")
        } else {
            authority.substringBefore(':')
        }
    return host.ifBlank { NO_HOST }
}

/** What an unusable or absent address reads as, so a log line never says "null". */
private const val NO_HOST = "<none>"
