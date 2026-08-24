package dev.jellyboost.player.resolve

import java.util.UUID

/**
 * @param mediaSourceId `null` lets the resolver apply the dash-less item-id convention the server
 *   expects.
 * @param audioStreamIndex absolute Jellyfin stream index, not a position in the audio list.
 * @param subtitleStreamIndex absolute Jellyfin stream index; `-1` explicitly disables subtitles,
 *   `null` lets the server choose the item's default.
 * @param enableDirectPlay / @param enableDirectStream `false` forbids that delivery method — how
 *   `DecoderFallbackHandler` forces a transcode after a renderer failure.
 * @param forceRemote ignores the downloaded copy so a track the local file lacks can be resolved;
 *   distinct from `enableDirectPlay = false`, which says these *bytes* cannot be decoded.
 * @param castTarget negotiates with `CastDeviceProfile` and ignores the downloaded copy — a `file://`
 *   URI means nothing on the receiver.
 * @param autoBitrate `PlaybackInfoResolver` overwrites [maxStreamingBitrate] with
 *   `AutoBitrateDetector`'s value, so an Auto caller leaves the cap `null`.
 */
internal data class PlaybackResolveRequest(
    val itemId: UUID,
    val mediaSourceId: String? = null,
    val startPositionTicks: Long = 0L,
    val maxStreamingBitrate: Int? = null,
    val audioStreamIndex: Int? = null,
    val subtitleStreamIndex: Int? = null,
    val enableDirectPlay: Boolean? = null,
    val enableDirectStream: Boolean? = null,
    val forceRemote: Boolean = false,
    val castTarget: Boolean = false,
    val autoBitrate: Boolean = false,
)
