package dev.jellyboost.player.session

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import dev.jellyboost.player.model.PlaybackMediaSource
import dev.jellyboost.player.model.jellyfinIndexOfTrackId
import timber.log.Timber

/**
 * Maps Jellyfin stream indices onto ExoPlayer tracks. The schemes do not line up: Jellyfin numbers
 * *all* streams of a file in one sequence, ExoPlayer numbers per type and only the streams it got.
 *
 * Three bridges: side-loaded subtitles carry an `external:<jellyfinIndex>` track id; side-loaded
 * audio has no id (`MediaItem` cannot name an audio source's tracks) and is matched by merge-child
 * position; embedded streams — a transcode's HLS renditions included — by position among embedded
 * streams of the same type.
 *
 * The id the player hands back is not the id that went in: merging prefixes every format and group
 * with the child index. Never compare track-id strings here — a prefix misread also makes a
 * side-loaded group look like a container one and shifts every positional count.
 */
internal class TrackSelectionController(
    private val player: Player,
) {
    /**
     * `TrackSelectionParameters` belong to the [Player], which here is a singleton shared with
     * `PlaybackService`: overrides and a disabled text renderer outlive the item that set them.
     *
     * Must be called before the new item is set; this session's own selection is applied after the
     * player reports the tracks it actually got.
     */
    fun reset() {
        player.trackSelectionParameters =
            player.trackSelectionParameters
                .buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .build()
    }

    /**
     * Side-loaded audio is matched by merge-child position: [ExoPlayerHandle.prepare] makes sidecar
     * file `i` merge child `i + 1`, so the k-th external track's group id begins `"${k + 1}:"`.
     * Container audio is matched positionally *after* excluding those sidecar groups.
     *
     * @return `false` when the stream is not in the current track list and the caller must re-resolve.
     */
    fun selectAudio(
        source: PlaybackMediaSource,
        jellyfinIndex: Int,
    ): Boolean {
        val audioGroups = groupsOfType(C.TRACK_TYPE_AUDIO)
        val sideLoaded = source.audioTracks.filter { it.isExternal }
        val sidecarChildren = 1..sideLoaded.size

        val group =
            when (val ordinal = sideLoaded.indexOfFirst { it.index == jellyfinIndex }) {
                -1 -> {
                    val position =
                        source.audioTracks
                            .filterNot { it.isExternal }
                            .indexOfFirst { it.index == jellyfinIndex }
                            .takeIf { it >= 0 } ?: return false
                    audioGroups
                        .filterNot { group ->
                            mergeChildIndex(group.mediaTrackGroup.id)?.let { it in sidecarChildren } == true
                        }.getOrNull(position)
                }

                else ->
                    audioGroups.firstOrNull { group ->
                        mergeChildIndex(group.mediaTrackGroup.id) == ordinal + 1
                    }
            } ?: return false

        applyOverride(C.TRACK_TYPE_AUDIO, group)
        return true
    }

    /**
     * `null` [jellyfinIndex] turns subtitles off — by disabling the whole text renderer, not merely
     * clearing the override: on a transcode every rendition is `AUTOSELECT=YES` with one
     * `DEFAULT=YES`, so a cleared selector picks one on its own.
     */
    @Suppress(
        // Mirrors `CastPlayerHandle.selectSubtitleTrack` deliberately — the two must pick the same track.
        "ReturnCount",
    )
    fun selectSubtitle(
        source: PlaybackMediaSource,
        jellyfinIndex: Int?,
    ): Boolean {
        if (jellyfinIndex == null) {
            player.trackSelectionParameters =
                player.trackSelectionParameters
                    .buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
            return true
        }

        val textGroups = groupsOfType(C.TRACK_TYPE_TEXT)

        val external =
            textGroups.firstOrNull { group ->
                (0 until group.length).any { jellyfinIndexOfTrackId(group.getTrackFormat(it).id) == jellyfinIndex }
            }
        if (external != null) {
            applyOverride(C.TRACK_TYPE_TEXT, external)
            return true
        }

        val embeddedPosition =
            source.subtitleTracks
                .filterNot { it.isExternal }
                .indexOfFirst { it.index == jellyfinIndex }
                .takeIf { it >= 0 } ?: return false

        // Side-loaded groups also live in this list, so they have to be excluded before counting.
        val embeddedGroups =
            textGroups.filter { group ->
                (0 until group.length).none { jellyfinIndexOfTrackId(group.getTrackFormat(it).id) != null }
            }
        val group = embeddedGroups.getOrNull(embeddedPosition) ?: return false
        applyOverride(C.TRACK_TYPE_TEXT, group)
        return true
    }

    private fun groupsOfType(trackType: Int): List<Tracks.Group> =
        player.currentTracks.groups.filter { it.type == trackType }

    /**
     * `MergingMediaPeriod.onPrepared` republishes child groups as `childIndex + ":" + id`
     * (Media3 1.9.0), so only the **leading** digit run counts: a doubly merged group reads `0:0:1`
     * and is child 0 of the outer merge, which is the one that matters here.
     */
    private fun mergeChildIndex(id: String?): Int? {
        val separator = id?.indexOf(':') ?: return null
        if (separator <= 0) return null
        val prefix = id.substring(0, separator)
        return if (prefix.all(Char::isDigit)) prefix.toIntOrNull() else null
    }

    private fun applyOverride(
        trackType: Int,
        group: Tracks.Group,
    ) {
        Timber.d("Selecting %s track group %s", trackType, group.mediaTrackGroup.id)
        player.trackSelectionParameters =
            player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(trackType, false)
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
                .build()
    }
}
