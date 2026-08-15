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
        private val repository: JellyfinRepository,
    ) : ViewModel() {
        /**
         * [startRadio]'s own failures — an Instant Mix fetch is a repository call this class makes
         * *before* there is anything to hand the controller, so it cannot ride [MusicController
         * .messages] the way a refusal or an unplayable track does.
         */
        private val radioMessages =
            MutableSharedFlow<MusicMessage>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

        /** Refusals, unplayable tracks and failed "Start radio" attempts, for the app chrome's snackbar. */
        val messages: Flow<MusicMessage> = merge(controller.messages, radioMessages)

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
            val startPositionMs = Ticks.ticksToMillis(item.userData.playbackPositionTicks)
            viewModelScope.launch { controller.play(listOf(item), startIndex = 0, startPositionMs = startPositionMs) }
        }

        /** The mini-player's play/pause button. */
        fun togglePlayPause() = controller.togglePlayPause()

        /** The mini-player's next button. */
        fun next() = controller.next()

        /**
         * A downloaded track tapped on the Downloads screen (M13).
         *
         * Audio must not ride the Downloads tab's video path — `Routes.Player` is the immersive
         * video screen, which would fail on an audio file and bypass the music queue entirely.
         * Instead the tap plays the track's downloaded *album context*: the album's tracks are
         * fetched through the delegating repository (offline answers from the `albumId` column, so
         * this works in airplane mode) and the queue starts at the tapped track, resumed at
         * [startPositionTicks]. A track with no album, or a fetch that fails, degrades to a
         * single-item queue of the track itself — the tap still plays.
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
         * "Start radio" — `AlbumDetailScreen`'s header action, `ArtistDetailScreen`'s, and
         * `NowPlayingScreen`'s (M13 Phase 6, docs/notes/music-m13-plan.md, key decision 11).
         *
         * Fetches the server's Instant Mix seeded from [item] and hands it straight to the queue,
         * exactly like [play] but resolved from one seed item rather than a caller-supplied list. A
         * failed fetch — offline, a server error, or a mix that came back empty — surfaces through
         * [messages] as [MusicMessage.RadioFailed] instead of a full-screen error: this is a
         * secondary action on an already-loaded screen, and its failure should not read as the
         * screen itself being broken.
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
