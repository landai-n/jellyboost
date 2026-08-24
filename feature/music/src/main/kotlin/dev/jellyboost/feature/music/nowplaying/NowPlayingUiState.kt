package dev.jellyboost.feature.music.nowplaying

import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.common.model.UserData
import dev.jellyboost.core.common.music.LyricLine
import dev.jellyboost.core.common.music.Lyrics
import dev.jellyboost.core.common.music.MusicPlaybackState
import dev.jellyboost.core.common.music.MusicRepeatMode

/**
 * Not the controller's own [MusicPlaybackState] passed through, for one reason: the favourite heart.
 * The controller's queue is a snapshot from when `play()` resolved it, so a favourite toggled
 * elsewhere reaches this screen only via the user-data change bus, overlaid in
 * [toNowPlayingUiState].
 */
data class NowPlayingUiState(
    val isIdle: Boolean = true,
    /** `null` for the brief window before a queue exists. */
    val track: JellyfinItem? = null,
    val queue: List<JellyfinItem> = emptyList(),
    val currentIndex: Int = 0,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val repeatMode: MusicRepeatMode = MusicRepeatMode.OFF,
    /**
     * `null` while idle, still fetching, *and* when the server has none — all three deliberately
     * collapse onto the same "hide the affordance" state.
     */
    val lyrics: Lyrics? = null,
) {
    val lyricsAvailable: Boolean get() = !lyrics?.lines.isNullOrEmpty()

    /** `null` when [lyrics] is absent, unsynced, or before the first timed line. */
    val activeLyricLineIndex: Int?
        get() = lyrics?.takeIf { it.isSynced }?.let { activeLyricLineIndex(it.lines, positionMs) }
}

/**
 * @param favoriteOverrides itemId → the newest [UserData] this app itself has written. A queue
 *   snapshot may be minutes old by the time a favourite toggled elsewhere reaches it.
 * @param lyricsByTrackId a `null` value means "fetched, the server has none"; a **missing key**
 *   means "not fetched yet". Both read the same here.
 */
internal fun MusicPlaybackState.toNowPlayingUiState(
    favoriteOverrides: Map<String, UserData> = emptyMap(),
    lyricsByTrackId: Map<String, Lyrics?> = emptyMap(),
): NowPlayingUiState =
    when (this) {
        MusicPlaybackState.Idle -> NowPlayingUiState(isIdle = true)

        is MusicPlaybackState.Active -> {
            val patchedQueue =
                if (favoriteOverrides.isEmpty()) {
                    queue
                } else {
                    queue.map { item -> favoriteOverrides[item.id]?.let { item.copy(userData = it) } ?: item }
                }
            val track = patchedQueue.getOrNull(currentIndex)
            NowPlayingUiState(
                isIdle = false,
                track = track,
                queue = patchedQueue,
                currentIndex = currentIndex,
                isPlaying = isPlaying,
                positionMs = positionMs,
                durationMs = durationMs,
                shuffleEnabled = shuffleEnabled,
                repeatMode = repeatMode,
                lyrics = track?.let { lyricsByTrackId[it.id] },
            )
        }
    }

/**
 * [lines] **must** already be in chronological server order — nothing re-sorts here, since this runs
 * on every position tick. A line with no [LyricLine.startTicks] (a blank separator in an otherwise
 * synced file) is skipped, not treated as "starts at 0", so the previous line stays active. Past the
 * last timed line, that line stays active.
 */
internal fun activeLyricLineIndex(
    lines: List<LyricLine>,
    positionMs: Long,
): Int? =
    lines
        .withIndex()
        .filter { (_, line) -> (line.startTicks ?: return@filter false) / TICKS_PER_MILLISECOND <= positionMs }
        .maxByOrNull { (index, _) -> index }
        ?.index

/** Jellyfin's 100ns ticks per millisecond. Redeclared, as `:player`'s `PlaybackSnapshot.kt` does. */
private const val TICKS_PER_MILLISECOND = 10_000L
