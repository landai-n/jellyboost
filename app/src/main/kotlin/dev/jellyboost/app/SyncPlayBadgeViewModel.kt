package dev.jellyboost.app

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jellyboost.core.common.syncplay.SyncPlayGroupHandle
import dev.jellyboost.core.common.syncplay.SyncPlaySession
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** A thin view over the `@Singleton` [SyncPlaySession], the same shape as [ConnectionViewModel]. */
@HiltViewModel
class SyncPlayBadgeViewModel
    @Inject
    constructor(
        syncPlaySession: SyncPlaySession,
    ) : ViewModel() {
        /** Non-null while this device is in a SyncPlay group. */
        val activeGroup: StateFlow<SyncPlayGroupHandle?> = syncPlaySession.activeGroup
    }
