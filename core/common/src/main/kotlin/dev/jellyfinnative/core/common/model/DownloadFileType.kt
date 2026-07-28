package dev.jellyfinnative.core.common.model

/**
 * What one file inside a downloaded item's directory is (docs/PLAN.md, "Data layer" →
 * `DownloadFileEntity`).
 *
 * [essential] is the whole reason this is an enum and not a string: it decides what a failure
 * means. The media file failing makes the item unplayable and moves it to
 * [DownloadStatus.ERROR]; a poster or a subtitle track failing is recorded on its own row and
 * nothing else changes — the plan's "optional-file failure → file ERROR, item still playable".
 */
enum class DownloadFileType(
    /** `true` when the item cannot be played offline without this file. */
    val essential: Boolean,
) {
    /** The video file itself. The only essential kind. */
    MEDIA(essential = true),

    /** An external text subtitle track, one row per stream index. */
    SUBTITLE(essential = false),

    /** The item's own poster — downloaded first so the queue row has artwork immediately. */
    IMAGE_PRIMARY(essential = false),

    /** The item's backdrop, used by the offline detail header. */
    IMAGE_BACKDROP(essential = false),

    /** The parent series' poster, so an episode can render its show offline. */
    IMAGE_SERIES_PRIMARY(essential = false),

    /** One trickplay tile sheet — offline scrubbing thumbnails (M9 consumes them). */
    TRICKPLAY_TILE(essential = false),
}
