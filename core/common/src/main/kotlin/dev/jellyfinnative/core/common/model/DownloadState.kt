package dev.jellyfinnative.core.common.model

/**
 * Download state of an item as far as the UI is concerned.
 *
 * Every item card renders a `DownloadBadge` from this — the one visual marker that distinguishes
 * downloaded media in the otherwise identical online/offline UI (docs/PLAN.md).
 *
 * The full pipeline that produces these states lands in M7; until then everything is
 * [NotDownloaded].
 */
sealed interface DownloadState {
    /** Not present on this device and not queued. */
    data object NotDownloaded : DownloadState

    /** Accepted into the download queue but not started. */
    data object Queued : DownloadState

    /** Actively transferring; [progress] is `0f..1f`. */
    data class Downloading(
        val progress: Float,
    ) : DownloadState

    /** Started, then paused by the user or by a constraint (e.g. Wi-Fi only). */
    data object Paused : DownloadState

    /** Fully on device and playable offline. */
    data object Downloaded : DownloadState

    /** An essential file failed; the item is not playable offline. */
    data object Failed : DownloadState

    /** `true` while the item occupies a slot in the download queue. */
    val isActive: Boolean
        get() = this is Queued || this is Downloading || this is Paused

    /**
     * `true` when asking for this item to be downloaded would actually do something.
     *
     * Anything already on the device or already in the queue is excluded; [Failed] is **not**, since
     * re-enqueueing a failure is exactly how it is retried (it keeps its queue position and the
     * bytes already on disk). This mirrors `DownloadEnqueuer.isRetryable`, which applies the same
     * rule to the episodes under a container — the difference is that a container expansion is the
     * only place the pipeline applies it, so a batch of *singles* has to ask the question itself.
     */
    val isDownloadable: Boolean
        get() = this is NotDownloaded || this is Failed
}
