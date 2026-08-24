package dev.jellyboost.app

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jellyboost.player.syncplay.SyncPlayController
import dev.jellyboost.player.syncplay.SyncPlayLaunchRequest
import kotlinx.coroutines.flow.SharedFlow
import javax.inject.Inject

/**
 * Exposes [SyncPlayController.launchRequests] to [JellyfinNavHost], the app's one collector for "the
 * group moved on and no player is open". A ViewModel because `hiltViewModel()` is the only seam
 * composition has for reaching a `@Singleton`.
 */
@HiltViewModel
class SyncPlayLaunchViewModel
    @Inject
    constructor(
        private val controller: SyncPlayController,
    ) : ViewModel() {
        val launchRequests: SharedFlow<SyncPlayLaunchRequest> = controller.launchRequests

        /**
         * The flow replays its last request so one raised with no Activity composed survives; without
         * this a *handled* request re-opens the player on every later recomposition.
         */
        fun consume() {
            controller.consumeLaunchRequest()
        }
    }
