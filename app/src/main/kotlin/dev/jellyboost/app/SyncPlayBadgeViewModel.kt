package dev.jellyboost.app

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jellyboost.core.common.syncplay.SyncPlayGroupHandle
import dev.jellyboost.core.common.syncplay.SyncPlaySession
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Whether the home top bar's Groups action should carry its active-group badge.
 *
 * A thin view over the `@Singleton` [SyncPlaySession] (bound in `:player`'s `SyncPlayModule` to the
 * `:core:common` contract) — exactly the same shape as [ConnectionViewModel] over
 * `ConnectionStateProvider`. `:app` already depends on `:player`
 * directly (it resolves `PlayerScreen`'s own ViewModel here), so the binding needs no cross-module
 * Hilt-graph reasoning beyond what already holds for the player screen itself.
 */
@HiltViewModel
class SyncPlayBadgeViewModel
    @Inject
    constructor(
        syncPlaySession: SyncPlaySession,
    ) : ViewModel() {
        /** Non-null while this device is in a SyncPlay group — what lights the badge. */
        val activeGroup: StateFlow<SyncPlayGroupHandle?> = syncPlaySession.activeGroup
    }
