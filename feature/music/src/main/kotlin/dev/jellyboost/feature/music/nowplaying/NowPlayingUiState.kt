package dev.jellyboost.feature.music.nowplaying

import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.common.model.UserData
import dev.jellyboost.core.common.music.LyricLine
import dev.jellyboost.core.common.music.Lyrics
import dev.jellyboost.core.common.music.MusicPlaybackState
import dev.jellyboost.core.common.music.MusicRepeatMode

/**
 * Everything [dev.jellyboost.feature.music.nowplaying.NowPlayingScreen] draws, derived from
 * [MusicController.state][dev.jellyboost.core.common.music.MusicController.state] (M13 Phase 4,
 * docs/notes/music-m13-plan.md).
 *
 * A plain data class rather than the controller's own [MusicPlaybackState] passed straight through,
 * for one reason: the favourite heart. The controller's queue is a snapshot taken when [play][
 * dev.jellyboost.core.common.music.MusicController.play] resolved it, and a favourite toggled
 * elsewhere in the app — the album screen behind this one, say — reaches this screen only through
 * [dev.jellyboost.data.userdata.UserDataRepository]'s change bus, the same local-first patch every
 * sibling detail ViewModel applies (`AlbumDetailViewModel.withUserDataIfMatching`). [toNowPlayingUiState]
 * is where that overlay happens, kept as a pure function so the derivation is testable without a
 * ViewModel or a fake controller.
 */
data class NowPlayingUiState(
    /** `true` while [dev.jellyboost.core.common.music.MusicController.state] is `Idle`. */
    val isIdle: Boolean = true,
    /** The track at [currentIndex], or `null` for the brief window before a queue exists. */
    val track: JellyfinItem? = null,
    val queue: List<JellyfinItem> = emptyList(),
    val currentIndex: Int = 0,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val repeatMode: MusicRepeatMode = MusicRepeatMode.OFF,
    /**
     * [track]'s lyrics (M13 Phase 6), or `null` while idle, still fetching, or the server has none
     * for this track — all three collapse onto the same "hide the affordance" state
     * [dev.jellyboost.feature.music.nowplaying.LyricsPane] draws for.
     */
    val lyrics: Lyrics? = null,
) {
    /** `true` once there is something [LyricsPane] can actually show. */
    val lyricsAvailable: Boolean get() = !lyrics?.lines.isNullOrEmpty()

    /**
     * The synced lyric line [positionMs] falls under, or `null` when [lyrics] is absent, unsynced,
     * or playback has not reached the first timed line yet.
     */
    val activeLyricLineIndex: Int?
        get() = lyrics?.takeIf { it.isSynced }?.let { activeLyricLineIndex(it.lines, positionMs) }
}

/**
 * Maps the controller's queue state onto what the screen draws, overlaying [favoriteOverrides] —
 * the local user-data changes seen since this screen started collecting — onto every queue item
 * that one names.
 *
 * @param favoriteOverrides itemId → the newest [UserData] this app itself has written for it. Only
 *   ever grows via [dev.jellyboost.data.userdata.UserDataRepository.changes]; a queue snapshot may
 *   be minutes old by the time a favourite toggled on another screen reaches it.
 * @param lyricsByTrackId itemId → the lyrics [NowPlayingViewModel] fetched for it, or `null` for a
 *   track the server has none for — [NowPlayingViewModel]'s own per-itemId cache (M13 Phase 6).
 *   Absent from this map entirely means "not fetched yet", which reads the same as `null` here.
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
 * The index of [lines] whose timing [positionMs] currently falls under, or `null` before the first
 * timed line (nothing to highlight yet).
 *
 * [lines] is assumed to already be in server order, which is chronological for a synced lyric file
 * — the same assumption the highlight relies on rather than re-sorting on every position tick. A
 * line with no [LyricLine.startTicks] (a blank separator inside an otherwise-synced file) is
 * skipped rather than treated as "starts at 0": it keeps whatever line came before it active. Once
 * [positionMs] passes the last timed line, that line stays active — there is nothing after it to
 * hand off to, which is the same "stay on the last one" rule [MusicPlaybackState.Active] itself
 * follows when a queue runs out.
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

/**
 * Jellyfin's 100ns ticks, expressed per millisecond.
 *
 * `:player`'s own `PlaybackSnapshot.kt` redeclares the same constant locally rather than sharing
 * it — `:feature:music` cannot depend on `:player` — and this file follows the same precedent.
 */
private const val TICKS_PER_MILLISECOND = 10_000L
