package dev.jellyfinnative.player.syncplay

import dev.jellyfinnative.player.syncplay.model.SyncPlayGroupQueue
import dev.jellyfinnative.player.syncplay.model.SyncPlayGroupSummary
import java.time.Instant
import java.util.UUID

/** Where [SyncPlayController] is: outside a group, on the way in, or in one. */
sealed interface SyncPlayState {
    /** Not in a group, and nothing running — no websocket, no ping loop, no scheduled command. */
    data object Idle : SyncPlayState

    /**
     * The websocket collection is open and the join (or create) call is in flight.
     *
     * Short-lived by construction: it ends either at [InGroup] or back at [Idle].
     */
    data object Joining : SyncPlayState

    /**
     * In a group.
     *
     * [queue] is `null` until the server's first `PlayQueueUpdate` arrives — a group can be joined
     * before anyone has chosen anything to watch.
     */
    data class InGroup(
        val group: SyncPlayGroupSummary,
        val queue: SyncPlayGroupQueue?,
        val phase: SyncPlayPhase,
    ) : SyncPlayState

    /**
     * Membership was lost server-side without anyone here asking for it, and is being taken back.
     *
     * Deliberately **not** a kind of [InGroup]: while this lasts the server really does not have this
     * session in [group], so anything keyed on membership — the reported server session for a
     * downloaded file above all (`SyncPlayLocalSession`) — has to see it go away and come back.
     * Short-lived by construction, like [Joining]: it ends at [InGroup] or at [Idle], within
     * `SyncPlayController.REJOIN_MAX_ATTEMPTS` attempts.
     *
     * @param attempt 1-based, for the log and for the UI to be able to say "still trying".
     */
    data class Rejoining(
        val group: SyncPlayGroupSummary,
        val attempt: Int,
    ) : SyncPlayState
}

/**
 * What *this member* is doing inside the group.
 *
 * Deliberately not the same thing as the group's own `SyncPlayGroupState`: the group can be playing
 * while this client is still loading the file, and that difference is exactly what the WAITING
 * overlay (M11 Phase 3) shows.
 */
sealed interface SyncPlayPhase {
    /** Prepared, ready reported, waiting for the server to say go. */
    data object Waiting : SyncPlayPhase

    /** Loading the item; the server has been told this client is buffering. */
    data object Buffering : SyncPlayPhase

    /** Playing, in lockstep with [anchor] — the only phase the drift monitor runs in. */
    data class Playing(
        val anchor: SyncPlayAnchor,
    ) : SyncPlayPhase

    /** Paused, by the group's command. */
    data object Paused : SyncPlayPhase
}

/**
 * "At server instant [at], playback was at [positionMs]" — the fixed point group playback is
 * measured against.
 *
 * Everything the drift monitor does follows from it: the position this client *should* be at is
 * `positionMs + (serverNow − at)`, so a wrong anchor is indistinguishable from a wrong clock.
 * [at] is on the **server's** clock, never the device's.
 */
data class SyncPlayAnchor(
    val positionMs: Long,
    val at: Instant,
)

/**
 * Something the user has to be told about, in the shape the existing player uses
 * (`PlayerUiState.PlayerMessage`): a typed event, with the copy owned by the UI layer.
 *
 * Collected from `SyncPlayController.messages` by the player screen and the groups screen
 * (M11 Phases 3 and 5).
 */
enum class SyncPlayMessage {
    /**
     * The connection was confirmed lost while in a group, so the group was left and playback paused.
     *
     * The user-visible copy is "Left SyncPlay — connection lost" (docs/notes/syncplay-m11-plan.md,
     * key decision 10 as amended twice): resuming from here plays solo. It is only reached once an
     * automatic rejoin has been tried and could not get the membership back.
     */
    ConnectionLost,

    /**
     * The server had dropped this session from the group and it has been taken back automatically.
     *
     * Low-key on purpose: nothing is asked of the user, and the group's own state has already put
     * this member back in step (DECISIONS.md 2026-07-31, auto-rejoin).
     */
    Rejoined,

    /** The join or create call failed; nothing was joined. */
    JoinFailed,

    /** The group no longer exists — the last other member left, or the server restarted. */
    GroupEnded,

    /** The server says this session is not in the group any more. */
    RemovedFromGroup,

    /** The group is watching something this account is not allowed to see. */
    LibraryAccessDenied,

    /** The group's current item could not be opened on this device. */
    ItemUnavailable,
}

/**
 * "The group moved to an item and there is no player open" — the app should navigate to one.
 *
 * Emitted rather than acted on because the controller has no idea what a screen is; the NavHost
 * collects these (M11 Phase 5). It is the other half of key decision 5: membership survives leaving
 * the player, so the group can move on while nothing is attached, and the app has to catch up.
 */
data class SyncPlayLaunchRequest(
    val itemId: UUID,
    val startPositionTicks: Long,
)
