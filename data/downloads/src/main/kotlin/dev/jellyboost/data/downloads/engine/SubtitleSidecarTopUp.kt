package dev.jellyboost.data.downloads.engine

import dev.jellyboost.core.common.di.IoDispatcher
import dev.jellyboost.core.common.model.DownloadFileType
import dev.jellyboost.core.common.model.DownloadStatus
import dev.jellyboost.core.common.runCatchingUnlessCancelled
import dev.jellyboost.core.database.dao.DownloadDao
import dev.jellyboost.core.database.entities.DownloadEntity
import dev.jellyboost.core.database.entities.DownloadFileEntity
import dev.jellyboost.core.database.entities.DownloadWithFiles
import dev.jellyboost.core.datastore.AppPreferences
import dev.jellyboost.data.downloads.plan.DownloadFilePlanner
import dev.jellyboost.data.downloads.plan.PlannedFile
import dev.jellyboost.data.downloads.storage.DownloadStorage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import org.jellyfin.sdk.model.api.BaseItemDto
import timber.log.Timber
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches the optional sidecars a **finished** download is missing — subtitles and the fonts they
 * name — without touching its media file.
 *
 * Not "just re-queue the row": the queue walks *every* file in the plan, and a transcoded media file
 * cannot be resumed — the server ignores `Range` on a live transcode, answers `200`, and
 * `FileDownloader` truncates and rewrites from zero. Picking up a 40 KB subtitle would spend the whole
 * download again, so this fetches the missing optional files directly and leaves the row `DOWNLOADED`.
 *
 * - Nothing at all on a metered connection while the user has asked for Wi-Fi-only downloads: that
 *   preference is normally WorkManager's `UNMETERED` constraint on the queue worker, but this fetches
 *   on the application scope where no constraint applies, so it must enforce the rule itself.
 * - Only rows that are [DownloadStatus.DOWNLOADED] — anything still in the queue is the queue's, and
 *   the two must not write the same file rows at once.
 * - Only [TOPPED_UP_TYPES] entries of the plan — the subtitle sidecars this is named for, and since
 *   2026-08-29 the font attachments that go with them, so a download taken before styled ASS shipped
 *   gains its faces on the next connectivity edge instead of drawing in the fallback family forever.
 * - Only files that are genuinely absent, which is what makes running this on every connectivity edge
 *   cheap.
 * - Never throws. A failure leaves exactly the gap that was there before.
 *
 * The plan is rebuilt from the **row's own quality**, the rule `DownloadQueue.reconcile` follows: the
 * point is to give this download the files today's planner would have given it, not to re-decide it.
 */
@Singleton
internal class SubtitleSidecarTopUp
    @Suppress(
        "LongParameterList",
    )
    @Inject
    constructor(
        private val downloadDao: DownloadDao,
        private val planner: DownloadFilePlanner,
        private val storage: DownloadStorage,
        private val downloader: FileDownloader,
        private val preferences: AppPreferences,
        private val metered: MeteredConnection,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        /**
         * @param items freshly-fetched DTOs — a stale cached blob is exactly what would plan the wrong
         *   stream set.
         * @return how many sidecar files were actually fetched.
         */
        suspend fun topUp(items: List<BaseItemDto>): Int {
            if (items.isEmpty()) return 0
            if (preferences.downloadOverWifiOnly.first() && metered.isMetered()) {
                Timber.i("Skipping the sidecar top-up: downloads are Wi-Fi-only and this connection is metered")
                return 0
            }

            var fetched = 0
            for (item in items) {
                fetched += topUpOne(item)
            }
            if (fetched > 0) Timber.i("Fetched %d missing sidecar file(s) for downloaded items", fetched)
            return fetched
        }

        private suspend fun topUpOne(item: BaseItemDto): Int {
            val stored = finishedDownload(item.id) ?: return 0
            val missing = missingSidecars(item, stored.download, stored.files)
            if (missing.isEmpty()) return 0

            Timber.i("%s is missing %d sidecar file(s); fetching them", stored.download.itemName, missing.size)
            return missing.count { planned -> fetch(stored.download, planned, stored.files) }
        }

        /** The download row and its files, or `null` when this item is not a finished download. */
        private suspend fun finishedDownload(itemId: UUID): DownloadWithFiles? =
            topUpOrNull { downloadDao.getWithFiles(itemId) }
                ?.takeIf { it.download.status == DownloadStatus.DOWNLOADED }

        /**
         * The sidecars today's plan wants that this download does not have. A file counts as present
         * only when its row says `DOWNLOADED` **and** the bytes are still there — the same test
         * `DownloadedMediaProvider` applies, so this repairs exactly the set the player would withhold.
         */
        private fun missingSidecars(
            item: BaseItemDto,
            download: DownloadEntity,
            files: List<DownloadFileEntity>,
        ): List<PlannedFile> {
            val planned =
                topUpOrNull {
                    planner.plan(item, download.directoryName, quality = download.quality)
                } ?: return emptyList()

            return planned
                .filter { it.type in TOPPED_UP_TYPES }
                .filterNot { file ->
                    files.any { row ->
                        row.type == file.type &&
                            row.streamIndex == file.streamIndex &&
                            row.status == DownloadStatus.DOWNLOADED &&
                            File(row.path).isFile
                    }
                }
        }

        /**
         * Fetches one sidecar and records it. An existing row keeps its file name, for
         * `DownloadQueue.reconcile`'s reason: the name on the row *is* whatever is on disk, and the
         * plan cannot be trusted to reproduce it.
         *
         * @return `true` when the file is now on disk.
         */
        private suspend fun fetch(
            download: DownloadEntity,
            planned: PlannedFile,
            files: List<DownloadFileEntity>,
        ): Boolean =
            topUpOrNull {
                storage.prepareItemDirectory(download.directoryName)
                val previous =
                    files.firstOrNull {
                        it.type == planned.type && it.streamIndex == planned.streamIndex
                    }
                val fileName = previous?.fileName ?: planned.fileName
                val target = storage.resolve(download.directoryName, fileName)

                val row =
                    when (previous) {
                        null -> {
                            val fresh =
                                DownloadFileEntity(
                                    itemId = download.itemId,
                                    type = planned.type,
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
         * [runCatchingUnlessCancelled] with this class's "never throws" promise on top of it. A failure
         * is one gap left unrepaired — the state the item was already in — so it is logged and folded
         * to `null`. The cancellation still gets through: this runs on the application scope, and
         * swallowing it would keep a coroutine writing to Room after the scope was torn down.
         */
        private inline fun <T> topUpOrNull(block: () -> T): T? =
            runCatchingUnlessCancelled(block)
                .onFailure { Timber.w(it, "Could not top up a sidecar file") }
                .getOrNull()

        private companion object {
            /**
             * Both optional kinds that a *finished* row can be missing and that the planner will name
             * again from the row's own quality. Fonts join subtitles because the planner only ever emits
             * one alongside the other, so topping up a subtitle without its faces would leave exactly the
             * gap this exists to close.
             */
            val TOPPED_UP_TYPES = setOf(DownloadFileType.SUBTITLE, DownloadFileType.FONT)
        }
    }
