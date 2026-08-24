package dev.jellyboost.feature.music.nowplaying

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.model.UserData
import dev.jellyboost.core.common.music.Lyrics
import dev.jellyboost.core.common.music.MusicController
import dev.jellyboost.core.common.music.MusicPlaybackState
import dev.jellyboost.data.JellyfinRepository
import dev.jellyboost.data.userdata.UserDataRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * State holder for [dev.jellyboost.feature.music.nowplaying.NowPlayingScreen] and its [QueueSheet].
 *
 * Unlike every other screen in `:feature:music`, this one injects [MusicController] directly rather
 * than going through `:app`'s `MusicPlaybackViewModel` — the arrangement that class's own KDoc
 * documents as existing so that *browse* screens do not each repeat the same two lines. NowPlaying
 * is not a browse screen; it *is* the queue's own view, so reading [MusicController.state] here is
 * the direct route: the interface lives in `:core:common` precisely so any feature may inject it
 * without ever depending on `:player`.
 *
 * Every transport verb is a thin pass-through — the controller already validates and no-ops the
 * ones that do not apply (e.g. [MusicController.togglePlayPause] while idle) — so this class adds
 * two things the controller does not know how to do itself: favouriting, and fetching the current
 * track's lyrics ([observeTrackChangesForLyrics]) for [LyricsPane].
 */
@HiltViewModel
class NowPlayingViewModel
    @Inject
    constructor(
        private val controller: MusicController,
        private val userDataRepository: UserDataRepository,
        private val repository: JellyfinRepository,
    ) : ViewModel() {
        /** Local user-data changes seen since this screen started collecting; see [toNowPlayingUiState]. */
        private val favoriteOverrides = MutableStateFlow<Map<String, UserData>>(emptyMap())

        /**
         * Lyrics already fetched this session, by track id — `null` means "fetched, the server has
         * none"; a missing key means "not fetched yet".
         *
         * Cleared to empty whenever the queue goes [MusicPlaybackState.Idle]: at that point there is
         * no current track for any of it to belong to, and the next queue this screen shows (a fresh
         * `play()`, not a resume of this one) starts the fetch fresh.
         */
        private val lyricsByTrackId = MutableStateFlow<Map<String, Lyrics?>>(emptyMap())

        val uiState: StateFlow<NowPlayingUiState> =
            combine(controller.state, favoriteOverrides, lyricsByTrackId) { state, overrides, lyrics ->
                state.toNowPlayingUiState(overrides, lyrics)
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STATE_STOP_TIMEOUT_MS),
                initialValue = controller.state.value.toNowPlayingUiState(),
            )

        init {
            viewModelScope.launch {
                userDataRepository.changes.collect { change ->
                    favoriteOverrides.update { it + (change.itemId to change.userData) }
                }
            }
            observeTrackChangesForLyrics()
        }

        fun togglePlayPause() = controller.togglePlayPause()

        fun next() = controller.next()

        fun previous() = controller.previous()

        fun seekTo(positionMs: Long) = controller.seekTo(positionMs)

        fun setShuffle(enabled: Boolean) = controller.setShuffle(enabled)

        fun cycleRepeat() = controller.cycleRepeat()

        /** A tap in [QueueSheet]. */
        fun jumpTo(index: Int) = controller.jumpTo(index)

        /** The remove button in [QueueSheet]. */
        fun removeAt(index: Int) = controller.removeAt(index)

        /**
         * The Stop button — [QueueSheet]'s header, and the overlay nav's, which is the wide
         * layout's only reachable one since its inline queue has no header.
         *
         * Ends the session rather than pausing it, so the state goes [MusicPlaybackState.Idle] and
         * `NowPlayingScreen`'s existing idle `LaunchedEffect` pops the screen; nothing here
         * navigates.
         */
        fun stop() = controller.stop()

        /** The up/down reorder buttons in [QueueSheet] — see that file's KDoc for why buttons. */
        fun moveItem(
            from: Int,
            to: Int,
        ) = controller.moveItem(from, to)

        /**
         * Toggles the favourite heart on the track currently playing — the local-first pattern
         * every sibling detail screen uses.
         */
        fun toggleFavorite() {
            val item = uiState.value.track ?: return
            viewModelScope.launch { userDataRepository.setFavorite(item.id, !item.userData.isFavorite) }
        }

        /**
         * Fetches [LyricsPane]'s lyrics for whichever track is current, re-fetching on every track
         * change and clearing the cache on [MusicPlaybackState.Idle].
         *
         * Keyed on the current item's id rather than [MusicPlaybackState.Active.currentIndex]: a
         * `moveItem` reorder changes the index without changing the *track*, and that must not
         * re-trigger a fetch this cache already has the answer for.
         */
        private fun observeTrackChangesForLyrics() {
            viewModelScope.launch {
                controller.state
                    .map { state -> (state as? MusicPlaybackState.Active)?.currentItem?.id }
                    .distinctUntilChanged()
                    .collect { trackId ->
                        if (trackId == null) {
                            lyricsByTrackId.value = emptyMap()
                            return@collect
                        }
                        if (lyricsByTrackId.value.containsKey(trackId)) return@collect

                        val lyrics = (repository.getLyrics(trackId) as? AppResult.Success)?.value
                        lyricsByTrackId.update { it + (trackId to lyrics) }
                    }
            }
        }

        private companion object {
            /**
             * How long [uiState] keeps collecting [MusicController.state] after the screen stops
             * observing it — long enough that a configuration change (rotation, into the queue
             * sheet and back) does not drop and immediately re-subscribe.
             */
            const val STATE_STOP_TIMEOUT_MS = 5_000L
        }
    }
