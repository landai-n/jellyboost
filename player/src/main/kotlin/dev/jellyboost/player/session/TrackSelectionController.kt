package dev.jellyboost.player.session

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import dev.jellyboost.player.model.PlaybackMediaSource
import dev.jellyboost.player.model.jellyfinIndexOfTrackId
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
 * Three bridges close the gap:
 *
 * - **side-loaded subtitles** carry an `external:<jellyfinIndex>` track id, set by
 *   `ExoMediaSourceFactory` and read back through [jellyfinIndexOfTrackId];
 * - **side-loaded audio** — a downloaded item's per-language sidecar files — has no id to carry:
 *   `MediaItem` cannot name an audio source's tracks the way `SubtitleConfiguration` names a
 *   subtitle's. It is matched by **merge-child position** instead, the k-th external audio track of
 *   the source being merge child `k + 1` because `ExoPlayerHandle.prepare` builds them in exactly
 *   that order (DECISIONS.md 2026-07-31, "Offline multi-track Phase 2");
 * - **embedded streams** are matched by their position among the *embedded* streams of the same
 *   type, which is the order ExoPlayer exposes them in.
 *
 * The id the player hands back is not the id that went in — merging a side-loaded source prefixes
 * every format and group with the child's index — which is why the subtitle read goes through
 * [jellyfinIndexOfTrackId] and never compares strings here, and why the audio read has anything to
 * navigate by at all. Every branch depends on it: a prefix misread is not merely a missed match, it
 * also makes a side-loaded group indistinguishable from a container one and so shifts the
 * positional count for everything else.
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
     * Selects the audio stream [jellyfinIndex].
     *
     * Two kinds of track, and which one this is decides how it is found:
     *
     * - **side-loaded** (a downloaded item's audio sidecars): matched by *merge-child position*.
     *   The source's external audio tracks are in the same ascending-index order the spec listed
     *   the sidecar files in, and [ExoPlayerHandle.prepare] made file `i` merge child `i + 1`, so
     *   the k-th external track is the group whose id begins `"${k + 1}:"`. There is no id to match
     *   on the way subtitles have one — `MediaItem` cannot name an audio source's tracks.
     * - **in the container** (everything streamed, and an original download): matched by position
     *   among the *container's* audio groups, as before. The sidecar groups share the list and are
     *   excluded first by the same prefix; a group with no prefix, prefix 0, or a prefix past the
     *   last sidecar is the primary source's.
     *
     * With no sidecars the exclusion is empty and this is the plain positional match it always was.
     *
     * @return `false` when the requested audio stream is not in the current ExoPlayer track list,
     *   meaning the caller has to ask the server for it instead.
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
     * Selects the subtitle stream [jellyfinIndex], or turns subtitles off when it is `null`.
     *
     * Side-loaded subtitles are found by track id, which is exact. Embedded ones fall back to
     * positional matching; a subtitle the server burned into the video has neither and forces a
     * re-resolve.
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
     * Which merge child published this track group, or `null` when nothing merged it.
     *
     * `MergingMediaPeriod.onPrepared` republishes each child's groups as
     * `new TrackGroup(childIndex + ":" + trackGroup.id, …)` (Media3 1.9.0), so the **leading** run
     * of digits before the first `:` is the child index. Only the leading one is read: a doubly
     * merged group — the main source of a downloaded item that has audio sidecars *and* subtitles
     * is merged once by `DefaultMediaSourceFactory` and again by `ExoPlayerHandle` — reads `0:0:1`
     * and is child 0 of the outer merge, which is what matters here.
     *
     * A group whose own id merely starts with digits and a colon cannot be told apart from a merged
     * one, and is not meant to be: unmerged, nothing else claims a child index, and merged, the
     * prefix in front of it is the answer.
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
