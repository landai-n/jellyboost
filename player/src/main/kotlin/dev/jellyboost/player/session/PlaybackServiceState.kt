package dev.jellyboost.player.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Written only by [PlaybackService], from `onCreate` and `onDestroy`. */
@Singleton
internal class PlaybackServiceState
    @Inject
    constructor() {
        private val _running = MutableStateFlow(false)

        /** `true` between [PlaybackService]'s `onCreate` and its `onDestroy`. */
        val running: StateFlow<Boolean> = _running.asStateFlow()

        fun setRunning(running: Boolean) {
            _running.value = running
        }
    }
