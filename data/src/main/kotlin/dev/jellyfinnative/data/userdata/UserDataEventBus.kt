package dev.jellyfinnative.data.userdata

import dev.jellyfinnative.core.common.model.UserData
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/** One item's user data changed locally. */
data class UserDataChange(
    val itemId: String,
    val userData: UserData,
)

/**
 * App-wide broadcast of local user-data changes.
 *
 * Every list ViewModel collects this and patches its already-loaded items in place, so marking an
 * episode watched on a detail page updates the home rows behind it with **no refetch** — the
 * Swiftfin pattern the plan adopts (docs/PLAN.md, "Data layer" → `UserDataRepositoryImpl`).
 *
 * The flow is hot and replay-free on purpose: a screen that was not listening at the time will
 * pick the value up from its next load, and replaying stale toggles into a freshly loaded screen
 * would be a bug, not a feature.
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

        /** Local user-data changes, in the order they were written. */
        val changes: SharedFlow<UserDataChange> = mutableChanges.asSharedFlow()

        /**
         * Publishes [change] without suspending.
         *
         * The buffer means this never blocks the write path; on the (implausible) overflow the
         * oldest pending change is dropped, and the affected screen simply keeps the value it had
         * until its next load.
         */
        fun emit(change: UserDataChange) {
            mutableChanges.tryEmit(change)
        }

        private companion object {
            /** Deep enough that a burst of toggles never blocks or drops in practice. */
            const val CHANGE_BUFFER = 64
        }
    }
