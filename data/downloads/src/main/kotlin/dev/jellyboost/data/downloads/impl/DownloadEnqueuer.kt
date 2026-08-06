package dev.jellyboost.data.downloads.impl

import android.database.sqlite.SQLiteException
import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.Ticks
import dev.jellyboost.core.common.model.DownloadQuality
import dev.jellyboost.core.common.model.DownloadStatus
import dev.jellyboost.core.database.TransactionRunner
import dev.jellyboost.core.database.dao.DownloadDao
import dev.jellyboost.core.database.dao.ItemDao
import dev.jellyboost.core.database.entities.DownloadEntity
import dev.jellyboost.core.database.entities.ItemSource
import dev.jellyboost.core.datastore.AppPreferences
import dev.jellyboost.data.cache.ItemEntityMapper
import dev.jellyboost.data.downloads.DownloadApi
import dev.jellyboost.data.downloads.engine.SiblingSeeder
import dev.jellyboost.data.downloads.plan.DownloadPaths
import dev.jellyboost.data.downloads.plan.downloadAudioStreamIndex
import dev.jellyboost.data.downloads.plan.isFolderItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaStreamType
import timber.log.Timber
import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns "the user tapped Download" into rows Room can hand to the queue (docs/PLAN.md, "Download
 * pipeline" → Enqueue).
 *
 * Four things happen, in this order, and the order is the point:
 *
 * 1. **A full re-fetch.** The item the user tapped came from a lean list request; the file plan
 *    needs `mediaSources`, `mediaStreams`, `trickplay` and the image tags, which only the full
 *    field set carries.
 * 2. **The parents too.** An episode's series and season are fetched and cached alongside it, so
 *    that offline the user can walk *up* from the downloaded episode to its show — the plan's
 *    "cached parents of downloaded items still open".
 * 3. **`ItemEntity(source = DOWNLOAD)`.** This is the row that makes the item appear in the offline
 *    home, library and search (M6's `OfflineJellyfinRepository` reads exactly this), and the row a
 *    later browse write-through is forbidden from demoting.
 * 4. **`DownloadEntity(QUEUED)`** at the end of the queue.
 *
 * Steps 1–3 are all-or-nothing: enqueueing a download whose metadata we failed to store would
 * produce files on disk that no screen can ever show.
 *
 * ### Containers expand
 * A season and a series are **folders**, and a folder has no file to fetch — asking the server for
 * one answers `400`. So when the item handed in is one, it is replaced by its episodes and every
 * one of them is enqueued exactly as a direct tap on that episode would have been: same re-fetch,
 * same quality preference, same paths (DECISIONS.md, 2026-07-29). That makes this class the one
 * place the rule lives, so no caller can reintroduce the bug by enqueuing a folder.
 *
 * M13 adds three more container kinds on the same rule — an album expands to its tracks in
 * disc/track order, an artist to every track of theirs album by album, a playlist to its audio
 * members in playlist order — and one exception to the *quality* half of it: audio is downloaded
 * as the original file whatever the preference says (see [planQuality]).
 */
@Singleton
internal class DownloadEnqueuer
    @Suppress(
        // Nine DI collaborators: enqueueing spans the API, both DAOs, the deleter (replace flow), the sibling seeder
        // and the transaction runner that makes the metadata and the rows land together.
        "LongParameterList",
    )
    @Inject
    constructor(
        private val api: DownloadApi,
        private val itemDao: ItemDao,
        private val downloadDao: DownloadDao,
        private val deleter: DownloadDeleter,
        private val mapper: ItemEntityMapper,
        private val appPreferences: AppPreferences,
        private val seeder: SiblingSeeder,
        private val transactionRunner: TransactionRunner,
        private val clock: Clock,
    ) {
        /**
         * Enqueues one item, or — for a season or a series — every episode under it.
         *
         * @param userId owner of the download — the delete cascade needs it.
         * @return the rows created, in queue order, or a failure describing why nothing was written.
         *   An empty list means every episode of a container is already on the device, which is a
         *   success with nothing left to do.
         */
        suspend fun enqueue(
            itemId: UUID,
            userId: UUID,
        ): AppResult<List<DownloadEntity>> {
            val fetched =
                when (val result = api.getFullItems(listOf(itemId))) {
                    is AppResult.Failure -> return result
                    is AppResult.Success -> result.value.firstOrNull()
                } ?: return AppResult.Failure(AppError.NotFound(itemId.toString()))

            return if (fetched.isFolderItem) enqueueContainer(fetched, userId) else enqueueSingle(fetched, userId)
        }

        /** A movie or an episode: itself, plus its parents for offline upward navigation. */
        private suspend fun enqueueSingle(
            item: BaseItemDto,
            userId: UUID,
        ): AppResult<List<DownloadEntity>> {
            val parents = fetchParents(listOf(item), exclude = setOf(item.id))
            return write(userId, cache = listOf(item) + parents, targets = listOf(item))
        }

        /**
         * A season or a series: its episodes, in order, minus the ones already on the device.
         *
         * Three rules, each with a failure mode behind it:
         *
         * - **The container's own row is deleted first.** Before this fix a tap on a season wrote a
         *   download row keyed on the *season*, which could only ever fail; those rows are still on
         *   users' devices and no retry will ever move them. A row for a folder is doomed by
         *   definition, so the cascade runs on it (files, rows, orphaned metadata) before the real
         *   downloads are queued.
         * - **Episodes already spoken for are skipped**, so re-tapping Download on a season the user
         *   half-downloaded does not restart it. `ERROR` and `CANCELLED` are the exceptions — see
         *   [isRetryable] for what each of them means.
         * - **Order is the server's**, which is broadcast order, so a queue drained top-to-bottom
         *   plays back in the order the user would watch.
         */
        @Suppress(
            // Six refusal reasons, each returning its own `AppError`; folding them loses which one fired.
            "ReturnCount",
        )
        private suspend fun enqueueContainer(
            container: BaseItemDto,
            userId: UUID,
        ): AppResult<List<DownloadEntity>> {
            val episodeIds =
                when (val result = childItemIds(container)) {
                    is AppResult.Failure -> return result
                    is AppResult.Success -> result.value
                }
            if (episodeIds.isEmpty()) {
                Timber.w("Nothing to download under %s (%s)", container.name, container.type)
                return AppResult.Failure(AppError.NotFound(container.id.toString()))
            }

            removeDoomedContainerRow(container)

            // One read for the whole season, not one per episode: a forty-episode series meant
            // forty statements before the enqueue could even begin (audit 2026-08-08, PERF-25). An
            // id with no row is absent from the answer, which `isRetryable` already reads as "yes".
            val existing = downloadDao.getAll(episodeIds).associateBy { it.itemId }
            val pending = episodeIds.filter { existing[it].isRetryable() }
            if (pending.isEmpty()) return AppResult.Success(emptyList())

            val fetched =
                when (val result = api.getFullItems(pending)) {
                    is AppResult.Failure -> return result
                    is AppResult.Success -> result.value.associateBy { it.id }
                }
            // `getItems(ids = …)` answers in its own order; the queue's is the one that was asked
            // for, which is the order the user would watch them in.
            val episodes = pending.mapNotNull(fetched::get)
            if (episodes.isEmpty()) return AppResult.Failure(AppError.NotFound(container.id.toString()))

            val known = episodes.map { it.id }.toSet() + container.id
            val parents = fetchParents(episodes, exclude = known)
            // A playlist is the one container that is not cached alongside what it expanded to: a
            // `PLAYLIST` row with `source = DOWNLOAD` would appear in the offline library as a
            // playlist whose track list is permanently empty, because Room has no
            // playlist-membership relation to fill it from (DECISIONS.md, 2026-08-05, "Offline
            // playlists deferred"). The tracks it queued are reachable offline through their own
            // albums and artists, which is what the M13 DoD asks for.
            val cached = if (container.type == BaseItemKind.PLAYLIST) emptyList() else listOf(container)
            return write(userId, cache = cached + episodes + parents, targets = episodes)
        }

        /** The ids under a container, or a failure when it is one this pipeline cannot expand. */
        private suspend fun childItemIds(container: BaseItemDto): AppResult<List<UUID>> =
            when (container.type) {
                BaseItemKind.SERIES -> api.getEpisodeIds(seriesId = container.id, seasonId = null)
                BaseItemKind.SEASON -> {
                    val seriesId =
                        container.seriesId
                            ?: return AppResult.Failure(AppError.NotFound(container.id.toString()))
                    api.getEpisodeIds(seriesId = seriesId, seasonId = container.id)
                }

                // The M13 music containers. Each answers ids in the order the matching screen shows
                // them, so a queue drained top-to-bottom downloads the album, the artist's
                // discography or the playlist in the order the user is looking at.
                BaseItemKind.MUSIC_ALBUM -> api.getAlbumTrackIds(container.id)
                BaseItemKind.MUSIC_ARTIST -> api.getArtistTrackIds(container.id)
                BaseItemKind.PLAYLIST -> api.getPlaylistTrackIds(container.id)

                // A box set or a library folder: the detail screen never offers Download on one, and
                // guessing what "download this library" means is not this milestone's business.
                else -> {
                    Timber.w("%s is a folder this pipeline cannot expand", container.type)
                    AppResult.Failure(AppError.Unknown())
                }
            }

        /**
         * Removes a download row keyed on the container itself, whatever state it is in.
         *
         * Such a row can never finish — that is the bug this fix is for — so leaving it would keep a
         * permanent failure on the Downloads screen next to the episodes that do work.
         */
        private suspend fun removeDoomedContainerRow(container: BaseItemDto) {
            if (downloadDao.get(container.id) == null) return

            Timber.i("Removing the unusable download row of %s", container.name)
            @Suppress("TooGenericExceptionCaught")
            try {
                // The cascade only removes rows that are out of the queue's reach
                // (`DownloadDao.deleteUnlessRunnable`), and a doomed container row is usually
                // `QUEUED` — it is a row that can never be transferred, so it never leaves that
                // status on its own. Claiming it first is what makes the delete take.
                downloadDao.demoteRunnable(listOf(container.id), DownloadStatus.CANCELLED, clock.instant())
                deleter.delete(container.id)
            } catch (error: Exception) {
                // Best effort: a stuck row that could not be cleaned up must not stop the episodes
                // the user actually asked for from being queued.
                Timber.w(error, "Could not remove the download row of %s", container.name)
            }
        }

        /**
         * The parents of the given items — series and season for an episode, album and album artist
         * for a track — best effort.
         *
         * A failure here is deliberately *not* fatal: the download itself is perfectly usable
         * without its parents cached, it only means the offline series page is missing until the
         * user next browses to it online.
         *
         * The music half is what makes the offline walk in the M13 DoD work at all: artist → album
         * → tracks reads `ItemDao.albumsOfArtist` and `ItemDao.tracksOfAlbum`, both of which filter
         * on `source = DOWNLOAD`, so an album row that was never cached is an artist page with
         * nothing on it. [dev.jellyboost.data.cache.ItemEntityMapper] fills the `albumId` /
         * `albumArtistId` query columns from these same DTOs.
         *
         * @param exclude ids already being cached by the caller — the item itself, and for an
         *   expanded container the container and its episodes.
         */
        private suspend fun fetchParents(
            items: List<BaseItemDto>,
            exclude: Set<UUID>,
        ): List<BaseItemDto> {
            val parentIds =
                items
                    .flatMap { it.parentIds() }
                    .filterNot { it in exclude }
                    .distinct()
            if (parentIds.isEmpty()) return emptyList()

            return when (val result = api.getFullItems(parentIds)) {
                is AppResult.Success -> result.value
                is AppResult.Failure -> {
                    Timber.w("Could not cache the parents of %s: %s", items.first().id, result.error)
                    emptyList()
                }
            }
        }

        /**
         * The rows the offline read path walks *up* to from this item.
         *
         * Four ids, of which any given item has at most two: an episode's series and season, a
         * track's album and album artist. `albumArtists.first()` rather than `artistItems.first()`
         * on purpose — the album artist is the one an album is filed under, and it is the id
         * `ItemEntityMapper` writes into the `albumArtistId` column the artist page queries.
         */
        private fun BaseItemDto.parentIds(): List<UUID> =
            listOfNotNull(seriesId, seasonId, albumId, albumArtists?.firstOrNull()?.id)

        /**
         * The one write: metadata for everything in [cache], a queue row for everything in
         * [targets].
         *
         * The quality preference is read once, here, and stamped onto every row: the pipeline must
         * not re-read a preference the user can change while the transfer it describes is
         * half-written (DECISIONS.md, 2026-07-29). Enqueuing a whole season therefore fixes one
         * quality for the season, which is also the only answer a user would expect.
         *
         * The one thing decided *per row* is whether that quality is worth asking the server for at
         * all — see [planQuality].
         *
         * ### One transaction
         * The metadata upsert and the queue rows land together or not at all, and the reads that
         * decide them sit inside the same block. Three things depended on that and had none of it
         * (audit 2026-08-08, CORR-4 and CORR-6):
         *
         * - a concurrent delete's orphan prune (`DownloadDeleter.pruneOrphanedItems`) reads "which
         *   items still have a download row" and deletes every `DOWNLOAD` item row outside that
         *   answer. Landing between this method's `itemDao.upsert` and its `downloadDao.upsert`, it
         *   deleted the metadata of an item whose row was one statement away — and the drain that
         *   later picked the row up failed with `MissingMetadataException`, which is *permanent*;
         * - `maxQueuePosition()` is read once and counted from, which only holds if no other
         *   enqueue can commit a row in between. Two taps in the same second used to produce two
         *   downloads at the same queue position;
         * - the per-row `downloadDao.get` is now the value the write is guarded on rather than a
         *   snapshot the write ignores — see [isRetryable].
         */
        private suspend fun write(
            userId: UUID,
            cache: List<BaseItemDto>,
            targets: List<BaseItemDto>,
        ): AppResult<List<DownloadEntity>> {
            val now = clock.instant()
            // Read before the transaction opens: a DataStore flow is not database work, and a
            // transaction is not the place to wait on one.
            val quality = appPreferences.downloadQuality.first()

            return try {
                AppResult.Success(transactionRunner.inTransaction { writeRows(userId, cache, targets, quality, now) })
            } catch (cancellation: CancellationException) {
                // The enqueue runs in the caller's coroutine — a ViewModel scope that dies with the
                // screen. Reporting a cancelled scope as `AppError.Storage` would put a "could not
                // download" message on a screen the user has already left, and would swallow the
                // cancellation the structured-concurrency machinery is owed (the audit's ARCH-08
                // rule).
                throw cancellation
            } catch (error: SQLiteException) {
                // Narrowed to Room's own failure: the block above is `upsert` calls and arithmetic,
                // so anything else escaping it is a bug in this class rather than a full disk, and
                // should surface as a crash instead of a swallowed "could not enqueue".
                Timber.e(error, "Could not enqueue %s", targets.firstOrNull()?.id)
                AppResult.Failure(AppError.Storage(error))
            }
        }

        /**
         * [write]'s body, run inside the transaction — the metadata for [cache] and a queue row for
         * every target that still wants one.
         */
        private suspend fun writeRows(
            userId: UUID,
            cache: List<BaseItemDto>,
            targets: List<BaseItemDto>,
            quality: DownloadQuality,
            now: java.time.Instant,
        ): List<DownloadEntity> {
            // The items and their parents in one upsert: a partially-cached hierarchy is the state
            // that makes offline navigation dead-end halfway up.
            //
            // Deliberately straight to the DAO and not through `BrowseCacheWriter`: these DTOs came
            // from `DownloadApi.DOWNLOAD_FIELDS`, so the blob written here is the rich one every
            // later lean browse write is forbidden from replacing.
            itemDao.upsert(cache.distinctBy { it.id }.map { mapper.toEntity(it, ItemSource.DOWNLOAD, now) })

            // Counted here rather than re-read per row: `maxQueuePosition()` only moves once the
            // previous row is committed, and a season enqueued in one go would otherwise pile
            // twenty episodes onto the same position.
            var nextPosition = (downloadDao.maxQueuePosition() ?: 0) + 1

            return targets.mapNotNull { dto ->
                val existing = downloadDao.get(dto.id)
                // The same rule the container path applies before fetching, applied here to every
                // target and inside the transaction, which is the only place it holds. Without it a
                // second tap on a *single* item — a badge one tick stale, a double tap — rewrote a
                // finished or in-flight row's quality, size and `sizeIsExact` from the current
                // preference, describing a file that had already been fetched under the old plan
                // (audit CORR-6).
                if (!existing.isRetryable()) {
                    Timber.i("%s is already downloaded or in flight; leaving its row alone", dto.name)
                    return@mapNotNull null
                }
                // Per row, not per tap: a season's 4K episode can be worth transcoding while the SD
                // one next to it is not.
                val (rowQuality, estimate) = dto.planQuality(quality)
                val row =
                    dto.toDownloadRow(
                        userId = userId,
                        quality = rowQuality,
                        now = now,
                        existing = existing,
                        position = existing?.queuePosition ?: nextPosition++,
                        estimate = estimate,
                        // The seed is read per row, after the ones before it were written, so the
                        // second episode of a season enqueued in one go is seeded from whatever
                        // finished *before* the tap — never from a sibling this same expansion
                        // queued and has not downloaded yet. That is why enqueue time is not the
                        // only moment seeding happens: `SiblingSeeder.seedPendingSiblingsOf` comes
                        // back to these rows as each episode lands
                        // (docs/features/download-quality.md).
                        projected = dto.siblingSeed(rowQuality, estimate),
                    )
                downloadDao.upsert(row)
                row
            }
        }

        @Suppress("LongParameterList")
        private fun BaseItemDto.toDownloadRow(
            userId: UUID,
            quality: DownloadQuality,
            now: java.time.Instant,
            existing: DownloadEntity?,
            position: Int,
            estimate: SizeEstimate,
            projected: Long?,
        ): DownloadEntity =
            DownloadEntity(
                itemId = id,
                userId = userId,
                status = DownloadStatus.QUEUED,
                mediaSourceId = mediaSources?.firstOrNull()?.id,
                quality = quality,
                // The row starts at zero downloaded but with the size the server reported, so the
                // queue tab can show a meaningful percentage before the first byte arrives.
                bytesDownloaded = existing?.bytesDownloaded ?: 0L,
                bytesTotal = estimate.bytes ?: existing?.bytesTotal ?: 0L,
                projectedBytes = projected,
                sizeIsExact = estimate.exact,
                // Only a transcode bakes one track in, and [quality] here is what the row is
                // *actually* written at — so a row the fallback in [planQuality] downgraded to
                // ORIGINAL records no pin, which is correct: that file will hold every audio
                // track of the source.
                bakedAudioStreamIndex = downloadAudioStreamIndex.takeIf { quality.isTranscoded },
                queuePosition = position,
                directoryName = DownloadPaths.itemDirectoryName(this),
                itemName = name.orEmpty().ifBlank { id.toString() },
                // The Downloads screen groups finished rows by this column (`DownloadItem.seriesKey`),
                // so a track files itself under its **album** the way an episode files itself under
                // its show — three tracks of *Rumours*, not three files. Falling back rather than
                // adding a column: the column already means "the heading these rows belong under",
                // and a track has no series to conflict with (M13 Phase 5).
                seriesName = seriesName ?: album?.takeIf { it.isNotBlank() },
                errorMessage = null,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            )

        /**
         * The quality this one row is actually written at, and the size that goes with it.
         *
         * The user's preference is the answer for every row except one case: a **transcode that
         * would not save space**. A quality step is a ceiling, not a target — asking `HIGH` of a
         * source that is already 1080p H.264 under 20 Mbps buys nothing, and the server would spend
         * an encode (or a stream copy) to hand back a file the size of the one it already has, minus
         * the extra audio tracks and plus a generation of quality loss. When the arithmetic says
         * that is what is about to happen, this stamps [DownloadQuality.ORIGINAL] on the row
         * instead, which is strictly better on every axis the pipeline measures: the exact size the
         * server reported, a resumable `Range`-honouring transfer, no server CPU, no re-encode.
         *
         * ### The comparison
         * Both halves are computed the way they would be *stamped*, not by a separate rule of their
         * own: the transcoded figure is [sizeEstimate] of the preferred quality — including the
         * [remuxBytes] stream-copy path, which is the case this rule catches most often, since a
         * remux is by construction about the size of the source's own video track — and the original
         * figure is [sizeEstimate] of [DownloadQuality.ORIGINAL], `mediaSources[0].size`. Comparing
         * anything other than what would actually be downloaded would let the row be downgraded on
         * the strength of a number nothing else in the pipeline uses.
         *
         * Both figures have to exist. An unknown original size (the server reported none) or an
         * uncomputable estimate (an item with no runtime) leaves the user's choice alone: the
         * preference is the default, and a guess is not grounds for overriding it.
         *
         * The decision is per row because [write] is handed a whole season at a time and the
         * episodes under it need not agree — a 4K episode may deserve the transcode while the SD one
         * beside it does not — and it is made *before* [toDownloadRow] so that `quality`,
         * `bytesTotal`, `sizeIsExact`, the projector gate and the file plan all describe the same
         * download.
         */
        @Suppress(
            // Quality planning walks the source list and returns the first plan that fits; the walk is the shape.
            "ReturnCount",
        )
        private fun BaseItemDto.planQuality(preferred: DownloadQuality): PlannedQuality {
            // Music is originals-only, and the row says so rather than the planner quietly ignoring
            // a quality it was stamped with (docs/notes/music-m13-plan.md, key decision 10). Every
            // rule downstream keys off this column — the transcode URL, the size projector, the
            // "a transcode cannot be paused" rule, the *Transcoded* marker on the row — so a track
            // written as ORIGINAL is a track none of that machinery can reach. Audio download
            // transcoding is a recorded deferred item.
            if (type == BaseItemKind.AUDIO) {
                return PlannedQuality(DownloadQuality.ORIGINAL, sizeEstimate(DownloadQuality.ORIGINAL))
            }

            val chosen = PlannedQuality(preferred, sizeEstimate(preferred))
            if (!preferred.isTranscoded) return chosen

            val transcodedBytes = chosen.estimate.bytes ?: return chosen
            val original = sizeEstimate(DownloadQuality.ORIGINAL)
            val originalBytes = original.bytes?.takeIf { it > 0L } ?: return chosen
            if (transcodedBytes < ORIGINAL_THRESHOLD * originalBytes) return chosen

            Timber.i(
                "%s: a %s transcode is estimated at %d bytes against an original of %d — downloading the original",
                name,
                preferred,
                transcodedBytes,
                originalBytes,
            )
            return PlannedQuality(DownloadQuality.ORIGINAL, original)
        }

        /**
         * How big the media file is expected to be, and whether that figure is the size the file
         * will *be* or merely a size it will not exceed.
         *
         * Three answers, in the order they are tried:
         *
         * 1. **[DownloadQuality.ORIGINAL]** — `mediaSources[0].size`, the file on disk, exact.
         * 2. **A stream copy** — see [remuxBytes]. The server will pass the video track through
         *    untouched, so the output is the source's video bytes plus one re-encoded AAC track:
         *    predictable, and marked exact.
         * 3. **A real transcode** — `runtime × min(cap, source bitrate)`, a deterministic upper
         *    bound and nothing more (DECISIONS.md, 2026-07-29). The bitrate is the *effective* one
         *    because a transcode can never need more bits per second than the source already
         *    carries; most sources sit well under a tier's cap, and estimating from the cap alone
         *    overstates the download by a large margin (a LOW episode estimated 552 MB and landed
         *    at 232 MB). When the source bitrate is missing or zero, the cap is the only number
         *    left.
         *
         * Answers 2 and 3 then carry [extraAudioBytes] on top, because a transcoded download is no
         * longer one file: every audio language the transcode did not bake in is fetched as its own
         * sidecar (DECISIONS.md, 2026-07-31, "Offline multi-track Phase 2"), and the figure this
         * returns is what the whole item is promised to weigh.
         *
         * @return `bytes = null` when there is nothing at all to go on — a transcode of an item
         *   with no runtime. Reporting the *source's* size there would promise a number for a file
         *   the user is not going to receive.
         */
        @Suppress(
            // Each return is a different estimate source (server, bitrate, none), tried in falling confidence.
            "ReturnCount",
        )
        private fun BaseItemDto.sizeEstimate(quality: DownloadQuality): SizeEstimate {
            val cap =
                quality.totalBitRate
                    ?: return SizeEstimate(mediaSources?.firstOrNull()?.size, exact = true)

            val ticks = runTimeTicks?.takeIf { it > 0L } ?: return SizeEstimate(null, exact = false)
            val seconds = ticks.toDouble() / Ticks.PER_SECOND
            val sidecars = extraAudioBytes(seconds)

            remuxBytes(quality, seconds)?.let {
                // Exact only while the item is single-language: a sidecar is itself a transcode, so
                // the moment there is one the total is a ceiling again.
                return SizeEstimate(it + sidecars, exact = sidecars == 0L)
            }

            val sourceBitRate = mediaSources?.firstOrNull()?.bitrate?.takeIf { it > 0 }
            val bitRate = if (sourceBitRate != null) minOf(cap, sourceBitRate) else cap
            return SizeEstimate((seconds * bitRate / Byte.SIZE_BITS).toLong() + sidecars, exact = false)
        }

        /**
         * What this item's **audio sidecars** are expected to weigh together, or `0` when it has at
         * most one audio track.
         *
         * One track is baked into the transcode itself and is already counted in the figure above;
         * every other one is a separate AAC download at [DownloadQuality.AUDIO_BITRATE], which is
         * around 165 MB for a two-hour film — far too much to leave out of the number the user is
         * shown before they agree to it, and far too much for the queue's floor to be missing while
         * the transfer runs.
         *
         * The junk video the sidecar is *fetched* through does not appear here on purpose: it is
         * deleted by the strip stage, so it is bandwidth rather than bytes on disk, and this figure
         * is the one the storage bar and the Downloaded tab are held to.
         */
        private fun BaseItemDto.extraAudioBytes(runtimeSeconds: Double): Long {
            val streams = mediaSources?.firstOrNull()?.mediaStreams.orEmpty()
            val extras = (streams.count { it.type == MediaStreamType.AUDIO } - 1).coerceAtLeast(0)
            if (extras == 0) return 0L
            return (extras * runtimeSeconds * DownloadQuality.AUDIO_BITRATE / Byte.SIZE_BITS).toLong()
        }

        /**
         * The size of a transcode the server will answer by **copying** the video stream, or `null`
         * when this is not one.
         *
         * `DownloadUrlFactory.transcodedVideoUrl` sends `allowVideoStreamCopy=true`, which means the
         * server re-encodes only what it has to. When it copies the video track the output is
         * arithmetic rather than a guess: the source's own video bytes, plus the one AAC track we
         * always ask for at [DownloadQuality.AUDIO_BITRATE] (`allowAudioStreamCopy=false`, so audio
         * is re-encoded whatever the source was). Matroska's own overhead is well under a percent.
         *
         * That exactness is this **file's**, and it survives only as far as the item has one audio
         * language. A second language is a second download the server has to re-encode, whose size
         * is a ceiling like any transcode's — so [sizeEstimate] adds [extraAudioBytes] to what this
         * returns and stops calling the result exact (DECISIONS.md, 2026-07-31, "Offline
         * multi-track Phase 2"). The arithmetic below is unchanged: it still describes precisely the
         * one file the server is about to stream-copy.
         *
         * Naming an `audioStreamIndex` (schema v8) does not disturb this: the request still yields
         * exactly one AAC track at the same bitrate, and the index it names is the one the server
         * would have chosen for itself. The estimate therefore still describes what is actually
         * requested — which is the property `planQuality` compares against the original's size.
         *
         * ### The conditions, and how far they are verified
         * Checked against `EncodingHelper.CanStreamCopyVideo` in jellyfin `release-10.11.z` (the
         * method runs ~a dozen gates in sequence; any one failing forces a re-encode):
         * - **codec**: `SupportedVideoCodecs` is populated straight from our `videoCodec=h264`, and
         *   the test is a case-insensitive exact match against the source stream's `Codec`.
         * - **height**: fails on `Height > MaxHeight` **or on a null `Height`**, so an unknown
         *   height is a re-encode, not a free pass.
         * - **bitrate**: fails on `BitRate > VideoBitRate` **or on a null `BitRate`** (there is a
         *   `LiveStreamId` escape hatch, and a download has none). This is the trap worth knowing:
         *   plenty of MKVs carry no per-stream bitrate, and those transcode however small they are.
         *   Requiring the value to be present is therefore not merely conservative — it is the
         *   server's own rule, and it is why this deliberately does **not** fall back to deriving
         *   video bytes from the source's total size.
         * - **input container**: an `avi` source has a special case in the same method that can
         *   force a re-encode, so one is never claimed as a copy.
         *
         * The remaining gates (profile, level, bit depth, ref frames, HDR range, framerate, max
         * width, anamorphic, subtitle burn-in) are each enforced *only* when the matching query
         * parameter is present, and this client sends none of them; the interlacing gate needs a
         * `deInterlace` request we also never send. So for **our** URL these four checks are the
         * whole of it. Should the URL ever grow one of those parameters, this comment is the
         * warning that it also grows a gate.
         */
        @Suppress("ReturnCount")
        private fun BaseItemDto.remuxBytes(
            quality: DownloadQuality,
            runtimeSeconds: Double,
        ): Long? {
            val source = mediaSources?.firstOrNull() ?: return null
            if (source.container.equals(AVI_CONTAINER, ignoreCase = true)) return null

            val video =
                source.mediaStreams?.firstOrNull { it.type == MediaStreamType.VIDEO } ?: return null
            if (!video.codec.equals(DownloadQuality.VIDEO_CODEC, ignoreCase = true)) return null

            val maxHeight = quality.maxHeight ?: return null
            val height = video.height ?: return null
            if (height > maxHeight) return null

            val videoCap = quality.videoBitRate ?: return null
            val videoBitRate = video.bitRate?.takeIf { it > 0 } ?: return null
            if (videoBitRate > videoCap) return null

            val bits = runtimeSeconds * (videoBitRate.toLong() + DownloadQuality.AUDIO_BITRATE)
            return (bits / Byte.SIZE_BITS).toLong()
        }

        /**
         * What this item is likely to weigh, judged from episodes of the same show already on the
         * device at the same quality — or `null` when there is nothing to judge from.
         *
         * The arithmetic is [SiblingSeeder]'s, and it is shared on purpose: the same question is
         * asked again when a sibling finishes and when the queue starts a row, and three copies of
         * a median would be three chances for the wordings on one screen to disagree.
         *
         * What stays here is the *gate*. A row whose size is exact — an `ORIGINAL` download, or a
         * transcode the server will answer with a video stream copy — is not seeded at all: a guess
         * cannot improve on an arithmetic answer, and it would flip the row's wording from a plain
         * figure to a hedged one for nothing. Films get `null` too: there are no siblings, and a
         * director's other work is not evidence.
         */
        @Suppress(
            // Guard chain over the sibling's own row state; every exit is "already handled elsewhere".
            "ReturnCount",
        )
        private suspend fun BaseItemDto.siblingSeed(
            quality: DownloadQuality,
            estimate: SizeEstimate,
        ): Long? {
            if (!quality.isTranscoded || estimate.exact) return null
            val ceiling = estimate.bytes?.takeIf { it > 0L } ?: return null
            val runtimeMillis = Ticks.positiveMillisOrNull(runTimeTicks) ?: return null

            return seeder.seedFor(
                itemId = id,
                seriesName = seriesName,
                quality = quality,
                runtimeMillis = runtimeMillis,
                ceilingBytes = ceiling,
            )
        }

        /**
         * `true` when a tap should (re)queue this item — the rule both [enqueueContainer]'s filter
         * and [writeRows]'s per-row guard apply.
         *
         * Three states qualify, and everything else is a row a second tap must not disturb (already
         * downloaded, downloading, paused or waiting):
         *
         * - **no row at all** — a first download;
         * - **`ERROR`** — the failure a second tap is meant to retry, keeping the queue position
         *   and the bytes already on disk;
         * - **`CANCELLED`** — the status a row holds between a cancel and its deletion. The UI maps
         *   it to *not downloaded* and offers **Download** again while the cascade behind it is
         *   still waiting out `DownloadScheduler.stop()`, so this is the ordinary re-download and it
         *   has to write: the row goes back to `QUEUED`, and the cascade arriving afterwards finds
         *   it runnable and leaves it alone (`DownloadDao.deleteUnlessRunnable`, audit CORR-1).
         */
        private fun DownloadEntity?.isRetryable(): Boolean =
            this == null || status == DownloadStatus.ERROR || status == DownloadStatus.CANCELLED

        private companion object {
            /** The one input container `CanStreamCopyVideo` has a special case for. */
            const val AVI_CONTAINER = "avi"

            /**
             * The share of the original file's size a transcode has to come in **under** to be
             * worth making at all — see [planQuality].
             *
             * `0.9`: a transcode that saves less than about a tenth of the file is not a trade, it
             * is a loss. What it costs is fixed and known — a generation of re-encoding, the
             * server's CPU for the length of the transfer, no byte-level resume, and a size the
             * queue can only estimate — and none of that is bought back by a saving the user would
             * struggle to notice on a storage bar. The margin also absorbs the estimate's own
             * slack in the right direction: the figure being compared is an upper bound for a real
             * re-encode, so a transcode judged *just* under the threshold is likely to save rather
             * more than the arithmetic promises, while one at or over it cannot save meaningfully
             * less than nothing.
             */
            const val ORIGINAL_THRESHOLD = 0.9
        }
    }

/**
 * The enqueue-time size prediction, and whether it is a figure or a ceiling.
 *
 * The two travel together because every caller needs both: the number goes in `bytesTotal` and the
 * flag in `sizeIsExact`, and computing one without the other is what would let a stream copy be
 * presented as *"up to"*.
 *
 * @property bytes the predicted size, or `null` when nothing could be predicted at all.
 * @property exact `true` when [bytes] is what the file will weigh (the server reported it, or the
 *   request will be answered with a video stream copy), `false` when it is an upper bound.
 */
internal data class SizeEstimate(
    val bytes: Long?,
    val exact: Boolean,
)

/**
 * The quality one row is written at, together with the size prediction that belongs to *that*
 * quality.
 *
 * The pair is what `DownloadEnqueuer.planQuality` answers, and it travels as a pair for the same
 * reason [SizeEstimate]'s two fields do: a row downgraded to [DownloadQuality.ORIGINAL] whose
 * `bytesTotal` was still the transcode's estimate would be a row promising a size for a file it is
 * not going to fetch.
 *
 * @property quality what `DownloadEntity.quality` is stamped with — the user's preference, unless
 *   the transcode it asks for would not have saved space.
 * @property estimate the size that [quality] implies, exactly as if it had been the preference all
 *   along.
 */
private data class PlannedQuality(
    val quality: DownloadQuality,
    val estimate: SizeEstimate,
)
