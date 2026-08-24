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

/** What [UserDataSyncWorker] turns into a WorkManager result. */
internal enum class SyncOutcome {
    NOTHING_PENDING,

    DRAINED,

    RETRY,
}

/** What most-recent-wins decided for one row. Named so the tests can assert on the *rule*. */
internal enum class SyncResolution {
    PUSHED,

    ADOPTED,

    FAILED,

    /** The item is gone from the server; the row was dropped rather than retried forever. */
    ABANDONED,
}

/**
 * Most-recent-wins reconciliation of the `user_data` rows the server has never seen. Separate from
 * [UserDataSyncWorker] so the rule is JVM-testable with a fixed clock.
 *
 * The two compared instants are deliberately **different fields**: the server's `lastPlayedDate`
 * (its only timestamp for this state) against the local `updatedAt`, because a favourite toggle
 * never touches `lastPlayedDate` and comparing those two would make every offline favourite lose to
 * a film watched last week. A server row with no `lastPlayedDate` is never newer; a tie goes to the
 * server, since adopting is idempotent and pushing is a wasted round trip.
 *
 * A push asserts the **whole** row: an offline session may have batched several operations into it,
 * so the worker cannot know which one produced the pending flag.
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
        /** One row failing must not abandon the rest — a 404 on a deleted item holds back nothing. */
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

        private suspend fun reconcile(row: UserDataEntity): SyncResolution {
            val server =
                when (val fetched = fetchServerUserData(row)) {
                    is AppResult.Failure ->
                        return when (fetched.error) {
                            // The change has nowhere to go; retrying forever would keep the worker
                            // permanently dirty.
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

        /** "At least as fresh": a tie counts as the server being newer. */
        private fun UserItemDataDto.isNewerThan(row: UserDataEntity): Boolean {
            val serverInstant: Instant = lastPlayedDate?.toSdkInstant() ?: return false
            return !row.updatedAt.isAfter(serverInstant)
        }

        /**
         * The upsert replaces the row outright, flag included, rather than going through
         * [UserDataDao.clearPendingSync]'s timestamp guard: the local value is meant to lose. The
         * one-round-trip window for a local write is closed by the next drain.
         */
        private suspend fun adopt(
            row: UserDataEntity,
            server: UserItemDataDto,
        ): SyncResolution {
            val adopted = server.toEntity(row.itemId, row.userId, clock.instant())
            userDataDao.upsert(adopted)
            eventBus.emit(UserDataChange(itemId = row.itemId.toString(), userData = adopted.toDomain()))
            Timber.i("Adopted the server's user data for %s (it was newer)", row.itemId)
            return SyncResolution.ADOPTED
        }

        /** Request order matters and is defined once, in [pushUserData]. */
        private suspend fun push(row: UserDataEntity): SyncResolution {
            val pushed = runCatchingApi { apiClient.pushUserData(row) }

            return when (pushed) {
                is AppResult.Failure ->
                    when (pushed.error) {
                        is AppError.NotFound -> abandon(row)
                        else -> fail(row, pushed.error)
                    }

                is AppResult.Success -> {
                    // Guarded on `updatedAt`: a local write that landed mid-flight keeps its flag.
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
