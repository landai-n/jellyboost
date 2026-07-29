package dev.jellyfinnative.core.common.model

/**
 * One row the home screen can show, as jellyfin-web names it in Settings → Home.
 *
 * The list of values is jellyfin-web's `HomeSectionType`, byte for byte: the user's chosen order
 * lives server-side in DisplayPreferences as `homesection0` … `homesection9`, and these are the
 * strings that map may hold (see `docs/notes/home-sections-feasibility.md`). Decoding them is
 * `:data`'s job — this enum is only the vocabulary, so that every layer above the wire format
 * talks about *sections* rather than about strings.
 *
 * Not every value is a row this app draws: v1 is movies and TV only, so [RESUME_AUDIO],
 * [RESUME_BOOK], [LIVE_TV] and [ACTIVE_RECORDINGS] are decoded and carried faithfully — losing
 * them would silently reorder everything after them — and simply skipped at render time.
 */
enum class HomeSectionType(
    /** The literal jellyfin-web/server value. */
    val serverValue: String,
) {
    /** An explicitly empty slot. Never rendered, and dropped from a resolved layout. */
    NONE("none"),

    /** *My Media* as tiles — the app's libraries row. */
    SMALL_LIBRARY_TILES("smalllibrarytiles"),

    /** *My Media* as large buttons. Rendered as the same libraries row in v1. */
    LIBRARY_BUTTONS("librarybuttons"),

    /** Live TV recordings in progress. Out of scope. */
    ACTIVE_RECORDINGS("activerecordings"),

    /** *Continue Watching* — video with playback progress. */
    RESUME("resume"),

    /** *Continue Listening*. Out of scope (no music in v1). */
    RESUME_AUDIO("resumeaudio"),

    /** *Continue Reading*. Out of scope (no books in v1). */
    RESUME_BOOK("resumebook"),

    /** *Latest &lt;library&gt;* — one row per library. */
    LATEST_MEDIA("latestmedia"),

    /** *Next Up* — the next unwatched episode per series. */
    NEXT_UP("nextup"),

    /** Live TV channels. Out of scope. */
    LIVE_TV("livetv"),
    ;

    companion object {
        /**
         * Decodes a raw `homesectionN` value, or `null` when it is missing or unrecognised.
         *
         * Forgiving on purpose. `null` is the normal case rather than an error — a user who never
         * opened Settings → Home has no `homesectionN` keys at all — and the caller answers it by
         * applying that slot's default, which is also the right answer for a value written by a
         * newer server than this app knows about. Matching is case-insensitive because the values
         * are hand-editable through the API.
         *
         * `"folders"` is jellyfin-web's legacy spelling of the libraries row and is accepted as an
         * alias for [SMALL_LIBRARY_TILES].
         */
        fun fromServerValue(raw: String?): HomeSectionType? {
            val value = raw?.trim()?.lowercase().orEmpty()
            if (value.isEmpty()) return null
            if (value == LEGACY_FOLDERS) return SMALL_LIBRARY_TILES
            return entries.firstOrNull { it.serverValue == value }
        }

        /** jellyfin-web's pre-rename value for the libraries row. */
        private const val LEGACY_FOLDERS = "folders"
    }
}
