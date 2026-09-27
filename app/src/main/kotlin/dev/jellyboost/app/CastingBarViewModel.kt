package dev.jellyboost.app

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jellyboost.player.cast.CastExitReason
import dev.jellyboost.player.cast.CastNowPlaying
import dev.jellyboost.player.cast.CastingItem
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * A view over the `@Singleton` [CastNowPlaying]: nothing is held here, the receiver's session outlives
 * every screen. The chrome's only way to reach a receiver, and it names no Cast type.
 */
@HiltViewModel
class CastingBarViewModel
    @Inject
    constructor(
        private val castNowPlaying: CastNowPlaying,
    ) : ViewModel() {
        val state: StateFlow<CastingItem?> = castNowPlaying.state

        /** What is on the receiver right now, read fresh — for a notification tap, not for drawing. */
        fun current(): CastingItem? = castNowPlaying.current()

        /** Why [state]'s last `non-null → null` happened — read fresh, at the moment it is asked for. */
        fun lastExitReason(): CastExitReason = castNowPlaying.lastExitReason()

        fun togglePlayPause() = castNowPlaying.togglePlayPause()
    }
