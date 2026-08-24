package dev.jellyboost.core.common.model

/**
 * What one file inside a downloaded item's directory is.
 *
 * [essential] is the whole reason this is an enum and not a string: it decides what a failure
 * means. The media file failing makes the item unplayable and moves it to
 * [DownloadStatus.ERROR]; a poster or a subtitle track failing is recorded on its own row and
 * nothing else changes: optional-file failure means the file goes to ERROR, the item stays
 * playable.
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

    /** One trickplay tile sheet — offline scrubbing thumbnails. */
    TRICKPLAY_TILE(essential = false),

    /**
     * An extra audio language of a transcoded download, one row per stream.
     *
     * A transcode bakes in exactly one audio track (`DownloadQuality`'s "hard ceiling"), so every
     * other language is fetched separately — as a video+audio `.mkv`, the only shape the server will
     * hand a specific `audioStreamIndex` over in — and stripped locally to
     * `audio.<index>.<lang>.m4a`, the file this row actually names.
     *
     * No migration: an unrecognised stored name decodes to [TRICKPLAY_TILE] (`DownloadConverters.kt`,
     * `DownloadFileTypeConverter.toDownloadFileType`), the least-essential kind, so a row an older
     * build cannot read degrades to a silently-skipped optional file rather than crashing it. Schema
     * stays v8 — this entry needs no column, only a name Room has never seen.
     */
    AUDIO(essential = false),
}
