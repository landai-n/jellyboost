package dev.jellyboost.core.ui.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// The two drop shadows of the 2026 refresh, expressed as Compose elevations.
//
// They are approximations, not translations. The mocks specify CSS box-shadows with an explicit
// offset, blur and spread (`0 8 24 rgba(0,0,0,.45)` and `0 16 44 rgba(0,0,0,.55)`); Compose's
// `Modifier.shadow` takes a single elevation and derives all three from it, with no control over
// offset or spread. The elevations below were picked so the *visible* extent of each shadow lands
// where the mock's does, and the ambient/spot colours carry the CSS alpha.
//
// The gap matters less than it sounds. On the `#101010` background a black shadow is nearly
// invisible, and the refresh's primary separators are the hairlines in `GlassDefaults`, not these
// shadows — they exist to stop a surface looking pasted on where it overlaps other content, not to
// draw its edge.

/** Stands in for the mocks' card shadow, CSS `0 8 24 rgba(0,0,0,.45)`. */
private val CardShadowElevation: Dp = 12.dp

/** Stands in for the mocks' pop shadow, CSS `0 16 44 rgba(0,0,0,.55)`. */
private val PopShadowElevation: Dp = 24.dp

private val CardShadowColor = Color.Black.copy(alpha = 0.45f)

private val PopShadowColor = Color.Black.copy(alpha = 0.55f)

/** The resting shadow under artwork cards and other content that sits on the page background. */
fun Modifier.cardShadow(shape: Shape): Modifier =
    shadow(
        elevation = CardShadowElevation,
        shape = shape,
        ambientColor = CardShadowColor,
        spotColor = CardShadowColor,
    )

/** The heavier shadow for surfaces that pop *above* the page: floating chrome, sheets, dialogs. */
fun Modifier.popShadow(shape: Shape): Modifier =
    shadow(
        elevation = PopShadowElevation,
        shape = shape,
        ambientColor = PopShadowColor,
        spotColor = PopShadowColor,
    )
