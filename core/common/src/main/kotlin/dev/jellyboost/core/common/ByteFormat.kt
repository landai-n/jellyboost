package dev.jellyboost.core.common

import java.util.Locale

/**
 * Human-readable bytes, in powers of 1000 with SI prefixes — what a media server reports and what a
 * user comparing "2.1 GB" against their free space expects.
 *
 * Deliberately not `android.text.format.Formatter`, which needs a `Context` and would make every
 * caller untestable and unpreviewable for the sake of a string.
 *
 * Lives in `:core:common` rather than `:core:ui`'s design system: every feature module that formats
 * a byte count already depends on `:core:common` for exactly this kind of shared, Android-free
 * logic, and a single tested copy here is what keeps every caller's formatting in sync.
 */
fun formatBytes(bytes: Long): String {
    if (bytes < BYTE_UNIT) return "$bytes B"
    var value = bytes.toDouble()
    var index = -1
    while (value >= BYTE_UNIT && index < BYTE_UNITS.lastIndex) {
        value /= BYTE_UNIT
        index++
    }
    return String.format(Locale.getDefault(), "%.1f %s", value, BYTE_UNITS[index])
}

private const val BYTE_UNIT = 1000.0
private val BYTE_UNITS = listOf("kB", "MB", "GB", "TB")
