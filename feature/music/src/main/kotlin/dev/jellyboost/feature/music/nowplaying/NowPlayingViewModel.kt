package dev.jellyboost.feature.music.nowplaying

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jellyboost.core.common.model.UserData
import dev.jellyboost.core.common.music.MusicController
import dev.jellyboost.data.userdata.UserDataRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * State holder for [dev.jellyboost.feature.music.nowplaying.NowPlayingScreen] and its [QueueSheet]
 * (M13 Phase 4, docs/notes/music-m13-plan.md).
 *
 * Unlike every other screen in `:feature:music`, this one injects [MusicController] directly rather
 * than going through `:app`'s `MusicPlaybackViewModel` — the arrangement that class's own KDoc
 * documents as existing so that *browse* screens do not each repeat the same two lines. NowPlaying
 * is not a browse screen; it *is* the queue's own view, so reading [MusicController.state] here is
 * the direct route the plan calls for (docs/notes/music-m13-plan.md, key decision 2: the interface
 * lives in `:core:common` precisely so any feature may inject it without ever depending on
 * `:player`).
 *
 * Every transport verb is a thin pass-through — the controller already validates and no-ops the
 * ones that do not apply (e.g. [MusicController.togglePlayPause] while idle) — so this class adds
 * nothing beyond the one thing the controller does not know how to do itself: favouriting.
 */
@HiltViewModel
class NowPlayingViewModel
    @Inject
    constructor(
        private val controller: MusicController,
        private val userDataRepository: UserDataRepository,
    ) : ViewModel() {
        /** Local user-data changes seen since this screen started collecting; see [toNowPlayingUiState]. */
        private val favoriteOverrides = MutableStateFlow<Map<String, UserData>>(emptyMap())

        val uiState: StateFlow<NowPlayingUiState> =
            combine(controller.state, favoriteOverrides) { state, overrides -> state.toNowPlayingUiState(overrides) }
                .stateIn(
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

        private companion object {
            /**
             * How long [uiState] keeps collecting [MusicController.state] after the screen stops
             * observing it — long enough that a configuration change (rotation, into the queue
             * sheet and back) does not drop and immediately re-subscribe.
             */
            const val STATE_STOP_TIMEOUT_MS = 5_000L
        }
    }
