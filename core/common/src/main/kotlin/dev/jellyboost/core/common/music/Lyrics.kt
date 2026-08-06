package dev.jellyboost.core.common.music

/**
 * A track's lyrics, synced or plain (M13 Phase 6, docs/notes/music-m13-plan.md, key decision 11).
 *
 * The domain shape for `LyricsApi.getLyrics`'s `LyricDto` — `:data`'s `OnlineJellyfinRepository`
 * maps one onto this, never the other way; `NowPlayingViewModel` and `LyricsPane` (`:feature:music`)
 * never see the SDK type, the same "UI never sees DTO" contract every other domain model in this
 * app follows.
 *
 * @param isSynced `true` when [lines] carry real timing and the pane should highlight/auto-scroll
 *   the active line from the position ticker; `false` renders [lines] as static, scrollable text.
 */
data class Lyrics(
    val lines: List<LyricLine>,
    val isSynced: Boolean,
)

/**
 * One line of lyrics.
 *
 * @param startTicks when this line begins, in Jellyfin's 100ns ticks — `null` for a line the
 *   source had no timing for (an unsynced lyric file, or a blank separator line inside an otherwise
 *   synced one).
 */
data class LyricLine(
    val startTicks: Long?,
    val text: String,
)
