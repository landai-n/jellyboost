package dev.jellyfinnative.data.cache

import dev.jellyfinnative.core.database.dao.ItemDao
import dev.jellyfinnative.core.database.dao.LibraryViewDao
import dev.jellyfinnative.core.database.dao.UserDataDao
import dev.jellyfinnative.core.database.entities.ItemEntity
import dev.jellyfinnative.core.database.entities.ItemSource
import dev.jellyfinnative.core.network.SessionRepository
import dev.jellyfinnative.core.network.di.ApplicationScope
import dev.jellyfinnative.core.network.model.SessionState
import dev.jellyfinnative.data.userdata.toEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import timber.log.Timber
import java.time.Clock
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Write-through half of the browse cache: everything the user successfully reads from the server
 * is mirrored into Room with `source = BROWSE_CACHE` (docs/PLAN.md, "Data layer" →
 * `OnlineJellyfinRepository`).
 *
 * ### Rule one: a browse write must never downgrade a download
 * If a row already exists with [ItemSource.DOWNLOAD], the refreshed metadata is stored but the row
 * keeps its source *and* its original [ItemEntity.cachedAt]. Both halves matter:
 *
 * - keeping the source stops a casual scroll past a downloaded film from making it evictable and
 *   orphaning its files on disk;
 * - keeping `cachedAt` stops that same scroll from reshuffling the offline "recently downloaded"
 *   rows, which order by exactly that column.
 *
 * ### Rule two: a server read refreshes `user_data`, unless the row is pending
 * Every read the app makes carries `userData` (list requests set `enableUserData = true`; `getItem`
 * always returns it), and that block is the server's own truth about watched/favourite/resume. It
 * is adopted into the `user_data` table for every item **whose row is not `toBeSynced`**.
 *
 * Without this the local mirror only ever moved on local writes, so it went stale the moment the
 * same user touched an item from another client — and `UserDataRepositoryImpl.setPosition`, which
 * pushes the item's *full* desired state built from that row, then quietly wrote the stale state
 * back to the server. That is the "stale local user-data rows corrupt server state on playback"
 * bug in STATUS.md: an item unwatched in jellyfin-web came back watched after five seconds of
 * playback in the app.
 *
 * A `toBeSynced = true` row is left completely untouched — it is the only copy of a change the
 * server has not accepted yet, and reconciling the two versions is most-recent-wins in M8's sync
 * worker, not a cache write's business. The check-then-write is not atomic against a concurrent
 * local write; the window is one Room read wide, both writes are already fire-and-forget, and the
 * worst case is a refreshed row the next read corrects — never a lost server-side change, since a
 * local write that reached the server has already reached it.
 *
 * This stays a *read* concern on purpose: the plan's "local-first always" write path
 * (docs/PLAN.md, "Data layer") is untouched.
 *
 * ### Fire and forget
 * [cacheItems] and [cacheViews] hand the work to the application scope and return immediately: a
 * home screen must not wait on a disk write to draw, and a failed cache write is a logged warning,
 * never a failed read. Callers that need determinism (the unit tests) inject a `TestScope`.
 *
 * The merge rule lives here rather than in a `@Transaction` DAO method deliberately — this way it
 * is JVM-unit-testable instead of only exercisable on a device.
 */
@Singleton
class BrowseCacheWriter
    @Inject
    constructor(
        private val itemDao: ItemDao,
        private val libraryViewDao: LibraryViewDao,
        private val userDataDao: UserDataDao,
        private val sessionRepository: SessionRepository,
        private val mapper: ItemEntityMapper,
        private val clock: Clock,
        @ApplicationScope private val scope: CoroutineScope,
    ) {
        /** Mirrors a successful item read into the browse cache, without blocking the caller. */
        fun cacheItems(dtos: List<BaseItemDto>) {
            if (dtos.isEmpty()) return
            scope.launch { writeItems(dtos) }
        }

        /** Mirrors a successful `getUserViews` into the cached library list. */
        fun cacheViews(dtos: List<BaseItemDto>) {
            if (dtos.isEmpty()) return
            scope.launch { writeViews(dtos) }
        }

        /**
         * The actual item write. Suspending and public so tests can await it directly instead of
         * racing the scope.
         *
         * The metadata write and the user-data refresh are guarded separately: they are independent
         * mirrors of the same response, and a failure of one is no reason to abandon the other.
         */
        suspend fun writeItems(dtos: List<BaseItemDto>) {
            val now = clock.instant()
            writeItemRows(dtos, now)
            refreshUserData(dtos, now)
        }

        private suspend fun writeItemRows(
            dtos: List<BaseItemDto>,
            now: Instant,
        ) {
            @Suppress("TooGenericExceptionCaught")
            try {
                val existing = itemDao.getCacheKeys(dtos.map { it.id }).associateBy { it.id }

                val rows =
                    dtos.map { dto ->
                        val row = mapper.toEntity(dto, ItemSource.BROWSE_CACHE, now)
                        val previous = existing[dto.id]
                        if (previous?.source == ItemSource.DOWNLOAD) {
                            row.copy(source = ItemSource.DOWNLOAD, cachedAt = previous.cachedAt)
                        } else {
                            row
                        }
                    }

                itemDao.upsert(rows)
            } catch (error: Exception) {
                Timber.w(error, "Could not write %d items through to the browse cache", dtos.size)
            }
        }

        /**
         * Adopts the server's `userData` into the local mirror for every item that has one and is
         * not waiting to be pushed — rule two in this class's documentation.
         */
        private suspend fun refreshUserData(
            dtos: List<BaseItemDto>,
            now: Instant,
        ) {
            // No session means no `userId` to key rows on. Nothing to do, and nothing wrong:
            // reads during sign-out land here.
            val userId = currentUserId() ?: return
            // A response without user data (`enableUserData` off, or an endpoint that omits it) says
            // nothing about the user's state, so it must not be read as "unwatched, not favourite".
            val fromServer = dtos.mapNotNull { dto -> dto.userData?.let { dto.id to it } }
            if (fromServer.isEmpty()) return

            @Suppress("TooGenericExceptionCaught")
            try {
                val pending = userDataDao.getPendingSyncIds(fromServer.map { it.first }, userId).toSet()
                val rows =
                    fromServer
                        .filterNot { (itemId, _) -> itemId in pending }
                        .map { (itemId, userData) -> userData.toEntity(itemId, userId, now) }
                if (rows.isEmpty()) return

                userDataDao.upsertAll(rows)
            } catch (error: Exception) {
                Timber.w(error, "Could not refresh %d user-data rows from the server", fromServer.size)
            }
        }

        private fun currentUserId(): UUID? = (sessionRepository.sessionState.value as? SessionState.LoggedIn)?.userId

        /** The actual library-view write. */
        suspend fun writeViews(dtos: List<BaseItemDto>) {
            @Suppress("TooGenericExceptionCaught")
            try {
                val now = clock.instant()
                val rows = dtos.mapIndexedNotNull { index, dto -> mapper.toEntity(dto, index, now) }
                if (rows.isEmpty()) return

                libraryViewDao.upsert(rows)
                // Libraries the user lost access to must not linger in the offline list. Guarded on
                // a non-empty result above, so a filtered-to-nothing response cannot wipe the table.
                libraryViewDao.deleteExcept(rows.map { it.id })
            } catch (error: Exception) {
                Timber.w(error, "Could not write the library list through to the cache")
            }
        }
    }
