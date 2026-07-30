package dev.jellyfinnative.player.syncplay

import dev.jellyfinnative.player.PlayerFixtures
import dev.jellyfinnative.player.model.PlaybackSnapshot
import dev.jellyfinnative.player.report.PlaybackReporter
import dev.jellyfinnative.player.resolve.PlaybackInfoResolver
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * What the server is told about a downloaded item that is being watched with a group (M11 Phase 6).
 *
 * [SyncPlayLocalSession] is a reconciliation, not a sequence of events, and that is what these tests
 * are shaped around: the same call answers "a group joined ten minutes into a film" and "a film
 * opened while already in a group", and the same call closes the session whether the group ended or
 * the item did. The costly half — one `PlaybackInfo` POST — must happen exactly once per item, and
 * the cheap half — a stop report on the way out — exactly once per group.
 */
class SyncPlayLocalSessionTest {
    private val resolver = mockk<PlaybackInfoResolver>()
    private val reporter = mockk<PlaybackReporter>(relaxed = true)
    private val statusHolder = SyncPlayStatusHolder()
    private val localSession = SyncPlayLocalSession(resolver, statusHolder, reporter)

    private val snapshot = PlaybackSnapshot(positionMs = 90_000L, isPlaying = true)

    @BeforeEach
    fun setUp() {
        coEvery { resolver.mintPlaySessionId(any(), any()) } returns MINTED_ID
    }

    // ---- minting ---------------------------------------------------------------------------------

    @Test
    fun `opening a download in a group mints one play session and publishes it`() =
        runTest {
            statusHolder.setInGroup(true)

            localSession.reconcile(PlayerFixtures.localSource(), snapshot)

            coVerify(exactly = 1) {
                resolver.mintPlaySessionId(PlayerFixtures.ITEM_ID, PlayerFixtures.ITEM_ID.toString())
            }
            statusHolder.mintedPlaySessionId.value shouldBe MINTED_ID
        }

    @Test
    fun `joining a group while a download is already playing mints then`() =
        runTest {
            // Nothing to report yet: playing a download alone is silent, as it has been since M8.
            localSession.reconcile(PlayerFixtures.localSource(), snapshot)
            coVerify(exactly = 0) { resolver.mintPlaySessionId(any(), any()) }

            statusHolder.setInGroup(true)
            localSession.reconcile(PlayerFixtures.localSource(), snapshot)

            coVerify(exactly = 1) { resolver.mintPlaySessionId(any(), any()) }
            statusHolder.mintedPlaySessionId.value shouldBe MINTED_ID
        }

    @Test
    fun `reconciling the same item again costs nothing`() =
        runTest {
            statusHolder.setInGroup(true)
            val source = PlayerFixtures.localSource()

            localSession.reconcile(source, snapshot)
            localSession.reconcile(source, snapshot)
            localSession.reconcile(source.withSelectedAudio(3), snapshot)

            // A track switch is the same file and the same session; a second POST would open a
            // second session on the dashboard for one member.
            coVerify(exactly = 1) { resolver.mintPlaySessionId(any(), any()) }
        }

    @Test
    fun `a failed mint is not an error, only a session without an id`() =
        runTest {
            coEvery { resolver.mintPlaySessionId(any(), any()) } returns null
            statusHolder.setInGroup(true)

            localSession.reconcile(PlayerFixtures.localSource(), snapshot)

            statusHolder.mintedPlaySessionId.value.shouldBeNull()
            // And it does not retry on every reconciliation, which would be one failing POST per
            // group event for the rest of the film.
            localSession.reconcile(PlayerFixtures.localSource(), snapshot)
            coVerify(exactly = 1) { resolver.mintPlaySessionId(any(), any()) }
        }

    @Test
    fun `a stream in a group needs no mint of its own`() =
        runTest {
            statusHolder.setInGroup(true)

            localSession.reconcile(PlayerFixtures.remoteSource(), snapshot)

            coVerify(exactly = 0) { resolver.mintPlaySessionId(any(), any()) }
            statusHolder.mintedPlaySessionId.value.shouldBeNull()
        }

    @Test
    fun `a download played alone never touches the server`() =
        runTest {
            localSession.reconcile(PlayerFixtures.localSource(), snapshot)

            coVerify(exactly = 0) { resolver.mintPlaySessionId(any(), any()) }
            coVerify(exactly = 0) { reporter.reportGroupExitStop(any(), any(), any()) }
            statusHolder.mintedPlaySessionId.value.shouldBeNull()
        }

    // ---- closing ---------------------------------------------------------------------------------

    @Test
    fun `leaving the group mid-item closes the session with the id it was opened under`() =
        runTest {
            statusHolder.setInGroup(true)
            val source = PlayerFixtures.localSource()
            localSession.reconcile(source, snapshot)

            // The controller's teardown flips both of these before anyone can observe the leave.
            statusHolder.setInGroup(false)
            statusHolder.setMintedPlaySessionId(null)
            localSession.reconcile(source, snapshot)

            coVerify(exactly = 1) { reporter.reportGroupExitStop(source, snapshot, MINTED_ID) }
        }

    @Test
    fun `the closing stop is sent once, however many times the leave is reconciled`() =
        runTest {
            statusHolder.setInGroup(true)
            val source = PlayerFixtures.localSource()
            localSession.reconcile(source, snapshot)

            statusHolder.setInGroup(false)
            localSession.reconcile(source, snapshot)
            localSession.reconcile(source, snapshot)

            coVerify(exactly = 1) { reporter.reportGroupExitStop(any(), any(), any()) }
        }

    @Test
    fun `the group moving to the next item mints again without a closing stop`() =
        runTest {
            statusHolder.setInGroup(true)
            localSession.reconcile(PlayerFixtures.localSource(), snapshot)

            coEvery { resolver.mintPlaySessionId(any(), any()) } returns "minted-session-2"
            localSession.reconcile(nextItem(), snapshot)

            // The ordinary stop report closed the outgoing item's session — it was still in the
            // group when it was sent — so a second one here would report the wrong item.
            coVerify(exactly = 0) { reporter.reportGroupExitStop(any(), any(), any()) }
            statusHolder.mintedPlaySessionId.value shouldBe "minted-session-2"
        }

    @Test
    fun `going back to the server for a track mid-group closes the local session`() =
        runTest {
            statusHolder.setInGroup(true)
            val source = PlayerFixtures.localSource()
            localSession.reconcile(source, snapshot)

            // A forced-remote track change replaces the file with the server's copy, which reports
            // on the play session the resolve produced.
            localSession.reconcile(PlayerFixtures.remoteSource(), snapshot)

            coVerify(exactly = 0) { reporter.reportGroupExitStop(any(), any(), any()) }
            statusHolder.mintedPlaySessionId.value.shouldBeNull()
        }

    @Test
    fun `closing the player screen forgets the session without reporting a second stop`() =
        runTest {
            statusHolder.setInGroup(true)
            val source = PlayerFixtures.localSource()
            localSession.reconcile(source, snapshot)

            localSession.onSessionClosed()

            // The screen's own stop report is already on its way, and it reads the minted id after
            // this returns — clearing the holder here would send it without one.
            coVerify(exactly = 0) { reporter.reportGroupExitStop(any(), any(), any()) }
            statusHolder.mintedPlaySessionId.value shouldBe MINTED_ID

            // Re-opening the same item in the same group must mint afresh: the server was told the
            // first session stopped.
            coEvery { resolver.mintPlaySessionId(any(), any()) } returns "minted-session-2"
            localSession.reconcile(source, snapshot)
            statusHolder.mintedPlaySessionId.value shouldBe "minted-session-2"
        }

    private fun nextItem() =
        PlayerFixtures.localSource().copy(
            itemId = java.util.UUID.fromString("11111111-2222-3333-4444-555555555555"),
            mediaSourceId = "11111111-2222-3333-4444-555555555555",
        )

    private companion object {
        const val MINTED_ID = "minted-session-1"
    }
}
