package dev.jellyboost.feature.settings

import dev.jellyboost.core.common.model.DownloadStatus
import dev.jellyboost.data.downloads.model.DownloadItem

/**
 * A minimal [DownloadItem], scoped to what this module's tests need.
 *
 * `feature/downloads` carries the full union builder for its own six call sites, but a Gradle test
 * source set only sees its own module's — the same boundary `data/downloads`'
 * `DownloadFixtures.directTransactionRunner` doc names for `:data` and `:data:downloads`. This is
 * settings' own copy, kept to the one field this module's tests ever vary.
 */
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
