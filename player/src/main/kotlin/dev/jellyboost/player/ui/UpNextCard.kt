package dev.jellyboost.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.jellyboost.core.common.Separators
import dev.jellyboost.core.ui.component.GhostPillButton
import dev.jellyboost.core.ui.component.GlassIconButton
import dev.jellyboost.core.ui.component.JellyfinAsyncImage
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.JellyfinTheme
import dev.jellyboost.core.ui.theme.glassSurface
import dev.jellyboost.player.R
import dev.jellyboost.player.upnext.UpNextEpisode
import dev.jellyboost.core.ui.R as CoreUiR

/**
 * "Up next", drawn over the last minute of an episode. Flat glass, not blurred: there is no Haze
 * backdrop over a `SurfaceView`.
 *
 * Nothing may be `remember`ed here — `PlayerScreen` composes this conditionally, so parked state
 * dies on the first seek that hides the card. Ownership belongs to `UpNextController`.
 *
 * Deliberately **not** one merged node, and deliberately without a whole-card click target: it
 * carries two actions, and a `clickable` container would merge them into one ambiguous stop.
 */
@Composable
internal fun UpNextCard(
    episode: UpNextEpisode,
    onPlayNext: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                // A cap, not a size: content wraps inside it, so fontScale 2.0 grows the card
                // downwards rather than off-screen (WCAG 1.4.4). Keep the pill on its own row.
                .widthIn(max = CARD_MAX_WIDTH)
                .glassSurface(shape = RoundedCornerShape(Dimens.CardCornerRadius), tint = VIDEO_GLASS_FILL)
                .padding(Dimens.SpaceMedium),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMedium),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMedium),
        ) {
            if (!episode.imageUrl.isNullOrBlank()) {
                JellyfinAsyncImage(
                    url = episode.imageUrl,
                    // Decorative: the sentence beside it already names the episode.
                    contentDescription = null,
                    modifier =
                        Modifier
                            .width(STILL_WIDTH)
                            .aspectRatio(STILL_ASPECT)
                            .clip(RoundedCornerShape(Dimens.SpaceSmall)),
                    placeholderIcon = null,
                )
            }

            UpNextLabel(episode = episode, modifier = Modifier.weight(1f))

            GlassIconButton(
                icon = Icons.Filled.Close,
                contentDescription = stringResource(R.string.player_up_next_dismiss),
                onClick = onDismiss,
                // Full white, not the chrome default of white@80%: read against a moving image.
                tint = Color.White,
            )
        }

        GhostPillButton(
            text = stringResource(R.string.player_up_next_play),
            onClick = onPlayNext,
            modifier = Modifier.align(Alignment.End),
            small = true,
            leadingIcon = Icons.Filled.SkipNext,
        )
    }
}

/**
 * The two static lines as one traversal stop, with an authored sentence rather than Compose's merge:
 * the spoken join is a comma for the ear while the drawn one stays a middle dot.
 */
@Composable
private fun UpNextLabel(
    episode: UpNextEpisode,
    modifier: Modifier = Modifier,
) {
    val eyebrow = stringResource(R.string.player_up_next_title)
    val number = episode.episodeNumberLabel()
    val title = episode.title.ifBlank { null }
    val line = listOfNotNull(number, title).joinToString(Separators.DOT)
    val spoken = listOfNotNull(eyebrow, number, title).joinToString(SPOKEN_SEPARATOR)

    Column(
        modifier =
            modifier.semantics(mergeDescendants = true) {
                contentDescription = spoken
                liveRegion = LiveRegionMode.Polite
            },
    ) {
        Text(
            text = eyebrow,
            style = MaterialTheme.typography.labelMedium,
            // 0.85 is a contrast floor: over this fill on a white frame (rgb 102) it is 4.69:1,
            // where 0.7 would be 3.76:1.
            color = Color.White.copy(alpha = EYEBROW_ALPHA),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = line,
            style = MaterialTheme.typography.titleSmall,
            color = Color.White,
            // One line: a wrapping title would push the buttons off the bottom of the card.
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * `S1 · E5`, or `E5` with no season, or `null` when the server gave no number. Must keep reading the
 * same resources `JellyfinItem.episodeNumberLabel` does: a Kotlin literal escapes `MissingTranslation`.
 */
@Composable
private fun UpNextEpisode.episodeNumberLabel(): String? {
    val number = indexNumber ?: return null
    val season = parentIndexNumber ?: return stringResource(CoreUiR.string.media_episode_label_short, number)
    return stringResource(CoreUiR.string.media_episode_label, season, number)
}

private val CARD_MAX_WIDTH = 360.dp

private val STILL_WIDTH = 100.dp

private const val STILL_ASPECT = 16f / 9f

/** See the contrast note at its one use. */
private const val EYEBROW_ALPHA = 0.85f

/** A comma, not [Separators.DOT]: the pause is what makes the spoken line a sentence. */
private const val SPOKEN_SEPARATOR = ", "

@Preview(name = "Up next", widthDp = 420, backgroundColor = 0xFF101010, showBackground = true)
@Composable
private fun UpNextCardPreview() {
    JellyfinTheme {
        UpNextCard(
            episode =
                UpNextEpisode(
                    itemId = "episode-11",
                    title = "The Bicameral Mind",
                    indexNumber = 11,
                    parentIndexNumber = 1,
                    imageUrl = null,
                ),
            onPlayNext = {},
            onDismiss = {},
        )
    }
}

@Preview(
    name = "Up next · fontScale 2.0",
    widthDp = 420,
    fontScale = 2.0f,
    backgroundColor = 0xFF101010,
    showBackground = true,
)
@Composable
private fun UpNextCardLargeTextPreview() {
    UpNextCardPreview()
}
