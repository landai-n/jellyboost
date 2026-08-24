package dev.jellyboost.core.ui.text

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.jellyboost.core.common.Separators
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.ui.R

/*
 * How an item's short facts are worded — the one implementation, in the one layer that can read a
 * resource.
 *
 * Hand-spelling an episode number in more than one place risks two different answers on screen at
 * once — a Kotlin string template built for cards and their accessibility descriptions, and a
 * string resource read elsewhere for the detail header's Play button, with TalkBack speaking one
 * and the eye reading the other. The resource form wins because it is the one a translator can
 * reach: `S` and `E` are initials of words, and a Kotlin literal is invisible to the
 * `MissingTranslation` gate.
 *
 * These live in `:core:ui` rather than on `JellyfinItem` for the same reason `UiText` does: a
 * derivation that resolves to user-facing words needs a composition to know the device's language,
 * and the model is deliberately free of them. `JellyfinItem.displaySubtitle` and
 * `JellyfinItem.episodeLabel` survive as the non-composable fallback for the one place that
 * genuinely has no composition — `MediaSession` metadata built in `:player` — and every drawing
 * surface now comes through here.
 */

/**
 * `S1 · E4`, or `E4` when the season number is unknown; `null` when there is no episode number.
 *
 * The top-left badge on a card, and the episode the detail page's Play button names.
 */
@Composable
fun JellyfinItem.episodeNumberLabel(): String? {
    val episode = indexNumber ?: return null
    val season = parentIndexNumber
    return if (season != null) {
        stringResource(R.string.media_episode_label, season, episode)
    } else {
        stringResource(R.string.media_episode_label_short, episode)
    }
}

/**
 * The second line under a title: `S1 · E4 · Trompe L'Oeil` for an episode, the series name for a
 * season, the production year for everything else.
 *
 * The **movie-year fallback is deliberate**.
 * `JellyfinItem.displaySubtitle` had it and the detail header's own `subtitleLine` did not, so the
 * same movie showed its year on a card and nothing under its detail title. Nothing in either
 * screen asked for the difference — no comment, no test — so it is drift, and the richer behaviour
 * is the one worth keeping: a year is the single most useful thing to say about a film after its
 * name. Detail pages for movies therefore gain a subtitle line they did not draw before.
 *
 * `null` when there is nothing to say, so a caller can skip the row rather than draw an empty one.
 */
@Composable
fun JellyfinItem.subtitleLine(): String? =
    when (type) {
        ItemType.EPISODE ->
            listOfNotNull(episodeNumberLabel(), name).joinToString(Separators.DOT).ifBlank { null }

        ItemType.SEASON -> seriesName
        else -> productionYear?.toString()
    }
