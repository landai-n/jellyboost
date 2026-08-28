package dev.jellyboost.core.common.model

/**
 * Which colour scheme the app draws in.
 *
 * [SYSTEM] is the default because the device setting is the answer the user already gave once, for
 * every app; the two explicit modes exist for the reader who wants this app to disagree with it.
 */
enum class ThemeMode {
    /** Follow the device's light/dark setting. Default. */
    SYSTEM,

    LIGHT,

    DARK,
    ;

    companion object {
        val DEFAULT: ThemeMode = SYSTEM

        /** A name this build does not know — a downgrade, a renamed constant — reads as a fresh install. */
        fun fromNameOrDefault(name: String?): ThemeMode = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
