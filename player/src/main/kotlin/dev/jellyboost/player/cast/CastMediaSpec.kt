package dev.jellyboost.player.cast

/**
 * A pure description of what to load onto a Cast receiver.
 *
 * The cast half of `PlaybackMediaItemSpec`, and it exists for the same reason: everything that has
 * to be *decided* — which URL, which credentials on it, which tracks and under which ids — is
 * decided in plain data by `CastSpecMapper`, so that it can be unit tested. What is left is
 * `CastMediaItemConverter`, a mechanical assembly of `MediaInfo` that needs a device to run at all.
 *
 * Nothing here names a `com.google.android.gms` type, which is what lets the mapper's tests run
 * without a Cast stack.
 *
 * @property mediaId `MediaItem.mediaId`; the Jellyfin item id, used to correlate player callbacks.
 * @property contentId the URL the **receiver** fetches. It already carries its `ApiKey` — the
 *   receiver is not this app and never sees `JellyfinAuthInterceptor`'s header.
 * @property contentType MIME type of [contentId]. Not optional as it is for ExoPlayer: a receiver
 *   does not sniff, and an HLS playlist offered without `application/x-mpegURL` is fetched as a
 *   progressive file and fails.
 * @property durationMs the item's runtime, `0` when the server does not know it.
 * @property startPositionMs where the server was asked to start.
 */
data class CastMediaSpec(
    val mediaId: String,
    val contentId: String,
    val contentType: String,
    val streamType: CastStreamType,
    val durationMs: Long,
    val startPositionMs: Long,
    val metadata: CastMetadata,
    val tracks: List<CastTrackSpec>,
)

/** Whether the receiver should treat the content as a seekable file or as a live feed. */
enum class CastStreamType {
    Buffered,
    Live,
}

/**
 * One side-loaded subtitle track, as the receiver will address it.
 *
 * @property id **the Jellyfin stream index**, used verbatim as the Cast `MediaTrack` id. Cast lets
 *   the sender choose the ids, so choosing the ones the rest of the app already speaks means
 *   `CastPlayerHandle.selectSubtitleTrack` can hand `RemoteMediaClient.setActiveMediaTracks` the
 *   index it was given without a lookup table in between.
 * @property mimeType always `text/vtt`; see `CastDeviceProfile`'s subtitle profiles.
 */
data class CastTrackSpec(
    val id: Int,
    val uri: String,
    val mimeType: String,
    val label: String,
    val language: String,
)

/**
 * What the receiver puts on the television while the item plays.
 *
 * Separate from the rest of the spec because it comes from somewhere else entirely: the title and
 * the artwork are the *screen's* material (`PlayerUiState`), not the playback negotiation's, and a
 * `PlaybackInfo` response carries neither. Every field is optional — a receiver with no metadata
 * shows its idle backdrop, which is a cosmetic loss and never a playback failure.
 */
data class CastMetadata(
    val title: String? = null,
    val subtitle: String? = null,
    val posterUrl: String? = null,
)
