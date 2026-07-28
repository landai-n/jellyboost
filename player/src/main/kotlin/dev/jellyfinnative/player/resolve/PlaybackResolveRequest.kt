package dev.jellyfinnative.player.resolve

import java.util.UUID

/**
 * One request to the server for "how should I play this".
 *
 * The same shape covers opening an item, changing quality, switching a track that the server has
 * to re-mux, and every decoder-fallback retry — all of which are re-negotiations, not local
 * changes (docs/PLAN.md, "Playback pipeline").
 *
 * @param mediaSourceId which media source to play, or `null` to let the resolver apply the
 *   dash-less item-id convention the server expects.
 * @param audioStreamIndex absolute Jellyfin stream index, not a position in the audio list.
 * @param subtitleStreamIndex absolute Jellyfin stream index; `-1` explicitly disables subtitles
 *   whereas `null` lets the server choose the item's default.
 * @param enableDirectPlay / @param enableDirectStream `false` forbids that delivery method, which
 *   is how `DecoderFallbackHandler` forces a transcode after a renderer failure.
 */
data class PlaybackResolveRequest(
    val itemId: UUID,
    val mediaSourceId: String? = null,
    val startPositionTicks: Long = 0L,
    val maxStreamingBitrate: Int? = null,
    val audioStreamIndex: Int? = null,
    val subtitleStreamIndex: Int? = null,
    val enableDirectPlay: Boolean? = null,
    val enableDirectStream: Boolean? = null,
)
