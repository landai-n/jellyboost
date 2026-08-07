package dev.jellyboost.core.common

/**
 * Punctuation used to join short facts onto one line, shared so every card, header and row spells
 * the same join the same way (DUP-12) — jellyfin-web's own convention for lines like
 * `"2016 · TV-MA · 4 seasons"` or `"S1 · E4"`.
 */
object Separators {
    /** Interpunct with a surrounding space on each side: `" · "`. */
    const val DOT = " · "
}
