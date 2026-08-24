package dev.jellyboost.player.syncplay.presence

import dev.jellyboost.player.syncplay.SyncPlayState

/**
 * `Joining` and `Rejoining` demand presence deliberately: being killed mid-handshake loses the
 * membership just as `InGroup` would. Playback releases it — `PlaybackService` is already a
 * foreground service holding the same process's network.
 */
internal fun syncPlayPresenceDemanded(
    state: SyncPlayState,
    playbackServiceRunning: Boolean,
): Boolean = state !is SyncPlayState.Idle && !playbackServiceRunning
