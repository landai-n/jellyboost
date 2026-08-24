package dev.jellyboost.player.syncplay

import dev.jellyboost.core.common.syncplay.SyncPlayGroupHandle
import dev.jellyboost.core.common.syncplay.SyncPlaySession
import dev.jellyboost.player.syncplay.di.SyncPlayScope
import dev.jellyboost.player.syncplay.model.SyncPlayGroupSummary
import dev.jellyboost.player.syncplay.model.SyncPlayQueueMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Translation only — holds no state, so the controller stays the single answer to "is there a group?".
 *
 * Every method is a *request to the server*: nothing plays, queues or moves on this device until the
 * server broadcasts the result, which the group's own `PlayQueueUpdate` then acts on.
 */
@Singleton
internal class ControllerSyncPlaySession
    @Inject
    constructor(
        private val controller: SyncPlayController,
        @SyncPlayScope scope: CoroutineScope,
    ) : SyncPlaySession {
        /**
         * `Eagerly`, not lazily: callers read `.value` to decide whether to offer a group action, and
         * a lazily-started projection answers `null` ("no group") on the first read after the last
         * subscriber left.
         */
        override val activeGroup: StateFlow<SyncPlayGroupHandle?> =
            controller.state
                .map { state -> (state as? SyncPlayState.InGroup)?.group?.toHandle() }
                .distinctUntilChanged()
                .stateIn(scope, SharingStarted.Eagerly, initialValue = null)

        /**
         * All ids or none: a queue is positional, so dropping a malformed entry from the middle would
         * hand the group a playlist whose indices no longer mean what the caller meant.
         */
        override suspend fun playForGroup(
            itemIds: List<String>,
            startPositionTicks: Long,
        ) {
            if (itemIds.isEmpty()) return
            val ids = itemIds.map { it.toItemIdOrNull() ?: return }
            controller.setNewQueue(
                itemIds = ids,
                playingItemPosition = 0,
                startPositionTicks = startPositionTicks,
            )
        }

        override suspend fun addToGroupQueue(
            itemId: String,
            next: Boolean,
        ) {
            val id = itemId.toItemIdOrNull() ?: return
            controller.addToQueue(
                itemIds = listOf(id),
                mode = if (next) SyncPlayQueueMode.QueueNext else SyncPlayQueueMode.Queue,
            )
        }
    }

private fun SyncPlayGroupSummary.toHandle() =
    SyncPlayGroupHandle(
        id = id.toString(),
        name = name,
        participantCount = participants.size,
    )

/** Malformed ids are logged and dropped, not thrown: a bad caller must not take the app down. */
private fun String.toItemIdOrNull(): UUID? =
    runCatching { UUID.fromString(this) }
        .getOrElse {
            Timber.w("Ignoring a SyncPlay request for a malformed item id: %s", this)
            null
        }
