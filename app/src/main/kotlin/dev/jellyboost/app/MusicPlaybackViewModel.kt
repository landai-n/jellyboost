package dev.jellyboost.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.Ticks
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.common.music.MusicController
import dev.jellyboost.core.common.music.MusicMessage
import dev.jellyboost.core.common.music.MusicPlaybackState
import dev.jellyboost.data.JellyfinRepository
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The music-queue verbs the navigation graph hands to the browse screens, so that no feature module
 * ever names the player. Nothing is held here — [MusicController] is a `@Singleton` whose state
 * outlives every screen.
 */
@HiltViewModel
class MusicPlaybackViewModel
    @Inject
    constructor(
        private val controller: MusicController,
        private val repository: JellyfinRepository,
    ) : ViewModel() {
        /**
         * [startRadio]'s own failures: an Instant Mix fetch happens *before* there is anything to
         * hand the controller, so it cannot ride `MusicController.messages`.
         */
        private val radioMessages =
            MutableSharedFlow<MusicMessage>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

        val messages: Flow<MusicMessage> = merge(controller.messages, radioMessages)

        val state: StateFlow<MusicPlaybackState> = controller.state

        /** Fire and forget: the caller is a click handler, and the outcome reaches the user through [messages]. */
        fun play(
            tracks: List<JellyfinItem>,
            startIndex: Int,
        ) {
            viewModelScope.launch { controller.play(tracks, startIndex) }
        }

        /**
         * One call rather than `play` then `setShuffle(true)`: the two-step version starts the first
         * track in queue order and only then reshuffles, so the album's opener plays first every time.
         */
        fun shuffle(tracks: List<JellyfinItem>) {
            viewModelScope.launch { controller.play(tracks, startIndex = 0, shuffled = true) }
        }

        /** Resumes [item] from its saved position — Home's *Continue Listening* row. */
        fun playResumed(item: JellyfinItem) {
            val startPositionMs = Ticks.ticksToMillis(item.userData.playbackPositionTicks)
            viewModelScope.launch { controller.play(listOf(item), startIndex = 0, startPositionMs = startPositionMs) }
        }

        fun togglePlayPause() = controller.togglePlayPause()

        fun next() = controller.next()

        fun previous() = controller.previous()

        /** Ends the session rather than pausing it: the [MusicPlaybackState.Idle] state is what hides the bar. */
        fun stop() = controller.stop()

        /**
         * Plays the track's downloaded *album context* — the album tracks come from the delegating
         * repository, which answers from the `albumId` column offline. A track with no album, or a
         * failed fetch, degrades to a single-item queue so the tap still plays.
         */
        fun playDownloadedAudio(
            item: JellyfinItem,
            startPositionTicks: Long = 0L,
        ) {
            viewModelScope.launch {
                val startPositionMs = Ticks.ticksToMillis(startPositionTicks)
                val albumTracks =
                    item.albumId?.let { albumId ->
                        when (val result = repository.getAlbumTracks(albumId)) {
                            is AppResult.Success -> result.value
                            is AppResult.Failure -> null
                        }
                    }
                val startIndex = albumTracks?.indexOfFirst { it.id == item.id }?.takeIf { it >= 0 }
                if (albumTracks != null && startIndex != null) {
                    controller.play(albumTracks, startIndex, startPositionMs = startPositionMs)
                } else {
                    controller.play(listOf(item), startIndex = 0, startPositionMs = startPositionMs)
                }
            }
        }

        /**
         * A failed fetch — offline, a server error, an empty mix — surfaces through [messages] rather
         * than as a full-screen error: this is a secondary action on an already-loaded screen.
         */
        fun startRadio(item: JellyfinItem) {
            viewModelScope.launch {
                when (val result = repository.getInstantMix(item.id)) {
                    is AppResult.Success ->
                        if (result.value.isNotEmpty()) {
                            controller.play(result.value)
                        } else {
                            radioMessages.emit(MusicMessage.RadioFailed(item.name))
                        }

                    is AppResult.Failure -> radioMessages.emit(MusicMessage.RadioFailed(item.name))
                }
            }
        }
    }
