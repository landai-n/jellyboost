package dev.jellyboost.data.homelayout

import dev.jellyboost.core.common.model.HomeSectionType

/**
 * The section each of the ten slots (jellyfin-web's `MAX_SECTIONS`) falls back to when the server
 * has no value for it.
 *
 * This is jellyfin-web's `DEFAULT_SECTIONS`, and it has to be hardcoded identically: a user who
 * never opened Settings → Home has **no** `homesectionN` keys at all, so "missing" means "apply
 * the client defaults", not "show nothing". Getting this list wrong would give a fresh account an
 * empty home screen.
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
 * The layout of a user who has never configured one — the same rows, in the same order, that the
 * home screen showed before it became configurable.
 *
 * Also the answer whenever the server cannot be asked and nothing has been cached yet, and the
 * initial value of `HomeUiState.sections`, so the very first frame is already right for the
 * overwhelmingly common case.
 */
val DEFAULT_HOME_SECTIONS: List<HomeSectionType> = resolveHomeSections(emptyMap())

/**
 * Turns the raw DisplayPreferences `customPrefs` map into the ordered list of rows to render.
 *
 * Every one of the ten slots is resolved independently: a missing key, an empty one, or a value
 * this build does not recognise all fall back to *that slot's* default rather than to nothing, so
 * a partially configured (or newer-than-us) layout degrades one row at a time.
 *
 * The result then drops [HomeSectionType.NONE] — an empty slot is not a row — and de-duplicates,
 * first occurrence winning, because the same section configured twice is one row in two places and
 * the home list keys rows by section.
 */
internal fun resolveHomeSections(customPrefs: Map<String, String?>): List<HomeSectionType> =
    DEFAULT_SLOTS
        .mapIndexed { slot, default ->
            HomeSectionType.fromServerValue(customPrefs["$HOME_SECTION_KEY_PREFIX$slot"]) ?: default
        }.filterNot { it == HomeSectionType.NONE }
        .distinct()

/** The `customPrefs` key prefix; one of `DEFAULT_SLOTS.indices` follows it. */
private const val HOME_SECTION_KEY_PREFIX = "homesection"
