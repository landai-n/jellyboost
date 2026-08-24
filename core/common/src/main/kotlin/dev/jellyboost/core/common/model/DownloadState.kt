package dev.jellyboost.core.common.model

sealed interface DownloadState {
    data object NotDownloaded : DownloadState

    data object Queued : DownloadState

    /** [progress] is `0f..1f`. */
    data class Downloading(
        val progress: Float,
    ) : DownloadState

    data object Paused : DownloadState

    data object Downloaded : DownloadState

    data object Failed : DownloadState

    val isActive: Boolean
        get() = this is Queued || this is Downloading || this is Paused

    /**
     * Anything already on the device or in the queue is excluded; [Failed] is **not**, since re-enqueueing a
     * failure is how it is retried — it keeps its queue position and the bytes already on disk. Mirrors
     * `DownloadEnqueuer.isRetryable`, which applies the same rule to the episodes under a container.
     */
    val isDownloadable: Boolean
        get() = this is NotDownloaded || this is Failed
}
