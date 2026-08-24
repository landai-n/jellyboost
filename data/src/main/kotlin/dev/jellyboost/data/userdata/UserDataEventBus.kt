package dev.jellyboost.data.userdata

import dev.jellyboost.core.common.model.UserData
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

data class UserDataChange(
    val itemId: String,
    val userData: UserData,
)

/**
 * List ViewModels patch already-loaded items in place from this, so a watched toggle on a detail
 * page updates the rows behind it with **no refetch**.
 *
 * Hot and replay-free on purpose: a screen that was not listening picks the value up from its next
 * load, and replaying stale toggles into a freshly loaded screen would be a bug.
 */
@Singleton
class UserDataEventBus
    @Inject
    constructor() {
        private val mutableChanges =
            MutableSharedFlow<UserDataChange>(
                replay = 0,
                extraBufferCapacity = CHANGE_BUFFER,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )

        /** In the order they were written. */
        val changes: SharedFlow<UserDataChange> = mutableChanges.asSharedFlow()

        /**
         * Never blocks the write path; on overflow the oldest change is dropped and the affected
         * screen keeps the value it had until its next load.
         */
        fun emit(change: UserDataChange) {
            mutableChanges.tryEmit(change)
        }

        private companion object {
            /** Deep enough that a burst of toggles never blocks or drops in practice. */
            const val CHANGE_BUFFER = 64
        }
    }
