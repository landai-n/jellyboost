package dev.jellyboost.feature.music

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.jellyboost.core.ui.theme.JellyfinGradients

/**
 * The accent wash every one of this module's browse screens carries behind its header.
 *
 * Straight off `LibraryGridScreen`, which introduced it for exactly this situation and states the
 * reason in one line: *"the screen has no artwork of its own, and this is what keeps the header from
 * reading as text on a black rectangle"*. The music library grid is that screen's direct sibling,
 * and the album, artist and playlist headers are the same shape — a title lockup on flat `#101010`
 * — while `:feature:detail`'s equivalent sits on a full-bleed backdrop. Without it these four were
 * the only headers in the app with no colour behind them at all.
 *
 * [JellyfinGradients.ScreenGlow] rather than [JellyfinGradients.HeroHalo]: the halo is sized to
 * compete with a backdrop underneath it, and at that strength on a bare background it reads as a
 * wash (that brush's own KDoc). Anchored to the top of the window and 320dp tall, the same box
 * `LibraryGridScreen` fills — the gradient's radius is width-derived, so it needs a box roughly as
 * tall as it is wide to finish inside rather than ending on a visible seam.
 */
@Composable
internal fun MusicScreenGlow(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().height(GlowHeight).background(JellyfinGradients.ScreenGlow))
}

private val GlowHeight = 320.dp
