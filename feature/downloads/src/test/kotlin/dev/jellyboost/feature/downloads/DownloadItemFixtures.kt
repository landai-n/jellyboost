package dev.jellyboost.feature.downloads

import dev.jellyboost.core.common.model.DownloadQuality
import dev.jellyboost.core.common.model.DownloadStatus
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.data.downloads.model.DownloadItem

/**
 * A [DownloadItem] with every field defaulted.
 *
 * [DownloadProgressRatchetTest], [DownloadSpeedTrackerTest], [DownloadGroupCacheTest],
 * [DownloadsUiStateTest], [DownloadRowsTest] and [DownloadsViewModelTest] would otherwise each
 * hand-roll their own `DownloadItem(...)` literal, hardcoding a different subset of its dozen
 * fields. This is their union — every field any of the six needs, defaulted to the plainest value
 * that type-checks — so each test's own helper can name only the two or three fields it actually
 * varies and delegate the rest here, the way `data/downloads`' `DownloadFixtures` builders do.
 */
@Suppress("LongParameterList")
internal fun downloadItem(
    itemId: String = "1",
    title: String = "Arrival",
    seriesName: String? = null,
    status: DownloadStatus = DownloadStatus.DOWNLOADING,
    bytesDownloaded: Long = 0L,
    bytesTotal: Long = 1_000L,
    bytesOnDisk: Long = 0L,
    queuePosition: Int = 0,
    quality: DownloadQuality = DownloadQuality.ORIGINAL,
    projectedBytes: Long? = null,
    sizeIsExact: Boolean = false,
    errorMessage: String? = null,
    item: JellyfinItem? = null,
) = DownloadItem(
    itemId = itemId,
    title = title,
    seriesName = seriesName,
    status = status,
    bytesDownloaded = bytesDownloaded,
    bytesTotal = bytesTotal,
    bytesOnDisk = bytesOnDisk,
    queuePosition = queuePosition,
    quality = quality,
    projectedBytes = projectedBytes,
    sizeIsExact = sizeIsExact,
    errorMessage = errorMessage,
    item = item,
)
