package dev.jellyboost.core.common.music

/**
 * @param isSynced `true` when [lines] carry real timing and the pane should highlight/auto-scroll the active
 *   line; `false` renders [lines] as static, scrollable text.
 */
data class Lyrics(
    val lines: List<LyricLine>,
    val isSynced: Boolean,
)

/**
 * @param startTicks when this line begins, in Jellyfin ticks — `null` for a line the source had no timing
 *   for (an unsynced file, or a blank separator inside an otherwise synced one).
 */
data class LyricLine(
    val startTicks: Long?,
    val text: String,
)
