package dev.jellyfinnative.data.downloads

/** Lifecycle of a single queued download, mirrored 1:1 by `DownloadEntity.status` (M7). */
enum class DownloadStatus {
    QUEUED,
    DOWNLOADING,
    PAUSED,
    DOWNLOADED,
    ERROR,
    CANCELLED,
    ;

    val isTerminal: Boolean get() = this == DOWNLOADED || this == CANCELLED
}
