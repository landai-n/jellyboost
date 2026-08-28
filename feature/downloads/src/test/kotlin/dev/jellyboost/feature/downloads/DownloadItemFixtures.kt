package dev.jellyboost.feature.downloads

import dev.jellyboost.core.common.model.DownloadQuality
import dev.jellyboost.core.common.model.DownloadStatus
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.data.downloads.model.DownloadItem
import java.util.UUID

/**
 * The union of every field the six test classes in this package need, each defaulted to the plainest
 * value that type-checks, so a test's own helper names only what it varies.
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
    itemType: ItemType? = null,
    albumName: String? = null,
    artistName: String? = null,
    groupId: UUID? = null,
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
    itemType = itemType,
    albumName = albumName,
    artistName = artistName,
    groupId = groupId,
    item = item,
)
