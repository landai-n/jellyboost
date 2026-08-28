package dev.jellyboost.data.downloads

import android.database.sqlite.SQLiteException
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.StartOnce
import dev.jellyboost.core.common.di.ApplicationScope
import dev.jellyboost.core.common.di.IoDispatcher
import dev.jellyboost.core.database.dao.DownloadDao
import dev.jellyboost.core.database.dao.ItemDao
import dev.jellyboost.core.database.entities.ItemEntity
import dev.jellyboost.core.database.entities.ItemSource
import dev.jellyboost.core.network.connectivity.ConnectionStateProvider
import dev.jellyboost.core.network.connectivity.onEachOnlineStretch
import dev.jellyboost.core.network.session.SessionGate
import dev.jellyboost.data.cache.ItemEntityMapper
import dev.jellyboost.data.downloads.engine.SubtitleSidecarTopUp
import dev.jellyboost.data.mapper.toItemType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.BaseItemDto
import timber.log.Timber
import java.time.Clock
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps every downloaded item's cached metadata **current with the server**, by re-fetching the full
 * DTO of the whole downloads table once per stretch of connectivity.
 *
 * **An ongoing sync, not a one-shot migration.** A download's metadata is written once, when it is
 * enqueued, and never again for as long as the file lives on the device — while the server's copy
 * keeps moving: a mis-scraped title fixed, artwork tags replaced, an episode renumbered. Without this
 * the offline library drifts away from the library it is a copy of. Rows whose blob is a *lean* browse
 * response rather than the rich `DOWNLOAD_FIELDS` one heal on the same pass; that is a side effect,
 * not the reason the class exists.
 *
 * Parents are refreshed too: the series and season rows behind a downloaded episode, and the album and
 * artist rows behind a track, are what the offline walk-up-to-the-show path reads and have no file of
 * their own to carry them.
 *
 * Each pass also hands its fresh DTOs to [SubtitleSidecarTopUp]: the *file plan* moves as well and an
 * optional file is allowed to fail outright, so a row on disk would otherwise stay permanently poorer
 * than the same item downloaded today.
 *
 * Runs on [onEachOnlineStretch], at most once per stretch — a flag set on attempt and cleared when the
 * connection drops — and is never retried within one. Rows keep their **original**
 * [ItemEntity.cachedAt]: that column is the offline "recently downloaded" ordering key, so stamping
 * `now` on every download at once would reshuffle the offline home on every sync. A failed batch is
 * logged and skipped, and an id the server no longer recognises leaves its local row exactly as it was.
 */
@Singleton
class DownloadedMetadataRefresher
    @Suppress(
        "LongParameterList",
    )
    @Inject
    internal constructor(
        private val connectionState: ConnectionStateProvider,
        private val sessionGate: SessionGate,
        private val api: DownloadApi,
        private val downloadDao: DownloadDao,
        private val itemDao: ItemDao,
        private val mapper: ItemEntityMapper,
        private val sidecars: SubtitleSidecarTopUp,
        private val clock: Clock,
        @ApplicationScope private val scope: CoroutineScope,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        private val startOnce = StartOnce()
        private val refreshedThisStretch = AtomicBoolean(false)

        /** Begins watching the connection. Idempotent — see [StartOnce]. */
        fun start() {
            startOnce {
                scope.launch {
                    connectionState.onEachOnlineStretch(
                        onOffline = { refreshedThisStretch.set(false) },
                        onOnline = { refresh() },
                    )
                }
            }
        }

        /**
         * Refreshes every downloaded item's stored metadata, once per stretch of connectivity. Never
         * throws: stale metadata is a background annoyance and the app is usable until the next pass.
         */
        suspend fun refresh() {
            if (!refreshedThisStretch.compareAndSet(false, true)) return
            withContext(ioDispatcher) { refreshAll() }
        }

        private suspend fun refreshAll() {
            val ids = downloadedItemIds()
            if (ids.isEmpty()) return

            // The connection state starts optimistically `ONLINE`, so the app-start pass can beat
            // `MainViewModel.restoreSession()`; without the gate the first request throws the SDK's
            // "Required value baseUrl is null".
            if (!sessionGate.ensureSession()) return

            val items = fetch(ids)
            if (items.isEmpty()) {
                Timber.w(
                    "Could not refresh the metadata of %d downloaded item(s); retrying on the next connection change",
                    ids.size,
                )
                return
            }

            val parents = fetch(parentIdsOf(items, known = items.mapTo(mutableSetOf()) { it.id }))
            store(items + parents)
            backfillGrouping(items)
            // Deliberately after the metadata write and never gating it: a sidecar that could not be
            // fetched must not cost the whole table its refresh.
            @Suppress("TooGenericExceptionCaught")
            try {
                sidecars.topUp(items)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Timber.w(error, "Could not top up the files of %d downloaded item(s)", items.size)
            }
        }

        /** Every id with a download row, or an empty list when the table cannot be read. */
        private suspend fun downloadedItemIds(): List<UUID> =
            try {
                downloadDao.allItemIds()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: SQLiteException) {
                Timber.w(error, "Could not list the downloaded items to refresh")
                emptyList()
            }

        /**
         * The parent ids of [items] that are not already being written. Mirrors
         * `DownloadEnqueuer.fetchParents`, and has to: those rows are the only way a downloaded episode
         * reaches its show offline, and they go stale exactly as the download itself does.
         */
        private fun parentIdsOf(
            items: List<BaseItemDto>,
            known: Set<UUID>,
        ): List<UUID> =
            items
                .flatMap { listOfNotNull(it.seriesId, it.seasonId, it.albumId, it.albumArtists?.firstOrNull()?.id) }
                .filterNot { it in known }
                .distinct()

        /**
         * The full DTOs of [ids], in batches: the ids travel in the query string of one `getItems`
         * request, and a few hundred would build a URL a reverse proxy rejects outright. A failed batch
         * costs only its own items.
         */
        private suspend fun fetch(ids: List<UUID>): List<BaseItemDto> {
            if (ids.isEmpty()) return emptyList()

            return ids.chunked(BATCH_SIZE).flatMap { chunk ->
                when (val result = api.getFullItems(chunk)) {
                    is AppResult.Success -> result.value
                    is AppResult.Failure -> {
                        Timber.w("Could not refresh a batch of %d downloaded item(s): %s", chunk.size, result.error)
                        emptyList()
                    }
                }
            }
        }

        /**
         * Writes straight to the DAO, not through `BrowseCacheWriter`: these DTOs came from
         * `DOWNLOAD_FIELDS`, and routing them through the writer would classify them as a browse read
         * and preserve the very blob this is here to replace.
         */
        private suspend fun store(dtos: List<BaseItemDto>) {
            val unique = dtos.distinctBy { it.id }
            if (unique.isEmpty()) return

            try {
                val now = clock.instant()
                val existing = itemDao.getCacheKeys(unique.map { it.id }).associateBy { it.id }
                val rows =
                    unique.map { dto ->
                        val row = mapper.toEntity(dto, ItemSource.DOWNLOAD, now)
                        // Keeping the original timestamp is what stops a bulk repair from
                        // reordering the offline "recently downloaded" rows.
                        existing[dto.id]?.let { row.copy(cachedAt = it.cachedAt) } ?: row
                    }

                itemDao.upsert(rows)
                Timber.i("Refreshed the cached metadata of %d downloaded item(s) and parent(s)", rows.size)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: SQLiteException) {
                Timber.w(error, "Could not store the refreshed metadata of %d item(s)", unique.size)
            }
        }

        /**
         * Fills in the grouping columns of rows written before they existed — including moving a track's
         * album out of `seriesName`, which held both facts. The `itemType IS NULL` guard lives in the
         * statement, so a row an enqueue has already stamped is never rewritten from this pass's fetch.
         *
         * The artist goes through a second statement as well: a row stamped between the grouping columns
         * and the artist column has an `itemType`, so the first write skips it and only the
         * `artistName IS NULL` guard can reach it.
         */
        private suspend fun backfillGrouping(dtos: List<BaseItemDto>) {
            try {
                dtos.forEach { dto ->
                    val type = dto.type.toItemType()
                    val artistName = dto.artistName()
                    downloadDao.backfillGrouping(
                        itemId = dto.id,
                        type = type,
                        seriesName = dto.seriesName?.takeIf { it.isNotBlank() },
                        albumName = dto.album?.takeIf { it.isNotBlank() },
                        artistName = artistName,
                        groupId = dto.seriesId ?: dto.albumId,
                    )
                    downloadDao.backfillArtist(itemId = dto.id, artistName = artistName)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: SQLiteException) {
                Timber.w(error, "Could not backfill the grouping of %d downloaded item(s)", dtos.size)
            }
        }

        /**
         * Mirrors `DownloadEnqueuer.toDownloadRow`, or a refresh would disagree with the enqueue —
         * blankness tested per operand included: a whitespace `albumArtist` that swallowed the
         * credited artists would be stamped `null`, and `backfillArtist`'s `artistName IS NULL` guard
         * would re-derive that same `null` on every pass thereafter.
         */
        private fun BaseItemDto.artistName(): String? =
            albumArtist?.takeIf { it.isNotBlank() }
                ?: artists?.joinToString(", ")?.takeIf { it.isNotBlank() }

        private companion object {
            /** Well under any URL limit at 36 characters an id. */
            const val BATCH_SIZE = 50
        }
    }
