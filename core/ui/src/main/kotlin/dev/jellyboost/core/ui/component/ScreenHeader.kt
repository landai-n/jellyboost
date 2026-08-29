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
 * `ItemDetailScreen.OverlayNav` is a fourth copy of this shape, deliberately not a caller — it
 * draws no title and puts Home at the end of the row, so folding it in would mean a boolean
 * choosing where Home goes.
 *
 * @param onHome `null` leaves Back as the header's only leading control. Two adjacent glass circles
 *   read as one affordance while doing different things, and Home is reachable from the nav bar.
 * @param surfaceTint see [GlassDefaults.ChromeFill] for a header over bright artwork.
 */
@Composable
fun ScreenHeader(
    onBack: () -> Unit,
    onHome: (() -> Unit)? = null,
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
        if (onHome != null) {
            GlassIconButton(
                icon = Icons.Filled.Home,
                contentDescription = stringResource(R.string.action_home),
                onClick = onHome,
                surfaceTint = surfaceTint,
            )
        }
        Column(
            modifier = Modifier.weight(1f).padding(start = Dimens.SpaceExtraSmall),
            content = title,
        )
        trailing()
    }
}

/**
 * Keeps `heading()` and its full untruncated text: this is where TalkBack's heading-jump lands on a
 * screen whose first stop is otherwise a Back button.
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
