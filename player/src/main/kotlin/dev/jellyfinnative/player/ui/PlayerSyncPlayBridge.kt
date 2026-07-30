package dev.jellyfinnative.player.ui

import dev.jellyfinnative.player.model.millisToTicks
import dev.jellyfinnative.player.syncplay.SyncPlayController
import dev.jellyfinnative.player.syncplay.SyncPlayMessage
import dev.jellyfinnative.player.syncplay.SyncPlayPhase
import dev.jellyfinnative.player.syncplay.SyncPlayPlaybackHost
import dev.jellyfinnative.player.syncplay.SyncPlayState
import dev.jellyfinnative.player.syncplay.model.SyncPlayRepeatMode
import dev.jellyfinnative.player.syncplay.model.SyncPlayShuffleMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The player's half of SyncPlay: one object between [PlayerViewModel] and [SyncPlayController].
 *
 * It exists so that group membership costs the ViewModel a *question* rather than a branch —
 * [isInGroup] plus a handful of one-line requests — and so that the mapping from the protocol's
 * vocabulary to the screen's ([PlayerSyncPlayState], [PlayerMessage]) lives somewhere it can be read
 * in one go. The ViewModel is already the longest class in the module (audit ARCH-10); this is the
 * same decomposition the position tracker and the session store got.
 *
 * ### The rule it enforces at this end
 * In a group, **no user action moves this player**: play, pause, seek and skip all become requests,
 * and the player moves only when the server rebroadcasts the command to everyone
 * (docs/notes/syncplay-m11-plan.md, key decision 11). The controller enforces the same rule at its
 * end — there is no path from an intent to `PlayerHandle` — so this class never needs to know what
 * the player is doing, only whether there is a group to ask.
 *
 * Attachment is idempotent because both ends can trigger it: the ViewModel attaches when its session
 * opens, and the controller's own `loadItem` opens a session. Without the guard, a group-driven load
 * would re-enter `attachHost` in the middle of the reconciliation that caused it.
 */
internal class PlayerSyncPlayBridge(
    private val controller: SyncPlayController,
    private val host: SyncPlayPlaybackHost,
) {
    private var attached = false

    /** `true` while this session is part of a group, and therefore not its own master. */
    val isInGroup: Boolean get() = controller.state.value is SyncPlayState.InGroup

    /** The group, as the player screen draws it; conflated, so a re-anchor changes nothing. */
    val states: Flow<PlayerSyncPlayState> = controller.state.map { it.toPlayerState() }.distinctUntilChanged()

    /** What the group needs to tell the user, in the player's own message vocabulary. */
    val messages: Flow<PlayerMessage> = controller.messages.map { it.toPlayerMessage() }

    /**
     * Offers this player to the group.
     *
     * A no-op outside a group beyond recording the offer — the controller keeps the host either way,
     * which is what lets a group joined later find a player already open.
     */
    fun attach() {
        if (attached) return
        attached = true
        controller.attachHost(host)
    }

    /**
     * Takes the player back.
     *
     * The controller sends `ignoreWait` from here (key decision 5) so a member with no player never
     * gates the group; this end must not send it as well.
     */
    fun detach() {
        if (!attached) return
        attached = false
        controller.detachHost(host)
    }

    /** Tells the group this member is re-negotiating, so it waits rather than plays on without us. */
    fun onBuffering() {
        if (isInGroup) controller.onHostBuffering()
    }

    /** @param isPlaying what the player is doing now, which is what the tap is asking to reverse. */
    fun requestPlayPause(isPlaying: Boolean) {
        if (isPlaying) controller.requestPause() else controller.requestUnpause()
    }

    fun requestSeek(positionMs: Long) {
        controller.requestSeek(positionMs.millisToTicks())
    }

    fun leaveGroup() {
        controller.leaveGroup()
    }

    fun setShuffle(shuffled: Boolean) {
        controller.setShuffle(if (shuffled) SyncPlayShuffleMode.Shuffle else SyncPlayShuffleMode.Sorted)
    }

    fun setRepeat(mode: SyncPlayRepeatMode) {
        controller.setRepeat(mode)
    }
}

private fun SyncPlayState.toPlayerState(): PlayerSyncPlayState =
    when (this) {
        // Joining is deliberately not "in a group": it lasts a round trip, and a control surface
        // that rearranges itself for it would flicker on every join.
        SyncPlayState.Idle, SyncPlayState.Joining -> PlayerSyncPlayState()

        is SyncPlayState.InGroup ->
            PlayerSyncPlayState(
                inGroup = true,
                groupName = group.name,
                participants = group.participants,
                phase = phase.toPlayerPhase(),
                queueSize = queue?.entries?.size ?: 0,
                hasQueue = queue?.playingEntry != null,
                isShuffled = queue?.shuffleMode == SyncPlayShuffleMode.Shuffle,
                repeatMode = queue?.repeatMode ?: SyncPlayRepeatMode.None,
            )
    }

/**
 * Drops the drift anchor on the way to the screen.
 *
 * `SyncPlayPhase.Playing` carries the anchor the drift monitor measures against, and it is replaced
 * on every group unpause. Keeping it in [PlayerUiState] would make the whole control surface
 * unequal to its predecessor for a value nothing on screen draws (audit PERF-04's rule).
 */
private fun SyncPlayPhase.toPlayerPhase(): PlayerSyncPlayPhase =
    when (this) {
        SyncPlayPhase.Waiting -> PlayerSyncPlayPhase.WAITING
        SyncPlayPhase.Buffering -> PlayerSyncPlayPhase.BUFFERING
        is SyncPlayPhase.Playing -> PlayerSyncPlayPhase.PLAYING
        SyncPlayPhase.Paused -> PlayerSyncPlayPhase.PAUSED
    }

private fun SyncPlayMessage.toPlayerMessage(): PlayerMessage =
    when (this) {
        SyncPlayMessage.ConnectionLost -> PlayerMessage.SyncPlayConnectionLost
        SyncPlayMessage.JoinFailed -> PlayerMessage.SyncPlayJoinFailed
        SyncPlayMessage.GroupEnded -> PlayerMessage.SyncPlayGroupEnded
        SyncPlayMessage.RemovedFromGroup -> PlayerMessage.SyncPlayRemoved
        SyncPlayMessage.LibraryAccessDenied -> PlayerMessage.SyncPlayLibraryAccessDenied
        SyncPlayMessage.ItemUnavailable -> PlayerMessage.SyncPlayItemUnavailable
    }
