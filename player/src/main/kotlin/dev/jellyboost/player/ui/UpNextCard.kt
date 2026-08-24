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
 * "Up next", drawn over the last minute of an episode.
 *
 * The same glass the rest of the player floats over the film: [VIDEO_GLASS_FILL] plus the standard
 * hairline, flat rather than blurred, because there is no Haze backdrop over a `SurfaceView` (see
 * `PlayerControls`' header for the whole of that reasoning). The card is the [SkipSegmentButton]'s
 * neighbour in the bottom-right corner and its successor for an outro — the pill offers to skip the
 * credits, the card offers to skip them *and* start the next episode — which is why
 * `PlayerViewModel.applySegmentDecision` suppresses the outro offer while this is up.
 *
 * ### What it is given, and what it is not
 * The episode and two callbacks, never [PlayerUiState]: a card that took the whole screen state
 * would recompose on every buffering flicker and every tick that moves the duration, for four
 * strings that change once an episode; a composable is passed what it draws, so that strong
 * skipping can do its work.
 *
 * Nothing is `remember`ed here. Whether the card is up, and whether it stays up after a dismissal,
 * are the ViewModel's (`UpNextController`) — this composable is conditionally composed by
 * `PlayerScreen` and would take any state parked in it away on the first seek that hides the card,
 * exactly the trap `PlayerPanel` state is hoisted out of.
 *
 * ### Accessibility
 * The card is deliberately **not** one merged node. It carries two different actions — play the
 * next episode, and stay for the credits — and merging would leave a screen reader with one stop
 * offering one of them. So it is three stops: the static block (still, eyebrow and episode line)
 * merged into one authored sentence and marked [LiveRegionMode.Polite] so it is announced once when
 * it appears, and the two buttons after it, each named for what it does (WCAG 2.5.3). The polite
 * announcement mirrors the skip pill's, for the same reason: the offer is time-boxed, and a user
 * who is not watching the screen has to be told it exists rather than left to find it by
 * traversal.
 *
 * There is no whole-card click target, though the corner is large enough to invite one: a
 * `clickable` container merges its descendants, which would fold the sentence and the pill back
 * into the single ambiguous stop the split above exists to avoid. The pill is the labelled
 * affordance, and it is the only one.
 *
 * @param episode what follows; its `imageUrl` is `null` for an episode the server has no still for,
 *   which drops the image slot rather than drawing an empty box beside the text.
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
                // A cap, not a size: the card stops growing well before it reaches the middle of a
                // tablet. Everything inside it wraps or ellipsises within the cap, which is what
                // keeps fontScale 2.0 a *taller* card rather than one off the edge of the screen
                // (WCAG 1.4.4) — and why the pill is on its own row below rather than beside the
                // text, where 2× type would have truncated the one label that names the action.
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
                    // Decorative: the sentence beside it already names the episode, and a second
                    // description of the same thing is one more stop saying nothing new.
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
                // Full white rather than the chrome default of white@80%, for the reason the top
                // bar's back button gives: this glyph is read against a moving image.
                tint = Color.White,
            )
        }

        GhostPillButton(
            text = stringResource(R.string.player_up_next_play),
            onClick = onPlayNext,
            modifier = Modifier.align(Alignment.End),
            small = true,
            leadingIcon = Icons.Filled.SkipNext,
            // The card is the surface here, so the pill takes the *in-content* glass fill rather
            // than the video fill its neighbour in the corner uses: white@6% over the card's own
            // dark, which is the pairing `JellyfinButtons` is drawn for.
        )
    }
}

/**
 * The two static lines — "Up next", then `S1 · E5 · Title` — as one traversal stop.
 *
 * The sentence is authored rather than left to Compose's own merge of the two `Text`s: what a screen
 * reader says then becomes a thing a test can state (`UpNextCardA11yTest`), and the join between the
 * number and the title can be a comma for the ear while staying a middle dot for the eye.
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
            // Held off full white so the line under it reads as the answer and this as the label.
            // 0.85 rather than something quieter is a contrast floor, not a taste: over this card's
            // fill on a white frame (rgb 102) white@0.85 is 4.69:1 and white@0.7 is 3.76:1 — the
            // same arithmetic `PlayerScreen.DIM_ALPHA` carries.
            color = Color.White.copy(alpha = EYEBROW_ALPHA),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = line,
            style = MaterialTheme.typography.titleSmall,
            color = Color.White,
            // One line: the card sits over the last seconds of a film, where a title that wrapped
            // to three lines would push the buttons off the bottom of it.
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * `S1 · E5`, or `E5` with no season, or `null` for an episode the server gave no number.
 *
 * Composed here rather than through `:core:ui`'s `JellyfinItem.episodeNumberLabel`, whose signature
 * is an extension on `JellyfinItem` and so cannot see an [UpNextEpisode] — the whole point of which
 * is to be four fields rather than forty. What it must not do is spell the label itself: `S` and `E`
 * are the initials of words, a Kotlin literal is invisible to the `MissingTranslation` gate, and
 * two spellings of an episode number could otherwise be on screen at once. So it reads the *same
 * two resources* the shared helper reads, chosen the same way.
 */
@Composable
private fun UpNextEpisode.episodeNumberLabel(): String? {
    val number = indexNumber ?: return null
    val season = parentIndexNumber ?: return stringResource(CoreUiR.string.media_episode_label_short, number)
    return stringResource(CoreUiR.string.media_episode_label, season, number)
}

/** How wide the card is allowed to get before its text starts ellipsising. */
private val CARD_MAX_WIDTH = 360.dp

/** The still, and the aspect every episode image in the app is drawn at. */
private val STILL_WIDTH = 100.dp

private const val STILL_ASPECT = 16f / 9f

/** See the contrast note at its one use. */
private const val EYEBROW_ALPHA = 0.85f

/**
 * What joins the card's two lines when they are *spoken* rather than drawn.
 *
 * A comma, not [Separators.DOT]: the pause a comma gives is what makes "Up next, S1 · E5, The
 * Bicameral Mind" a sentence rather than a run of fragments. The same choice `EpisodeRow` makes for
 * its own authored description. The number keeps the dot the shared resource spells it with, since
 * that is the one form the app speaks everywhere else.
 */
private const val SPOKEN_SEPARATOR = ", "

// No still: a preview has no server to fetch one from, so what these two show is the layout's
// other half — the text and the two affordances, at 1× and at the accessibility scale that has to
// leave both readable.
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
