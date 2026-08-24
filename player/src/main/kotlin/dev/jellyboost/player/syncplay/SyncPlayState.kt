package dev.jellyboost.player.syncplay

import dev.jellyboost.player.syncplay.model.SyncPlayGroupQueue
import dev.jellyboost.player.syncplay.model.SyncPlayGroupState
import dev.jellyboost.player.syncplay.model.SyncPlayGroupSummary
import java.time.Instant
import java.util.UUID

internal sealed interface SyncPlayState {
    /** Nothing running: no websocket, no ping loop, no scheduled command. */
    data object Idle : SyncPlayState

    data object Joining : SyncPlayState

    /** [queue] is `null` until the first `PlayQueueUpdate`: a group can be joined before it plays. */
    data class InGroup(
        val group: SyncPlayGroupSummary,
        val queue: SyncPlayGroupQueue?,
        /**
         * What the **group** is doing — never confuse with [phase], which is what *this member* is
         * doing and is a lie after a `SendCommand` that never arrived. Nets are measured against this.
         */
        val groupState: SyncPlayGroupState,
        val phase: SyncPlayPhase,
    ) : SyncPlayState

    /**
     * Membership lost server-side and being taken back. Deliberately **not** a kind of [InGroup]:
     * the server really does not have this session in [group], so anything keyed on membership —
     * `SyncPlayLocalSession` above all — must see it go away and come back.
     *
     * @param attempt 1-based.
     */
    data class Rejoining(
        val group: SyncPlayGroupSummary,
        val attempt: Int,
    ) : SyncPlayState
}

/**
 * What *this member* is doing — not the group's own `SyncPlayGroupState`. The difference is what the
 * WAITING overlay shows.
 */
internal sealed interface SyncPlayPhase {
    /** Prepared, ready reported, waiting for the server to say go. */
    data object Waiting : SyncPlayPhase

    data object Buffering : SyncPlayPhase

    /** The only phase the drift monitor runs in. */
    data class Playing(
        val anchor: SyncPlayAnchor,
    ) : SyncPlayPhase

    data object Paused : SyncPlayPhase
}

/**
 * "At server instant [at], playback was at [positionMs]". The drift monitor's target position is
 * `positionMs + (serverNow − at)`, so [at] is on the **server's** clock, never the device's.
 */
internal data class SyncPlayAnchor(
    val positionMs: Long,
    val at: Instant,
)

/** A typed event; the copy belongs to the UI layer. */
enum class SyncPlayMessage {
    /** Only after an automatic rejoin failed: the group was left and playback paused. */
    ConnectionLost,

    /** The session was dropped server-side and taken back automatically; nothing is asked of the user. */
    Rejoined,

    JoinFailed,

    /** The group no longer exists — the last other member left, or the server restarted. */
    GroupEnded,

    RemovedFromGroup,

    LibraryAccessDenied,

    ItemUnavailable,
}

/**
 * "The group moved to an item and there is no player open" — the NavHost collects these. Membership
 * survives leaving the player, so the group can move on while nothing is attached.
 */
data class SyncPlayLaunchRequest(
    val itemId: UUID,
    val startPositionTicks: Long,
)
