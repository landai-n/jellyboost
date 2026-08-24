package dev.jellyboost.core.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import dev.jellyboost.core.ui.R
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.GlassDefaults
import dev.jellyboost.core.ui.theme.JellyfinTypeExtras

/**
 * The header a **pushed** screen wears: two glass circles — Back, then Home — the screen's title,
 * and whatever trailing affordance the screen owns, over the status-bar inset.
 *
 * Both navigation affordances rather than one: a pushed destination shows no tab bar to escape
 * through, so Home is as much a way out as Back is. `LibraryGridScreen` established the shape and
 * settings and the SyncPlay groups screen copied it — including a
 * `private val HeaderPadding = 20.dp` apiece, kept in step by a prose comment in each file saying
 * it matched the other two. That is now [Dimens.HeaderPadding], and the row itself is here.
 *
 * ### What the detail screen does instead, and why it is not a caller
 * `ItemDetailScreen.OverlayNav` is a fourth copy of this shape, and it is deliberately left out:
 * it draws no title, and it puts Home at the *end* of the row behind the favourite heart,
 * because it floats over a full-bleed backdrop rather than sitting above a list. Folding it in would
 * mean a boolean choosing where Home goes — exactly the representable-nonsense parameter this
 * codebase argues against elsewhere. It shares the two things worth sharing: the `action_back` /
 * `action_home` labels and [GlassDefaults.ChromeFill], which [surfaceTint] exists to accept
 * from any future header that also sits on artwork.
 *
 * @param title the title block, laid out in a weighted column so a trailing action is pushed to the
 *   end. Usually one [ScreenHeaderTitle]; the library grid adds its item-count line underneath.
 * @param trailing the screen's own affordance at the end of the row — the SyncPlay *Create* circle,
 *   the library grid's *Sort*. Empty for a screen that has none.
 * @param contentPadding defaults to the gutter on both sides and a small vertical breath. The
 *   library grid overrides it: its chip row sits directly underneath, so its header carries a full
 *   gutter on top and only [Dimens.SpaceSmall] below, and the two read as one block.
 * @param surfaceTint the glass fill of the two circles — see [GlassDefaults.ChromeFill] for when a
 *   header over bright artwork needs the darker one.
 */
@Composable
fun ScreenHeader(
    onBack: () -> Unit,
    onHome: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues =
        PaddingValues(horizontal = Dimens.HeaderPadding, vertical = Dimens.SpaceSmall),
    surfaceTint: Color = GlassDefaults.Fill,
    trailing: @Composable RowScope.() -> Unit = {},
    title: @Composable ColumnScope.() -> Unit,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassIconButton(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.action_back),
            onClick = onBack,
            surfaceTint = surfaceTint,
        )
        GlassIconButton(
            icon = Icons.Filled.Home,
            contentDescription = stringResource(R.string.action_home),
            onClick = onHome,
            surfaceTint = surfaceTint,
        )
        Column(
            modifier = Modifier.weight(1f).padding(start = Dimens.SpaceExtraSmall),
            content = title,
        )
        trailing()
    }
}

/**
 * The title inside a [ScreenHeader].
 *
 * A heading to a screen reader as well as to the eye, and one that speaks its full text: this is
 * where TalkBack's heading-jump lands on a screen whose first stop is otherwise a Back button.
 * The library grid's header was the only one of the three
 * that declared it; folding the three into one composable is what gives settings and the SyncPlay
 * groups screen the same landing spot.
 *
 * @param style [JellyfinTypeExtras.ScreenTitle], or [JellyfinTypeExtras.ScreenTitleLarge] on a wide
 *   layout where the same size reads a step too small.
 */
@Composable
fun ScreenHeaderTitle(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = JellyfinTypeExtras.ScreenTitle,
) {
    Text(
        text = text,
        style = style,
        color = MaterialTheme.colorScheme.onBackground,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier =
            modifier.semantics {
                heading()
                contentDescription = text
            },
    )
}
