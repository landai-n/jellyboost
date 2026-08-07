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
 * The app's view of [SyncPlayController], for modules that may not see it.
 *
 * A translation layer and nothing else: `:core:common`'s vocabulary in, the controller's intents
 * out (docs/notes/syncplay-m11-plan.md, key decision 2). It holds no state of its own, so there is
 * exactly one answer to "is there a group?" in the app and it is the controller's.
 *
 * Every method here is a *request to the server*, like every other in-group intent — nothing plays,
 * queues or moves on this device until the server broadcasts the result (key decision 11). That is
 * why "play this for the group" is not a variant of the ordinary Play: the ordinary Play opens a
 * player here, this one changes what everybody is watching and lets the group's own
 * `PlayQueueUpdate` bring the player up.
 */
@Singleton
internal class ControllerSyncPlaySession
    @Inject
    constructor(
        private val controller: SyncPlayController,
        @SyncPlayScope scope: CoroutineScope,
    ) : SyncPlaySession {
        /**
         * Eagerly shared, and deliberately so: callers read `.value` to decide whether to *offer* a
         * group action at all, and a lazily-started projection would answer `null` — "no group" — for
         * the first read after every subscriber went away. The cost is one collector on an in-memory
         * `StateFlow` for the life of the process.
         */
        override val activeGroup: StateFlow<SyncPlayGroupHandle?> =
            controller.state
                .map { state -> (state as? SyncPlayState.InGroup)?.group?.toHandle() }
                .distinctUntilChanged()
                .stateIn(scope, SharingStarted.Eagerly, initialValue = null)

        /**
         * All ids or none: a queue is positional, and dropping a malformed entry from the middle
         * would silently hand the group a playlist whose indices no longer mean what the caller
         * meant. An empty list is nothing to play, so it too goes nowhere.
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

/**
 * Ids cross the `:core:common` seam as strings and the protocol wants `UUID`s.
 *
 * A malformed one is dropped with a log rather than thrown: the only way to get here is a caller
 * passing something that is not a Jellyfin item id, and taking the app down for it would be worse
 * than a group action that quietly does nothing.
 */
private fun String.toItemIdOrNull(): UUID? =
    runCatching { UUID.fromString(this) }
        .getOrElse {
            Timber.w("Ignoring a SyncPlay request for a malformed item id: %s", this)
            null
        }
