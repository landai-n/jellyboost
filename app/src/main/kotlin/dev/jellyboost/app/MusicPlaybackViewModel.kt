package dev.jellyboost.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.common.music.MusicController
import dev.jellyboost.core.common.music.MusicMessage
import dev.jellyboost.core.common.music.MusicPlaybackState
import dev.jellyboost.player.model.ticksToMillis
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
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

        /** The queue and its transport state, for [MiniPlayer] and its visibility rule. */
        val state: StateFlow<MusicPlaybackState> = controller.state

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

        /**
         * Resumes [item] from its saved position — Home's *Continue Listening* row (M13 Phase 4).
         *
         * A single-item queue, exactly like tapping any other track, except started at
         * [dev.jellyboost.core.common.model.UserData.playbackPositionTicks] rather than from zero.
         * The ticks-to-millis conversion is `:player`'s own (`PlaybackSnapshot.kt`) — `:app` already
         * depends on `:player` for the video screen, so reusing it here needs no new dependency.
         */
        fun playResumed(item: JellyfinItem) {
            val startPositionMs = item.userData.playbackPositionTicks.ticksToMillis()
            viewModelScope.launch { controller.play(listOf(item), startIndex = 0, startPositionMs = startPositionMs) }
        }

        /** The mini-player's play/pause button. */
        fun togglePlayPause() = controller.togglePlayPause()

        /** The mini-player's next button. */
        fun next() = controller.next()
    }
