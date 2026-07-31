package dev.jellyfinnative.player.ui

import dev.jellyfinnative.core.common.AppResult
import dev.jellyfinnative.player.PlayerFixtures
import dev.jellyfinnative.player.model.PlaybackMediaItemSpec
import dev.jellyfinnative.player.syncplay.SyncPlayPhase
import dev.jellyfinnative.player.syncplay.SyncPlayState
import dev.jellyfinnative.player.syncplay.group
import dev.jellyfinnative.player.syncplay.model.SyncPlayGroupState
import io.mockk.Ordering
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * When the ViewModel reconciles the server's view of a downloaded item with the group (Phase 6).
 *
 * The reconciliation itself is [dev.jellyfinnative.player.syncplay.SyncPlayLocalSession]'s and is
 * pinned there; what belongs here are the three *moments* only the player screen knows about, and
 * one ordering that is invisible until it breaks: the reconcile runs **before** the start report,
 * because the id that report is keyed on is what it mints.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PlayerSyncPlayReportingTest : PlayerViewModelFixture() {
    @Test
    fun `a session that opens reconciles before the start is reported`() =
        runTest(dispatcher) {
            openingALocalFile()

            viewModel()
            advanceUntilIdle()

            coVerify(ordering = Ordering.ORDERED) {
                syncPlayLocalSession.reconcile(any(), any())
                reporter.reportStart(any(), any())
            }
        }

    @Test
    fun `joining a group mid-playback reconciles again`() =
        runTest(dispatcher) {
            openingALocalFile()
            viewModel()
            advanceUntilIdle()

            syncPlayState.value =
                SyncPlayState.InGroup(
                    group(),
                    queue = null,
                    groupState = SyncPlayGroupState.Idle,
                    phase = SyncPlayPhase.Waiting,
                )
            advanceUntilIdle()

            // Once for the open, once for the join: a download that was playing alone joins the
            // group's session without being re-opened.
            coVerify(exactly = 2) { syncPlayLocalSession.reconcile(any(), any()) }
        }

    @Test
    fun `leaving the group mid-playback reconciles the session away`() =
        runTest(dispatcher) {
            openingALocalFile()
            syncPlayState.value =
                SyncPlayState.InGroup(
                    group(),
                    queue = null,
                    groupState = SyncPlayGroupState.Idle,
                    phase = SyncPlayPhase.Waiting,
                )
            viewModel()
            advanceUntilIdle()

            syncPlayState.value = SyncPlayState.Idle
            advanceUntilIdle()

            coVerify(exactly = 2) { syncPlayLocalSession.reconcile(any(), any()) }
        }

    @Test
    fun `a phase change inside the same group reconciles nothing`() =
        runTest(dispatcher) {
            openingALocalFile()
            syncPlayState.value =
                SyncPlayState.InGroup(
                    group(),
                    queue = null,
                    groupState = SyncPlayGroupState.Idle,
                    phase = SyncPlayPhase.Waiting,
                )
            viewModel()
            advanceUntilIdle()

            syncPlayState.value =
                SyncPlayState.InGroup(
                    group(),
                    queue = null,
                    groupState = SyncPlayGroupState.Paused,
                    phase = SyncPlayPhase.Paused,
                )
            advanceUntilIdle()

            // Membership is the only thing that changes what the server should be told; a group
            // that pauses is the same session it was a moment ago.
            coVerify(exactly = 1) { syncPlayLocalSession.reconcile(any(), any()) }
        }

    @Test
    fun `closing the screen forgets the session after the stop report is handed over`() =
        runTest(dispatcher) {
            openingALocalFile()
            val model = viewModel()
            advanceUntilIdle()

            model.releaseSession()

            coVerify(ordering = Ordering.ORDERED) {
                reporter.reportStopDetached(any(), any())
                syncPlayLocalSession.onSessionClosed()
            }
        }

    /** The session resolves to the file on disk, which is the only case any of this applies to. */
    private fun openingALocalFile() {
        coEvery { resolver.resolve(any()) } returns AppResult.Success(PlayerFixtures.localSource())
        every { mediaSourceFactory.create(any()) } returns
            PlaybackMediaItemSpec(
                mediaId = PlayerFixtures.ITEM_ID.toString(),
                uri = PlayerFixtures.LOCAL_MEDIA_URI,
            )
    }
}
