package dev.jellyboost.player.gesture

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal data class GestureIndicator(
    val target: SwipeTarget,
    val value: Float,
)

/**
 * The linger timer must key on [revision], not on [indicator]: equal values do not notify, and a
 * [hide] followed by a same-value [show] within one frame would otherwise leave the overlay visible
 * with no timer running. A timer may only hide the show it was started for.
 */
internal class GestureIndicatorState {
    var indicator: GestureIndicator? by mutableStateOf(null)
        private set

    var revision: Int by mutableIntStateOf(0)
        private set

    fun show(
        target: SwipeTarget,
        value: Float,
    ) {
        indicator = GestureIndicator(target, value)
        revision++
    }

    fun hide(revision: Int) {
        if (this.revision == revision) indicator = null
    }
}
