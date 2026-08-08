package dev.jellyboost.data.userdata

import android.database.sqlite.SQLiteException
import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.di.IoDispatcher
import dev.jellyboost.core.common.model.UserData
import dev.jellyboost.core.database.TransactionRunner
import dev.jellyboost.core.database.dao.UserDataDao
import dev.jellyboost.core.database.entities.UserDataEntity
import dev.jellyboost.core.network.SessionRepository
import dev.jellyboost.core.network.connectivity.ConnectionStateProvider
import dev.jellyboost.core.network.model.SessionState
import dev.jellyboost.core.network.runCatchingApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import timber.log.Timber
import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local-first [UserDataRepository] (docs/PLAN.md, "Data layer").
 *
 * Every write follows the same four steps, in this order:
 *
 * 1. upsert the Room row with `toBeSynced = true` and a fresh `updatedAt`;
 * 2. publish the new value on [UserDataEventBus] — this is what patches the screens;
 * 3. push to the server;
 * 4. on success clear `toBeSynced`; on failure leave it set and enqueue [UserDataSyncWorker].
 *
 * Steps 1 and 2 are the contract. Steps 3 and 4 are best effort: a failing push is a logged
 * warning and a scheduled retry, never a failed operation, because the change is already durable
 * and already on screen. While offline they are skipped altogether — see [pushToServer].
 */
@Singleton
internal class UserDataRepositoryImpl
    @Suppress(
        // Nine DI collaborators: one optimistic write spans the DAO, the transaction runner that makes its
        // read-modify-write atomic, the API, the event bus that fans the change out to open screens, and the retry
        // scheduler.
        "LongParameterList",
    )
    @Inject
    constructor(
        private val userDataDao: UserDataDao,
        private val apiClient: ApiClient,
        private val sessionRepository: SessionRepository,
        private val eventBus: UserDataEventBus,
        private val syncScheduler: UserDataSyncScheduler,
        private val connectionState: ConnectionStateProvider,
        private val transactionRunner: TransactionRunner,
        private val clock: Clock,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : UserDataRepository {
        override val changes: SharedFlow<UserDataChange> get() = eventBus.changes

        override suspend fun setPlayed(
            itemId: String,
            played: Boolean,
        ): AppResult<UserData> =
            write(
                itemId = itemId,
                edit = { current ->
                    current.copy(
                        played = played,
                        // The server clears the resume position when an item is marked watched;
                        // not mirroring that would leave a progress bar on a watched card until
                        // the next sync overwrote it.
                        playbackPositionTicks = if (played) 0L else current.playbackPositionTicks,
                        lastPlayedDate = if (played) clock.instant() else current.lastPlayedDate,
                    )
                },
                // No position re-assertion here even though `markPlayedItem` clears the server's:
                // the edit above mirrors that locally, so the two already agree. The rule and the
                // reasoning live in one place — see [pushUserData].
                push = { row -> apiClient.pushPlayedState(row) },
            )

        override suspend fun setFavorite(
            itemId: String,
            favorite: Boolean,
        ): AppResult<UserData> =
            write(
                itemId = itemId,
                edit = { current -> current.copy(isFavorite = favorite) },
                push = { row -> apiClient.pushFavoriteState(row) },
            )

        override suspend fun setPosition(
            itemId: String,
            positionTicks: Long,
        ): AppResult<UserData> =
            write(
                itemId = itemId,
                edit = { current ->
                    current.copy(
                        playbackPositionTicks = positionTicks.coerceAtLeast(0L),
                        lastPlayedDate = clock.instant(),
                    )
                },
                push = { row -> apiClient.pushFullState(row) },
            )

        /**
         * The local-first write path shared by all three operations.
         *
         * @param edit applies the operation to the current row (or to a fresh, empty one).
         * @param push delivers the resulting row to the server.
         */
        @Suppress(
            // The optimistic-write path exits early on no session, no change, and offline — three real states.
            "ReturnCount",
        )
        private suspend fun write(
            itemId: String,
            edit: (UserDataEntity) -> UserDataEntity,
            push: suspend (UserDataEntity) -> Unit,
        ): AppResult<UserData> {
            val userId = currentUserId() ?: return AppResult.Failure(AppError.Unauthorized())
            val id = itemId.toUuidOrNull() ?: return AppResult.Failure(AppError.NotFound(itemId))

            val stored =
                when (val result = storeLocally(id, userId, edit)) {
                    is AppResult.Failure -> return result
                    is AppResult.Success -> result.value
                }

            // Publish before touching the network: this is the whole point of local-first.
            eventBus.emit(UserDataChange(itemId = itemId, userData = stored.toDomain()))

            pushToServer(stored, push)

            return AppResult.Success(stored.toDomain())
        }

        /**
         * Step 1 of the write: read the row, apply [edit] to it, store the result — **as one
         * transaction**.
         *
         * Read-modify-write over a row two independent callers touch, and until audit CORR-2 the
         * three steps were three separate DAO calls. The interleaving is not exotic: during
         * playback `PlaybackReporter` calls [setPosition] every five seconds, and each of those
         * reads the whole row and writes the whole row back. A "mark watched" from another screen
         * landing between a tick's read and its write was overwritten by the stale snapshot — the
         * watched tick flicked back off locally, *and* the tick then pushed `played = false` to the
         * server, so the mark was lost on both sides.
         *
         * The [TransactionRunner] seam is the same one the browse cache's merge uses (audit H3):
         * the decision stays a plain Kotlin lambda the tests can drive, and the read that feeds it
         * plus the write that follows it cannot be stepped into.
         */
        private suspend fun storeLocally(
            id: UUID,
            userId: UUID,
            edit: (UserDataEntity) -> UserDataEntity,
        ): AppResult<UserDataEntity> =
            withContext(ioDispatcher) {
                try {
                    val next =
                        transactionRunner.inTransaction {
                            val current =
                                userDataDao.getUserData(id, userId)
                                    ?: UserDataEntity(itemId = id, userId = userId, updatedAt = clock.instant())
                            edit(current).copy(toBeSynced = true, updatedAt = clock.instant()).also {
                                userDataDao.upsert(it)
                            }
                        }
                    AppResult.Success(next)
                } catch (cancellation: CancellationException) {
                    // A `withContext` that was cancelled has not written anything; reporting it as
                    // `AppError.Storage` would tell the caller the disk failed and, worse, swallow
                    // the cancellation this coroutine owes its parent (the audit's ARCH-08 rule).
                    throw cancellation
                } catch (error: SQLiteException) {
                    // Narrowed to Room's own failure: everything else in the block is a read, a
                    // `copy` and a lambda the caller supplied, so a different exception is a bug
                    // rather than a full disk and should not be dressed up as one.
                    Timber.e(error, "Could not write user data for %s", id)
                    AppResult.Failure(AppError.Storage(error))
                }
            }

        /**
         * Step 3 of the write, guarded on connectivity.
         *
         * While offline the push is not attempted at all. It could only fail, and during playback
         * `PlaybackReporter` calls [setPosition] every five seconds, so each tick used to cost a
         * doomed request and a warning stack (STATUS.md, "Known issues"). Nothing is lost by
         * skipping it: [storeLocally] has already set `toBeSynced = true`, and [UserDataSyncTrigger]
         * drains every pending row on the next `OFFLINE → ONLINE` edge and at app start — which is
         * also why the offline path does not bother scheduling the worker per write.
         *
         * When online this is exactly the pre-existing behaviour: push, clear the flag on success,
         * warn and schedule a retry on failure.
         */
        private suspend fun pushToServer(
            row: UserDataEntity,
            push: suspend (UserDataEntity) -> Unit,
        ) {
            if (!connectionState.state.value.isOnline) {
                Timber.d("User data for %s stays pending (offline, not pushing)", row.itemId)
                return
            }

            val pushed = withContext(ioDispatcher) { runCatchingApi { push(row) } }

            when (pushed) {
                is AppResult.Success -> clearPendingFlag(row)
                is AppResult.Failure -> {
                    Timber.w("User data for %s stays pending: %s", row.itemId, pushed.error)
                    syncScheduler.enqueue()
                }
            }
        }

        private suspend fun clearPendingFlag(row: UserDataEntity) {
            withContext(ioDispatcher) {
                try {
                    // Guarded on `updatedAt`: if the user toggled again while the push was in
                    // flight, the newer row keeps its flag instead of being declared synced.
                    userDataDao.clearPendingSync(row.itemId, row.userId, row.updatedAt)
                } catch (cancellation: CancellationException) {
                    // Best effort, but not at the price of a swallowed cancellation: the row simply
                    // stays pending and `UserDataSyncTrigger` drains it later (ARCH-08).
                    throw cancellation
                } catch (error: SQLiteException) {
                    Timber.w(error, "Could not clear the pending flag for %s", row.itemId)
                }
            }
        }

        private fun currentUserId(): UUID? = (sessionRepository.sessionState.value as? SessionState.LoggedIn)?.userId
    }

private fun String.toUuidOrNull(): UUID? = runCatching { UUID.fromString(this) }.getOrNull()
