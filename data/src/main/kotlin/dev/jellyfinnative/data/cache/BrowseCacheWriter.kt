package dev.jellyfinnative.data.cache

import dev.jellyfinnative.core.database.dao.ItemDao
import dev.jellyfinnative.core.database.dao.LibraryViewDao
import dev.jellyfinnative.core.database.entities.ItemEntity
import dev.jellyfinnative.core.database.entities.ItemSource
import dev.jellyfinnative.core.network.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import timber.log.Timber
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Write-through half of the browse cache: everything the user successfully reads from the server
 * is mirrored into Room with `source = BROWSE_CACHE` (docs/PLAN.md, "Data layer" →
 * `OnlineJellyfinRepository`).
 *
 * ### The one rule
 * A browse write must **never downgrade a download**. If a row already exists with
 * [ItemSource.DOWNLOAD], the refreshed metadata is stored but the row keeps its source *and* its
 * original [ItemEntity.cachedAt]. Both halves matter:
 *
 * - keeping the source stops a casual scroll past a downloaded film from making it evictable and
 *   orphaning its files on disk;
 * - keeping `cachedAt` stops that same scroll from reshuffling the offline "recently downloaded"
 *   rows, which order by exactly that column.
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
         */
        suspend fun writeItems(dtos: List<BaseItemDto>) {
            @Suppress("TooGenericExceptionCaught")
            try {
                val now = clock.instant()
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
