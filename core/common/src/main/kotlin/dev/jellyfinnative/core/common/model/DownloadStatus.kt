package dev.jellyfinnative.core.common.model

/**
 * Lifecycle of a single queued download, mirrored 1:1 by `DownloadEntity.status` and by
 * `DownloadFileEntity.status` (docs/PLAN.md, "Data layer").
 *
 * Lives in `:core:common` rather than in `:data:downloads` because `:core:database` persists it and
 * cannot depend on a module that sits above it (DECISIONS.md 2026-07-28, "M7: `DownloadStatus` and
 * `DownloadFileType` moved to `:core:common`").
 *
 * The six states are exactly the ones the plan names. Two distinctions are worth spelling out:
 *
 * - [PAUSED] is a *user* decision that survives a process death; a download stopped because the
 *   device left Wi-Fi goes back to [QUEUED] instead, so WorkManager's constraint resumes it by
 *   itself when the network comes back.
 * - [ERROR] means an **essential** file failed. An optional file (artwork, a subtitle track) that
 *   fails leaves the item [DOWNLOADED] and marks only its own row [ERROR] — the item is still
 *   playable offline, which is the property the plan's "essential/optional" split protects.
 */
enum class DownloadStatus {
    /** Accepted into the queue, waiting for its turn. */
    QUEUED,

    /** The one item currently transferring — the queue runs strictly one at a time. */
    DOWNLOADING,

    /** Stopped by the user; only an explicit resume moves it back to [QUEUED]. */
    PAUSED,

    /** Every essential file is on disk. */
    DOWNLOADED,

    /** An essential file failed; the item is not playable offline. */
    ERROR,

    /** Removed from the queue. A cancelled row is deleted, so this is a transient state. */
    CANCELLED,
    ;

    /** `true` once the queue has nothing left to do for this row. */
    val isTerminal: Boolean get() = this == DOWNLOADED || this == CANCELLED

    /** `true` while the row occupies a slot in the queue tab. */
    val isPending: Boolean get() = this == QUEUED || this == DOWNLOADING || this == PAUSED
}
