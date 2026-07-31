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
 * #1, B1/B2).
 *
 * The guard therefore remembers two things, not one: the command *applied* to the player, and the
 * command currently *pending*. A repeat of either is a no-op — the applied one because acting twice
 * is the storm, the pending one because it is already going to happen. The distinction matters
 * because a pending command that never applies — superseded by the next one, or cancelled — is
 * *forgotten*. Remembering it would turn the server's own recovery re-send into a no-op and leave
 * this member stuck on a state it never reached, which is the one thing the re-send exists to fix.
 *
 * A command emitted before the newer of those two is stale and dropped, so the group's timeline only
 * ever moves forwards and a straggler cannot displace a newer command still waiting to fire.
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

        /**
         * The command [pending] is waiting to apply.
         *
         * Held only while it can still happen: it is dropped the moment the command applies, is
         * superseded, or is cancelled — a command that never reached the player must not be
         * mistaken for one that did.
         */
        private var pendingScheduled: TakenCommand? = null

        /** The last command actually applied to the player, so its repeats can be told apart. */
        private var lastApplied: TakenCommand? = null

        /**
         * Schedules [command], replacing whatever was pending.
         *
         * Ignored when it is the same command as the one already applied or already pending (same
         * type, instant, position and slot), or when it was emitted before either — see the class
         * docs.
         */
        fun schedule(command: SyncPlayCommand) {
            val taken = TakenCommand(command.identity(), command.emittedAt)
            if (taken.identity == lastApplied?.identity || taken.identity == pendingScheduled?.identity) {
                Timber.d("Ignoring a repeated SyncPlay %s for %s", command.type, command.whenInstant)
                return
            }
            val newestKnown = newestEmittedAt()
            if (newestKnown != null && command.emittedAt.isBefore(newestKnown)) {
                Timber.d("Ignoring a stale SyncPlay %s emitted at %s", command.type, command.emittedAt)
                return
            }
            // Order matters: the superseded command stops being pending — job and record together,
            // before its replacement is written — so nothing it left behind can outlive it.
            pending?.cancel()
            pending = null
            pendingScheduled = taken
            pending =
                scope.launch {
                    val localWhen = timeSync.toLocalTime(command.whenInstant)
                    val waitMillis = Duration.between(clock.instant(), localWhen).toMillis()
                    if (waitMillis > 0L) delay(waitMillis)
                    withContext(mainDispatcher) {
                        // Only ours to clear: a schedule() that overtook us already wrote its own.
                        if (pendingScheduled === taken) pendingScheduled = null
                        lastApplied = taken
                        val result = apply(command, localWhen)
                        _applied.tryEmit(result)
                    }
                    pending = null
                }
        }

        /**
         * Drops the pending command and forgets what was taken on — when the group session ends, and
         * only then (`SyncPlayController.teardown` and `standDown`).
         *
         * The memory goes with it deliberately: the next session is a new timeline, and a command
         * remembered across it would silence the first thing the group said.
         *
         * Deliberately **not** called when the player screen detaches. The screen going away does not
         * take the player with it — `PlaybackService` keeps the shared ExoPlayer playing — so the
         * group's commands still have somewhere to land, and forgetting them would only make the
         * server's "client got lost" re-send re-apply a state the player never left.
         */
        fun cancel() {
            pending?.cancel()
            pending = null
            pendingScheduled = null
            lastApplied = null
        }

        /**
         * The later of the two remembered emission stamps, or `null` when nothing is remembered.
         *
         * Both count: a pending command is the newest thing the server said, and an applied one is
         * the newest thing that happened. Measuring staleness against only one of them would let a
         * straggler through.
         */
        private fun newestEmittedAt(): Instant? =
            listOfNotNull(pendingScheduled, lastApplied).maxOfOrNull { it.emittedAt }

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
