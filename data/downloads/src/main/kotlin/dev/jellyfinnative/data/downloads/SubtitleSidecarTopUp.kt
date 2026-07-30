package dev.jellyfinnative.data.downloads

import dev.jellyfinnative.core.common.model.DownloadFileType
import dev.jellyfinnative.core.common.model.DownloadStatus
import dev.jellyfinnative.core.database.dao.DownloadDao
import dev.jellyfinnative.core.database.entities.DownloadEntity
import dev.jellyfinnative.core.database.entities.DownloadFileEntity
import dev.jellyfinnative.core.database.entities.DownloadWithFiles
import dev.jellyfinnative.core.network.di.IoDispatcher
import dev.jellyfinnative.data.downloads.engine.FileDownloader
import dev.jellyfinnative.data.downloads.plan.DownloadFilePlanner
import dev.jellyfinnative.data.downloads.plan.PlannedFile
import dev.jellyfinnative.data.downloads.storage.DownloadStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import org.jellyfin.sdk.model.api.BaseItemDto
import timber.log.Timber
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches the subtitle sidecars a **finished** download is missing, without touching its media file.
 *
 * ### Why this exists
 * The file plan is not fixed for all time. Phase 0 of the offline multi-track work
 * (docs/notes/offline-multitrack-design.md) made a transcoded download fetch an extracted `.srt`
 * for every *embedded* text subtitle as well, and a row already on the device does not retroactively
 * grow one. Without a repair path an old MEDIUM download holds fewer subtitles than a fresh one,
 * permanently, and the only fix a user has is to delete it and download it again — which is not a
 * fix, it is a two-gigabyte apology. The same gap opens whenever an optional file simply failed: the
 * queue logs it, marks the item `DOWNLOADED` anyway (correctly — a film without one subtitle track
 * is still a film) and never comes back to it.
 *
 * ### Why it is not "just re-queue the row"
 * Because that would re-download the film. `DownloadQueue.reconcile` does rebuild the plan and does
 * insert rows for files it has never seen — but the queue then walks *every* file in the plan, and
 * the media file of a transcoded row cannot be resumed: the server ignores `Range` on a live
 * transcode, answers `200`, and `FileDownloader` truncates and rewrites from zero. Re-queueing to
 * pick up a 40 KB subtitle would therefore spend the whole download again. So this fetches the
 * missing optional files directly and leaves the row `DOWNLOADED` throughout.
 *
 * ### What it will and will not do
 * - Only rows that are [DownloadStatus.DOWNLOADED]. Anything still in the queue is the queue's, and
 *   the two must not write the same file rows at once.
 * - Only [DownloadFileType.SUBTITLE] entries of the plan. Artwork and trickplay tiles are not what
 *   this is for, and widening it later is a decision, not an oversight.
 * - Only files that are genuinely absent — no row, or a row whose bytes are not on disk. A complete
 *   sidecar is never re-fetched, which is what makes running this on every connectivity edge cheap.
 * - Never throws. A failure leaves exactly the gap that was there before.
 *
 * The plan is rebuilt from the **row's own quality**, the same rule `DownloadQueue.reconcile`
 * follows: the point is to give this download the files today's planner would have given it, not to
 * re-decide what it should have been.
 */
@Singleton
class SubtitleSidecarTopUp
    @Inject
    constructor(
        private val downloadDao: DownloadDao,
        private val planner: DownloadFilePlanner,
        private val storage: DownloadStorage,
        private val downloader: FileDownloader,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        /**
         * Tops up every item in [items] that needs it.
         *
         * @param items freshly-fetched DTOs — the refresher already has them, and a stale cached
         *   blob is exactly what would plan the wrong stream set.
         * @return how many sidecar files were actually fetched, for the log line and the tests.
         */
        suspend fun topUp(items: List<BaseItemDto>): Int {
            var fetched = 0
            for (item in items) {
                fetched += topUpOne(item)
            }
            if (fetched > 0) Timber.i("Fetched %d missing subtitle sidecar(s) for downloaded items", fetched)
            return fetched
        }

        private suspend fun topUpOne(item: BaseItemDto): Int {
            val stored = finishedDownload(item.id) ?: return 0
            val missing = missingSidecars(item, stored.download, stored.files)
            if (missing.isEmpty()) return 0

            Timber.i("%s is missing %d subtitle sidecar(s); fetching them", stored.download.itemName, missing.size)
            return missing.count { planned -> fetch(stored.download, planned, stored.files) }
        }

        /** The download row and its files, or `null` when this item is not a finished download. */
        private suspend fun finishedDownload(itemId: UUID): DownloadWithFiles? =
            runCatchingUnlessCancelled { downloadDao.getWithFiles(itemId) }
                ?.takeIf { it.download.status == DownloadStatus.DOWNLOADED }

        /**
         * The sidecars today's plan wants that this download does not have.
         *
         * A file counts as present only when its row says `DOWNLOADED` **and** the bytes are still
         * there — the same test `DownloadedMediaProvider` applies before offering the track, so this
         * repairs exactly the set the player would otherwise withhold.
         */
        private fun missingSidecars(
            item: BaseItemDto,
            download: DownloadEntity,
            files: List<DownloadFileEntity>,
        ): List<PlannedFile> {
            val planned =
                runCatchingUnlessCancelled {
                    planner.plan(item, download.directoryName, quality = download.quality)
                } ?: return emptyList()

            return planned
                .filter { it.type == DownloadFileType.SUBTITLE }
                .filterNot { file ->
                    files.any { row ->
                        row.type == DownloadFileType.SUBTITLE &&
                            row.streamIndex == file.streamIndex &&
                            row.status == DownloadStatus.DOWNLOADED &&
                            File(row.path).isFile
                    }
                }
        }

        /**
         * Fetches one sidecar and records it.
         *
         * An existing row keeps its file name, for `DownloadQueue.reconcile`'s reason: the name on
         * the row *is* whatever is on disk, and the plan cannot be trusted to reproduce it.
         *
         * @return `true` when the file is now on disk.
         */
        private suspend fun fetch(
            download: DownloadEntity,
            planned: PlannedFile,
            files: List<DownloadFileEntity>,
        ): Boolean =
            runCatchingUnlessCancelled {
                storage.prepareItemDirectory(download.directoryName)
                val previous =
                    files.firstOrNull {
                        it.type == DownloadFileType.SUBTITLE && it.streamIndex == planned.streamIndex
                    }
                val fileName = previous?.fileName ?: planned.fileName
                val target = storage.resolve(download.directoryName, fileName)

                val row =
                    when (previous) {
                        null -> {
                            val fresh =
                                DownloadFileEntity(
                                    itemId = download.itemId,
                                    type = DownloadFileType.SUBTITLE,
                                    streamIndex = planned.streamIndex,
                                    fileName = fileName,
                                    path = target.absolutePath,
                                    url = planned.url,
                                    status = DownloadStatus.DOWNLOADING,
                                )
                            fresh.copy(id = downloadDao.insertFile(fresh))
                        }

                        else -> {
                            val updated =
                                previous.copy(
                                    path = target.absolutePath,
                                    url = planned.url,
                                    status = DownloadStatus.DOWNLOADING,
                                )
                            downloadDao.updateFile(updated)
                            updated
                        }
                    }

                // A partial sidecar from an earlier failure would otherwise be resumed with a
                // `Range` the server has no reason to honour on a freshly-extracted stream.
                if (target.exists()) target.delete()

                val written = downloader.download(planned.url, target, ioDispatcher) { _, _ -> }
                downloadDao.updateFileProgress(row.id, written, written)
                downloadDao.setFileStatus(row.id, DownloadStatus.DOWNLOADED)
                true
            } ?: false

        /**
         * [runCatching] that still lets a cancellation through.
         *
         * This runs on the application scope behind the metadata refresher; swallowing its
         * cancellation would keep a coroutine writing to Room after the scope was torn down
         * (the audit's STAB-06 rule).
         */
        @Suppress("TooGenericExceptionCaught")
        private inline fun <T> runCatchingUnlessCancelled(block: () -> T): T? =
            try {
                block()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Timber.w(error, "Could not top up a subtitle sidecar")
                null
            }
    }
