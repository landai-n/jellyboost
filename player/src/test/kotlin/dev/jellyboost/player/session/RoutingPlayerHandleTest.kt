package dev.jellyboost.player.session

import app.cash.turbine.test
import dev.jellyboost.player.PlayerFixtures
import dev.jellyboost.player.model.PlaybackMediaItemSpec
import dev.jellyboost.player.model.PlaybackSnapshot
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import javax.inject.Provider

/**
 * Unit tests for [RoutingPlayerHandle].
 *
 * Two claims, and the first is the one that protects everything M5 through M11 built: **with no
 * cast session this handle is a pass-through**. Every call has to land on the local player, in the
 * same shape, and every answer has to come back untouched — that is what makes "casting changed
 * nothing about playing on your own" a fact rather than a hope.
 *
 * The second is that switching is complete: the transport, the answers *and* the event stream all
 * move together, because a collector still hearing from the player that was just stopped would
 * attribute its `Ended` to the session that replaced it.
 */
class RoutingPlayerHandleTest {
    private val local = FakePlayerHandle()
    private val cast = FakePlayerHandle()

    private val handle = RoutingPlayerHandle(local, Provider { cast })

    private val spec = PlaybackMediaItemSpec(mediaId = PlayerFixtures.ITEM_ID.toString(), uri = "https://server/x")
    private val source = PlayerFixtures.remoteSource()

    @Test
    fun `starts local, and stays local until something says otherwise`() {
        handle.activeHandle.value shouldBe local
    }

    @Test
    fun `with no cast session every call is the local player's and only the local player's`() {
        handle.prepare(source, spec, startPositionMs = 42L, playWhenReady = true)
        handle.play()
        handle.pause()
        handle.seekTo(9_000L)
        handle.selectAudioTrack(source, jellyfinIndex = 3)
        handle.selectSubtitleTrack(source, jellyfinIndex = 4)
        handle.setPlaybackSpeed(1.5f)
        handle.stop()
        handle.release()

        local.prepared.single().spec shouldBe spec
        local.prepared.single().startPositionMs shouldBe 42L
        local.playCount shouldBe 1
        local.pauseCount shouldBe 1
        local.seekedToMs shouldBe listOf(9_000L)
        local.selectedAudioIndices shouldBe listOf(3)
        local.selectedSubtitleIndices shouldBe listOf(4)
        local.playbackSpeeds shouldBe listOf(1.5f)
        local.stopped shouldBe true
        local.releaseCount shouldBe 1

        // The cast handle exists only behind a Provider; nothing above may have touched it.
        cast.hadNoTransportCalls shouldBe true
        cast.releaseCount shouldBe 0
    }

    @Test
    fun `once cast is active the same calls go to the receiver instead`() {
        handle.setActive(PlaybackTarget.Cast)

        handle.prepare(source, spec, startPositionMs = 0L, playWhenReady = false)
        handle.play()
        handle.seekTo(1_000L)
        handle.selectSubtitleTrack(source, jellyfinIndex = null)
        handle.setPlaybackSpeed(2f)
        handle.stop()

        cast.prepared.single().spec shouldBe spec
        cast.playCount shouldBe 1
        cast.seekedToMs shouldBe listOf(1_000L)
        cast.selectedSubtitleIndices shouldBe listOf(null)
        cast.playbackSpeeds shouldBe listOf(2f)
        cast.stopped shouldBe true

        local.hadNoTransportCalls shouldBe true
    }

    @Test
    fun `the three-argument prepare routes as well, since the local handle still takes it`() {
        handle.setActive(PlaybackTarget.Cast)

        handle.prepare(spec, startPositionMs = 5L, playWhenReady = true)

        cast.prepared.single().startPositionMs shouldBe 5L
        local.prepared.shouldBeEmpty()
    }

    @Test
    fun `the answer a track selection gives comes from the active handle`() {
        cast.trackSelectionSucceeds = false
        local.trackSelectionSucceeds = true

        handle.selectAudioTrack(source, jellyfinIndex = 1) shouldBe true

        handle.setActive(PlaybackTarget.Cast)
        // `false` is the contract that sends the caller back to the server, and it has to survive
        // the delegation intact or a cast audio switch would silently do nothing.
        handle.selectAudioTrack(source, jellyfinIndex = 1) shouldBe false
    }

    @Test
    fun `the snapshot is whichever player is actually playing`() {
        local.snapshot = PlaybackSnapshot(positionMs = 10L)
        cast.snapshot = PlaybackSnapshot(positionMs = 5_000L)

        handle.snapshot().positionMs shouldBe 10L

        handle.setActive(PlaybackTarget.Cast)
        handle.snapshot().positionMs shouldBe 5_000L

        handle.setActive(PlaybackTarget.Local)
        handle.snapshot().positionMs shouldBe 10L
    }

    /**
     * `runCurrent` before every emission, deliberately.
     *
     * The handles publish through a replay-less `MutableSharedFlow`, so anything emitted before the
     * inner collector of `flatMapLatest` has actually subscribed is dropped rather than buffered —
     * and re-subscribing is exactly what a switch does. Letting the scheduler settle first is what
     * makes the assertions about routing rather than about timing.
     */
    @Test
    fun `events come from the active handle, and stop coming from the one it replaced`() =
        runTest {
            handle.events.test {
                runCurrent()
                local.emit(PlayerEvent.Ready)
                awaitItem() shouldBe PlayerEvent.Ready

                handle.setActive(PlaybackTarget.Cast)
                runCurrent()
                cast.emit(PlayerEvent.IsPlayingChanged(isPlaying = true))
                awaitItem() shouldBe PlayerEvent.IsPlayingChanged(isPlaying = true)

                // Stopping the local player emits `Ended`; attributing that to the cast session
                // would close the screen the receiver has just started playing to.
                local.emit(PlayerEvent.Ended)
                runCurrent()
                expectNoEvents()

                handle.setActive(PlaybackTarget.Local)
                runCurrent()
                local.emit(PlayerEvent.TracksChanged)
                awaitItem() shouldBe PlayerEvent.TracksChanged

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `setting the target it is already on is not a switch`() {
        handle.setActive(PlaybackTarget.Local)

        handle.activeHandle.value shouldBe local
    }

    @Test
    fun `the player that was in charge is the one stopped when the other takes over`() {
        handle.setActive(PlaybackTarget.Cast)

        handle.stopInactive()

        // A phone that kept playing under a television is what skipping this sounds like.
        local.stopped shouldBe true
        cast.stopped shouldBe false
    }

    @Test
    fun `stopping the inactive player never builds a cast one that does not exist`() {
        var built = 0
        val untouched =
            RoutingPlayerHandle(
                local,
                Provider {
                    built++
                    cast
                },
            )

        untouched.stopInactive()

        // Asking the provider here would load the app's first `com.google.android.gms` class on a
        // device that has no Play services — for a player nothing has ever routed to.
        built shouldBe 0
        cast.stopped shouldBe false
        local.stopped shouldBe false
    }
}
