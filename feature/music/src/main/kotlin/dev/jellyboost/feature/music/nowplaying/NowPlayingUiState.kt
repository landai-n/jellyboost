package dev.jellyboost.feature.music.nowplaying

import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.common.model.UserData
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
)

/**
 * Maps the controller's queue state onto what the screen draws, overlaying [favoriteOverrides] —
 * the local user-data changes seen since this screen started collecting — onto every queue item
 * that one names.
 *
 * @param favoriteOverrides itemId → the newest [UserData] this app itself has written for it. Only
 *   ever grows via [dev.jellyboost.data.userdata.UserDataRepository.changes]; a queue snapshot may
 *   be minutes old by the time a favourite toggled on another screen reaches it.
 */
internal fun MusicPlaybackState.toNowPlayingUiState(
    favoriteOverrides: Map<String, UserData> = emptyMap(),
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
            NowPlayingUiState(
                isIdle = false,
                track = patchedQueue.getOrNull(currentIndex),
                queue = patchedQueue,
                currentIndex = currentIndex,
                isPlaying = isPlaying,
                positionMs = positionMs,
                durationMs = durationMs,
                shuffleEnabled = shuffleEnabled,
                repeatMode = repeatMode,
            )
        }
    }
