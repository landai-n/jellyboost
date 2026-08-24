package dev.jellyboost.core.common.model

/**
 * The list of values is jellyfin-web's `HomeSectionType`, byte for byte: the user's chosen order lives
 * server-side in DisplayPreferences as `homesection0` … `homesection9`, and these are the strings that map
 * may hold.
 *
 * Values this app draws nothing for ([RESUME_BOOK], [LIVE_TV], [ACTIVE_RECORDINGS]) are still decoded and
 * carried faithfully — losing them would silently reorder everything after them — and skipped at render time.
 */
enum class HomeSectionType(
    val serverValue: String,
) {
    /** An explicitly empty slot: never rendered, and dropped from a resolved layout. */
    NONE("none"),

    SMALL_LIBRARY_TILES("smalllibrarytiles"),

    /** *My Media* as large buttons. Rendered as the same libraries row. */
    LIBRARY_BUTTONS("librarybuttons"),

    ACTIVE_RECORDINGS("activerecordings"),

    RESUME("resume"),

    RESUME_AUDIO("resumeaudio"),

    RESUME_BOOK("resumebook"),

    LATEST_MEDIA("latestmedia"),

    NEXT_UP("nextup"),

    LIVE_TV("livetv"),
    ;

    companion object {
        /**
         * Forgiving on purpose: `null` is the normal case, not an error — a user who never opened
         * Settings → Home has no `homesectionN` keys — and it is also the right answer for a value written by
         * a newer server. Matching is case-insensitive because the values are hand-editable through the API,
         * and `"folders"` is accepted as jellyfin-web's legacy alias for [SMALL_LIBRARY_TILES].
         */
        fun fromServerValue(raw: String?): HomeSectionType? {
            val value = raw?.trim()?.lowercase().orEmpty()
            if (value.isEmpty()) return null
            if (value == LEGACY_FOLDERS) return SMALL_LIBRARY_TILES
            return entries.firstOrNull { it.serverValue == value }
        }

        private const val LEGACY_FOLDERS = "folders"
    }
}
