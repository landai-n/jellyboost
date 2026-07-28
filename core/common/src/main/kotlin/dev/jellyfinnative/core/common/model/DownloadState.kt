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
}
