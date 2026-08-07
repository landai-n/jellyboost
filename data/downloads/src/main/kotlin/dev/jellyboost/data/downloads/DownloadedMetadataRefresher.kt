package dev.jellyboost.data.downloads

import android.database.sqlite.SQLiteException
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.di.ApplicationScope
import dev.jellyboost.core.common.di.IoDispatcher
import dev.jellyboost.core.database.dao.DownloadDao
import dev.jellyboost.core.database.dao.ItemDao
import dev.jellyboost.core.database.entities.ItemEntity
import dev.jellyboost.core.database.entities.ItemSource
import dev.jellyboost.core.network.connectivity.ConnectionStateProvider
import dev.jellyboost.core.network.session.SessionGate
import dev.jellyboost.data.cache.ItemEntityMapper
import dev.jellyboost.data.downloads.engine.SubtitleSidecarTopUp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
 * ### This is an ongoing sync, not a one-shot migration
 * Read that first, because the class was born out of a bug and it would be easy for a later reader
 * to conclude the bug is fixed and the class is dead code. It is not. A download's copy of its
 * metadata is written once, when it is enqueued, and then never again for as long as the file lives
 * on the device — while the server's copy keeps moving. Someone fixes a mis-scraped title, an
 * identify/refresh pass replaces the artwork tags, an overview or a genre list is corrected, an
 * episode is renumbered. Without this class every one of those edits is invisible offline for the
 * lifetime of the download, and the offline library slowly drifts away from the library it is a copy
 * of. That is what it is for, permanently, on a device that never had a bug at all.
 *
 * ### The historical repair, which is simply the first thing it does
 * `DownloadEnqueuer` stores each downloaded item's complete `DOWNLOAD_FIELDS` response in
 * [ItemEntity.dto] — the offline detail page's only source of overview, genres, cast, taglines,
 * chapters and media streams. An earlier build let a **lean** browse-list write replace that blob,
 * so scrolling past a downloaded film online reduced its offline detail page to a title and a
 * poster. `BrowseCacheWriter` no longer allows it, and `OnlineJellyfinRepository.getItem` (the one
 * caller that passes `full = true`) repairs a gutted row on the way past — but only for the item the
 * user happens to open while online. On a device that upgraded across the bug, every other damaged
 * row stays damaged until someone visits it, one by one, which is not a repair anyone would finish.
 * The first pass this class makes on such a device happens to heal all of them at once. That is a
 * welcome side effect of keeping metadata current, not the reason the sync exists.
 *
 * Either way the work is the same: one batched `getFullItems` for the whole downloads table, written
 * exactly the way the enqueuer writes a fresh download — parents included, because the series and
 * season rows behind a downloaded episode go stale (and were gutted) for the same reasons, and they
 * are what the offline "walk up to the show" path reads.
 *
 * ### The files, too
 * Metadata is not the only thing a finished download can be missing. The *file plan* moves as well —
 * phase 0 of the offline multi-track work taught it to fetch a sidecar for every embedded text
 * subtitle of a transcoded download — and an optional file is allowed to fail outright. A row on
 * disk would otherwise stay permanently poorer than the same item downloaded today, with no repair
 * short of deleting and re-downloading it. So each pass hands its freshly-fetched DTOs to
 * [SubtitleSidecarTopUp], which fetches only the small files that are genuinely absent and never
 * touches the media file. It fits here and nowhere else: this is the one place in the app that is
 * online, holds current DTOs for every downloaded item, and runs at a cadence a repair can afford.
 *
 * ### When it runs
 * The shape is [dev.jellyboost.data.userdata.UserDataSyncTrigger]'s, deliberately: collect
 * [ConnectionStateProvider.state], map to online-ness, `distinctUntilChanged`, and act on **every**
 * `true` — including the flow's initial value, which is the app-start check (DECISIONS.md,
 * 2026-07-29, on why that trigger does not `drop(1)` where the screen-refresh signal does). One code
 * path therefore covers both "the app started online" and "the connection came back".
 *
 * ### Once per online stretch
 * A flag is set when a refresh is attempted and cleared when the connection drops, so a stretch of
 * connectivity costs at most one pass. `distinctUntilChanged` on its own already collapses a
 * flapping probe into a single edge; the flag is what also makes a second, direct [refresh] call a
 * no-op. A failed pass is **not** retried within the stretch — the next offline → online edge picks
 * it up, and the whole thing is one request for a few dozen items.
 *
 * ### What it preserves
 * The rows are written with `source = DOWNLOAD` and, for a row that already exists, its **original**
 * [ItemEntity.cachedAt]. That column is the offline "recently downloaded" ordering key
 * (`BrowseCacheWriter`, rule one), so stamping `now` onto all eighteen downloads at once would
 * silently reshuffle the offline home into refresh order on every sync. Only a row this pass
 * creates — a parent that was never cached — gets the current time.
 *
 * ### What it tolerates
 * Everything. A failed batch is logged and skipped, so one bad chunk cannot cost the others their
 * update; an id the server no longer recognises is simply absent from the response
 * ([DownloadApi.getFullItems]) and leaves its local row exactly as it was — deleting a download
 * because the server lost the item is not this class's call to make.
 */
@Singleton
class DownloadedMetadataRefresher
    @Inject
    constructor(
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
        private val started = AtomicBoolean(false)
        private val refreshedThisStretch = AtomicBoolean(false)

        /**
         * Begins watching the connection. Idempotent, for the same reason `UserDataSyncTrigger.start`
         * is: `:app` calls it from `Application.onCreate`, and a process can be re-created without
         * the singleton being.
         */
        fun start() {
            if (!started.compareAndSet(false, true)) return

            scope.launch {
                connectionState.state
                    .map { it.isOnline }
                    .distinctUntilChanged()
                    .collect { isOnline ->
                        if (isOnline) refresh() else refreshedThisStretch.set(false)
                    }
            }
        }

        /**
         * Refreshes every downloaded item's stored metadata, once per stretch of connectivity.
         *
         * Public so a caller with a better moment than a connectivity edge can ask for it, and so
         * the tests can await the work instead of racing the application scope. Never throws: stale
         * metadata is a background annoyance and the app is perfectly usable until the next pass.
         */
        suspend fun refresh() {
            if (!refreshedThisStretch.compareAndSet(false, true)) return
            withContext(ioDispatcher) { refreshAll() }
        }

        private suspend fun refreshAll() {
            val ids = downloadedItemIds()
            if (ids.isEmpty()) return

            // The connection state starts optimistically `ONLINE`, so the app-start pass can easily
            // beat `MainViewModel.restoreSession()`; without the gate the first request throws the
            // SDK's "Required value baseUrl is null" (see SessionGate).
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
            // …and, with the same fresh DTOs already in hand, the optional files those downloads
            // never got. Deliberately after the metadata write and never gating it: a sidecar that
            // could not be fetched must not cost the whole table its refresh, and the next
            // connectivity edge tries again for free.
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
         * The series and season ids of [items] that are not already being written.
         *
         * Mirrors `DownloadEnqueuer.fetchParents`: a downloaded episode is only reachable from its
         * show offline because those two rows were cached alongside it. They go stale exactly like
         * the episode does — a renamed show, new series artwork — and the lean-write bug gutted them
         * too.
         */
        private fun parentIdsOf(
            items: List<BaseItemDto>,
            known: Set<UUID>,
        ): List<UUID> =
            items
                .flatMap { listOfNotNull(it.seriesId, it.seasonId) }
                .filterNot { it in known }
                .distinct()

        /**
         * The full DTOs of [ids], in batches.
         *
         * Chunked defensively: the ids travel in the query string of one `getItems` request, and a
         * user with a few hundred downloaded episodes would otherwise build a URL long enough for a
         * reverse proxy to reject outright. A failed batch costs only its own items — returning
         * nothing at all because the last chunk timed out would throw away the updates that worked.
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
         * Writes the refreshed metadata, straight to the DAO.
         *
         * Deliberately not through `BrowseCacheWriter`, for the reason `DownloadEnqueuer` gives: these
         * DTOs came from `DOWNLOAD_FIELDS`, so this is the rich blob every lean browse write is
         * forbidden from replacing — routing it through the writer would classify it as a browse read
         * and preserve the very blob it is here to replace.
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

        private companion object {
            /**
             * Ids per `getItems` request. Well under any URL limit at 36 characters an id, and far
             * more than the handful of items a real downloads table holds.
             */
            const val BATCH_SIZE = 50
        }
    }
