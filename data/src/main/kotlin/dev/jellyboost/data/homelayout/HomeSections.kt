package dev.jellyboost.data.homelayout

import dev.jellyboost.core.common.model.HomeSectionType

/**
 * jellyfin-web's `DEFAULT_SECTIONS`, hardcoded identically across all ten slots (`MAX_SECTIONS`): a
 * user who never opened Settings → Home has **no** `homesectionN` keys, so "missing" means "apply
 * the client defaults", not "show nothing".
 */
private val DEFAULT_SLOTS =
    listOf(
        HomeSectionType.SMALL_LIBRARY_TILES,
        HomeSectionType.RESUME,
        HomeSectionType.RESUME_AUDIO,
        HomeSectionType.RESUME_BOOK,
        HomeSectionType.LIVE_TV,
        HomeSectionType.NEXT_UP,
        HomeSectionType.LATEST_MEDIA,
        HomeSectionType.NONE,
        HomeSectionType.NONE,
        HomeSectionType.NONE,
    )

/**
 * Also the answer when the server cannot be asked with nothing cached, and the initial value of
 * `HomeUiState.sections`, so the first frame is already right for the common case.
 */
val DEFAULT_HOME_SECTIONS: List<HomeSectionType> = resolveHomeSections(emptyMap())

/**
 * Each slot resolves independently — missing, empty or unrecognised falls back to *that slot's*
 * default — so a partially configured or newer-than-us layout degrades one row at a time.
 *
 * [HomeSectionType.NONE] is dropped and duplicates removed, first occurrence winning: the home list
 * keys its rows by section.
 */
internal fun resolveHomeSections(customPrefs: Map<String, String?>): List<HomeSectionType> =
    DEFAULT_SLOTS
        .mapIndexed { slot, default ->
            HomeSectionType.fromServerValue(customPrefs["$HOME_SECTION_KEY_PREFIX$slot"]) ?: default
        }.filterNot { it == HomeSectionType.NONE }
        .distinct()

/** One of `DEFAULT_SLOTS.indices` follows this prefix. */
private const val HOME_SECTION_KEY_PREFIX = "homesection"
