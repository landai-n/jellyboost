package dev.jellyboost.core.ui.text

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.jellyboost.core.common.Separators
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.ui.R

/*
 * Episode wording must stay in resource form, never a Kotlin string template: `S` and `E` are
 * initials of words, and a literal is invisible to the `MissingTranslation` gate.
 *
 * These live in `:core:ui` rather than on `JellyfinItem` because resolving to user-facing words
 * needs a composition. `JellyfinItem.displaySubtitle`/`episodeLabel` remain the non-composable
 * fallback for `MediaSession` metadata in `:player`; every drawing surface comes through here.
 */

/** `S1 · E4`, or `E4` when the season number is unknown; `null` when there is no episode number. */
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
 * The movie-year fallback is deliberate — a detail header once dropped it while cards kept it, and
 * the same movie showed its year in one place and not the other. `null` when there is nothing to
 * say, so a caller can skip the row rather than draw an empty one.
 */
@Composable
fun JellyfinItem.subtitleLine(): String? =
    when (type) {
        ItemType.EPISODE ->
            listOfNotNull(episodeNumberLabel(), name).joinToString(Separators.DOT).ifBlank { null }

        ItemType.SEASON -> seriesName
        else -> productionYear?.toString()
    }
