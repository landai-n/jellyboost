package dev.jellyboost.feature.music

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.jellyboost.core.ui.theme.screenGlow

/**
 * [screenGlow], never the hero halo: the halo is sized to compete with a backdrop underneath it and
 * reads as a wash on a bare background.
 *
 * The height **must** stay derived from the width with [aspectRatio], because the brush's radius is
 * width-derived (fade-out at 0.76 × 0.8 ≈ 61 % of the width): a fixed height that fits a phone chops
 * the gradient mid-fade on a tablet and draws a hard seam across the page.
 */
@Composable
internal fun MusicScreenGlow(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().aspectRatio(GLOW_ASPECT).screenGlow())
}

/** Width : height. Height ≈ 70 % of width — past the brush's ~61 % fade-out. */
private const val GLOW_ASPECT = 10f / 7f
