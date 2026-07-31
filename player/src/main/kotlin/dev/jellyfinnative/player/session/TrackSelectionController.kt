package dev.jellyfinnative.player.session

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import dev.jellyfinnative.player.model.PlaybackMediaSource
import dev.jellyfinnative.player.model.jellyfinIndexOfTrackId
import timber.log.Timber

/**
 * Maps Jellyfin stream indices onto ExoPlayer tracks.
 *
 * The two numbering schemes do not line up, and that mismatch is the classic source of "the
 * subtitle picker selects the wrong language" bugs:
 *
 * - Jellyfin numbers **all** streams of a file in one sequence — video, audio, subtitles, external
 *   subtitle files — and its API parameters use those absolute indices.
 * - ExoPlayer sees one track group per stream it was actually given, numbered per type, and knows
 *   nothing about streams the server withheld.
 *
 * Two bridges close the gap: side-loaded subtitles carry an `external:<jellyfinIndex>` track id
 * (set by `ExoMediaSourceFactory`), and embedded streams are matched by their position among the
 * *embedded* streams of the same type, which is the order ExoPlayer exposes them in.
 *
 * The id the player hands back is not the id that went in — merging a side-loaded source prefixes
 * it with the child's index — which is why the read goes through [jellyfinIndexOfTrackId] and never
 * compares strings here. Both branches depend on it: an id it fails to decode is not merely a
 * missed exact match, it also makes a side-loaded group indistinguishable from a container one and
 * so shifts the positional count for everything else.
 */
internal class TrackSelectionController(
    private val player: Player,
) {
    /**
     * Returns the process-wide player's selection parameters to a clean slate for a new media item.
     *
     * `TrackSelectionParameters` belong to the [Player], not to the item it is playing, and the
     * player here is a singleton shared with `PlaybackService` — so an override left by the last
     * item, or the disabled text renderer that "subtitles off" leaves behind, silently governs
     * whatever is prepared next. One film watched without subtitles would keep every later one from
     * ever showing any, and an audio override would name a track group the new stream does not have.
     *
     * Called from `PlayerHandle.prepare`, before the item is set. What *this* session wants is
     * applied afterwards, once the player reports the tracks it actually got.
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
     * @return `false` when the requested audio stream is not in the current ExoPlayer track list,
     *   meaning the caller has to ask the server for it instead.
     */
    fun selectAudio(
        source: PlaybackMediaSource,
        jellyfinIndex: Int,
    ): Boolean {
        val position =
            source.audioTracks
                .filterNot { it.isExternal }
                .indexOfFirst { it.index == jellyfinIndex }
                .takeIf { it >= 0 } ?: return false

        val group = groupsOfType(C.TRACK_TYPE_AUDIO).getOrNull(position) ?: return false
        applyOverride(C.TRACK_TYPE_AUDIO, group)
        return true
    }

    /**
     * Selects the subtitle stream [jellyfinIndex], or turns subtitles off when it is `null`.
     *
     * Side-loaded subtitles are found by track id, which is exact. Embedded ones fall back to
     * positional matching; a subtitle the server burned into the video has neither and forces a
     * re-resolve.
     */
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
