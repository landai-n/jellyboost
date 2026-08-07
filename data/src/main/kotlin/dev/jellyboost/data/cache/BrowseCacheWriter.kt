package dev.jellyboost.data.cache

import android.database.sqlite.SQLiteException
import dev.jellyboost.core.common.di.ApplicationScope
import dev.jellyboost.core.database.TransactionRunner
import dev.jellyboost.core.database.dao.ItemDao
import dev.jellyboost.core.database.dao.LibraryViewDao
import dev.jellyboost.core.database.dao.UserDataDao
import dev.jellyboost.core.database.entities.ItemCacheKey
import dev.jellyboost.core.database.entities.ItemEntity
import dev.jellyboost.core.database.entities.ItemSource
import dev.jellyboost.core.network.SessionRepository
import dev.jellyboost.core.network.model.SessionState
import dev.jellyboost.data.userdata.toEntity
import kotlinx.coroutines.CancellationException
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
 * keeps its source and its original [ItemEntity.cachedAt]:
 *
 * - keeping the source stops a casual scroll past a downloaded film from making it evictable and
 *   orphaning its files on disk;
 * - keeping `cachedAt` stops that same scroll from reshuffling the offline "recently downloaded"
 *   rows, which order by exactly that column.
 *
 * ### Rule one-and-a-half: only a *full* read may replace a download's stored blob
 * The [ItemEntity.dto] blob is the offline detail screen's only source of overview, genres, cast
 * and taglines, so a **lean** write must leave it alone. A browse list request only asks the server
 * for the fields the list needs (`OnlineJellyfinRepository`'s list calls request just
 * `PRIMARY_IMAGE_ASPECT_RATIO`), so its DTO is lean by construction — writing it straight into
 * [ItemEntity.dto] would replace the rich blob `DownloadEnqueuer` stored at download time with one
 * missing everything read back out of it. This was exactly that bug (docs/POLISH.md): browsing
 * online wiped the description of a film downloaded for offline viewing.
 *
 * Preserving the blob **unconditionally** was the over-correction, and it was its own bug: it also
 * discarded the one response that is strictly better than what is stored. `getItem`
 * (`/Users/{userId}/Items/{itemId}`) is the endpoint that always serialises the *complete* field
 * set, and a row whose blob an earlier build already gutted could therefore never be repaired —
 * opening the item online refetched everything and then threw it away, leaving a bare offline
 * detail page forever.
 *
 * So the distinction is made **explicitly, by the caller**, not sniffed out of the DTO's shape: the
 * `full` flag on [cacheItems]/[writeItems] is `true` only where the response is known to carry the
 * complete field set. A full write replaces the blob — repairing a gutted row on the way — while a
 * lean write preserves it. Sniffing (does this DTO have an overview? does it have media sources?)
 * would be guesswork about a server response, and it would get an item that genuinely has no
 * overview wrong in the direction that loses data.
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
 * ### The merge decides in Kotlin, but reads and writes atomically
 * The merge *rule* lives here rather than inside a `@Transaction` DAO method deliberately — this way
 * it is JVM-unit-testable instead of only exercisable on a device. What that cost, until audit
 * HYG-3 found it, was atomicity: [writeItemRows] read the existing rows' sources, spent thirty lines
 * deciding, and only then upserted, so `DownloadEnqueuer` — which upserts `DOWNLOAD` rows straight
 * to the DAO — could turn one of those rows into a download in between. The stale snapshot's
 * else-branch then wrote `BROWSE_CACHE` back over it with the lean list blob: rule one broken by the
 * very class that exists to enforce it, and (now that eviction is wired) a downloaded film made
 * evictable while its files sit on disk. The window is real and reachable — open a season page and
 * tap Download while the list write is still in flight.
 *
 * Both properties are kept: the decision is still the pure, testable [mergeRows], and the read that
 * feeds it plus the write that follows it run inside one [TransactionRunner.inTransaction] block, so
 * nothing can change underneath them.
 */
@Singleton
class BrowseCacheWriter
    @Suppress(
        // Eight DI collaborators: the write-through merge needs three DAOs plus the transaction runner that makes the
        // read-merge-write atomic (audit H3).
        "LongParameterList",
    )
    @Inject
    constructor(
        private val itemDao: ItemDao,
        private val libraryViewDao: LibraryViewDao,
        private val userDataDao: UserDataDao,
        private val sessionRepository: SessionRepository,
        private val mapper: ItemEntityMapper,
        private val clock: Clock,
        private val transactionRunner: TransactionRunner,
        @ApplicationScope private val scope: CoroutineScope,
    ) {
        /**
         * Mirrors a successful item read into the browse cache, without blocking the caller.
         *
         * @param full `true` only when [dtos] came from a request that returns the **complete**
         *   field set — in practice `getItem`, and nothing else. It is what allows the write to
         *   replace a downloaded item's stored blob (and so repair one an older build gutted); a
         *   lean read must leave that blob exactly where it is. The default is the safe answer:
         *   preserve.
         */
        fun cacheItems(
            dtos: List<BaseItemDto>,
            full: Boolean = false,
        ) {
            if (dtos.isEmpty()) return
            scope.launch { writeItems(dtos, full) }
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
        suspend fun writeItems(
            dtos: List<BaseItemDto>,
            full: Boolean = false,
        ) {
            val now = clock.instant()
            writeItemRows(dtos, now, full)
            refreshUserData(dtos, now)
        }

        /**
         * Reads what is already stored, merges the response against it, and writes the result —
         * **as one transaction**, so no concurrent `DOWNLOAD` upsert can land between the read the
         * decision is made from and the write that acts on it (audit HYG-3).
         */
        private suspend fun writeItemRows(
            dtos: List<BaseItemDto>,
            now: Instant,
            full: Boolean,
        ) {
            try {
                transactionRunner.inTransaction {
                    val existing = itemDao.getCacheKeys(dtos.map { it.id }).associateBy { it.id }

                    // The blob is only fetched for rows that are actually downloads *and* only when
                    // the incoming DTO is lean enough to need protecting — everything else is happy
                    // to be replaced wholesale, and reading a multi-kilobyte blob for a page of
                    // ordinary browse-cache rows would be pure waste (the reason `getCacheKeys`
                    // excludes it in the first place). A `full` write skips the read entirely: it is
                    // going to overwrite every blob it fetched.
                    val downloadIds =
                        if (full) {
                            emptyList()
                        } else {
                            existing.values.filter { it.source == ItemSource.DOWNLOAD }.map { it.id }
                        }
                    val richBlobs =
                        if (downloadIds.isEmpty()) {
                            emptyMap()
                        } else {
                            itemDao.getItems(downloadIds).associate { it.id to it.dto }
                        }

                    itemDao.upsert(mergeRows(dtos, existing, richBlobs, now))
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: SQLiteException) {
                Timber.w(error, "Could not write %d items through to the browse cache", dtos.size)
            }
        }

        /**
         * The merge rule itself, as a pure function of what came back and what is already stored —
         * no database access, so it stays a JVM unit test rather than a device one.
         *
         * @param existing what [ItemDao.getCacheKeys] said about these ids, keyed by id. A missing
         *   entry is a row that does not exist yet.
         * @param richBlobs the stored `dto` blobs worth preserving, keyed by id — empty on a full
         *   write, so the complete response wins and a previously gutted row is repaired.
         */
        internal fun mergeRows(
            dtos: List<BaseItemDto>,
            existing: Map<UUID, ItemCacheKey>,
            richBlobs: Map<UUID, String>,
            now: Instant,
        ): List<ItemEntity> =
            dtos.map { dto ->
                val row = mapper.toEntity(dto, ItemSource.BROWSE_CACHE, now)
                val previous = existing[dto.id]
                if (previous?.source == ItemSource.DOWNLOAD) {
                    row.copy(
                        source = ItemSource.DOWNLOAD,
                        cachedAt = previous.cachedAt,
                        dto = richBlobs[dto.id] ?: row.dto,
                    )
                } else {
                    row
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

            try {
                val pending = userDataDao.getPendingSyncIds(fromServer.map { it.first }, userId).toSet()
                val rows =
                    fromServer
                        .filterNot { (itemId, _) -> itemId in pending }
                        .map { (itemId, userData) -> userData.toEntity(itemId, userId, now) }
                if (rows.isEmpty()) return

                userDataDao.upsertAll(rows)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: SQLiteException) {
                Timber.w(error, "Could not refresh %d user-data rows from the server", fromServer.size)
            }
        }

        private fun currentUserId(): UUID? = (sessionRepository.sessionState.value as? SessionState.LoggedIn)?.userId

        /** The actual library-view write. */
        suspend fun writeViews(dtos: List<BaseItemDto>) {
            try {
                val now = clock.instant()
                val rows = dtos.mapIndexedNotNull { index, dto -> mapper.toEntity(dto, index, now) }
                if (rows.isEmpty()) return

                libraryViewDao.upsert(rows)
                // Libraries the user lost access to must not linger in the offline list. Guarded on
                // a non-empty result above, so a filtered-to-nothing response cannot wipe the table.
                libraryViewDao.deleteExcept(rows.map { it.id })
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: SQLiteException) {
                Timber.w(error, "Could not write the library list through to the cache")
            }
        }
    }
