package dev.jellyboost.player.cast

import dev.jellyboost.player.PlayerFixtures
import dev.jellyboost.player.deviceprofile.CastReceiverClass
import dev.jellyboost.player.model.PlaybackMediaSource
import dev.jellyboost.player.model.PlaybackSnapshot
import dev.jellyboost.player.report.PlaybackReporter
import dev.jellyboost.player.session.FakePlayerHandle
import dev.jellyboost.player.session.PlayerEvent
import dev.jellyboost.player.session.RoutingPlayerHandle
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.jupiter.api.Test
import java.util.UUID
import javax.inject.Provider
import kotlin.time.Duration.Companion.milliseconds

/**
 * [CastSessionMonitor] keeps the Cast framework's session lifecycle out of this suite, so these
 * tests run without Play services, a `CastContext`, or a receiver on the network.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CastSessionCoordinatorTest {
    private val dispatcher = StandardTestDispatcher()

    private val local = FakePlayerHandle()
    private val cast = FakePlayerHandle()
    private val routing = RoutingPlayerHandle(local, Provider { cast })

    private val reporter = mockk<PlaybackReporter>(relaxed = true)
    private val status = CastStatusHolder()
    private val metadata = CastMetadataHolder()

    /** Captures the coordinator's listener so a test can be the Cast framework. */
    private val monitor =
        object : CastSessionMonitor {
            var listener: CastSessionListener? = null

            override fun start(listener: CastSessionListener) {
                this.listener = listener
            }
        }

    private val coordinator =
        CastSessionCoordinator(
            monitor = monitor,
            routing = routing,
            reporter = reporter,
            status = status,
            detachedScope = CoroutineScope(dispatcher),
            mainDispatcher = dispatcher,
            metadata = metadata,
        ).also { it.start() }

    private val framework get() = requireNotNull(monitor.listener) { "The coordinator never started watching" }

    private val source = PlayerFixtures.remoteSource()

    private val host =
        object : CastPlaybackHost {
            override var castSource: PlaybackMediaSource? = source
        }

    @Test
    fun `nothing is casting until the framework says so`() {
        coordinator.isCasting shouldBe false
        coordinator.connection.value shouldBe CastConnection.None
        routing.activeHandle.value shouldBe local
    }

    @Test
    fun `a session start puts the receiver in charge and names it`() {
        framework.onSessionStarted("Living Room TV")

        coordinator.connection.value shouldBe CastConnection.Connected("Living Room TV")
        coordinator.isCasting shouldBe true
        routing.activeHandle.value shouldBe cast
    }

    @Test
    fun `the receiver's model is classified once, at session start`() {
        framework.onSessionStarted("Salon", "Chromecast Ultra")

        coordinator.connection.value shouldBe
            CastConnection.Connected("Salon", CastReceiverClass.ULTRA_4K)
        // Which is what `PlaybackInfoResolver` reads at negotiation time.
        status.receiver shouldBe CastReceiverClass.ULTRA_4K
    }

    @Test
    fun `an unknown model lands on the conservative floor`() {
        framework.onSessionStarted("Salon", "Some Future Receiver")

        status.receiver shouldBe CastReceiverClass.LEGACY_1080P
    }

    @Test
    fun `a session end hands playback back to this device`() {
        framework.onSessionStarted("Living Room TV")

        framework.onSessionEnded()

        coordinator.connection.value shouldBe CastConnection.None
        coordinator.isCasting shouldBe false
        routing.activeHandle.value shouldBe local
    }

    @Test
    fun `with a screen attached the coordinator reports nothing — the screen does`() {
        framework.onSessionStarted("Living Room TV")
        coordinator.attachHost(host)

        framework.onSessionEnded()

        // Two stop reports would double the encoder kill and race each other for the position.
        verify(exactly = 0) { reporter.reportStopDetached(any(), any()) }
        verify(exactly = 0) { reporter.startReporting(any(), any(), any()) }
    }

    @Test
    fun `once the screen goes the coordinator takes the progress ticker over`() {
        every { reporter.startReporting(any(), any(), any()) } returns Job()
        framework.onSessionStarted("Living Room TV")
        coordinator.attachHost(host)

        coordinator.detachHost(host)

        verify(exactly = 1) { reporter.startReporting(any(), any(), any()) }
    }

    @Test
    fun `a screen that goes with nothing casting starts no ticker`() {
        coordinator.attachHost(host)

        coordinator.detachHost(host)

        verify(exactly = 0) { reporter.startReporting(any(), any(), any()) }
    }

    @Test
    fun `a local session's detach leaves no orphan for a later failed cast attempt to report`() {
        // Must not remember the local film as "what the receiver was playing" and stop-report it at zero.
        coordinator.attachHost(host)
        coordinator.detachHost(host)

        framework.onSessionEnded() // a start failure surfaces as an end, with no start before it

        verify(exactly = 0) { reporter.reportStopDetached(any(), any()) }
    }

    @Test
    fun `a host with nothing open leaves nothing to report`() {
        every { reporter.startReporting(any(), any(), any()) } returns Job()
        framework.onSessionStarted("Living Room TV")
        coordinator.attachHost(host)
        host.castSource = null

        coordinator.detachHost(host)

        verify(exactly = 0) { reporter.startReporting(any(), any(), any()) }
    }

    @Test
    fun `a stale screen's teardown cannot detach the one that replaced it`() {
        every { reporter.startReporting(any(), any(), any()) } returns Job()
        framework.onSessionStarted("Living Room TV")
        coordinator.attachHost(host)

        coordinator.detachHost(
            object : CastPlaybackHost {
                override val castSource: PlaybackMediaSource? = source
            },
        )

        verify(exactly = 0) { reporter.startReporting(any(), any(), any()) }
    }

    @Test
    fun `the ticker stops when a screen comes back`() {
        val ticker = Job()
        every { reporter.startReporting(any(), any(), any()) } returns ticker
        framework.onSessionStarted("Living Room TV")
        coordinator.attachHost(host)
        coordinator.detachHost(host)

        coordinator.attachHost(host)

        ticker.isCancelled shouldBe true
    }

    @Test
    fun `a session that ends with no screen sends the final stop itself`() {
        every { reporter.startReporting(any(), any(), any()) } returns Job()
        val onTheTelevision = PlaybackSnapshot(positionMs = 90_000L, isPlaying = true)
        cast.snapshot = onTheTelevision
        framework.onSessionStarted("Living Room TV")
        coordinator.attachHost(host)
        coordinator.detachHost(host)

        framework.onSessionEnded()

        // Position must be read off the *cast* player, before routing goes back to a local one
        // that never played anything.
        verify(exactly = 1) { reporter.reportStopDetached(source, onTheTelevision) }
        routing.activeHandle.value shouldBe local
    }

    @Test
    fun `the ticker is cancelled when the session ends`() {
        val ticker = Job()
        every { reporter.startReporting(any(), any(), any()) } returns ticker
        framework.onSessionStarted("Living Room TV")
        coordinator.attachHost(host)
        coordinator.detachHost(host)

        framework.onSessionEnded()

        ticker.isCancelled shouldBe true
    }

    @Test
    fun `a resumed session is a started one, since a receiver that is playing is a receiver`() {
        framework.onSessionStarted(null)

        coordinator.connection.value shouldBe CastConnection.Connected(null)
        routing.activeHandle.value shouldBe cast
    }

    @Test
    fun `the cast player is silenced once the session ends`() {
        // Left alone it keeps its listener, media items, and stale `loaded` spec for the process's life.
        framework.onSessionStarted("Living Room TV")

        framework.onSessionEnded()

        cast.stopped shouldBe true
    }

    // ---- what the attached screen is told, and when -----------------------------------------------

    /** A host that records the two transfer edges. */
    private class RecordingHost(
        override var castSource: PlaybackMediaSource?,
    ) : CastPlaybackHost {
        val started = mutableListOf<Pair<String?, PlaybackSnapshot>>()
        val ended = mutableListOf<PlaybackSnapshot>()
        val lost = mutableListOf<PlaybackSnapshot>()

        override fun onCastStarted(
            deviceName: String?,
            from: PlaybackSnapshot,
        ) {
            started += deviceName to from
        }

        override fun onCastEnded(at: PlaybackSnapshot) {
            ended += at
        }

        override fun onCastItemLost(lastHeld: PlaybackSnapshot) {
            lost += lastHeld
        }
    }

    @Test
    fun `the screen is told where the outgoing player was, read before routing moved`() {
        val recording = RecordingHost(source)
        val onThePhone = PlaybackSnapshot(positionMs = 600_000L, isPlaying = true)
        local.snapshot = onThePhone
        coordinator.attachHost(recording)

        framework.onSessionStarted("Living Room TV")

        // A screen that took its own snapshot would be asking a cast player that has not started.
        recording.started shouldBe listOf("Living Room TV" to onThePhone)
    }

    @Test
    fun `a repeated start for a live session does not re-run the transfer`() {
        // A resumed (suspended) session reports as a start too; re-running would reload the
        // receiver off a cast player that may still answer zero.
        val recording = RecordingHost(source)
        coordinator.attachHost(recording)
        framework.onSessionStarted("Living Room TV")

        framework.onSessionStarted("Living Room TV")

        recording.started.size shouldBe 1
    }

    @Test
    fun `this device is stopped when a receiver takes the film`() {
        coordinator.attachHost(RecordingHost(source))

        framework.onSessionStarted("Living Room TV")

        local.stopped shouldBe true
    }

    @Test
    fun `the screen is told where the television got to`() {
        val recording = RecordingHost(source)
        val onTheTelevision = PlaybackSnapshot(positionMs = 900_000L, isPlaying = true)
        cast.snapshot = onTheTelevision
        coordinator.attachHost(recording)
        framework.onSessionStarted("Living Room TV")

        framework.onSessionEnded()

        recording.ended shouldBe listOf(onTheTelevision)
        // The screen owes the stop report for it, so this one must not send a second.
        verify(exactly = 0) { reporter.reportStopDetached(any(), any()) }
    }

    @Test
    fun `a screen that has gone is told nothing, and reported for instead`() {
        every { reporter.startReporting(any(), any(), any()) } returns Job()
        val recording = RecordingHost(source)
        cast.snapshot = PlaybackSnapshot(positionMs = 900_000L)
        coordinator.attachHost(recording)
        framework.onSessionStarted("Living Room TV")
        coordinator.detachHost(recording)

        framework.onSessionEnded()

        recording.ended shouldBe emptyList()
        verify(exactly = 1) { reporter.reportStopDetached(any(), any()) }
    }

    // ---- a receiver that lets go of the item -------------------------------------------------------

    /** Where the receiver last held the film before it let go. */
    private val lastHeld = PlaybackSnapshot(positionMs = 1_200_000L, durationMs = 7_200_000L, isPlaying = true)

    /** Lets the coordinator's collector subscribe (and follow a routing flip) before and after the event. */
    private fun receiverSays(event: PlayerEvent) {
        dispatcher.scheduler.runCurrent()
        cast.tryEmit(event)
        dispatcher.scheduler.runCurrent()
    }

    private fun elapse(ms: Long) {
        dispatcher.scheduler.advanceTimeBy(ms.milliseconds)
        dispatcher.scheduler.runCurrent()
    }

    private fun castingDetached(): Job {
        val ticker = Job()
        every { reporter.startReporting(any(), any(), any()) } returns ticker
        framework.onSessionStarted("Living Room TV")
        coordinator.attachHost(host)
        coordinator.detachHost(host)
        return ticker
    }

    @Test
    fun `with no screen, a receiver that stopped the item is closed once the grace period is up`() {
        val ticker = castingDetached()

        receiverSays(PlayerEvent.RemoteItemMissing(lastHeld))
        elapse(GRACE_MS)

        verify(exactly = 1) { reporter.reportStopDetached(source, lastHeld) }
        // Five minutes of "Skipping a progress tick" was the symptom this ends.
        ticker.isCancelled shouldBe true
        // The session is still up: the next open must still cast.
        coordinator.isCasting shouldBe true
    }

    @Test
    fun `the grace period is respected — a blink is not a stop`() {
        castingDetached()

        receiverSays(PlayerEvent.RemoteItemMissing(lastHeld))
        elapse(GRACE_MS - 100L)

        verify(exactly = 0) { reporter.reportStopDetached(any(), any()) }
    }

    @Test
    fun `an item that comes back within the grace period is not stopped`() {
        castingDetached()
        receiverSays(PlayerEvent.RemoteItemMissing(lastHeld))
        elapse(GRACE_MS / 2)

        receiverSays(PlayerEvent.RemoteItemMissingCleared)
        elapse(GRACE_MS)

        verify(exactly = 0) { reporter.reportStopDetached(any(), any()) }
    }

    @Test
    fun `the session ending later finds nothing left to report`() {
        castingDetached()
        receiverSays(PlayerEvent.RemoteItemMissing(lastHeld))
        elapse(GRACE_MS)

        // The television closes its idle session some minutes later.
        framework.onSessionEnded()

        // One stop report per source: the drop's, and not a second from `onCastEnded`.
        verify(exactly = 1) { reporter.reportStopDetached(any(), any()) }
        coordinator.connection.value shouldBe CastConnection.None
    }

    @Test
    fun `a session that ends during the grace period is reported by the end alone`() {
        cast.snapshot = PlaybackSnapshot(isValid = false)
        castingDetached()
        receiverSays(PlayerEvent.RemoteItemMissing(lastHeld))

        framework.onSessionEnded()
        elapse(GRACE_MS)

        verify(exactly = 1) { reporter.reportStopDetached(any(), any()) }
    }

    @Test
    fun `with a screen attached the screen is told, and the coordinator reports nothing`() {
        val recording = RecordingHost(source)
        coordinator.attachHost(recording)
        framework.onSessionStarted("Living Room TV")

        receiverSays(PlayerEvent.RemoteItemMissing(lastHeld))
        elapse(GRACE_MS)

        recording.lost shouldBe listOf(lastHeld)
        verify(exactly = 0) { reporter.reportStopDetached(any(), any()) }
    }

    @Test
    fun `a screen that is not attached any more when the grace period ends is not told`() {
        every { reporter.startReporting(any(), any(), any()) } returns Job()
        val recording = RecordingHost(source)
        coordinator.attachHost(recording)
        framework.onSessionStarted("Living Room TV")
        receiverSays(PlayerEvent.RemoteItemMissing(lastHeld))

        coordinator.detachHost(recording)
        elapse(GRACE_MS)

        recording.lost shouldBe emptyList()
        verify(exactly = 1) { reporter.reportStopDetached(source, lastHeld) }
    }

    @Test
    fun `nothing is judged with no cast session`() {
        coordinator.attachHost(host)
        coordinator.detachHost(host)

        receiverSays(PlayerEvent.RemoteItemMissing(lastHeld))
        elapse(GRACE_MS)

        verify(exactly = 0) { reporter.reportStopDetached(any(), any()) }
    }

    // ---- a suspended session ------------------------------------------------------------------------

    @Test
    fun `a suspended session is still casting, and says it is reconnecting`() {
        framework.onSessionStarted("Salon", "Chromecast Ultra")

        framework.onSessionSuspended()

        coordinator.connection.value shouldBe
            CastConnection.Connected("Salon", CastReceiverClass.ULTRA_4K, suspended = true)
        coordinator.isCasting shouldBe true
        routing.activeHandle.value shouldBe cast
    }

    @Test
    fun `a resume clears it without re-running the transfer`() {
        val recording = RecordingHost(source)
        coordinator.attachHost(recording)
        framework.onSessionStarted("Salon")
        framework.onSessionSuspended()

        // The framework's resume arrives as a start for the same session.
        framework.onSessionStarted("Salon")

        coordinator.connection.value shouldBe CastConnection.Connected("Salon")
        recording.started.size shouldBe 1
    }

    @Test
    fun `a suspension with nothing connected is ignored`() {
        framework.onSessionSuspended()

        coordinator.connection.value shouldBe CastConnection.None
    }

    @Test
    fun `a session that fails to resume ends like any other`() {
        framework.onSessionStarted("Salon")
        framework.onSessionSuspended()

        framework.onSessionEnded()

        coordinator.connection.value shouldBe CastConnection.None
        routing.activeHandle.value shouldBe local
    }

    // ---- a screen coming back to what the receiver holds ------------------------------------------

    /** Casting [source], screen gone, receiver reading valid at [at]. */
    private fun castingDetachedAt(at: PlaybackSnapshot): Job {
        cast.snapshot = at
        return castingDetached()
    }

    /** A second screen, for whatever it opened: the receiver's own source (adopted) or another. */
    private fun screenFor(opened: PlaybackMediaSource?) =
        object : CastPlaybackHost {
            override val castSource: PlaybackMediaSource? = opened
        }

    @Test
    fun `the receiver holds the item it is playing, and a screen for it adopts it without a report`() {
        val ticker = castingDetachedAt(ON_THE_TELEVISION)

        val held = coordinator.heldSourceFor(source.itemId)

        held shouldBe CastReceiverHold(source = source, reading = ON_THE_TELEVISION, isBuffering = false)
        coordinator.attachHost(screenFor(held?.source))
        // The screen's ticker carries on under the same play session: no stop, and no second ticker.
        verify(exactly = 0) { reporter.reportStopDetached(any(), any()) }
        ticker.isCancelled shouldBe true
        coordinator.detached.value shouldBe null
    }

    @Test
    fun `the id is compared as a UUID, so its case does not matter`() {
        castingDetachedAt(ON_THE_TELEVISION)

        val upper = UUID.fromString(source.itemId.toString().uppercase(java.util.Locale.ROOT))

        coordinator.heldSourceFor(upper)?.source shouldBe source
    }

    @Test
    fun `a receiver still buffering the item holds it too`() {
        castingDetachedAt(PlaybackSnapshot(isValid = false))
        receiverSays(PlayerEvent.Buffering(true))

        coordinator.heldSourceFor(source.itemId) shouldBe
            CastReceiverHold(source = source, reading = PlaybackSnapshot(isValid = false), isBuffering = true)
    }

    @Test
    fun `a receiver with no valid reading and no buffering holds nothing of ours`() {
        // Stopped from the television, or taken over by another sender: adopting it would open a
        // screen with nothing behind it.
        castingDetachedAt(PlaybackSnapshot(isValid = false))

        coordinator.heldSourceFor(source.itemId) shouldBe null
    }

    @Test
    fun `another item is not held`() {
        castingDetachedAt(ON_THE_TELEVISION)

        coordinator.heldSourceFor(OTHER_ITEM) shouldBe null
    }

    @Test
    fun `nothing is held while the receiver is losing the item`() {
        castingDetachedAt(ON_THE_TELEVISION)
        receiverSays(PlayerEvent.RemoteItemMissing(lastHeld))

        coordinator.heldSourceFor(source.itemId) shouldBe null
    }

    @Test
    fun `nothing is held with no session, or with a screen attached`() {
        coordinator.heldSourceFor(source.itemId) shouldBe null

        framework.onSessionStarted("Living Room TV")
        coordinator.attachHost(host)

        coordinator.heldSourceFor(source.itemId) shouldBe null
    }

    @Test
    fun `a screen that opens something else reports the orphan's stop once, where it was last held`() {
        val ticker = castingDetachedAt(ON_THE_TELEVISION)
        // The other screen's open has already loaded the receiver: its reading is not the orphan's.
        cast.snapshot = PlaybackSnapshot(isValid = false)

        coordinator.attachHost(screenFor(PlayerFixtures.remoteSource().copy(itemId = OTHER_ITEM)))

        verify(exactly = 1) { reporter.reportStopDetached(source, ON_THE_TELEVISION) }
        ticker.isCancelled shouldBe true
        coordinator.detached.value shouldBe null
    }

    @Test
    fun `the orphan's stop is not sent again when the session ends`() {
        castingDetachedAt(ON_THE_TELEVISION)
        val other = screenFor(PlayerFixtures.remoteSource().copy(itemId = OTHER_ITEM))
        coordinator.attachHost(other)
        coordinator.detachHost(other)

        framework.onSessionEnded()

        // One for the orphan, one for the other item the second screen left behind — never two for one.
        verify(exactly = 1) { reporter.reportStopDetached(source, any()) }
    }

    @Test
    fun `the orphan's stop carries the freshest reading anyone took, not the one at detach`() {
        castingDetachedAt(ON_THE_TELEVISION)
        val later = ON_THE_TELEVISION.copy(positionMs = 960_000L)
        cast.snapshot = later
        coordinator.readReceiver()
        cast.snapshot = PlaybackSnapshot(isValid = false)

        coordinator.attachHost(screenFor(null))

        verify(exactly = 1) { reporter.reportStopDetached(source, later) }
    }

    @Test
    fun `a same-item screen that negotiated its own stream still closes the one it replaced`() {
        // Same item, new play session: not the receiver's source, so it is an orphan all the same.
        castingDetachedAt(ON_THE_TELEVISION)

        coordinator.attachHost(screenFor(source.copy(playSessionId = "another-session")))

        verify(exactly = 1) { reporter.reportStopDetached(source, ON_THE_TELEVISION) }
    }

    // ---- a session that ends with the receiver already gone -----------------------------------------

    @Test
    fun `a detached session ended with the receiver gone is closed at the last valid reading, not positionless`() {
        castingDetachedAt(ON_THE_TELEVISION)
        // The casting bar kept reading the receiver while the film played on.
        val later = ON_THE_TELEVISION.copy(positionMs = TWENTY_SEVEN_MINUTES_MS)
        cast.snapshot = later
        coordinator.readReceiver()
        // Disconnected from the Cast notification: the final snapshot belongs to nothing.
        cast.snapshot = PlaybackSnapshot(isValid = false)

        framework.onSessionEnded()

        // A positionless stop is "played to the end, resume at zero" to the server.
        verify(exactly = 1) { reporter.reportStopDetached(source, later) }
        verify(exactly = 0) { reporter.reportStopDetached(any(), match { !it.isValid }) }
    }

    /**
     * The device walk's final read: `onCastEnded` reads the routing handle *before* it switches to
     * local, and a torn-down `RemoteCastPlayer` still claims our item there — at zero. After the switch
     * the idle local player answers a valid zero too. Neither may reach the detached stop.
     */
    @Test
    fun `a detached session whose final reading is a torn-down zero is closed at the last valid reading`() {
        castingDetachedAt(ON_THE_TELEVISION)
        cast.snapshot = PlaybackSnapshot(positionMs = 0L, isValid = true)
        local.snapshot = PlaybackSnapshot()

        framework.onSessionEnded()

        verify(exactly = 1) { reporter.reportStopDetached(source, ON_THE_TELEVISION) }
        verify(exactly = 0) { reporter.reportStopDetached(any(), match { it.positionMs == 0L }) }
    }

    @Test
    fun `a zero reading after a later valid one is not the receiver's position, for the ticker or the bar`() {
        castingDetachedAt(ON_THE_TELEVISION)
        cast.snapshot = PlaybackSnapshot(positionMs = 0L, isValid = true)

        coordinator.readReceiver().isValid shouldBe false
        coordinator.lastHeld shouldBe ON_THE_TELEVISION
    }

    @Test
    fun `a detached receiver that drops the item holding a stale zero is closed at the last valid reading`() {
        castingDetachedAt(ON_THE_TELEVISION)

        receiverSays(PlayerEvent.RemoteItemMissing(PlaybackSnapshot(positionMs = 0L, isValid = true)))
        elapse(GRACE_MS + 1L)

        verify(exactly = 1) { reporter.reportStopDetached(source, ON_THE_TELEVISION) }
    }

    @Test
    fun `a screen's last valid reading seeds the detached stop when the receiver answers nothing as it goes`() {
        every { reporter.startReporting(any(), any(), any()) } returns Job()
        val seen = ON_THE_TELEVISION.copy(positionMs = TWENTY_SEVEN_MINUTES_MS)
        val screen =
            object : CastPlaybackHost {
                override val castSource: PlaybackMediaSource = source
                override val lastValidReading: PlaybackSnapshot = seen
            }
        framework.onSessionStarted("Living Room TV")
        coordinator.attachHost(screen)
        cast.snapshot = PlaybackSnapshot(isValid = false)
        coordinator.detachHost(screen)

        framework.onSessionEnded()

        verify(exactly = 1) { reporter.reportStopDetached(source, seen) }
    }

    @Test
    fun `a buffering hold carries the last valid reading, not the source's start`() {
        castingDetachedAt(ON_THE_TELEVISION)
        cast.snapshot = PlaybackSnapshot(isValid = false)
        receiverSays(PlayerEvent.Buffering(true))

        val held = coordinator.heldSourceFor(source.itemId)

        held?.reading?.isValid shouldBe false
        held?.lastValidReading shouldBe ON_THE_TELEVISION
    }

    // ---- what the casting bar reads ----------------------------------------------------------------

    @Test
    fun `the detached item carries what the receiver was told it is, captured as the screen went`() {
        val poster = CastMetadata(title = "Arrival", subtitle = "2016", posterUrl = "https://server/p.jpg")
        metadata.publish(source.itemId.toString(), poster)
        castingDetachedAt(ON_THE_TELEVISION)

        // Another screen's fetch replaces the holder's one entry; the bar keeps its own.
        metadata.publish(OTHER_ITEM.toString(), CastMetadata(title = "Something else"))

        coordinator.detached.value shouldBe DetachedCast(source, poster)
    }

    @Test
    fun `the detached item goes with the session, and with a dropped item`() {
        castingDetachedAt(ON_THE_TELEVISION)
        receiverSays(PlayerEvent.RemoteItemMissing(lastHeld))
        elapse(GRACE_MS)

        coordinator.detached.value shouldBe null

        coordinator.attachHost(host)
        coordinator.detachHost(host)
        coordinator.detached.value?.source shouldBe source

        framework.onSessionEnded()

        coordinator.detached.value shouldBe null
    }

    @Test
    fun `the bar's toggle pauses a receiver that means to play, and plays one that does not`() {
        castingDetachedAt(ON_THE_TELEVISION)
        cast.playWhenReady = true

        coordinator.toggleDetachedPlayback()
        cast.pauseCount shouldBe 1

        coordinator.toggleDetachedPlayback()
        cast.playCount shouldBe 1
    }

    @Test
    fun `the bar's toggle plays a receiver left paused under a stale play intent, as the screen's does`() {
        castingDetachedAt(ON_THE_TELEVISION)
        cast.playWhenReady = true
        cast.isSettledPaused = true

        coordinator.toggleDetachedPlayback()

        cast.playCount shouldBe 1
        cast.pauseCount shouldBe 0
    }

    @Test
    fun `the bar's toggle does nothing while a screen owns the transport`() {
        framework.onSessionStarted("Living Room TV")
        coordinator.attachHost(host)
        cast.playWhenReady = true

        coordinator.toggleDetachedPlayback()

        cast.pauseCount shouldBe 0
        cast.playCount shouldBe 0
    }

    @Test
    fun `receiver buffering is followed while casting, and forgotten when the session ends`() {
        framework.onSessionStarted("Living Room TV")

        receiverSays(PlayerEvent.Buffering(true))
        coordinator.receiverBuffering.value shouldBe true

        framework.onSessionEnded()
        coordinator.receiverBuffering.value shouldBe false
    }

    @Test
    fun `a local player's buffering is not the receiver's`() {
        dispatcher.scheduler.runCurrent()
        local.tryEmit(PlayerEvent.Buffering(true))
        dispatcher.scheduler.runCurrent()

        coordinator.receiverBuffering.value shouldBe false
    }

    private companion object {
        val GRACE_MS = CastSessionCoordinator.ITEM_LOST_GRACE.inWholeMilliseconds

        /** Fifteen minutes in, on the television. */
        val ON_THE_TELEVISION = PlaybackSnapshot(positionMs = 900_000L, durationMs = 7_200_000L, isPlaying = true)

        /** About 27:20 — where the television had got to when the session was disconnected on the device. */
        const val TWENTY_SEVEN_MINUTES_MS = 1_640_000L

        val OTHER_ITEM: UUID = UUID.fromString("9e8d7c6b-5a49-4382-a1b0-c9d8e7f6a5b4")
    }
}
