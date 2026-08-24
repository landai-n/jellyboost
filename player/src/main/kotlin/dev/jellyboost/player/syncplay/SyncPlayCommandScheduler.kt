package dev.jellyboost.player.syncplay

import dev.jellyboost.core.common.di.MainDispatcher
import dev.jellyboost.player.model.ticksToMillis
import dev.jellyboost.player.session.PlayerHandle
import dev.jellyboost.player.syncplay.di.SyncPlayScope
import dev.jellyboost.player.syncplay.model.SyncPlayCommand
import dev.jellyboost.player.syncplay.model.SyncPlayCommandType
import dev.jellyboost.player.syncplay.time.SyncPlayTimeSync
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
 * Applies a `SendCommand` at the instant the server named, never on arrival: the server says "play
 * at 20:41:03.250 server time", and applying on arrival would put each member out by its own
 * network latency. One pending slot, because a group is one timeline — a pause issued while an
 * unpause is pending *replaces* it. A past-due command is not dropped: an unpause catches up by
 * seeking to `position + (now − when)`. A command emitted before the newer of the two remembered
 * commands is stale and dropped.
 *
 * ### Applied exactly once
 * The server re-sends the group's *current* state command to a single session whenever it thinks
 * that session got lost ("Client got lost, sending current state") — verbatim, same `when` and
 * position — and acting on such a repeat re-seeks, re-buffers, emits another readiness and earns
 * another repeat: a feedback storm. So both the *applied* and the *pending* command are remembered,
 * and a pending one that never applies is deliberately **forgotten**: remembering it would turn the
 * server's own recovery re-send into a no-op.
 */
@Singleton
internal class SyncPlayCommandScheduler
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
         * Commands after they have been applied to the player — the *applied* moment, not the
         * scheduled one, because an unpause is what establishes the drift monitor's anchor.
         */
        val applied: SharedFlow<SyncPlayAppliedCommand> = _applied.asSharedFlow()

        private var pending: Job? = null

        /**
         * Held only while it can still happen: dropped the moment the command applies, is
         * superseded, or is cancelled — a command that never reached the player must not be
         * mistaken for one that did.
         */
        private var pendingScheduled: TakenCommand? = null

        private var lastApplied: TakenCommand? = null

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
                    // Bookkeeping on the scheduler's own single-threaded scope, never on main, and
                    // written *before* the main hop: the reverse order would let an applied command
                    // go unrecorded and its verbatim re-send re-applied.
                    if (pendingScheduled === taken) pendingScheduled = null
                    lastApplied = taken
                    val result = withContext(mainDispatcher) { apply(command, localWhen) }
                    _applied.tryEmit(result)
                    // Guarded by identity: a completion racing a schedule() that already replaced
                    // this job must not orphan the replacement's cancellation handle.
                    if (pending === coroutineContext[Job]) pending = null
                }
        }

        /**
         * Called when the group session ends, and only then — deliberately **not** when the player
         * screen detaches: `PlaybackService` keeps the shared ExoPlayer playing, so the group's
         * commands still have somewhere to land, and forgetting them would make the server's
         * "client got lost" re-send re-apply a state the player never left.
         */
        fun cancel() {
            pending?.cancel()
            pending = null
            pendingScheduled = null
            lastApplied = null
        }

        /**
         * "Applied" describes the *player*, not this class: a command applied before the player was
         * rebuilt (track change, quality change, decoder fallback) has not been applied to the
         * player that comes back. The rebuild re-runs the buffering→ready handshake and the server
         * answers with the standing command verbatim, which a remembered one would deduplicate away.
         * The pending slot survives, and its `emittedAt` keeps anchoring the staleness check.
         */
        fun forgetApplied() {
            lastApplied = null
        }

        /** Both count: measuring staleness against only one of them would let a straggler through. */
        private fun newestEmittedAt(): Instant? =
            listOfNotNull(pendingScheduled, lastApplied).maxOfOrNull { it.emittedAt }

        private fun apply(
            command: SyncPlayCommand,
            localWhen: Instant,
        ): SyncPlayAppliedCommand {
            Timber.d(
                "Applying SyncPlay %s scheduled for %s, %d ms after its local instant",
                command.type,
                command.whenInstant,
                Duration.between(localWhen, clock.instant()).toMillis(),
            )
            return when (command.type) {
                // Seek first, then pause: the server pairs a pause with the position it wants
                // everyone parked at, and pausing first would leave a visible jump afterwards.
                SyncPlayCommandType.Pause -> {
                    command.positionMillis()?.let(playerHandle::seekTo)
                    playerHandle.pause()
                    SyncPlayAppliedCommand(command, anchor = null)
                }
                // A seek deliberately does not touch play/pause state: the group's own state
                // machine pairs a seek with WAITING, and the unpause ending it restarts playback.
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
            // With no position given, the only reading available is this client's own, wound back
            // by however late the command is.
            val anchorMillis = command.positionMillis() ?: (positionMillis - lateByMillis)
            val targetMillis = anchorMillis + lateByMillis

            // On time and already on the anchor, seeking would only cost a re-buffer.
            if (abs(targetMillis - positionMillis) > SEEK_EPSILON_MS) {
                playerHandle.seekTo(targetMillis)
            }
            playerHandle.play()
            return SyncPlayAppliedCommand(command, SyncPlayAnchor(anchorMillis, command.whenInstant))
        }

        private fun SyncPlayCommand.positionMillis(): Long? = positionTicks?.ticksToMillis()

        private fun SyncPlayCommand.identity() = CommandIdentity(type, whenInstant, positionTicks, playlistItemId)

        /**
         * `emittedAt` is deliberately not part of a command's identity: the server stamps every
         * send with `DateTime.UtcNow`, so a re-send of the identical group state differs in that
         * field alone.
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
             * How far off the anchor an unpause tolerates before it seeks, in milliseconds: small
             * enough that no one sees it, large enough that rounding does not re-buffer.
             */
            const val SEEK_EPSILON_MS = 250L

            private const val APPLIED_BUFFER = 8
        }
    }

/** [anchor] is non-null exactly for an unpause — no other command establishes one. */
internal data class SyncPlayAppliedCommand(
    val command: SyncPlayCommand,
    val anchor: SyncPlayAnchor?,
)
