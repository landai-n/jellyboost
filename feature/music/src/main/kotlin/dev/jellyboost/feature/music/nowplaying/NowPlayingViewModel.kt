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
 * Injects [MusicController] directly, unlike the browse screens that go through `:app`'s
 * `MusicPlaybackViewModel`: this screen *is* the queue's own view. The transport verbs are thin
 * pass-throughs — the controller already validates and no-ops the ones that do not apply.
 */
@HiltViewModel
class NowPlayingViewModel
    @Inject
    constructor(
        private val controller: MusicController,
        private val userDataRepository: UserDataRepository,
        private val repository: JellyfinRepository,
    ) : ViewModel() {
        /** Local user-data changes seen since this screen started collecting. */
        private val favoriteOverrides = MutableStateFlow<Map<String, UserData>>(emptyMap())

        /**
         * A `null` value means "fetched, the server has none"; a **missing key** means "not fetched
         * yet". Cleared on [MusicPlaybackState.Idle], so the next queue starts fresh.
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

        fun jumpTo(index: Int) = controller.jumpTo(index)

        fun removeAt(index: Int) = controller.removeAt(index)

        /**
         * Ends the session rather than pausing it, so the state goes [MusicPlaybackState.Idle] and
         * `NowPlayingScreen`'s idle `LaunchedEffect` pops the screen. Nothing here navigates.
         */
        fun stop() = controller.stop()

        fun moveItem(
            from: Int,
            to: Int,
        ) = controller.moveItem(from, to)

        fun toggleFavorite() {
            val item = uiState.value.track ?: return
            viewModelScope.launch { userDataRepository.setFavorite(item.id, !item.userData.isFavorite) }
        }

        /**
         * Keyed on the current item's **id**, never [MusicPlaybackState.Active.currentIndex]: a
         * `moveItem` reorder changes the index without changing the track, and must not re-fetch.
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
            /** Long enough that a rotation, or the queue sheet and back, does not re-subscribe. */
            const val STATE_STOP_TIMEOUT_MS = 5_000L
        }
    }
