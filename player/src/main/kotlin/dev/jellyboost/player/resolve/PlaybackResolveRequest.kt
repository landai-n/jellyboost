package dev.jellyboost.player.resolve

import java.util.UUID

/**
 * One request to the server for "how should I play this".
 *
 * The same shape covers opening an item, changing quality, switching a track that the server has
 * to re-mux, and every decoder-fallback retry — all of which are re-negotiations, not local
 * changes.
 *
 * @param mediaSourceId which media source to play, or `null` to let the resolver apply the
 *   dash-less item-id convention the server expects.
 * @param audioStreamIndex absolute Jellyfin stream index, not a position in the audio list.
 * @param subtitleStreamIndex absolute Jellyfin stream index; `-1` explicitly disables subtitles
 *   whereas `null` lets the server choose the item's default.
 * @param enableDirectPlay / @param enableDirectStream `false` forbids that delivery method, which
 *   is how `DecoderFallbackHandler` forces a transcode after a renderer failure.
 * @param forceRemote skips the download on disk and goes to the server even for an item that is
 *   fully downloaded. It exists for exactly one caller: a track change an online user asked for that
 *   the downloaded file cannot supply. Without it the re-resolve would run `LocalPlaybackResolver`
 *   over the same file and hand back the same tracks, so the switch could never be applied — see
 *   `PlayerViewModel.selectAudioTrack`. Distinct from `enableDirectPlay = false`, which says "these
 *   *bytes* cannot be decoded" and therefore also forbids the server's own direct play.
 * @param castTarget whether the stream is for a **Cast receiver** rather than for this device. It
 *   changes two things and nothing else: the negotiation is sent with `CastDeviceProfile` instead of
 *   this device's, because the decoders that matter are the television's; and, like [forceRemote],
 *   it skips the copy on disk, because a `file://` URI means nothing on the other side of the
 *   network.
 * @param autoBitrate whether the cap should be chosen by measurement, not by the user:
 *   `PlaybackInfoResolver` overwrites [maxStreamingBitrate] with `AutoBitrateDetector`'s value when
 *   this is set. A caller building an Auto request therefore leaves the
 *   cap `null` and never waits on the measurement itself. Carried through to
 *   `RemotePlaybackMediaSource` so the picker can tell a measured 8 Mbps from a hand-picked one.
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
