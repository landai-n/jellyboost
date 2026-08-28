package dev.jellyboost.player.session

import dev.jellyboost.player.model.PlaybackMediaItemSpec

/**
 * Whether libass can still find its track for [spec] once the source has been merged.
 *
 * `MergingMediaPeriod` republishes every child format as `childIndex + ":" + id`, and `ass-media`
 * strips exactly **one** such prefix before matching a selected format against the track it parsed.
 * A spec carrying audio sidecars *and* side-loaded subtitles is merged twice — `DefaultMediaSourceFactory`
 * merges the subtitles into the main source, then [ExoPlayerHandle] merges the sidecars around it — so
 * the id arrives as `0:1:external:2`, one strip leaves `1:external:2`, and no track ever matches.
 * libass would then draw nothing at all, which is worse than the unstyled rendering it replaced.
 *
 * Those items keep Media3's own SSA parsing: unstyled, but on screen.
 */
internal fun styledAssSurvivesMerge(spec: PlaybackMediaItemSpec): Boolean =
    spec.audioSidecars.isEmpty() || spec.subtitles.isEmpty()
