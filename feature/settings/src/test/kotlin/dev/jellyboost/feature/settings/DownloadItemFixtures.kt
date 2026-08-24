package dev.jellyboost.feature.settings

import dev.jellyboost.core.common.model.DownloadStatus
import dev.jellyboost.data.downloads.model.DownloadItem

/** A Gradle test source set only sees its own module's fixtures, hence this module-local copy. */
internal fun downloadItem(itemId: String) =
    DownloadItem(
        itemId = itemId,
        title = "Item $itemId",
        seriesName = null,
        status = DownloadStatus.DOWNLOADED,
        bytesDownloaded = 0L,
        bytesTotal = 0L,
        bytesOnDisk = 0L,
        queuePosition = 0,
    )
