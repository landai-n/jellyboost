package dev.jellyboost.core.common

import java.util.Locale

/**
 * Human-readable bytes, in powers of 1000 with SI prefixes — what a media server reports and what a
 * user comparing "2.1 GB" against their free space expects.
 *
 * Deliberately not `android.text.format.Formatter`, which needs a `Context` and would make every
 * caller untestable and unpreviewable for the sake of a string.
 *
 * Lived as three near-identical copies — `:feature:settings`, `:feature:downloads`,
 * `:feature:detail` — each keeping its own on the reasoning that features never depend on each
 * other (docs/PLAN.md, "Project skeleton") and that eight lines did not earn a place in `:core:ui`'s
 * design system. That reasoning was about `:core:ui` specifically; it never applied to
 * `:core:common`, which every one of those modules already depends on for exactly this kind of
 * shared, Android-free logic. Only one of the three copies had a test, and a fix to one would not
 * have reached the other two (docs/notes/audit-2026-07.md, ARCH-11).
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
