package dev.jellyboost.player.ui

import dev.jellyboost.player.model.PlaybackMediaSource
import dev.jellyboost.player.model.PlaybackSnapshot
import dev.jellyboost.player.model.millisToTicks
import dev.jellyboost.player.syncplay.SyncPlayController
import dev.jellyboost.player.syncplay.SyncPlayLocalSession
import dev.jellyboost.player.syncplay.SyncPlayMessage
import dev.jellyboost.player.syncplay.SyncPlayPhase
import dev.jellyboost.player.syncplay.SyncPlayPlaybackHost
import dev.jellyboost.player.syncplay.SyncPlayState
import dev.jellyboost.player.syncplay.model.SyncPlayGroupState
import dev.jellyboost.player.syncplay.model.SyncPlayRepeatMode
import dev.jellyboost.player.syncplay.model.SyncPlayShuffleMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map

/**
 * The player's half of SyncPlay, between [PlayerViewModel] and [SyncPlayController].
 *
 * In a group, **no user action moves this player**: play, pause, seek and skip are requests, and the
 * player moves only when the server rebroadcasts the command. Never call `PlayerHandle` from here.
 *
 * Attachment must stay idempotent — both ends trigger it, and without the guard a group-driven load
 * re-enters `attachHost` inside the reconciliation that caused it.
 */
internal class PlayerSyncPlayBridge(
    private val controller: SyncPlayController,
    private val localSession: SyncPlayLocalSession,
    private val host: SyncPlayPlaybackHost,
) {
    private var attached = false

    val isInGroup: Boolean get() = controller.state.value is SyncPlayState.InGroup

    /**
     * Read when the item ends, to keep the screen open: the controller asks the server for the next
     * item, whose `PlayQueueUpdate` reloads *this* session.
     */
    val hasNextInQueue: Boolean
        get() = (controller.state.value as? SyncPlayState.InGroup)?.queue?.hasFollowingEntry == true

    val states: Flow<PlayerSyncPlayState> = controller.state.map { it.toPlayerState() }.distinctUntilChanged()

    val messages: Flow<PlayerMessage> = controller.messages.map { it.toPlayerMessage() }

    /**
     * Join/leave only, unlike [states]. The current value is dropped because the session open itself
     * already reconciles before reporting the start.
     */
    val membership: Flow<Boolean> =
        controller.state
            .map { it is SyncPlayState.InGroup }
            .distinctUntilChanged()
            .drop(1)

    /** The controller keeps the host even outside a group, so a later join finds this player open. */
    fun attach() {
        if (attached) return
        attached = true
        controller.attachHost(host)
    }

    /** The controller sends `ignoreWait` for this; do not send it here as well. */
    fun detach() {
        if (!attached) return
        attached = false
        controller.detachHost(host)
    }

    /**
     * Only meaningful for a **downloaded** item; must be called at both moments that change the
     * answer — a session opening, and the group being joined or left. See [SyncPlayLocalSession].
     */
    suspend fun syncServerSession(
        source: PlaybackMediaSource?,
        snapshot: PlaybackSnapshot,
    ) {
        localSession.reconcile(source, snapshot)
    }

    /** The player screen is going away; the group may well outlive it. */
    fun onSessionClosed() {
        localSession.onSessionClosed()
    }

    fun onBuffering() {
        if (isInGroup) controller.onHostBuffering()
    }

    /**
     * Reverses what the **group** is doing, never what this player is doing: after a missed echo the
     * local `isPlaying` disagrees with the group, and deciding on it re-sends the last command.
     */
    fun requestPlayPause() {
        val groupState = (controller.state.value as? SyncPlayState.InGroup)?.groupState
        if (groupState == SyncPlayGroupState.Playing) controller.requestPause() else controller.requestUnpause()
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
        // Neither Joining nor Rejoining counts as "in a group": while rejoining the server does not
        // have this session in the group, so transport requests would go unanswered.
        SyncPlayState.Idle, SyncPlayState.Joining -> PlayerSyncPlayState()

        is SyncPlayState.Rejoining -> PlayerSyncPlayState()

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
                groupPlaying = groupState == SyncPlayGroupState.Playing,
            )
    }

/**
 * Drops the drift anchor: it is replaced on every group unpause, and keeping it in [PlayerUiState]
 * would make the whole control surface unequal to its predecessor for a value nothing draws.
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
        SyncPlayMessage.Rejoined -> PlayerMessage.SyncPlayRejoined
        SyncPlayMessage.JoinFailed -> PlayerMessage.SyncPlayJoinFailed
        SyncPlayMessage.GroupEnded -> PlayerMessage.SyncPlayGroupEnded
        SyncPlayMessage.RemovedFromGroup -> PlayerMessage.SyncPlayRemoved
        SyncPlayMessage.LibraryAccessDenied -> PlayerMessage.SyncPlayLibraryAccessDenied
        SyncPlayMessage.ItemUnavailable -> PlayerMessage.SyncPlayItemUnavailable
    }
