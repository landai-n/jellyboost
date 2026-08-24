package dev.jellyboost.core.ui.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Approximations of CSS box-shadows (`0 8 24 rgba(0,0,0,.45)`, `0 16 44 rgba(0,0,0,.55)`):
// `Modifier.shadow` derives offset, blur and spread from one elevation, so these were picked to
// match each shadow's *visible* extent, with the alpha carried by the ambient/spot colours.

private val CardShadowElevation: Dp = 12.dp

private val PopShadowElevation: Dp = 24.dp

private val CardShadowColor = Color.Black.copy(alpha = 0.45f)

private val PopShadowColor = Color.Black.copy(alpha = 0.55f)

fun Modifier.cardShadow(shape: Shape): Modifier =
    shadow(
        elevation = CardShadowElevation,
        shape = shape,
        ambientColor = CardShadowColor,
        spotColor = CardShadowColor,
    )

fun Modifier.popShadow(shape: Shape): Modifier =
    shadow(
        elevation = PopShadowElevation,
        shape = shape,
        ambientColor = PopShadowColor,
        spotColor = PopShadowColor,
    )
