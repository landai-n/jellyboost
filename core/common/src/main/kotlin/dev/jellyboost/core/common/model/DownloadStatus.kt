package dev.jellyboost.core.common.model

/**
 * Lifecycle of a single queued download, mirrored 1:1 by `DownloadEntity.status` and `DownloadFileEntity.status`.
 *
 * [PAUSED] is a *user* decision that survives process death; a download stopped because the device left Wi-Fi
 * goes back to [QUEUED] instead, so WorkManager's constraint resumes it by itself. [ERROR] means an
 * **essential** file failed — an optional one leaves the item [DOWNLOADED] and marks only its own row.
 */
enum class DownloadStatus {
    QUEUED,

    /** The one item currently transferring — the queue runs strictly one at a time. */
    DOWNLOADING,

    PAUSED,

    DOWNLOADED,

    ERROR,

    /** A cancelled row is deleted, so this is a transient state. */
    CANCELLED,
    ;

    val isTerminal: Boolean get() = this == DOWNLOADED || this == CANCELLED

    val isPending: Boolean get() = this == QUEUED || this == DOWNLOADING || this == PAUSED
}
