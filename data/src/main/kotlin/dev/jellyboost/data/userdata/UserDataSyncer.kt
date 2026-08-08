package dev.jellyboost.data.userdata

import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.di.IoDispatcher
import dev.jellyboost.core.database.dao.UserDataDao
import dev.jellyboost.core.database.entities.UserDataEntity
import dev.jellyboost.core.network.runCatchingApi
import dev.jellyboost.core.network.toSdkInstant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.UserItemDataDto
import timber.log.Timber
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** How a drain ended — what [UserDataSyncWorker] turns into a WorkManager result. */
internal enum class SyncOutcome {
    /** Nothing was waiting; the worker had nothing to do. */
    NOTHING_PENDING,

    /** Every pending row was reconciled and no longer carries the flag. */
    DRAINED,

    /** At least one row could not be reconciled because the server was not reachable. */
    RETRY,
}

/** What most-recent-wins decided for one row. Named so the tests can assert on the *rule*. */
internal enum class SyncResolution {
    /** The local row is newer; it was pushed to the server. */
    PUSHED,

    /** The server's copy is newer; it was adopted locally and published on the event bus. */
    ADOPTED,

    /** The server could not be reached; the row keeps its flag and the drain will be retried. */
    FAILED,

    /** The item is gone from the server; the row was dropped rather than retried forever. */
    ABANDONED,
}

/**
 * Reconciles the local `user_data` rows the server has never seen — **most-recent-wins**
 * (docs/PLAN.md, "Confirmed decisions": *"User-data sync conflict: most-recent-wins — compare
 * `lastPlayedDate` before pushing; keep newer position"*).
 *
 * Separate from [UserDataSyncWorker] so the rule can be unit tested on the JVM with a fixed clock:
 * WorkManager only starts on a device, and the decision matrix is the densest logic in the
 * milestone.
 *
 * ### The comparison
 * Two instants are compared, and they are deliberately *not* the same field on both sides:
 *
 * | side | value | why |
 * |---|---|---|
 * | server | `userData.lastPlayedDate` | the only timestamp the server exposes for this state |
 * | local | `UserDataEntity.updatedAt` | when *this device* last changed the row |
 *
 * `updatedAt` rather than the local `lastPlayedDate` because a favourite toggle never touches
 * `lastPlayedDate`: comparing those two would make every offline favourite lose to a film watched
 * last week. Both are read through `SdkDateTime`'s helpers, which is what makes the SDK's
 * zone-aware `LocalDateTime` round-trip to the instant it denotes.
 *
 * A server row with **no** `lastPlayedDate` (never played, or never touched) cannot be newer than
 * anything, so the local change wins. A tie goes to the server: an identical instant means the
 * server already holds this state, and adopting it is idempotent while pushing it is a wasted
 * round trip.
 *
 * ### What a push sends
 * The full desired state, through the same endpoints `UserDataRepositoryImpl` uses for the
 * equivalent single operation — [pushUserData], which is where those three requests and the order
 * they go in are defined for both callers. The worker cannot know *which* operation produced the
 * pending row — it may be several, batched by an offline session — so it asserts the whole row
 * rather than guessing.
 */
@Singleton
internal class UserDataSyncer
    @Inject
    constructor(
        private val userDataDao: UserDataDao,
        private val apiClient: ApiClient,
        private val eventBus: UserDataEventBus,
        private val clock: Clock,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        /**
         * Drains every row still marked `toBeSynced`.
         *
         * One row failing does not abandon the rest: each is independent, and a 404 on a deleted
         * item must not hold back a resume position the user is waiting to see on another client.
         */
        suspend fun sync(): SyncOutcome =
            withContext(ioDispatcher) {
                val pending = userDataDao.getPendingSync()
                if (pending.isEmpty()) return@withContext SyncOutcome.NOTHING_PENDING

                Timber.i("Reconciling %d pending user-data row(s)", pending.size)
                val resolutions = pending.map { reconcile(it) }

                when {
                    resolutions.any { it == SyncResolution.FAILED } -> SyncOutcome.RETRY
                    else -> SyncOutcome.DRAINED
                }
            }

        /** Applies most-recent-wins to one row. */
        private suspend fun reconcile(row: UserDataEntity): SyncResolution {
            val server =
                when (val fetched = fetchServerUserData(row)) {
                    is AppResult.Failure ->
                        return when (fetched.error) {
                            // The item no longer exists (or is no longer visible to this user), so
                            // the change has nowhere to go. Retrying it forever would keep the
                            // worker permanently dirty; dropping the flag is the honest outcome.
                            is AppError.NotFound -> abandon(row)
                            else -> fail(row, fetched.error)
                        }

                    is AppResult.Success -> fetched.value
                }

            return when {
                server != null && server.isNewerThan(row) -> adopt(row, server)
                else -> push(row)
            }
        }

        private suspend fun fetchServerUserData(row: UserDataEntity): AppResult<UserItemDataDto?> =
            runCatchingApi {
                apiClient.userLibraryApi
                    .getItem(itemId = row.itemId, userId = row.userId)
                    .content.userData
            }

        /**
         * `true` when the server's state is at least as fresh as this row's.
         *
         * A server row with no `lastPlayedDate` — never played, or never touched — is never newer:
         * the local row is then the only thing that knows anything happened. A tie counts as the
         * server being newer; see this class's documentation.
         */
        private fun UserItemDataDto.isNewerThan(row: UserDataEntity): Boolean {
            val serverInstant: Instant = lastPlayedDate?.toSdkInstant() ?: return false
            return !row.updatedAt.isAfter(serverInstant)
        }

        /**
         * The server is newer: take its value and tell the screens.
         *
         * The upsert replaces the row outright, including its flag, rather than going through
         * [UserDataDao.clearPendingSync]'s timestamp guard — the whole point is that the local value
         * loses. The window in which a local write could land between the fetch and this write is
         * one round trip wide and is closed by the next drain, exactly as it is in `BrowseCacheWriter`.
         */
        private suspend fun adopt(
            row: UserDataEntity,
            server: UserItemDataDto,
        ): SyncResolution {
            val adopted = server.toEntity(row.itemId, row.userId, clock.instant())
            userDataDao.upsert(adopted)
            // Same channel a local write publishes on, so a list showing the item repaints without
            // a refetch — the whole point of the event bus.
            eventBus.emit(UserDataChange(itemId = row.itemId.toString(), userData = adopted.toDomain()))
            Timber.i("Adopted the server's user data for %s (it was newer)", row.itemId)
            return SyncResolution.ADOPTED
        }

        /**
         * The local row is newer: assert it on the server, then clear the flag.
         *
         * The three requests and the order they go in are [pushUserData]'s — shared with
         * `UserDataRepositoryImpl`, which is where the second copy of them used to live (audit
         * DUP-5). The rule that decides the order (`markPlayedItem` clears the server's resume
         * position, so the position is asserted last) is documented there, once.
         */
        private suspend fun push(row: UserDataEntity): SyncResolution {
            val pushed = runCatchingApi { apiClient.pushUserData(row) }

            return when (pushed) {
                is AppResult.Failure ->
                    when (pushed.error) {
                        is AppError.NotFound -> abandon(row)
                        else -> fail(row, pushed.error)
                    }

                is AppResult.Success -> {
                    // Guarded on `updatedAt`: a local write that landed while this was in flight is
                    // newer than what the server just accepted and keeps its flag.
                    userDataDao.clearPendingSync(row.itemId, row.userId, row.updatedAt)
                    Timber.i("Pushed the local user data for %s (it was newer)", row.itemId)
                    SyncResolution.PUSHED
                }
            }
        }

        private fun fail(
            row: UserDataEntity,
            error: AppError,
        ): SyncResolution {
            Timber.w("User data for %s stays pending: %s", row.itemId, error)
            return SyncResolution.FAILED
        }

        private suspend fun abandon(row: UserDataEntity): SyncResolution {
            Timber.w("Item %s is gone from the server; dropping its pending user data", row.itemId)
            userDataDao.clearPendingSync(row.itemId, row.userId, row.updatedAt)
            return SyncResolution.ABANDONED
        }
    }
