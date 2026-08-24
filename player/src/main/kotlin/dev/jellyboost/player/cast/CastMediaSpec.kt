package dev.jellyboost.player.cast

/**
 * Names no `com.google.android.gms` type, so `CastSpecMapper`'s tests run without a Cast stack.
 *
 * @property contentId the URL the **receiver** fetches, so it must carry its `ApiKey` in the URL —
 *   the receiver never sees `JellyfinAuthInterceptor`'s header.
 * @property contentType required, unlike for ExoPlayer: a receiver does not sniff, and an HLS
 *   playlist offered without `application/x-mpegURL` is fetched as a progressive file and fails.
 * @property durationMs `0` when the server does not know it.
 */
internal data class CastMediaSpec(
    val mediaId: String,
    val contentId: String,
    val contentType: String,
    val streamType: CastStreamType,
    val durationMs: Long,
    val startPositionMs: Long,
    val metadata: CastMetadata,
    val tracks: List<CastTrackSpec>,
)

internal enum class CastStreamType {
    Buffered,
    Live,
}

/**
 * @property id the Jellyfin stream index, used verbatim as the Cast `MediaTrack` id so that
 *   `CastPlayerHandle.selectSubtitleTrack` needs no lookup table.
 * @property mimeType always `text/vtt`; see `CastDeviceProfile`'s subtitle profiles.
 */
internal data class CastTrackSpec(
    val id: Int,
    val uri: String,
    val mimeType: String,
    val label: String,
    val language: String,
)

/** Every field stays optional: missing metadata costs an idle backdrop, never playback. */
internal data class CastMetadata(
    val title: String? = null,
    val subtitle: String? = null,
    val posterUrl: String? = null,
)
