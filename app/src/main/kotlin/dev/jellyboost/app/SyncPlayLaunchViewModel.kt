package dev.jellyboost.app

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jellyboost.player.syncplay.SyncPlayController
import dev.jellyboost.player.syncplay.SyncPlayLaunchRequest
import kotlinx.coroutines.flow.SharedFlow
import javax.inject.Inject

/**
 * Exposes [SyncPlayController.launchRequests] to [JellyfinNavHost], which is the app's one
 * collector for "the group moved on and no player is open".
 *
 * A ViewModel rather than an injection straight into the NavHost composable because Compose has no
 * `@Inject` of its own — `hiltViewModel()` is the seam every other screen already uses to reach a
 * `@Singleton` from composition, and the controller itself is `:player`-only (`SyncPlayController`
 * is not part of the `:core:common` cross-feature contract; nothing outside `:player` and `:app`
 * needs it, and `:app` already depends on `:player` directly).
 */
@HiltViewModel
class SyncPlayLaunchViewModel
    @Inject
    constructor(
        private val controller: SyncPlayController,
    ) : ViewModel() {
        val launchRequests: SharedFlow<SyncPlayLaunchRequest> = controller.launchRequests

        /**
         * Tells the controller its replayed request has been handled.
         *
         * The flow replays its last request so one raised with no Activity composed survives until
         * the next composition; consuming it is what stops a *handled* request from re-opening the
         * player on every later recomposition.
         */
        fun consume() {
            controller.consumeLaunchRequest()
        }
    }
