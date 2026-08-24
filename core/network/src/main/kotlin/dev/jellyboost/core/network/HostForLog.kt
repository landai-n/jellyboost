package dev.jellyboost.core.network

/**
 * The host part of [address], for a log line.
 *
 * Discovery, reachability, sign-in and the SyncPlay socket are the paths that hold a user's server
 * address, and they are also the ones whose logs get pasted into a bug report. What a maintainer
 * needs from those lines is *which candidate this was*, and a host alone answers that; the scheme
 * says nothing and the port is the remaining half of a ready-to-use address. So the port and scheme
 * are dropped, consistent with logging the shape of a value elsewhere in this codebase rather than
 * the value itself.
 *
 * This is a log helper and nothing more — never route, connect or compare on its output. It accepts
 * anything a user might have typed (a bare hostname, `host:port`, a full URL, an IPv6 literal), so
 * it cannot be strict, and it answers `<none>` rather than throwing for input it cannot read.
 *
 * Public rather than module-internal: `:player`'s SyncPlay socket logs the websocket URL it is
 * opening, and a second copy of this in that module is exactly the kind of drift a shared helper
 * prevents.
 */
fun hostForLog(address: String?): String {
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
