package dev.jellyboost.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.common.music.MusicController
import dev.jellyboost.core.common.music.MusicMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The music-queue verbs the navigation graph hands to the browse screens.
 *
 * The `SyncPlayLaunchViewModel` arrangement: `:feature:music` publishes plain
 * `(tracks, startIndex) -> Unit` callbacks and knows nothing about who plays them, `:app` resolves
 * the singleton behind them here, and no feature module ever names the player. The alternative —
 * injecting [MusicController] into each music ViewModel — would work (the interface lives in
 * `:core:common`) but would repeat the same two lines in four ViewModels and put a queue verb on
 * objects whose subject is a *list*.
 *
 * Nothing is held here: [MusicController] is a `@Singleton` whose state outlives every screen, so
 * this class is a lifecycle-scoped way of *calling* it and nothing else.
 */
@HiltViewModel
class MusicPlaybackViewModel
    @Inject
    constructor(
        private val controller: MusicController,
    ) : ViewModel() {
        /** Refusals and unplayable tracks, for the app chrome's snackbar. */
        val messages: Flow<MusicMessage> = controller.messages

        /**
         * Plays [tracks] starting at [startIndex] — a track tap, or a "Play" button at index 0.
         *
         * Fire and forget: resolving a queue takes a moment and the caller is a click handler. The
         * outcome reaches the user through [messages] and through the queue simply starting.
         */
        fun play(
            tracks: List<JellyfinItem>,
            startIndex: Int,
        ) {
            viewModelScope.launch { controller.play(tracks, startIndex) }
        }

        /**
         * Plays [tracks] shuffled — the Shuffle button.
         *
         * One call rather than `play` followed by `setShuffle(true)`: the two-step version starts
         * the first track in queue order and only then reshuffles, so the album's opening track
         * plays first every single time, which is the one thing a shuffle button must not do.
         */
        fun shuffle(tracks: List<JellyfinItem>) {
            viewModelScope.launch { controller.play(tracks, startIndex = 0, shuffled = true) }
        }
    }
