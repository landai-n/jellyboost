package dev.jellyfinnative.player.syncplay

import dev.jellyfinnative.player.di.MainDispatcher
import dev.jellyfinnative.player.model.ticksToMillis
import dev.jellyfinnative.player.session.PlayerHandle
import dev.jellyfinnative.player.syncplay.di.SyncPlayScope
import dev.jellyfinnative.player.syncplay.model.SyncPlayCommand
import dev.jellyfinnative.player.syncplay.model.SyncPlayCommandType
import dev.jellyfinnative.player.syncplay.time.SyncPlayTimeSync
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Applies a `SendCommand` to the player at the instant the server said, and not before.
 *
 * This is the whole point of SyncPlay: the server does not say "play now", it says "play at
 * 20:41:03.250 server time", and every member converts that to its own clock and waits. Playing on
 * arrival instead would put each member out by its own network latency, which is exactly the skew
 * the protocol exists to remove.
 *
 * ### One pending slot
 * A group is one timeline, so there is only ever one command worth waiting for: a pause issued
 * while an unpause is still pending *replaces* it. Keeping a queue would let a superseded command
 * fire after the one that overtook it.
 *
 * ### Past-due commands
 * A command whose instant has already passed — the app was backgrounded, the socket reconnected,
 * the join handshake took a moment — is not dropped. An unpause catches up by seeking to
 * `position + (now − when)`, because the group has been playing for that long without us. That is
 * the difference between rejoining in sync and rejoining permanently behind.
 *
 * ### Applied exactly once
 * The server re-sends the group's *current* state command to a single session whenever it thinks
 * that session got lost — `PausedGroupState.HandleRequest(ReadyGroupRequest)` and
 * `PlayingGroupState`'s equivalent both do it, verbatim ("Client got lost, sending current state").
 * Those repeats carry the same `when` and the same position as the command already applied, so
 * acting on them again is at best wasted work and at worst a re-seek that re-buffers, emits another
 * readiness, and earns another repeat — the feedback storm found on device (STATUS.md, DoD session
 * #1, B1/B2). So a command identical to the last one scheduled is a no-op, and a command *emitted*
 * before it is stale and dropped: the group's timeline only ever moves forwards.
 */
@Singleton
class SyncPlayCommandScheduler
    @Inject
    constructor(
        private val playerHandle: PlayerHandle,
        private val timeSync: SyncPlayTimeSync,
        private val clock: Clock,
        @SyncPlayScope private val scope: CoroutineScope,
        @MainDispatcher private val mainDispatcher: CoroutineDispatcher,
    ) {
        private val _applied =
            MutableSharedFlow<SyncPlayAppliedCommand>(
                extraBufferCapacity = APPLIED_BUFFER,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )

        /**
         * Commands after they have been applied to the player.
         *
         * The controller needs the *applied* moment, not the scheduled one: an unpause is what
         * establishes the drift monitor's anchor, and until it actually runs there is nothing to
         * anchor to.
         */
        val applied: SharedFlow<SyncPlayAppliedCommand> = _applied.asSharedFlow()

        private var pending: Job? = null

        /** What was last taken on, so an identical repeat and a stale straggler can be told apart. */
        private var lastTaken: TakenCommand? = null

        /**
         * Schedules [command], replacing whatever was pending.
         *
         * Ignored when it is the same command as the one already taken on (same type, instant,
         * position and slot), or when it was emitted before it — see the class docs.
         */
        fun schedule(command: SyncPlayCommand) {
            val taken = TakenCommand(command.identity(), command.emittedAt)
            lastTaken?.let { previous ->
                if (previous.identity == taken.identity) {
                    Timber.d("Ignoring a repeated SyncPlay %s for %s", command.type, command.whenInstant)
                    return
                }
                if (command.emittedAt.isBefore(previous.emittedAt)) {
                    Timber.d("Ignoring a stale SyncPlay %s emitted at %s", command.type, command.emittedAt)
                    return
                }
            }
            lastTaken = taken
            pending?.cancel()
            pending =
                scope.launch {
                    val localWhen = timeSync.toLocalTime(command.whenInstant)
                    val waitMillis = Duration.between(clock.instant(), localWhen).toMillis()
                    if (waitMillis > 0L) delay(waitMillis)
                    withContext(mainDispatcher) {
                        val result = apply(command, localWhen)
                        _applied.tryEmit(result)
                    }
                    pending = null
                }
        }

        /**
         * Drops the pending command and forgets what was taken on — on leaving a group, and when the
         * player screen detaches.
         *
         * The memory goes with it deliberately: a player that re-attaches has to be told the group's
         * current state again, and that repeat is the only thing that will say it.
         */
        fun cancel() {
            pending?.cancel()
            pending = null
            lastTaken = null
        }

        private fun apply(
            command: SyncPlayCommand,
            localWhen: Instant,
        ): SyncPlayAppliedCommand {
            Timber.d("Applying SyncPlay %s scheduled for %s", command.type, command.whenInstant)
            return when (command.type) {
                // Seek first, then pause: the server pairs a pause with the position it wants
                // everyone parked at, and pausing first would leave a visible jump afterwards.
                SyncPlayCommandType.Pause -> {
                    command.positionMillis()?.let(playerHandle::seekTo)
                    playerHandle.pause()
                    SyncPlayAppliedCommand(command, anchor = null)
                }
                // A seek deliberately does not touch play/pause state. The group's own state machine
                // pairs a seek with WAITING and re-runs the buffering/ready handshake, and the
                // unpause that ends it is what starts playback again.
                SyncPlayCommandType.Seek -> {
                    command.positionMillis()?.let(playerHandle::seekTo)
                    SyncPlayAppliedCommand(command, anchor = null)
                }
                SyncPlayCommandType.Stop -> {
                    playerHandle.stop()
                    SyncPlayAppliedCommand(command, anchor = null)
                }
                SyncPlayCommandType.Unpause -> applyUnpause(command, localWhen)
            }
        }

        private fun applyUnpause(
            command: SyncPlayCommand,
            localWhen: Instant,
        ): SyncPlayAppliedCommand {
            val lateByMillis = Duration.between(localWhen, clock.instant()).toMillis().coerceAtLeast(0L)
            val positionMillis = playerHandle.snapshot().positionMs
            // Where the group was at `when`. With no position given, the only reading available is
            // this client's own, wound back by however late the command is.
            val anchorMillis = command.positionMillis() ?: (positionMillis - lateByMillis)
            val targetMillis = anchorMillis + lateByMillis

            // On time and already on the anchor, seeking would only cost a re-buffer. Off it — by a
            // resume position, or by the catch-up above — it is the only way to start in step.
            if (abs(targetMillis - positionMillis) > SEEK_EPSILON_MS) {
                playerHandle.seekTo(targetMillis)
            }
            playerHandle.play()
            return SyncPlayAppliedCommand(command, SyncPlayAnchor(anchorMillis, command.whenInstant))
        }

        private fun SyncPlayCommand.positionMillis(): Long? = positionTicks?.ticksToMillis()

        private fun SyncPlayCommand.identity() = CommandIdentity(type, whenInstant, positionTicks, playlistItemId)

        /**
         * What makes two `SendCommand`s the same instruction.
         *
         * `emittedAt` is deliberately not part of it: the server stamps every send with
         * `DateTime.UtcNow`, so a re-send of the identical group state differs in that field alone.
         */
        private data class CommandIdentity(
            val type: SyncPlayCommandType,
            val whenInstant: Instant,
            val positionTicks: Long?,
            val playlistItemId: UUID,
        )

        private data class TakenCommand(
            val identity: CommandIdentity,
            val emittedAt: Instant,
        )

        companion object {
            /**
             * How far off the anchor an unpause tolerates before it seeks, in milliseconds.
             *
             * Small enough that no one sees it, large enough that the ordinary "already parked
             * where the group paused" case does not re-buffer for a few frames of rounding.
             */
            const val SEEK_EPSILON_MS = 250L

            /** Applied commands buffered for a controller that is momentarily busy. */
            private const val APPLIED_BUFFER = 8
        }
    }

/**
 * A command, after the scheduler applied it.
 *
 * [anchor] is non-null exactly for an unpause — it is the fixed point the drift monitor measures
 * against, and no other command establishes one.
 */
data class SyncPlayAppliedCommand(
    val command: SyncPlayCommand,
    val anchor: SyncPlayAnchor?,
)
