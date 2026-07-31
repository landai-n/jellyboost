package dev.jellyfinnative.player.syncplay.presence

import dev.jellyfinnative.player.syncplay.SyncPlayState

/**
 * Whether the group needs [SyncPlayPresenceService] to hold the process's network right now.
 *
 * The whole start/stop rule, as a pure function, so it can be pinned by a unit test with no
 * `Context`, no service and no framework in the way — the failure it exists to prevent (a group
 * silently dropped while the app is off screen) is not something a device can be asked to reproduce
 * on demand.
 *
 * Two clauses, each carrying one half of DECISIONS.md 2026-07-31:
 *
 * - **any state but [SyncPlayState.Idle] demands it.** `Joining` and `Rejoining` are included
 *   deliberately: both are moments where the app is talking to the server about a membership it
 *   does not yet have, and being killed in one of them is exactly as bad as being killed in
 *   `InGroup` — worse, since a rejoin is usually happening *because* the connection already
 *   misbehaved once.
 * - **playback releases it.** `PlaybackService` is already a foreground service and already keeps
 *   the network up; a second notification for the same process would be noise.
 */
internal fun syncPlayPresenceDemanded(
    state: SyncPlayState,
    playbackServiceRunning: Boolean,
): Boolean = state !is SyncPlayState.Idle && !playbackServiceRunning
