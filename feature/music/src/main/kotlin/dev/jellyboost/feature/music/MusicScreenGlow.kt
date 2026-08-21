package dev.jellyboost.feature.music

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.jellyboost.core.ui.theme.screenGlow

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
 * [screenGlow] rather than the hero halo: the halo is sized to
 * compete with a backdrop underneath it, and at that strength on a bare background it reads as a
 * wash (the glow modifier's own KDoc). The box's height is derived from its width with [aspectRatio],
 * because the brush's radius is width-derived (fade-out at 0.76 × 0.8 ≈ 61% of the width): a fixed
 * height that fits a phone chops the gradient mid-fade on a tablet and draws a hard seam across
 * the page (device walk, 2026-08-15). 10:7 keeps the fade's end comfortably inside the box on any
 * width, and on a phone comes out within a few dp of the 320dp the box used to be.
 */
@Composable
internal fun MusicScreenGlow(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().aspectRatio(GLOW_ASPECT).screenGlow())
}

/** Width : height. Height ≈ 70% of width — past the brush's ~61%-of-width fade-out. */
private const val GLOW_ASPECT = 10f / 7f
