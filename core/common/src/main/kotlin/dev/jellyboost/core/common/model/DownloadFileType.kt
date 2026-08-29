package dev.jellyboost.core.common.model

/**
 * [essential] decides what a failure means: the media file failing makes the item unplayable and moves it to
 * [DownloadStatus.ERROR], while a poster or subtitle failing is recorded on its own row and the item stays
 * playable.
 */
enum class DownloadFileType(
    val essential: Boolean,
) {
    MEDIA(essential = true),

    /** One row per stream index. */
    SUBTITLE(essential = false),

    /** Downloaded first, so the queue row has artwork immediately. */
    IMAGE_PRIMARY(essential = false),

    IMAGE_BACKDROP(essential = false),

    IMAGE_SERIES_PRIMARY(essential = false),

    TRICKPLAY_TILE(essential = false),

    /**
     * An extra audio language of a transcoded download, one row per stream. A transcode bakes in exactly one
     * audio track, so every other language is fetched separately as a video+audio `.mkv` — the only shape the
     * server will hand a specific `audioStreamIndex` over in — and stripped locally to `.m4a`.
     *
     * No migration: an unrecognised stored name decodes to [TRICKPLAY_TILE], the least-essential kind, so a
     * row an older build cannot read degrades to a skipped optional file rather than crashing it.
     */
    AUDIO(essential = false),

    /**
     * One font attached to the source container, fetched for a **transcoded** download that carries an
     * ASS/SSA sidecar. The server's re-encode holds video and audio only, so the attachments libass would
     * otherwise read out of the `.mkv` are gone, and a styled subtitle falls back to the default family.
     * An `ORIGINAL` download keeps the whole container and therefore needs none of these — including when
     * it carries a sidecar anyway, which an *external* subtitle does at every quality.
     *
     * Same no-migration note as [AUDIO]: an older build decodes the unknown name to [TRICKPLAY_TILE] and
     * skips the file.
     */
    FONT(essential = false),
}
