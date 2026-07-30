package dev.jellyfinnative.app

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jellyfinnative.player.syncplay.SyncPlayController
import dev.jellyfinnative.player.syncplay.SyncPlayLaunchRequest
import kotlinx.coroutines.flow.SharedFlow
import javax.inject.Inject

/**
 * Exposes [SyncPlayController.launchRequests] to [JellyfinNavHost], which is the app's one
 * collector for "the group moved on and no player is open" (docs/notes/syncplay-m11-plan.md, key
 * decision 5 and Phase 5).
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
        controller: SyncPlayController,
    ) : ViewModel() {
        val launchRequests: SharedFlow<SyncPlayLaunchRequest> = controller.launchRequests
    }
