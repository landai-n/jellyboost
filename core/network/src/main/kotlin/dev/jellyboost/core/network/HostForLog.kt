package dev.jellyboost.core.network

/**
 * The host part of [address], for a log line: discovery, reachability, sign-in and the SyncPlay socket all
 * hold a user's server address, and their logs are the ones pasted into bug reports. Scheme and port are
 * dropped — a host alone says which candidate this was, without spelling out a ready-to-use address.
 *
 * A log helper and nothing more: never route, connect or compare on its output. It accepts anything a user
 * might type and answers `<none>` rather than throwing. Public so `:player`'s SyncPlay socket shares it.
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

private const val NO_HOST = "<none>"
