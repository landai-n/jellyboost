package dev.jellyboost.player.syncplay

import dev.jellyboost.core.network.ConnectionState
import dev.jellyboost.core.network.SessionStateHolder
import dev.jellyboost.core.network.connectivity.ConnectionStateProvider
import dev.jellyboost.core.network.model.SessionState
import dev.jellyboost.player.model.PlaybackSnapshot
import dev.jellyboost.player.model.millisToTicks
import dev.jellyboost.player.session.FakePlayerHandle
import dev.jellyboost.player.session.PlayerEvent
import dev.jellyboost.player.syncplay.model.SyncPlayCommand
import dev.jellyboost.player.syncplay.model.SyncPlayCommandType
import dev.jellyboost.player.syncplay.model.SyncPlayGroupEvent
import dev.jellyboost.player.syncplay.model.SyncPlayGroupQueue
import dev.jellyboost.player.syncplay.model.SyncPlayGroupState
import dev.jellyboost.player.syncplay.model.SyncPlayQueueEntry
import dev.jellyboost.player.syncplay.model.SyncPlayQueueMode
import dev.jellyboost.player.syncplay.model.SyncPlayQueueUpdateReason
import dev.jellyboost.player.syncplay.model.SyncPlayRepeatMode
import dev.jellyboost.player.syncplay.model.SyncPlayRequestKind
import dev.jellyboost.player.syncplay.model.SyncPlayShuffleMode
import dev.jellyboost.player.syncplay.socket.SyncPlaySocketState
import dev.jellyboost.player.syncplay.time.SyncPlayPinger
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.junit.jupiter.api.Test
import java.net.HttpURLConnection.HTTP_FORBIDDEN
import java.time.Instant
import java.util.UUID

/**
 * Unit tests for [SyncPlayController].
 *
 * Two properties carry the whole feature and neither is visible from a single call site:
 *
 * 1. **No in-group action moves this player locally** (docs/notes/syncplay-m11-plan.md, key
 *    decision 11). Every intent test therefore asserts what the *player* was not asked to do, which
 *    is the only way to state it.
 * 2. **A confirmed connection loss pauses and leaves; a flap does nothing** (key decision 10 as
 *    amended). Both are exercised, because the failure mode of getting the second one wrong — a
 *    group dropped every time a train goes through a tunnel — is invisible in a test that only
 *    covers the first.
 *
 * `runCurrent()` rather than `advanceUntilIdle()` throughout: an in-group controller runs a ping
 * loop and a drift monitor for ever, so "advance until nothing is scheduled" never returns.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LargeClass") // One class per collaborator would hide the interactions being pinned.
class SyncPlayControllerTest {
    private val origin = Instant.parse("2026-07-30T18:41:00Z")
    private val itemId = UUID.fromString("00000000-0000-0000-0000-0000000000c1")
    private val otherItemId = UUID.fromString("00000000-0000-0000-0000-0000000000c2")
    private val playlistItemId = UUID.fromString("00000000-0000-0000-0000-0000000000d1")
    private val otherPlaylistItemId = UUID.fromString("00000000-0000-0000-0000-0000000000d2")

    // Joining -------------------------------------------------------------------------------------

    @Test
    fun `joining collects the websocket, so the queue update that follows is not missed`() =
        runTest {
            val fixture = fixture()

            fixture.controller.joinGroup(group())
            runCurrent()

            fixture.socket.collectors shouldBe 2
            fixture.api
                .callsOf<SyncPlayCall.JoinGroup>()
                .single()
                .groupId shouldBe group().id
            fixture.controller.state.value
                .shouldBeInstanceOf<SyncPlayState.InGroup>()
            fixture.status.inGroup.value shouldBe true

            fixture.socket.emit(SyncPlayGroupEvent.QueueChanged(queue()))
            runCurrent()

            fixture.host.loaded shouldBe listOf(itemId to 0L)
        }

    @Test
    fun `the clock is measured before the join call, not after it`() =
        runTest {
            val fixture = fixture()

            fixture.controller.joinGroup(group())
            runCurrent()

            // Order, not merely presence: `SyncPlayTimeSync.offset` is ZERO until a sample records
            // one, the pinger only starts once the group has been entered, and the server can send
            // the group's current command the moment the join returns — so a clock measured after
            // the join is a first command converted to local time against an assumed offset.
            fixture.api.calls.take(2) shouldBe
                listOf(SyncPlayCall.SampleServerTime, SyncPlayCall.JoinGroup(group().id))
        }

    @Test
    fun `a clock sample that fails does not stop the join`() =
        runTest {
            val fixture = fixture()
            fixture.api.failNextSample = java.io.IOException("timeout")

            fixture.controller.joinGroup(group())
            runCurrent()

            fixture.controller.state.value
                .shouldBeInstanceOf<SyncPlayState.InGroup>()
        }

    @Test
    fun `a failed join leaves nothing running`() =
        runTest {
            val fixture = fixture()
            fixture.api.joinError = IllegalStateException("403")

            fixture.controller.joinGroup(group())
            runCurrent()

            fixture.controller.state.value shouldBe SyncPlayState.Idle
            fixture.status.inGroup.value shouldBe false
            fixture.socket.collectors shouldBe 0
            fixture.messages shouldBe listOf(SyncPlayMessage.JoinFailed)
        }

    @Test
    fun `creating a group joins it`() =
        runTest {
            val fixture = fixture()

            fixture.controller.createGroup("Film night")
            runCurrent()

            fixture.api
                .callsOf<SyncPlayCall.CreateGroup>()
                .single()
                .name shouldBe "Film night"
            (fixture.controller.state.value as SyncPlayState.InGroup).group shouldBe group()
        }

    @Test
    fun `the join handshake buffers, opens the item paused, then reports ready`() =
        runTest {
            val fixture = fixture()
            joinWithQueue(fixture, startTicks = 60_000L.millisToTicks())

            fixture.api
                .callsOf<SyncPlayCall.ReportBuffering>()
                .single()
                .playlistItemId shouldBe playlistItemId
            fixture.host.loaded shouldBe listOf(itemId to 60_000L.millisToTicks())
            // Opening an item is not playing it: the group decides when playback starts.
            fixture.player.hadNoTransportCalls shouldBe true

            fixture.player.emit(PlayerEvent.Ready)
            runCurrent()

            val ready = fixture.api.callsOf<SyncPlayCall.ReportReady>().single()
            ready.playlistItemId shouldBe playlistItemId
            ready.positionTicks shouldBe 60_000L.millisToTicks()
            ready.isPlaying shouldBe false
        }

    @Test
    fun `the server's unpause starts playback at the instant it named, and anchors the drift monitor`() =
        runTest {
            val fixture = fixture()
            joinWithQueue(fixture)
            fixture.player.emit(PlayerEvent.Ready)
            runCurrent()
            fixture.player.resetCalls()

            val whenInstant = now().plusMillis(1_000)
            fixture.socket.emit(command(SyncPlayCommandType.Unpause, whenInstant, positionMs = 60_000))
            runCurrent()

            fixture.player.playCount shouldBe 0

            advanceTimeBy(1_000)
            runCurrent()

            fixture.player.playCount shouldBe 1
            val phase = (fixture.controller.state.value as SyncPlayState.InGroup).phase
            phase shouldBe SyncPlayPhase.Playing(SyncPlayAnchor(60_000L, whenInstant))
        }

    @Test
    fun `a group that is waiting keeps this player paused`() =
        runTest {
            val fixture = fixture()
            joinWithQueue(fixture)
            fixture.player.emit(PlayerEvent.Ready)
            runCurrent()
            fixture.player.resetCalls()

            fixture.socket.emit(
                SyncPlayGroupEvent.StateChanged(SyncPlayGroupState.Waiting, SyncPlayRequestKind.Buffer),
            )
            advanceTimeBy(10_000)
            runCurrent()

            (fixture.controller.state.value as SyncPlayState.InGroup).phase shouldBe SyncPlayPhase.Waiting
            fixture.player.hadNoTransportCalls shouldBe true
        }

    @Test
    fun `an item the host already has open is adopted rather than reloaded, and still owes a ready`() =
        runTest {
            val fixture = fixture()
            // The user opened this item themselves and then the group moved onto it — or the group's
            // own `PlayQueueUpdate` opened it through a launch request, which is the same shape.
            fixture.host.snapshot = SyncPlayHostSnapshot(itemId, 30_000L.millisToTicks(), isPlaying = true)

            joinWithQueue(fixture)

            fixture.host.loaded shouldBe emptyList()
            // Buffering *first*: an adopted player has very often only just been handed the item, so
            // an immediate `ready` claims a readiness nobody has (DECISIONS.md, 2026-07-31). It is
            // also what puts the group back to waiting on this member, which is what earns the
            // unpause that clears the WAITING overlay.
            val buffering = fixture.api.callsOf<SyncPlayCall.ReportBuffering>().single()
            buffering.playlistItemId shouldBe playlistItemId
            buffering.positionTicks shouldBe 30_000L.millisToTicks()
            buffering.isPlaying shouldBe true
            fixture.api.callsOf<SyncPlayCall.ReportReady>().shouldBeEmpty()
            (fixture.controller.state.value as SyncPlayState.InGroup).phase shouldBe SyncPlayPhase.Buffering

            fixture.player.emit(PlayerEvent.Ready)
            runCurrent()

            val ready = fixture.api.callsOf<SyncPlayCall.ReportReady>().single()
            ready.playlistItemId shouldBe playlistItemId
            ready.positionTicks shouldBe 30_000L.millisToTicks()
            // The adopted player was running, so answering the group's wait parks it first — a
            // `ready` that claims to be playing is answered `AllExceptCurrentSession` and this
            // member is sent nothing (`WaitingGroupState.cs`:484-498, DECISIONS.md 2026-07-31).
            ready.isPlaying shouldBe false
        }

    @Test
    fun `an adopted player that never announces itself still answers the group`() =
        runTest {
            val fixture = fixture()
            fixture.host.snapshot = SyncPlayHostSnapshot(itemId, 30_000L.millisToTicks(), isPlaying = false)

            joinWithQueue(fixture)
            // No `PlayerEvent.Ready`: the player was already prepared before the host was attached,
            // so its readiness has been and gone. Without the fallback the group would wait on a
            // member for an event that cannot happen again.
            advanceTimeBy(SyncPlayController.SETTLED_READY_FALLBACK_MS + 1)
            runCurrent()

            fixture.api
                .callsOf<SyncPlayCall.ReportReady>()
                .single()
                .positionTicks shouldBe 30_000L.millisToTicks()
        }

    @Test
    fun `an item that cannot be opened is reported rather than left gating the group`() =
        runTest {
            val fixture = fixture()
            fixture.host.loadSucceeds = false

            joinWithQueue(fixture)

            fixture.messages shouldBe listOf(SyncPlayMessage.ItemUnavailable)
        }

    @Test
    fun `a load cancelled from the host's side is not reported unplayable and skips nothing`() =
        runTest {
            val fixture = fixture()
            // The host runs the load on its own scope; a screen dismissed mid-load cancels it from
            // under the controller (audit SP-02). A cancelled load says nothing about the item —
            // treating it as unplayable silently advanced the queue for the whole group.
            fixture.host.loadError = CancellationException("screen going away")

            joinWithQueue(fixture)

            fixture.messages.shouldBeEmpty()
            fixture.api.callsOf<SyncPlayCall.RequestNextItem>().shouldBeEmpty()

            // The collector survived it, and the slot is still openable once a load can run again.
            fixture.host.loadError = null
            fixture.socket.emit(SyncPlayGroupEvent.QueueChanged(queue()))
            runCurrent()
            fixture.host.loaded shouldBe listOf(itemId to 0L, itemId to 0L)
        }

    @Test
    fun `a queue older than the one already applied is dropped`() =
        runTest {
            val fixture = fixture()
            joinWithQueue(fixture)

            // The group moves on to the second slot...
            fixture.socket.emit(
                SyncPlayGroupEvent.QueueChanged(
                    twoItemQueue(playingIndex = 1).copy(lastUpdate = origin.plusSeconds(10)),
                ),
            )
            runCurrent()
            fixture.host.loaded shouldBe listOf(itemId to 0L, otherItemId to 0L)

            // ...and a straggler published before that move — most concretely the pre-join stash
            // replayed after a live update (audit SP-03) — must not drag it back.
            fixture.socket.emit(
                SyncPlayGroupEvent.QueueChanged(
                    twoItemQueue(playingIndex = 0).copy(lastUpdate = origin.plusSeconds(5)),
                ),
            )
            runCurrent()

            fixture.host.loaded shouldBe listOf(itemId to 0L, otherItemId to 0L)
            (fixture.controller.state.value as SyncPlayState.InGroup).queue?.playingItemIndex shouldBe 1
        }

    @Test
    fun `a launch request raised with no collector attached is replayed to the next one`() =
        runTest {
            val fixture = fixture(attachHost = false)
            joinWithQueue(fixture, startTicks = 12_000L.millisToTicks())
            fixture.launchRequests shouldBe listOf(SyncPlayLaunchRequest(itemId, 12_000L.millisToTicks()))

            // A NavHost composed later — its collector did not exist when the request was raised
            // (audit SP-12: task swipe or Activity reclaim while the presence service holds the
            // group), and the request must survive until it does.
            val replayed = mutableListOf<SyncPlayLaunchRequest>()
            backgroundScope.launch { fixture.controller.launchRequests.collect { replayed += it } }
            runCurrent()
            replayed shouldBe listOf(SyncPlayLaunchRequest(itemId, 12_000L.millisToTicks()))

            // Consuming it is what keeps a handled request from re-navigating on the next
            // composition.
            fixture.controller.consumeLaunchRequest()
            val afterConsume = mutableListOf<SyncPlayLaunchRequest>()
            backgroundScope.launch { fixture.controller.launchRequests.collect { afterConsume += it } }
            runCurrent()
            afterConsume.shouldBeEmpty()
        }

    @Test
    fun `a detached member reports buffering and ready from the shared player's position`() =
        runTest {
            val fortyMinutesMs = 40 * 60_000L
            val fixture = fixture()
            joinPlaying(fixture)
            fixture.controller.detachHost(fixture.host)
            runCurrent()
            // The screen is gone but the shared player plays on, forty minutes into the film.
            fixture.player.snapshot = fixture.player.snapshot.copy(positionMs = fortyMinutesMs, isPlaying = true)
            fixture.api.clearCalls()

            blip(fixture)
            advanceTimeBy(SyncPlayController.SETTLED_READY_FALLBACK_MS + 1)
            runCurrent()

            // Both reports carry the player's own reading, not zero (audit SP-13): the server
            // holds the group and schedules its resume off reported positions.
            fixture.api
                .callsOf<SyncPlayCall.ReportBuffering>()
                .single()
                .positionTicks shouldBe fortyMinutesMs.millisToTicks()
            fixture.api
                .callsOf<SyncPlayCall.ReportReady>()
                .single()
                .positionTicks shouldBe fortyMinutesMs.millisToTicks()
        }

    @Test
    fun `leaving during a rejoin tells the server, and the abandoned rejoin never comes back`() =
        runTest {
            val fixture = fixture()
            fixture.api.groups = listOf(group())
            joinPlaying(fixture)
            // The rejoin cannot progress, so the session sits in Rejoining with the loop alive.
            fixture.api.getGroupsError = java.io.IOException("still down")
            blipThenDropped(fixture)
            fixture.controller.state.value
                .shouldBeInstanceOf<SyncPlayState.Rejoining>()
            fixture.api.clearCalls()

            fixture.controller.leaveGroup()
            runCurrent()

            // The leave reached the server even though the rejoin owned the session (audit SP-10)...
            fixture.api.callsOf<SyncPlayCall.LeaveGroup>() shouldBe listOf(SyncPlayCall.LeaveGroup)
            fixture.controller.state.value shouldBe SyncPlayState.Idle

            // ...and the loop the leave cancelled cannot re-enter the group behind the user's back.
            fixture.api.getGroupsError = null
            advanceTimeBy(SyncPlayController.REJOIN_RETRY_DELAY_MS * SyncPlayController.REJOIN_MAX_ATTEMPTS * 2)
            runCurrent()
            fixture.controller.state.value shouldBe SyncPlayState.Idle
            fixture.api.callsOf<SyncPlayCall.JoinGroup>().shouldBeEmpty()
        }

    // Intents -------------------------------------------------------------------------------------

    @Test
    fun `every in-group transport intent is a server request and touches no player`() =
        runTest {
            val fixture = fixture()
            joinWithQueue(fixture)

            assertIntent(fixture, SyncPlayCall.RequestPause) { it.requestPause() }
            assertIntent(fixture, SyncPlayCall.RequestUnpause) { it.requestUnpause() }
            assertIntent(fixture, SyncPlayCall.RequestSeek(90_000)) { it.requestSeek(90_000) }
            assertIntent(fixture, SyncPlayCall.RequestNextItem(playlistItemId)) { it.requestNext() }
            assertIntent(fixture, SyncPlayCall.RequestPreviousItem(playlistItemId)) { it.requestPrevious() }
            assertIntent(fixture, SyncPlayCall.SetPlaylistItem(playlistItemId)) {
                it.requestSetPlaylistItem(playlistItemId)
            }
        }

    @Test
    fun `every in-group queue intent is a server request and touches no player`() =
        runTest {
            val fixture = fixture()
            joinWithQueue(fixture)

            assertIntent(fixture, SyncPlayCall.SetNewQueue(listOf(itemId), 0, 0)) { it.setNewQueue(listOf(itemId)) }
            assertIntent(fixture, SyncPlayCall.AddToQueue(listOf(otherItemId), SyncPlayQueueMode.QueueNext)) {
                it.addToQueue(listOf(otherItemId), SyncPlayQueueMode.QueueNext)
            }
            assertIntent(fixture, SyncPlayCall.MovePlaylistItem(playlistItemId, 2)) {
                it.moveQueueItem(playlistItemId, 2)
            }
            assertIntent(fixture, SyncPlayCall.RemoveFromPlaylist(listOf(playlistItemId), false, false)) {
                it.removeFromQueue(listOf(playlistItemId))
            }
            assertIntent(fixture, SyncPlayCall.SetShuffleMode(SyncPlayShuffleMode.Shuffle)) {
                it.setShuffle(SyncPlayShuffleMode.Shuffle)
            }
            assertIntent(fixture, SyncPlayCall.SetRepeatMode(SyncPlayRepeatMode.All)) {
                it.setRepeat(SyncPlayRepeatMode.All)
            }
        }

    @Test
    fun `an intent outside a group does nothing at all`() =
        runTest {
            val fixture = fixture()

            fixture.controller.requestPause()
            runCurrent()

            fixture.api.calls shouldBe emptyList()
        }

    @Test
    fun `an item playing to its end asks the group to move on, it does not move on by itself`() =
        runTest {
            val fixture = fixture()
            joinWithQueue(fixture)
            fixture.api.clearCalls()
            fixture.player.resetCalls()

            fixture.player.emit(PlayerEvent.Ended)
            runCurrent()

            fixture.api
                .callsOf<SyncPlayCall.RequestNextItem>()
                .single()
                .playlistItemId shouldBe playlistItemId
            fixture.player.hadNoTransportCalls shouldBe true
        }

    // Host attachment -------------------------------------------------------------------------------

    @Test
    fun `detaching the player asks the group to stop waiting on us, and keeps the membership`() =
        runTest {
            val fixture = fixture()
            joinWithQueue(fixture)
            fixture.api.clearCalls()

            fixture.controller.detachHost(fixture.host)
            runCurrent()

            fixture.api
                .callsOf<SyncPlayCall.SetIgnoreWait>()
                .single()
                .ignoreWait shouldBe true
            fixture.controller.state.value
                .shouldBeInstanceOf<SyncPlayState.InGroup>()
        }

    @Test
    fun `the group still reaches a player whose screen has gone away`() =
        runTest {
            val fixture = fixture()
            joinPlaying(fixture)
            fixture.player.resetCalls()

            fixture.controller.detachHost(fixture.host)
            runCurrent()

            // Giving back the screen is not giving back the player: `PlaybackService` keeps the
            // shared ExoPlayer running, so the phase must go on saying what the group is doing.
            // Forcing it to `Paused` here is what used to take the drift monitor down with it.
            (fixture.controller.state.value as SyncPlayState.InGroup)
                .phase
                .shouldBeInstanceOf<SyncPlayPhase.Playing>()

            // And a command the group issues after the screen went must still land on that player,
            // which a cancelled scheduler would have swallowed.
            fixture.socket.emit(command(SyncPlayCommandType.Pause, now().plusMillis(500), positionMs = 30_000))
            runCurrent()
            fixture.player.pauseCount shouldBe 0

            advanceTimeBy(500)
            runCurrent()

            fixture.player.pauseCount shouldBe 1
            fixture.player.seekedToMs shouldBe listOf(30_000L)
            (fixture.controller.state.value as SyncPlayState.InGroup).phase shouldBe SyncPlayPhase.Paused
        }

    @Test
    fun `a stale host cannot detach the player that replaced it`() =
        runTest {
            val fixture = fixture()
            joinWithQueue(fixture)
            fixture.api.clearCalls()

            fixture.controller.detachHost(FakeSyncPlayPlaybackHost())
            runCurrent()

            fixture.api.callsOf<SyncPlayCall.SetIgnoreWait>() shouldBe emptyList()
        }

    @Test
    fun `a queue update with no player attached asks the app to open one`() =
        runTest {
            val fixture = fixture(attachHost = false)

            joinWithQueue(fixture, startTicks = 12_000L.millisToTicks())

            fixture.launchRequests shouldBe listOf(SyncPlayLaunchRequest(itemId, 12_000L.millisToTicks()))
            fixture.host.loaded shouldBe emptyList()
        }

    @Test
    fun `re-attaching a player clears the ignore-wait and opens what the group is on`() =
        runTest {
            val fixture = fixture()
            joinWithQueue(fixture)
            fixture.controller.detachHost(fixture.host)
            runCurrent()
            fixture.api.clearCalls()

            fixture.controller.attachHost(fixture.host)
            runCurrent()

            fixture.api
                .callsOf<SyncPlayCall.SetIgnoreWait>()
                .single()
                .ignoreWait shouldBe false
        }

    // The group's queue (M11 Phase 4) --------------------------------------------------------------

    @Test
    fun `the group moving to another item opens it here, at the position the group named`() =
        runTest {
            val fixture = fixture()
            joinWithQueue(fixture, queue = twoItemQueue(playingIndex = 0))
            fixture.host.loaded shouldBe listOf(itemId to 0L)

            fixture.socket.emit(
                SyncPlayGroupEvent.QueueChanged(
                    twoItemQueue(playingIndex = 1, startTicks = 45_000L.millisToTicks()),
                ),
            )
            runCurrent()

            fixture.host.loaded shouldBe
                listOf(itemId to 0L, otherItemId to 45_000L.millisToTicks())
            // Opened paused and handed to the ordinary handshake: buffering out, ready back.
            fixture.api
                .callsOf<SyncPlayCall.ReportBuffering>()
                .last()
                .playlistItemId shouldBe otherPlaylistItemId
            fixture.player.hadNoTransportCalls shouldBe true
        }

    @Test
    fun `a reorder that leaves the playing item where it is reloads nothing`() =
        runTest {
            val fixture = fixture()
            joinWithQueue(fixture, queue = twoItemQueue(playingIndex = 0))
            fixture.host.loaded shouldBe listOf(itemId to 0L)

            // The same two slots, swapped — the group is still on the same one, now at index 1.
            fixture.socket.emit(
                SyncPlayGroupEvent.QueueChanged(
                    twoItemQueue(playingIndex = 1, reason = SyncPlayQueueUpdateReason.MoveItem).let { moved ->
                        moved.copy(entries = moved.entries.reversed())
                    },
                ),
            )
            runCurrent()

            fixture.host.loaded shouldBe listOf(itemId to 0L)
        }

    @Test
    fun `an entry this device cannot open is skipped, and never skipped twice`() =
        runTest {
            val fixture = fixture()
            fixture.host.loadSucceeds = false

            joinWithQueue(fixture, queue = twoItemQueue(playingIndex = 0))

            fixture.messages shouldBe listOf(SyncPlayMessage.ItemUnavailable)
            fixture.api.callsOf<SyncPlayCall.RequestNextItem>().map { it.playlistItemId } shouldBe
                listOf(playlistItemId)

            // The server re-sends the same slot — a queue of unplayable items must not cycle.
            fixture.socket.emit(SyncPlayGroupEvent.QueueChanged(twoItemQueue(playingIndex = 0)))
            runCurrent()

            fixture.messages shouldBe listOf(SyncPlayMessage.ItemUnavailable, SyncPlayMessage.ItemUnavailable)
            fixture.api.callsOf<SyncPlayCall.RequestNextItem>().map { it.playlistItemId } shouldBe
                listOf(playlistItemId)
        }

    @Test
    fun `an item that opens forgives the slot skipped to reach it`() =
        runTest {
            val fixture = fixture()
            fixture.host.loadSucceeds = false
            joinWithQueue(fixture, queue = twoItemQueue(playingIndex = 0))
            fixture.api.clearCalls()

            // The group moves on, this one opens, and then the first slot comes round again.
            fixture.host.loadSucceeds = true
            fixture.socket.emit(SyncPlayGroupEvent.QueueChanged(twoItemQueue(playingIndex = 1)))
            runCurrent()
            fixture.host.loadSucceeds = false
            fixture.socket.emit(SyncPlayGroupEvent.QueueChanged(twoItemQueue(playingIndex = 0)))
            runCurrent()

            fixture.api.callsOf<SyncPlayCall.RequestNextItem>().map { it.playlistItemId } shouldBe
                listOf(playlistItemId)
        }

    @Test
    fun `the same item queued twice is started again rather than adopted where it stands`() =
        runTest {
            val fixture = fixture()
            val secondSlot = SyncPlayQueueEntry(itemId, otherPlaylistItemId)
            val queue =
                queue().copy(entries = listOf(SyncPlayQueueEntry(itemId, playlistItemId), secondSlot))

            joinWithQueue(fixture, queue = queue)
            fixture.host.loaded shouldBe listOf(itemId to 0L)

            fixture.socket.emit(SyncPlayGroupEvent.QueueChanged(queue.copy(playingItemIndex = 1)))
            runCurrent()

            fixture.host.loaded shouldBe listOf(itemId to 0L, itemId to 0L)
        }

    // The handshake, and the loops it used to close (M11 fix batch) --------------------------------

    @Test
    fun `buffering reports say what the player is really doing`() =
        runTest {
            val fixture = fixture()
            // The user was already watching this item, still running, when the group moved onto it.
            fixture.host.snapshot = SyncPlayHostSnapshot(itemId, 30_000L.millisToTicks(), isPlaying = true)

            joinWithQueue(fixture)

            // The adoption handshake's own report says it, before any readiness is claimed. The
            // server tracks what each member reported here, and jellyfin-web sends its real state.
            fixture.api
                .callsOf<SyncPlayCall.ReportBuffering>()
                .single()
                .isPlaying shouldBe true

            fixture.api.clearCalls()
            fixture.controller.onHostBuffering()
            runCurrent()
            fixture.api
                .callsOf<SyncPlayCall.ReportBuffering>()
                .single()
                .isPlaying shouldBe true

            // And a player that really has stopped still reports `false`.
            fixture.host.snapshot = fixture.host.snapshot.copy(isPlaying = false)
            fixture.api.clearCalls()
            fixture.controller.onHostBuffering()
            runCurrent()
            fixture.api
                .callsOf<SyncPlayCall.ReportBuffering>()
                .single()
                .isPlaying shouldBe false
        }

    @Test
    fun `a ready report parks a running player, and is reported from a stopped one`() =
        runTest {
            val fixture = fixture()
            // The player really is running when the group's wait has to be answered: the post-seek
            // settle, the adopt path and the re-negotiation after a blip all reach this state.
            fixture.host.snapshot = SyncPlayHostSnapshot(itemId, 30_000L.millisToTicks(), isPlaying = true)

            joinWithQueue(fixture)
            fixture.player.resetCalls()

            fixture.player.emit(PlayerEvent.Ready)
            runCurrent()

            // `WaitingGroupState.cs`:484-498 — a `ready` whose `IsPlaying` is true from a session
            // more than `2 × highestPing` behind is answered `AllExceptCurrentSession`: everyone
            // else is told to resume and *this* member is sent nothing, on the assumption that a
            // client already playing will catch up by itself. It does not: it sits under "Waiting
            // for group" until somebody moves the group. So the player is parked first, and the
            // report is then true as well as safe.
            fixture.player.pauseCount shouldBe 1
            val ready = fixture.api.callsOf<SyncPlayCall.ReportReady>().single()
            ready.isPlaying shouldBe false
            // Parked where it stood, not rewound: the server's unpause resumes from here.
            ready.positionTicks shouldBe 30_000L.millisToTicks()
        }

    @Test
    fun `a ready report from an already stopped player touches nothing`() =
        runTest {
            val fixture = fixture()
            fixture.host.snapshot = SyncPlayHostSnapshot(itemId, 30_000L.millisToTicks(), isPlaying = false)

            joinWithQueue(fixture)
            fixture.player.resetCalls()

            fixture.player.emit(PlayerEvent.Ready)
            runCurrent()

            // Parking is idempotent — it has to be, because the pause net and the WAITING hold both
            // pause the same player and neither may be turned into a second transport call.
            fixture.player.hadNoTransportCalls shouldBe true
            fixture.api
                .callsOf<SyncPlayCall.ReportReady>()
                .single()
                .isPlaying shouldBe false
        }

    @Test
    fun `applying the group's pause is not answered with a ready report`() =
        runTest {
            val fixture = fixture()
            joinPlaying(fixture)
            fixture.api.clearCalls()

            fixture.socket.emit(command(SyncPlayCommandType.Pause, now(), positionMs = 30_000))
            runCurrent()
            // Seeking to the position the group parked at makes the player ready again. Reporting
            // that is what the server answers with the very pause that caused it — the storm.
            fixture.player.emit(PlayerEvent.Ready)
            runCurrent()

            fixture.api.callsOf<SyncPlayCall.ReportReady>() shouldBe emptyList()
        }

    @Test
    fun `the same pause sent again moves nothing, so the loop has nothing to feed on`() =
        runTest {
            val fixture = fixture()
            joinPlaying(fixture)
            fixture.socket.emit(command(SyncPlayCommandType.Pause, now(), positionMs = 30_000))
            runCurrent()
            fixture.player.resetCalls()
            fixture.api.clearCalls()

            // "Client got lost, sending current state" — the same instruction, freshly stamped.
            fixture.socket.emit(
                command(SyncPlayCommandType.Pause, now(), positionMs = 30_000, emittedAt = now().plusMillis(1)),
            )
            fixture.player.emit(PlayerEvent.Ready)
            advanceTimeBy(10_000)
            runCurrent()

            fixture.player.hadNoTransportCalls shouldBe true
            fixture.api.callsOf<SyncPlayCall.ReportReady>() shouldBe emptyList()
        }

    @Test
    fun `a group seek is answered with a ready even when the player never re-buffers`() =
        runTest {
            val fixture = fixture()
            joinPlaying(fixture)
            fixture.api.clearCalls()

            // A seek resets every member to buffering server-side; the group waits for each `ready`.
            fixture.socket.emit(command(SyncPlayCommandType.Seek, now(), positionMs = 90_000))
            runCurrent()
            fixture.api.callsOf<SyncPlayCall.ReportReady>() shouldBe emptyList()

            advanceTimeBy(SyncPlayController.SETTLED_READY_FALLBACK_MS)
            runCurrent()

            fixture.api
                .callsOf<SyncPlayCall.ReportReady>()
                .single()
                .playlistItemId shouldBe playlistItemId
        }

    @Test
    fun `the group going to waiting from playing pauses this member, and says nothing`() =
        runTest {
            val fixture = fixture()
            joinPlaying(fixture)
            fixture.player.resetCalls()
            fixture.api.clearCalls()

            fixture.socket.emit(
                SyncPlayGroupEvent.StateChanged(SyncPlayGroupState.Waiting, SyncPlayRequestKind.Buffer),
            )
            runCurrent()

            fixture.player.pauseCount shouldBe 1
            (fixture.controller.state.value as SyncPlayState.InGroup).phase shouldBe SyncPlayPhase.Waiting

            // Being told to wait is not a handshake: the pause makes the player ready again and that
            // must not turn into a report.
            fixture.player.emit(PlayerEvent.Ready)
            advanceTimeBy(10_000)
            runCurrent()
            fixture.api.callsOf<SyncPlayCall.ReportReady>() shouldBe emptyList()
            fixture.player.seekedToMs shouldBe emptyList()
        }

    @Test
    fun `a handshake into a playing group syncs itself when the group sends no command`() =
        runTest {
            val fixture = fixture()
            joinWithQueue(fixture, queue = twoItemQueue(playingIndex = 0))
            fixture.player.emit(PlayerEvent.Ready)
            runCurrent()
            fixture.socket.emit(command(SyncPlayCommandType.Unpause, now(), positionMs = 0))
            runCurrent()

            // The item ends, the group advances and reports itself playing — and then goes quiet.
            fixture.player.emit(PlayerEvent.Ended)
            runCurrent()
            fixture.socket.emit(
                SyncPlayGroupEvent.QueueChanged(
                    twoItemQueue(playingIndex = 1, reason = SyncPlayQueueUpdateReason.NextItem),
                ),
            )
            runCurrent()
            fixture.player.emit(PlayerEvent.Ready)
            runCurrent()
            fixture.socket.emit(
                SyncPlayGroupEvent.StateChanged(SyncPlayGroupState.Playing, SyncPlayRequestKind.Ready),
            )
            runCurrent()
            fixture.player.resetCalls()
            fixture.api.clearCalls()

            advanceTimeBy(SyncPlayController.SELF_SYNC_TIMEOUT_MS)
            runCurrent()

            // Stage one asks rather than acts, and the server answers a request for the state it is
            // already in with the authoritative command ("client got lost").
            fixture.api.callsOf<SyncPlayCall.RequestUnpause>().size shouldBe 1
            fixture.player.hadNoTransportCalls shouldBe true

            advanceTimeBy(SyncPlayController.COMMAND_REPEAT_TIMEOUT_MS)
            runCurrent()

            // Nothing came back, so the local fallback lands — today's behaviour, one window later.
            fixture.player.playCount shouldBe 1
            fixture.player.seekedToMs shouldBe
                listOf(SyncPlayController.SELF_SYNC_TIMEOUT_MS + SyncPlayController.COMMAND_REPEAT_TIMEOUT_MS)
            (fixture.controller.state.value as SyncPlayState.InGroup)
                .phase
                .shouldBeInstanceOf<SyncPlayPhase.Playing>()
            // Recovering must not re-open the handshake: another ready is what the storm is made of.
            fixture.api.callsOf<SyncPlayCall.ReportReady>() shouldBe emptyList()
            // And the second window is a fallback, not another ask: one request per episode.
            fixture.api.callsOf<SyncPlayCall.RequestUnpause>().size shouldBe 1
        }

    @Test
    fun `a playing group that sent no command is asked to repeat itself, and the repeat lands`() =
        runTest {
            val fixture = fixture()
            joinWithQueue(fixture)
            fixture.player.emit(PlayerEvent.Ready)
            runCurrent()
            fixture.socket.emit(
                SyncPlayGroupEvent.StateChanged(SyncPlayGroupState.Playing, SyncPlayRequestKind.Ready),
            )
            runCurrent()
            fixture.player.resetCalls()
            fixture.api.clearCalls()

            advanceTimeBy(SyncPlayController.SELF_SYNC_TIMEOUT_MS)
            runCurrent()

            // A request for the state the group is already in is not a state change: the server
            // reads it as "client got lost" and re-sends the current command to this session alone
            // (`PlayingGroupState.HandleRequest(UnpauseGroupRequest)`, prevState == Playing).
            fixture.api.callsOf<SyncPlayCall.RequestUnpause>().size shouldBe 1
            fixture.player.hadNoTransportCalls shouldBe true

            val whenInstant = now()
            fixture.socket.emit(command(SyncPlayCommandType.Unpause, whenInstant, positionMs = 90_000))
            runCurrent()

            // The group's own timeline, exact — not the inferred anchor the fallback would have used.
            fixture.player.playCount shouldBe 1
            (fixture.controller.state.value as SyncPlayState.InGroup).phase shouldBe
                SyncPlayPhase.Playing(SyncPlayAnchor(90_000L, whenInstant))

            advanceTimeBy(SyncPlayController.COMMAND_REPEAT_TIMEOUT_MS * 2)
            runCurrent()

            // The applied command stood the second window down: no local self-sync behind it, and
            // nothing asked for twice.
            fixture.player.playCount shouldBe 1
            fixture.api.callsOf<SyncPlayCall.RequestUnpause>().size shouldBe 1
        }

    @Test
    fun `a paused group that sent no command is asked to repeat itself, and the repeat lands`() =
        runTest {
            val fixture = fixture()
            joinPlaying(fixture)
            fixture.player.resetCalls()
            fixture.api.clearCalls()

            fixture.socket.emit(
                SyncPlayGroupEvent.StateChanged(SyncPlayGroupState.Paused, SyncPlayRequestKind.Pause),
            )
            advanceTimeBy(SyncPlayController.PAUSE_NET_TIMEOUT_MS)
            runCurrent()

            // `PausedGroupState.HandleRequest(PauseGroupRequest)` with prevState == Paused answers
            // the same way, and its command carries the position the group is parked at.
            fixture.api.callsOf<SyncPlayCall.RequestPause>().size shouldBe 1
            fixture.player.pauseCount shouldBe 0

            fixture.socket.emit(command(SyncPlayCommandType.Pause, now(), positionMs = 90_000))
            runCurrent()

            // Parked where the group is, rather than merely stopped where this member happened to be.
            fixture.player.pauseCount shouldBe 1
            fixture.player.seekedToMs shouldBe listOf(90_000L)

            advanceTimeBy(SyncPlayController.COMMAND_REPEAT_TIMEOUT_MS * 2)
            runCurrent()

            fixture.player.pauseCount shouldBe 1
            fixture.api.callsOf<SyncPlayCall.RequestPause>().size shouldBe 1
        }

    @Test
    fun `a state flip inside the second window stands the local fallback down too`() =
        runTest {
            val fixture = fixture()
            joinWithQueue(fixture)
            fixture.player.emit(PlayerEvent.Ready)
            runCurrent()
            fixture.socket.emit(
                SyncPlayGroupEvent.StateChanged(SyncPlayGroupState.Playing, SyncPlayRequestKind.Ready),
            )
            runCurrent()
            fixture.player.resetCalls()
            fixture.api.clearCalls()

            advanceTimeBy(SyncPlayController.SELF_SYNC_TIMEOUT_MS)
            runCurrent()
            fixture.api.callsOf<SyncPlayCall.RequestUnpause>().size shouldBe 1

            // The group changed its mind while the elicited command was still owed. The re-armed net
            // is the same job, so the ordinary disarm reaches it.
            advanceTimeBy(SyncPlayController.COMMAND_REPEAT_TIMEOUT_MS - 1)
            fixture.socket.emit(
                SyncPlayGroupEvent.StateChanged(SyncPlayGroupState.Paused, SyncPlayRequestKind.Pause),
            )
            runCurrent()
            advanceTimeBy(SyncPlayController.SELF_SYNC_TIMEOUT_MS + SyncPlayController.COMMAND_REPEAT_TIMEOUT_MS)
            runCurrent()

            // Nothing started a player in a group that is now paused, and the paused group's own net
            // has a stopped player to look at, so it asks for nothing either.
            fixture.player.hadNoTransportCalls shouldBe true
            fixture.api.callsOf<SyncPlayCall.RequestUnpause>().size shouldBe 1
            fixture.api.callsOf<SyncPlayCall.RequestPause>() shouldBe emptyList()
        }

    @Test
    fun `a self-sync measures from the instant the group's position was true, not from now`() =
        runTest {
            val fixture = fixture()
            // The group published "60 s" twenty seconds ago and has been playing ever since, so it
            // is at 80 s now. Pairing that position with *this* moment is the browser-resume desync
            // in the bug report: the member lands twenty seconds short and the drift monitor, handed
            // the same anchor, then defends the short timeline instead of closing the gap.
            val stale =
                queue(startTicks = 60_000L.millisToTicks())
                    .copy(lastUpdate = origin.minusMillis(GROUP_HEAD_START_MS))
            joinWithQueue(fixture, queue = stale)
            fixture.player.emit(PlayerEvent.Ready)
            runCurrent()
            fixture.player.resetCalls()

            fixture.socket.emit(
                SyncPlayGroupEvent.StateChanged(SyncPlayGroupState.Playing, SyncPlayRequestKind.Ready),
            )
            runCurrent()
            // Both windows: the server is asked to repeat itself first, and only a repeat that never
            // comes leaves the inferred anchor to do the work.
            advanceTimeBy(SyncPlayController.SELF_SYNC_TIMEOUT_MS + SyncPlayController.COMMAND_REPEAT_TIMEOUT_MS)
            runCurrent()

            fixture.player.playCount shouldBe 1
            fixture.player.seekedToMs shouldBe
                listOf(
                    60_000L + GROUP_HEAD_START_MS +
                        SyncPlayController.SELF_SYNC_TIMEOUT_MS + SyncPlayController.COMMAND_REPEAT_TIMEOUT_MS,
                )
            // The anchor handed on to the drift monitor is the queue's own instant, so every later
            // correction is measured against the group's real timeline.
            (fixture.controller.state.value as SyncPlayState.InGroup).phase shouldBe
                SyncPlayPhase.Playing(SyncPlayAnchor(60_000L, stale.lastUpdate))
        }

    @Test
    fun `an unpause arriving in time stands the safety net down`() =
        runTest {
            val fixture = fixture()
            joinPlaying(fixture)
            fixture.player.resetCalls()

            advanceTimeBy(SyncPlayController.SELF_SYNC_TIMEOUT_MS + SyncPlayController.COMMAND_REPEAT_TIMEOUT_MS)
            runCurrent()

            // The net is what would call `play` again; the drift monitor's corrective seeks (which
            // this fake player earns by never advancing) are not it.
            fixture.player.playCount shouldBe 0
            // Neither stage runs: a member playing in step has nothing to ask the server to repeat.
            fixture.api.callsOf<SyncPlayCall.RequestUnpause>() shouldBe emptyList()
        }

    @Test
    fun `a rebuilt player accepts the server's verbatim re-send of a command it already applied`() =
        runTest {
            val fixture = fixture()
            joinWithQueue(fixture)
            fixture.player.emit(PlayerEvent.Ready)
            runCurrent()
            val unpause = command(SyncPlayCommandType.Unpause, now(), positionMs = 0)
            fixture.socket.emit(unpause)
            runCurrent()
            fixture.player.playCount shouldBe 1

            // A track change throws the prepared player away; the host re-enters the handshake.
            fixture.controller.onHostBuffering()
            runCurrent()
            advanceTimeBy(2_000)
            fixture.player.snapshot = PlaybackSnapshot(positionMs = 0, isPlaying = false)
            fixture.player.resetCalls()

            // The server settles the rebuilt member by re-sending the standing command verbatim —
            // same instant, same position, only `emittedAt` fresh. Remembered as applied, this was
            // dropped as a repeat and the member never resumed (device, 2026-07-31: the blind
            // fallback then jumped from 6:35 to 27:27).
            fixture.socket.emit(
                command(SyncPlayCommandType.Unpause, unpause.whenInstant, positionMs = 0, emittedAt = now()),
            )
            runCurrent()

            fixture.player.playCount shouldBe 1
            // Two seconds past due, so the ordinary catch-up lands on the group's real position.
            fixture.player.seekedToMs shouldBe listOf(2_000L)
        }

    @Test
    fun `a resume out of a pause anchors on the parked player, not the stale queue`() =
        runTest {
            val fixture = fixture()
            joinPlaying(fixture)
            // The group pauses at 90 s — the command parks this member exactly there.
            fixture.socket.emit(
                SyncPlayGroupEvent.StateChanged(SyncPlayGroupState.Paused, SyncPlayRequestKind.Pause),
            )
            fixture.socket.emit(command(SyncPlayCommandType.Pause, now(), positionMs = 90_000))
            runCurrent()
            fixture.player.snapshot = PlaybackSnapshot(positionMs = 90_000, isPlaying = false)
            fixture.player.resetCalls()

            // Parked for a minute: the queue's own reading (0 s, published at join) is long stale.
            advanceTimeBy(60_000)
            // The resume's command frame is lost; only the state update arrives.
            fixture.socket.emit(
                SyncPlayGroupEvent.StateChanged(SyncPlayGroupState.Playing, SyncPlayRequestKind.Unpause),
            )
            runCurrent()
            advanceTimeBy(SyncPlayController.SELF_SYNC_TIMEOUT_MS + SyncPlayController.COMMAND_REPEAT_TIMEOUT_MS)
            runCurrent()

            fixture.player.playCount shouldBe 1
            // The group froze where this member is parked, so the fallback resumes from the parked
            // position plus its own delay — not from the queue's position plus a minute of elapsed
            // wall clock, which is the 23-minute jump measured on device (run 3).
            fixture.player.seekedToMs shouldBe
                listOf(90_000L + SyncPlayController.SELF_SYNC_TIMEOUT_MS + SyncPlayController.COMMAND_REPEAT_TIMEOUT_MS)
        }

    @Test
    fun `a group that says it is paused and sends no command pauses this member`() =
        runTest {
            val fixture = fixture()
            joinPlaying(fixture)
            fixture.player.resetCalls()
            fixture.api.clearCalls()

            fixture.socket.emit(
                SyncPlayGroupEvent.StateChanged(SyncPlayGroupState.Paused, SyncPlayRequestKind.Pause),
            )
            runCurrent()

            // Nothing local yet: the group's own `SendCommand` is still what is supposed to do this.
            fixture.player.hadNoTransportCalls shouldBe true
            val inGroup = fixture.controller.state.value as SyncPlayState.InGroup
            inGroup.groupState shouldBe SyncPlayGroupState.Paused
            inGroup.phase shouldBe SyncPlayPhase.Paused

            advanceTimeBy(SyncPlayController.PAUSE_NET_TIMEOUT_MS)
            runCurrent()

            // Stage one: a redundant pause request, which a group that is already paused answers by
            // re-sending its own pause to this session. Still nothing local.
            fixture.api.callsOf<SyncPlayCall.RequestPause>().size shouldBe 1
            fixture.player.hadNoTransportCalls shouldBe true

            advanceTimeBy(SyncPlayController.COMMAND_REPEAT_TIMEOUT_MS)
            runCurrent()

            // The command never came, and a member playing on alone is the one failure the phase
            // cannot even see: `Paused` shuts the drift monitor off with it.
            fixture.player.pauseCount shouldBe 1
            // The net only ever pauses — no seek, no `play`, and nothing reported to the server.
            fixture.player.seekedToMs shouldBe emptyList()
            fixture.player.playCount shouldBe 0
            fixture.api.callsOf<SyncPlayCall.ReportReady>() shouldBe emptyList()
            // The fallback window asks nothing: one elicit per state-change episode.
            fixture.api.callsOf<SyncPlayCall.RequestPause>().size shouldBe 1
        }

    @Test
    fun `the group's own pause arriving in time stands the pause net down`() =
        runTest {
            val fixture = fixture()
            joinPlaying(fixture)
            fixture.player.resetCalls()

            fixture.socket.emit(
                SyncPlayGroupEvent.StateChanged(SyncPlayGroupState.Paused, SyncPlayRequestKind.Pause),
            )
            runCurrent()
            fixture.socket.emit(command(SyncPlayCommandType.Pause, now(), positionMs = 30_000))
            runCurrent()

            fixture.player.pauseCount shouldBe 1

            advanceTimeBy(SyncPlayController.PAUSE_NET_TIMEOUT_MS + SyncPlayController.COMMAND_REPEAT_TIMEOUT_MS)
            runCurrent()

            // Once, by the command the group sent — the net armed behind it must not pause again.
            fixture.player.pauseCount shouldBe 1
            // An applied command stands both stages down, so nothing is asked for either.
            fixture.api.callsOf<SyncPlayCall.RequestPause>() shouldBe emptyList()
        }

    @Test
    fun `the pause net does nothing to a player that is already stopped`() =
        runTest {
            val fixture = fixture()
            joinWithQueue(fixture)
            fixture.player.emit(PlayerEvent.Ready)
            runCurrent()
            fixture.player.resetCalls()

            fixture.socket.emit(
                SyncPlayGroupEvent.StateChanged(SyncPlayGroupState.Paused, SyncPlayRequestKind.Pause),
            )
            advanceTimeBy(SyncPlayController.PAUSE_NET_TIMEOUT_MS + SyncPlayController.COMMAND_REPEAT_TIMEOUT_MS)
            runCurrent()

            fixture.player.hadNoTransportCalls shouldBe true
            // Nothing to recover, so nothing is asked for either: a stopped player is already where
            // the group is, and a request per lost pause would be traffic for its own sake.
            fixture.api.callsOf<SyncPlayCall.RequestPause>() shouldBe emptyList()
        }

    @Test
    fun `joining a group that is already paused stops a player that was running solo`() =
        runTest {
            val fixture = fixture()
            // The user was watching this alone, and joins a group that is sitting paused.
            fixture.player.snapshot = PlaybackSnapshot(isPlaying = true)

            fixture.controller.joinGroup(group(state = SyncPlayGroupState.Paused))
            runCurrent()

            (fixture.controller.state.value as SyncPlayState.InGroup).groupState shouldBe SyncPlayGroupState.Paused
            fixture.player.hadNoTransportCalls shouldBe true

            advanceTimeBy(SyncPlayController.PAUSE_NET_TIMEOUT_MS + SyncPlayController.COMMAND_REPEAT_TIMEOUT_MS)
            runCurrent()

            fixture.player.pauseCount shouldBe 1
        }

    @Test
    fun `a waiting group holds a player that is still running, whatever this member's phase says`() =
        runTest {
            val fixture = fixture()
            joinPlaying(fixture)
            fixture.player.resetCalls()

            // The pause the group sent never arrived: the phase says `Paused` over a player that is
            // still playing, which is the reading the hold must not take its answer from.
            fixture.socket.emit(
                SyncPlayGroupEvent.StateChanged(SyncPlayGroupState.Paused, SyncPlayRequestKind.Pause),
            )
            runCurrent()
            (fixture.controller.state.value as SyncPlayState.InGroup).phase shouldBe SyncPlayPhase.Paused
            fixture.player.hadNoTransportCalls shouldBe true

            fixture.socket.emit(
                SyncPlayGroupEvent.StateChanged(SyncPlayGroupState.Waiting, SyncPlayRequestKind.Buffer),
            )
            runCurrent()

            // Held at once, well inside the pause net's window — a member that plays on behind the
            // WAITING overlay is drifting ahead, phase or no phase.
            fixture.player.pauseCount shouldBe 1
        }

    @Test
    fun `a group that stops playing cancels the self-sync armed behind it`() =
        runTest {
            val fixture = fixture()
            joinWithQueue(fixture)
            fixture.player.emit(PlayerEvent.Ready)
            runCurrent()
            fixture.socket.emit(
                SyncPlayGroupEvent.StateChanged(SyncPlayGroupState.Playing, SyncPlayRequestKind.Ready),
            )
            runCurrent()
            fixture.player.resetCalls()

            // The group changed its mind inside the net's window; nothing armed while it was playing
            // may start playback in a group that is now paused.
            advanceTimeBy(SyncPlayController.SELF_SYNC_TIMEOUT_MS - 1)
            fixture.socket.emit(
                SyncPlayGroupEvent.StateChanged(SyncPlayGroupState.Paused, SyncPlayRequestKind.Pause),
            )
            runCurrent()
            advanceTimeBy(SyncPlayController.SELF_SYNC_TIMEOUT_MS * 2)
            runCurrent()

            fixture.player.hadNoTransportCalls shouldBe true
        }

    @Test
    fun `a queue update that puts everyone back to buffering is answered, slot unchanged or not`() =
        runTest {
            val fixture = fixture()
            joinWithQueue(fixture)
            fixture.player.emit(PlayerEvent.Ready)
            runCurrent()
            fixture.api.clearCalls()

            // The group restarted the item it is already on (`Unpause` out of Idle, server-side):
            // same slot, nothing to load, and `SetAllBuffering(true)` waiting on a ready all the same.
            fixture.socket.emit(SyncPlayGroupEvent.QueueChanged(queue()))
            runCurrent()

            fixture.host.loaded.size shouldBe 1
            fixture.api
                .callsOf<SyncPlayCall.ReportReady>()
                .single()
                .playlistItemId shouldBe playlistItemId

            // A reorder is not one of those, and stays silent.
            fixture.api.clearCalls()
            fixture.socket.emit(
                SyncPlayGroupEvent.QueueChanged(queue().copy(reason = SyncPlayQueueUpdateReason.MoveItem)),
            )
            runCurrent()
            fixture.api.callsOf<SyncPlayCall.ReportReady>() shouldBe emptyList()
        }

    // Losing the group ------------------------------------------------------------------------------

    @Test
    fun `a confirmed connection loss pauses the player at once, and leaves once the rejoin has failed`() =
        runTest {
            val fixture = fixture()
            // The connection really is gone, so the group list cannot be read either.
            fixture.api.getGroupsError = java.io.IOException("socket closed")
            joinPlaying(fixture)

            fixture.socket.failStreams(java.io.IOException("socket closed"))
            runCurrent()

            // Frozen immediately — the rejoin never lets playback run on out of step.
            fixture.player.pauseCount shouldBe 1
            fixture.status.inGroup.value shouldBe false
            fixture.messages shouldBe emptyList()

            advanceTimeBy(SyncPlayController.REJOIN_RETRY_DELAY_MS * SyncPlayController.REJOIN_MAX_ATTEMPTS)
            runCurrent()

            fixture.controller.state.value shouldBe SyncPlayState.Idle
            fixture.messages shouldBe listOf(SyncPlayMessage.ConnectionLost)

            // Nothing keeps running: no drift correction, no ping, no scheduled command.
            fixture.player.resetCalls()
            fixture.api.clearCalls()
            advanceTimeBy(30_000)
            runCurrent()
            fixture.player.hadNoTransportCalls shouldBe true
            fixture.api.calls shouldBe emptyList()
        }

    @Test
    fun `a confirmed loss the connection comes back from is a rejoin, not an ending`() =
        runTest {
            val fixture = fixture()
            fixture.api.groups = listOf(group())
            joinPlaying(fixture)
            fixture.player.resetCalls()
            fixture.api.clearCalls()

            // The device case exactly: three seconds of Wi-Fi off costs more than the grace window
            // once association and the reachability probe are counted, so the loss is confirmed
            // before anything has heard a `NotInGroup`.
            fixture.connection.value = ConnectionState.OFFLINE_NO_NETWORK
            runCurrent()
            advanceTimeBy(SyncPlayController.CONNECTIVITY_GRACE_MS + 1)
            runCurrent()
            fixture.controller.state.value
                .shouldBeInstanceOf<SyncPlayState.Rejoining>()
            // Not a single call spent on a network that is known to be down.
            fixture.api.callsOf<SyncPlayCall.GetGroups>() shouldBe emptyList()

            fixture.connection.value = ConnectionState.ONLINE
            runCurrent()

            fixture.api.callsOf<SyncPlayCall.GetGroups>().size shouldBe 1
            fixture.controller.state.value
                .shouldBeInstanceOf<SyncPlayState.InGroup>()
            fixture.messages shouldBe listOf(SyncPlayMessage.Rejoined)
            fixture.player.playCount shouldBe 0
        }

    @Test
    fun `going offline freezes at once, and is a confirmed loss once the grace window is out`() =
        runTest {
            val fixture = fixture()
            joinPlaying(fixture)
            fixture.player.resetCalls()

            fixture.connection.value = ConnectionState.OFFLINE_NO_NETWORK
            runCurrent()

            // Frozen immediately, so a hard Wi-Fi kill still stops within the window rather than at
            // the end of it — but the group is not given up on yet.
            fixture.player.pauseCount shouldBe 1
            fixture.controller.state.value
                .shouldBeInstanceOf<SyncPlayState.InGroup>()
            fixture.messages shouldBe emptyList()

            advanceTimeBy(SyncPlayController.CONNECTIVITY_GRACE_MS + 1)
            runCurrent()

            // Still not given up on: the rejoin gets its attempts first, all of them spent waiting
            // for a network that never comes back.
            fixture.controller.state.value
                .shouldBeInstanceOf<SyncPlayState.Rejoining>()
            advanceTimeBy(SyncPlayController.REJOIN_RETRY_DELAY_MS * SyncPlayController.REJOIN_MAX_ATTEMPTS * 2)
            runCurrent()

            fixture.controller.state.value shouldBe SyncPlayState.Idle
            fixture.messages shouldBe listOf(SyncPlayMessage.ConnectionLost)
        }

    @Test
    fun `a connectivity blip shorter than the grace window keeps the group and re-negotiates`() =
        runTest {
            val fixture = fixture()
            joinPlaying(fixture)
            fixture.player.resetCalls()
            fixture.api.clearCalls()

            fixture.connection.value = ConnectionState.OFFLINE_NO_NETWORK
            runCurrent()
            advanceTimeBy(BLIP_MS)
            fixture.connection.value = ConnectionState.ONLINE
            runCurrent()

            fixture.controller.state.value
                .shouldBeInstanceOf<SyncPlayState.InGroup>()
            fixture.messages shouldBe emptyList()
            // Held for the blip rather than run on into a drift the group cannot see...
            fixture.player.pauseCount shouldBe 1
            // ...and the group is asked to put this member back in step rather than told nothing.
            fixture.api.callsOf<SyncPlayCall.ReportBuffering>().size shouldBe 1

            // The window that was opened must not fire behind it.
            advanceTimeBy(SyncPlayController.CONNECTIVITY_GRACE_MS * 2)
            runCurrent()
            fixture.controller.state.value
                .shouldBeInstanceOf<SyncPlayState.InGroup>()
            fixture.messages shouldBe emptyList()
        }

    @Test
    fun `a streak of failed ping cycles is a confirmed loss, once`() =
        runTest {
            val fixture = fixture()
            // The device case: the OS still says "online" while the platform has cut this app's
            // network, so every REST call times out and the socket never comes back.
            fixture.api.failEverySample = java.io.IOException("timeout")
            fixture.api.getGroupsError = java.io.IOException("timeout")
            joinPlaying(fixture)
            fixture.player.resetCalls()

            advanceTimeBy(SyncPlayPinger.FAST_INTERVAL_MS * SyncPlayController.PING_FAILURE_STREAK)
            runCurrent()

            // Frozen at once, and given up on only after the rejoin attempts have failed too.
            fixture.player.pauseCount shouldBe 1
            advanceTimeBy(SyncPlayController.REJOIN_RETRY_DELAY_MS * SyncPlayController.REJOIN_MAX_ATTEMPTS)
            runCurrent()

            fixture.controller.state.value shouldBe SyncPlayState.Idle
            fixture.messages shouldBe listOf(SyncPlayMessage.ConnectionLost)

            advanceTimeBy(60_000)
            runCurrent()
            fixture.player.pauseCount shouldBe 1
            fixture.messages shouldBe listOf(SyncPlayMessage.ConnectionLost)
        }

    @Test
    fun `a socket state flap the SDK recovers from does not drop the group`() =
        runTest {
            val fixture = fixture()
            joinPlaying(fixture)
            fixture.player.resetCalls()

            fixture.socket.setConnectionState(SyncPlaySocketState.Disconnected(java.io.IOException("blip")))
            runCurrent()
            fixture.socket.setConnectionState(SyncPlaySocketState.Connecting)
            runCurrent()
            fixture.socket.setConnectionState(SyncPlaySocketState.Connected)
            runCurrent()

            fixture.controller.state.value
                .shouldBeInstanceOf<SyncPlayState.InGroup>()
            fixture.player.pauseCount shouldBe 0
            fixture.messages shouldBe emptyList()
        }

    @Test
    fun `the server saying we are not in the group over a healthy socket tears down without pausing`() =
        runTest {
            val fixture = fixture()
            fixture.api.groups = listOf(group())
            joinPlaying(fixture)
            fixture.player.resetCalls()
            fixture.api.clearCalls()

            fixture.socket.emit(SyncPlayGroupEvent.NotInGroup)
            runCurrent()

            fixture.controller.state.value shouldBe SyncPlayState.Idle
            fixture.messages shouldBe listOf(SyncPlayMessage.RemovedFromGroup)
            fixture.player.pauseCount shouldBe 0
            // Nothing was wrong with the connection, so the removal was somebody's decision: obeyed,
            // not undone. Not even the group list is asked for.
            fixture.api.calls shouldBe emptyList()
        }

    @Test
    fun `a group that no longer exists tears down`() =
        runTest {
            val fixture = fixture()
            joinWithQueue(fixture)

            fixture.socket.emit(SyncPlayGroupEvent.GroupGone)
            runCurrent()

            fixture.controller.state.value shouldBe SyncPlayState.Idle
            fixture.messages shouldBe listOf(SyncPlayMessage.GroupEnded)
        }

    @Test
    fun `a library this account may not see tears down`() =
        runTest {
            val fixture = fixture()
            joinWithQueue(fixture)

            fixture.socket.emit(SyncPlayGroupEvent.LibraryAccessDenied)
            runCurrent()

            fixture.controller.state.value shouldBe SyncPlayState.Idle
            fixture.messages shouldBe listOf(SyncPlayMessage.LibraryAccessDenied)
        }

    @Test
    fun `being removed from our own group tears down`() =
        runTest {
            val fixture = fixture()
            joinWithQueue(fixture)

            fixture.socket.emit(SyncPlayGroupEvent.Left(group().id))
            runCurrent()

            fixture.controller.state.value shouldBe SyncPlayState.Idle
            fixture.messages shouldBe listOf(SyncPlayMessage.RemovedFromGroup)
        }

    @Test
    fun `leaving tells the server and stops everything, without touching playback`() =
        runTest {
            val fixture = fixture()
            joinPlaying(fixture)
            fixture.player.resetCalls()

            fixture.controller.leaveGroup()
            runCurrent()

            fixture.api.callsOf<SyncPlayCall.LeaveGroup>().size shouldBe 1
            fixture.controller.state.value shouldBe SyncPlayState.Idle
            fixture.socket.collectors shouldBe 0
            // Leaving deliberately does not pause: playback simply carries on, now solo.
            fixture.player.hadNoTransportCalls shouldBe true
            fixture.messages shouldBe emptyList()
        }

    @Test
    fun `signing out leaves the group`() =
        runTest {
            val fixture = fixture()
            joinWithQueue(fixture)
            fixture.api.clearCalls()

            fixture.session.value = SessionState.LoggedOut
            runCurrent()

            fixture.api.callsOf<SyncPlayCall.LeaveGroup>().size shouldBe 1
            fixture.controller.state.value shouldBe SyncPlayState.Idle
            fixture.status.inGroup.value shouldBe false
        }

    // Taking the group back (auto-rejoin) -------------------------------------------------------------

    @Test
    fun `a membership dropped after a blip is taken back, and the player is not started by it`() =
        runTest {
            val fixture = fixture()
            fixture.api.groups = listOf(group())
            joinPlaying(fixture)
            fixture.player.resetCalls()
            fixture.api.clearCalls()

            blipThenDropped(fixture)

            fixture.api.callsOf<SyncPlayCall.GetGroups>().size shouldBe 1
            fixture.api
                .callsOf<SyncPlayCall.JoinGroup>()
                .single()
                .groupId shouldBe group().id
            fixture.controller.state.value
                .shouldBeInstanceOf<SyncPlayState.InGroup>()
            fixture.status.inGroup.value shouldBe true
            fixture.messages shouldBe listOf(SyncPlayMessage.Rejoined)
            // Frozen throughout: the group's own answer to the handshake is what resumes anyone.
            fixture.player.playCount shouldBe 0

            // The server's post-join queue re-enters the handshake on the item already open, rather
            // than reloading it — which is what keeps "rejoin" from looking like "start again".
            fixture.socket.emit(SyncPlayGroupEvent.QueueChanged(queue()))
            runCurrent()

            fixture.host.loaded.size shouldBe 1
            fixture.api
                .callsOf<SyncPlayCall.ReportBuffering>()
                .last()
                .playlistItemId shouldBe playlistItemId

            // The adopted player is prepared already and may never re-buffer, so the owed `ready` is
            // the fallback's (DECISIONS.md, 2026-07-31) — but it is still owed and still sent.
            advanceTimeBy(SyncPlayController.SETTLED_READY_FALLBACK_MS + 1)
            runCurrent()

            fixture.api
                .callsOf<SyncPlayCall.ReportReady>()
                .last()
                .playlistItemId shouldBe playlistItemId
            fixture.player.playCount shouldBe 0
        }

    @Test
    fun `a rejoin makes membership fall and rise, so a downloaded item re-mints its session`() =
        runTest {
            val fixture = fixture()
            fixture.api.groups = listOf(group())
            joinPlaying(fixture)

            blipThenDropped(fixture)

            // Idle, in the group, out of it while the server did not have us, in it again. The middle
            // `false` is the whole point: `SyncPlayLocalSession` mints on the way back up.
            fixture.membershipEdges shouldBe listOf(false, true, false, true)
        }

    @Test
    fun `a call refused after a blip is a lost membership, and is rejoined too`() =
        runTest {
            val fixture = fixture()
            fixture.api.groups = listOf(group())
            joinPlaying(fixture)
            fixture.api.clearCalls()
            // The re-negotiation that follows the blip is the first call to find out.
            fixture.api.failNextBuffering = InvalidStatusException(HTTP_FORBIDDEN)

            blip(fixture)

            fixture.api.callsOf<SyncPlayCall.GetGroups>().size shouldBe 1
            fixture.controller.state.value
                .shouldBeInstanceOf<SyncPlayState.InGroup>()
            fixture.messages shouldBe listOf(SyncPlayMessage.Rejoined)
        }

    @Test
    fun `a group that has dissolved is asked after once, and then given up on`() =
        runTest {
            val fixture = fixture()
            // Nobody else was in it, so the server removed the group along with this session.
            fixture.api.groups = emptyList()
            joinPlaying(fixture)
            fixture.api.clearCalls()

            blipThenDropped(fixture)

            fixture.api.callsOf<SyncPlayCall.GetGroups>().size shouldBe 1
            fixture.api.callsOf<SyncPlayCall.JoinGroup>() shouldBe emptyList()
            fixture.controller.state.value shouldBe SyncPlayState.Idle
            fixture.messages shouldBe listOf(SyncPlayMessage.GroupEnded)

            // And no attempt keeps running behind the teardown.
            advanceTimeBy(SyncPlayController.REJOIN_RETRY_DELAY_MS * 4)
            runCurrent()
            fixture.api.callsOf<SyncPlayCall.GetGroups>().size shouldBe 1
            fixture.messages shouldBe listOf(SyncPlayMessage.GroupEnded)
        }

    @Test
    fun `rejoining is tried exactly three times, two seconds apart, and then reported once`() =
        runTest {
            val fixture = fixture()
            fixture.api.getGroupsError = java.io.IOException("server unreachable")
            joinPlaying(fixture)
            fixture.api.clearCalls()

            blipThenDropped(fixture)
            fixture.api.callsOf<SyncPlayCall.GetGroups>().size shouldBe 1
            fixture.controller.state.value
                .shouldBeInstanceOf<SyncPlayState.Rejoining>()

            advanceTimeBy(SyncPlayController.REJOIN_RETRY_DELAY_MS - 1)
            runCurrent()
            fixture.api.callsOf<SyncPlayCall.GetGroups>().size shouldBe 1

            advanceTimeBy(1)
            runCurrent()
            fixture.api.callsOf<SyncPlayCall.GetGroups>().size shouldBe 2

            advanceTimeBy(SyncPlayController.REJOIN_RETRY_DELAY_MS)
            runCurrent()
            fixture.api.callsOf<SyncPlayCall.GetGroups>().size shouldBe SyncPlayController.REJOIN_MAX_ATTEMPTS
            fixture.controller.state.value shouldBe SyncPlayState.Idle
            fixture.messages shouldBe listOf(SyncPlayMessage.ConnectionLost)

            // Once out, we stay out: no background loop keeps asking.
            advanceTimeBy(60_000)
            runCurrent()
            fixture.api.callsOf<SyncPlayCall.GetGroups>().size shouldBe SyncPlayController.REJOIN_MAX_ATTEMPTS
            fixture.messages shouldBe listOf(SyncPlayMessage.ConnectionLost)
        }

    @Test
    fun `leaving during a rejoin aborts it, silently`() =
        runTest {
            val fixture = fixture()
            fixture.api.getGroupsError = java.io.IOException("server unreachable")
            joinPlaying(fixture)
            blipThenDropped(fixture)
            fixture.controller.state.value
                .shouldBeInstanceOf<SyncPlayState.Rejoining>()
            fixture.api.clearCalls()

            fixture.controller.leaveGroup()
            runCurrent()

            fixture.controller.state.value shouldBe SyncPlayState.Idle
            advanceTimeBy(SyncPlayController.REJOIN_RETRY_DELAY_MS * 4)
            runCurrent()
            fixture.api.callsOf<SyncPlayCall.GetGroups>() shouldBe emptyList()
            fixture.messages shouldBe emptyList()
        }

    @Test
    fun `signing out during a rejoin aborts it, silently`() =
        runTest {
            val fixture = fixture()
            fixture.api.getGroupsError = java.io.IOException("server unreachable")
            joinPlaying(fixture)
            blipThenDropped(fixture)
            fixture.api.clearCalls()

            fixture.session.value = SessionState.LoggedOut
            runCurrent()

            fixture.controller.state.value shouldBe SyncPlayState.Idle
            fixture.status.inGroup.value shouldBe false
            advanceTimeBy(SyncPlayController.REJOIN_RETRY_DELAY_MS * 4)
            runCurrent()
            fixture.api.callsOf<SyncPlayCall.GetGroups>() shouldBe emptyList()
            fixture.messages shouldBe emptyList()
        }

    @Test
    fun `a library this account may not see is never rejoined, however bad the connection was`() =
        runTest {
            val fixture = fixture()
            fixture.api.groups = listOf(group())
            joinPlaying(fixture)
            fixture.api.clearCalls()

            blip(fixture)
            fixture.socket.emit(SyncPlayGroupEvent.LibraryAccessDenied)
            runCurrent()

            fixture.controller.state.value shouldBe SyncPlayState.Idle
            fixture.messages shouldBe listOf(SyncPlayMessage.LibraryAccessDenied)
            fixture.api.callsOf<SyncPlayCall.GetGroups>() shouldBe emptyList()
        }

    @Test
    fun `a removal long after the connection settled is obeyed rather than undone`() =
        runTest {
            val fixture = fixture()
            fixture.api.groups = listOf(group())
            joinPlaying(fixture)
            blip(fixture)
            fixture.api.clearCalls()

            // The blip is ancient history by the time this arrives, so it explains nothing.
            advanceTimeBy(SyncPlayController.REJOIN_TROUBLE_WINDOW_MS + 1)
            runCurrent()
            fixture.socket.emit(SyncPlayGroupEvent.NotInGroup)
            runCurrent()

            fixture.controller.state.value shouldBe SyncPlayState.Idle
            fixture.messages shouldBe listOf(SyncPlayMessage.RemovedFromGroup)
            fixture.api.callsOf<SyncPlayCall.GetGroups>() shouldBe emptyList()
        }

    // Coming back to the app ------------------------------------------------------------------------

    @Test
    fun `a membership the background cost us is asked for again when the app comes back`() =
        runTest {
            val fixture = fixture()
            fixture.api.groups = listOf(group())
            val lost = backgroundedUntilLost(fixture)

            // The network is back long before the user is, and nothing is running to notice it.
            fixture.connection.value = ConnectionState.ONLINE
            runCurrent()
            fixture.controller.state.value shouldBe SyncPlayState.Idle
            fixture.api.clearCalls()

            fixture.controller.onAppForegrounded()
            runCurrent()

            fixture.api.callsOf<SyncPlayCall.GetGroups>().size shouldBe 1
            fixture.api
                .callsOf<SyncPlayCall.JoinGroup>()
                .single()
                .groupId shouldBe group().id
            fixture.controller.state.value
                .shouldBeInstanceOf<SyncPlayState.InGroup>()
            fixture.status.inGroup.value shouldBe true
            fixture.messages shouldBe lost + SyncPlayMessage.Rejoined
        }

    @Test
    fun `the group having gone in the meantime is forgotten without a word`() =
        runTest {
            val fixture = fixture()
            fixture.api.groups = listOf(group())
            val lost = backgroundedUntilLost(fixture)

            // Everyone else left too, so the server disposed of it.
            fixture.api.groups = emptyList()
            fixture.connection.value = ConnectionState.ONLINE
            runCurrent()
            fixture.api.clearCalls()

            fixture.controller.onAppForegrounded()
            runCurrent()

            fixture.api.callsOf<SyncPlayCall.GetGroups>().size shouldBe 1
            fixture.controller.state.value shouldBe SyncPlayState.Idle
            // Silent: a re-check that finds nothing must not announce itself on every foreground.
            fixture.messages shouldBe lost

            // And forgotten, so the next foreground spends nothing on it.
            fixture.api.clearCalls()
            fixture.controller.onAppForegrounded()
            runCurrent()
            fixture.api.calls shouldBe emptyList()
        }

    @Test
    fun `a foreground re-check that fails is silent, does not loop, and may be tried again`() =
        runTest {
            val fixture = fixture()
            fixture.api.groups = listOf(group())
            val lost = backgroundedUntilLost(fixture)

            // Online as far as the OS is concerned, but the server is still not answering — the
            // device's own failure mode (B8), and the reason a re-check cannot assume it will work.
            fixture.connection.value = ConnectionState.ONLINE
            fixture.api.getGroupsError = java.io.IOException("no route to host")
            runCurrent()
            fixture.api.clearCalls()

            fixture.controller.onAppForegrounded()
            runCurrent()
            advanceTimeBy(SyncPlayController.REJOIN_RETRY_DELAY_MS * SyncPlayController.REJOIN_MAX_ATTEMPTS)
            runCurrent()

            fixture.api.callsOf<SyncPlayCall.GetGroups>().size shouldBe SyncPlayController.REJOIN_MAX_ATTEMPTS
            fixture.controller.state.value shouldBe SyncPlayState.Idle
            fixture.messages shouldBe lost

            // Nothing keeps trying in the background...
            fixture.api.clearCalls()
            advanceTimeBy(60_000)
            runCurrent()
            fixture.api.calls shouldBe emptyList()

            // ...and the memory survived the failure, so the next foreground gets it back.
            fixture.api.getGroupsError = null
            fixture.controller.onAppForegrounded()
            runCurrent()
            fixture.controller.state.value
                .shouldBeInstanceOf<SyncPlayState.InGroup>()
        }

    @Test
    fun `a loss older than the foreground window is dropped rather than acted on`() =
        runTest {
            val fixture = fixture()
            fixture.api.groups = listOf(group())
            val lost = backgroundedUntilLost(fixture)
            fixture.connection.value = ConnectionState.ONLINE
            runCurrent()
            fixture.api.clearCalls()

            advanceTimeBy(SyncPlayController.FOREGROUND_REJOIN_WINDOW_MS + 1)
            fixture.controller.onAppForegrounded()
            runCurrent()

            fixture.api.calls shouldBe emptyList()
            fixture.controller.state.value shouldBe SyncPlayState.Idle
            fixture.messages shouldBe lost
        }

    @Test
    fun `a group left on purpose is never taken back, however soon the app comes back`() =
        runTest {
            val fixture = fixture()
            fixture.api.groups = listOf(group())
            joinPlaying(fixture)

            fixture.controller.leaveGroup()
            runCurrent()
            fixture.api.clearCalls()

            fixture.controller.onAppForegrounded()
            runCurrent()

            fixture.api.calls shouldBe emptyList()
            fixture.controller.state.value shouldBe SyncPlayState.Idle
        }

    @Test
    fun `signing out forgets a group that was lost, so the next account never sees it`() =
        runTest {
            val fixture = fixture()
            fixture.api.groups = listOf(group())
            backgroundedUntilLost(fixture)
            fixture.connection.value = ConnectionState.ONLINE
            runCurrent()

            fixture.session.value = SessionState.LoggedOut
            runCurrent()
            fixture.api.clearCalls()

            fixture.controller.onAppForegrounded()
            runCurrent()

            fixture.api.calls shouldBe emptyList()
            fixture.controller.state.value shouldBe SyncPlayState.Idle
        }

    @Test
    fun `coming back while still in a group pings at once rather than waiting for the cadence`() =
        runTest {
            val fixture = fixture()
            joinPlaying(fixture)
            // Past the pinger's opening burst, so the loop is sitting on its five-second wait.
            advanceTimeBy(SyncPlayPinger.FAST_INTERVAL_MS * SyncPlayPinger.FAST_SAMPLES)
            runCurrent()
            fixture.api.clearCalls()

            fixture.controller.onAppForegrounded()
            runCurrent()

            // A connection that died off screen starts its failure streak now, not five seconds on.
            fixture.api.callsOf<SyncPlayCall.SampleServerTime>().size shouldBe 1
            fixture.controller.state.value
                .shouldBeInstanceOf<SyncPlayState.InGroup>()
        }

    // Fixture ---------------------------------------------------------------------------------------

    /**
     * The device failure in full: the app is backgrounded, the platform quietly cuts its network,
     * and the group is gone by the time anything can be done about it.
     *
     * @return the messages emitted by the loss, so a test can assert that the foreground re-check
     *   added nothing to them.
     */
    private suspend fun TestScope.backgroundedUntilLost(fixture: Fixture): List<SyncPlayMessage> {
        joinPlaying(fixture)
        fixture.connection.value = ConnectionState.OFFLINE_NO_NETWORK
        runCurrent()
        advanceTimeBy(SyncPlayController.CONNECTIVITY_GRACE_MS + 1)
        runCurrent()
        advanceTimeBy(SyncPlayController.REJOIN_RETRY_DELAY_MS * SyncPlayController.REJOIN_MAX_ATTEMPTS * 2)
        runCurrent()
        fixture.controller.state.value shouldBe SyncPlayState.Idle
        fixture.messages shouldBe listOf(SyncPlayMessage.ConnectionLost)
        return fixture.messages.toList()
    }

    /** A Wi-Fi blip the grace window rides out — and the trouble that explains a later removal. */
    private fun TestScope.blip(fixture: Fixture) {
        fixture.connection.value = ConnectionState.OFFLINE_NO_NETWORK
        runCurrent()
        advanceTimeBy(BLIP_MS)
        fixture.connection.value = ConnectionState.ONLINE
        runCurrent()
    }

    /**
     * The device case in full: a blip the client survives and the *server* does not.
     *
     * The dropped websocket ends the session, `OnSessionEnded` leaves the group on this client's
     * behalf, and the next request comes back `NotInGroup`.
     */
    private suspend fun TestScope.blipThenDropped(fixture: Fixture) {
        blip(fixture)
        fixture.socket.emit(SyncPlayGroupEvent.NotInGroup)
        runCurrent()
    }

    private fun TestScope.assertIntent(
        fixture: Fixture,
        expected: SyncPlayCall,
        intent: (SyncPlayController) -> Unit,
    ) {
        fixture.api.clearCalls()
        fixture.player.resetCalls()

        intent(fixture.controller)
        runCurrent()

        fixture.api.calls shouldBe listOf(expected)
        fixture.player.hadNoTransportCalls shouldBe true
    }

    /** Joins, and lets the server publish a queue — one entry unless a test supplies another. */
    private suspend fun TestScope.joinWithQueue(
        fixture: Fixture,
        startTicks: Long = 0L,
        queue: SyncPlayGroupQueue = queue(startTicks),
    ) {
        fixture.controller.joinGroup(group())
        runCurrent()
        fixture.socket.emit(SyncPlayGroupEvent.QueueChanged(queue))
        runCurrent()
    }

    /** Joins, readies up, and lets the group start playing — the state most failures matter in. */
    private suspend fun TestScope.joinPlaying(fixture: Fixture) {
        joinWithQueue(fixture)
        fixture.player.emit(PlayerEvent.Ready)
        runCurrent()
        fixture.socket.emit(command(SyncPlayCommandType.Unpause, now(), positionMs = 0))
        runCurrent()
        (fixture.controller.state.value as SyncPlayState.InGroup).phase.shouldBeInstanceOf<SyncPlayPhase.Playing>()
    }

    private fun TestScope.now(): Instant = origin.plusMillis(currentTime)

    private fun command(
        type: SyncPlayCommandType,
        whenInstant: Instant,
        positionMs: Long?,
        emittedAt: Instant = whenInstant,
    ) = SyncPlayCommand(
        type = type,
        whenInstant = whenInstant,
        positionTicks = positionMs?.millisToTicks(),
        playlistItemId = playlistItemId,
        emittedAt = emittedAt,
    )

    private fun queue(startTicks: Long = 0L) =
        SyncPlayGroupQueue(
            entries = listOf(SyncPlayQueueEntry(itemId, playlistItemId)),
            playingItemIndex = 0,
            startPositionTicks = startTicks,
            isPlaying = false,
            shuffleMode = SyncPlayShuffleMode.Sorted,
            repeatMode = SyncPlayRepeatMode.None,
            reason = SyncPlayQueueUpdateReason.NewPlaylist,
            lastUpdate = origin,
        )

    /**
     * Two slots holding two different items, with the group on [playingIndex].
     *
     * Two is the smallest queue that can tell "the group moved on" apart from "the queue was
     * re-sent", which is the whole of Phase 4's reconciliation.
     */
    private fun twoItemQueue(
        playingIndex: Int,
        startTicks: Long = 0L,
        reason: SyncPlayQueueUpdateReason = SyncPlayQueueUpdateReason.NewPlaylist,
    ) = queue(startTicks).copy(
        entries =
            listOf(
                SyncPlayQueueEntry(itemId, playlistItemId),
                SyncPlayQueueEntry(otherItemId, otherPlaylistItemId),
            ),
        playingItemIndex = playingIndex,
        reason = reason,
    )

    /** Everything a test needs to drive the controller and to see what it did. */
    @Suppress("LongParameterList") // A test-only bag of collaborators; grouping them would only hide them.
    private class Fixture(
        val controller: SyncPlayController,
        val api: FakeSyncPlayApi,
        val socket: FakeSyncPlaySocket,
        val player: FakePlayerHandle,
        val host: FakeSyncPlayPlaybackHost,
        val status: SyncPlayStatusHolder,
        val connection: MutableStateFlow<ConnectionState>,
        val session: MutableStateFlow<SessionState>,
        val messages: List<SyncPlayMessage>,
        val launchRequests: List<SyncPlayLaunchRequest>,
        /**
         * Every change of "is this session in a group", oldest first.
         *
         * Exactly the flow `PlayerSyncPlayBridge.membership` is built from, and collected here
         * because a rejoin has to make it go `false` and back — that edge is what re-mints the
         * server-visible session of a downloaded file (`SyncPlayLocalSession`). A `StateFlow`
         * conflates, so "the rejoin was too quick for the collector" is a real failure mode and this
         * is where it would show.
         */
        val membershipEdges: List<Boolean>,
    )

    private fun TestScope.fixture(attachHost: Boolean = true): Fixture {
        val clock = VirtualClock(testScheduler, origin)
        val timeSync = timeSyncWithOffset(clock, offsetMillis = 0L)
        val api = FakeSyncPlayApi(clock)
        api.createdGroup = group()
        val socket = FakeSyncPlaySocket()
        val player = FakePlayerHandle()
        val host = FakeSyncPlayPlaybackHost()
        val status = SyncPlayStatusHolder()
        val main = StandardTestDispatcher(testScheduler)

        val connection = MutableStateFlow(ConnectionState.ONLINE)
        val connectionProvider = mockk<ConnectionStateProvider>()
        every { connectionProvider.state } returns connection

        val session = MutableStateFlow<SessionState>(loggedIn())
        val sessionHolder = mockk<SessionStateHolder>()
        every { sessionHolder.state } returns session

        val controller =
            SyncPlayController(
                api = api,
                socket = socket,
                timeSync = timeSync,
                scheduler = SyncPlayCommandScheduler(player, timeSync, clock, backgroundScope, main),
                driftMonitor = SyncPlayDriftMonitor(player, timeSync, main),
                pinger = SyncPlayPinger(api, timeSync),
                statusHolder = status,
                playerHandle = player,
                connectionState = connectionProvider,
                sessionStateHolder = sessionHolder,
                clock = clock,
                scope = backgroundScope,
                mainDispatcher = main,
            )

        val recorded = record(controller)
        player.snapshot = PlaybackSnapshot()
        if (attachHost) controller.attachHost(host)
        runCurrent()

        return Fixture(
            controller,
            api,
            socket,
            player,
            host,
            status,
            connection,
            session,
            recorded.messages,
            recorded.launchRequests,
            recorded.membershipEdges,
        )
    }

    /** Everything the controller publishes, collected into lists the assertions can read. */
    private fun TestScope.record(controller: SyncPlayController): Recorded {
        val recorded = Recorded()
        backgroundScope.launch { controller.messages.collect { recorded.messages += it } }
        backgroundScope.launch { controller.launchRequests.collect { recorded.launchRequests += it } }
        backgroundScope.launch {
            controller.state
                .map { it is SyncPlayState.InGroup }
                .distinctUntilChanged()
                .collect { recorded.membershipEdges += it }
        }
        runCurrent()
        return recorded
    }

    private class Recorded(
        val messages: MutableList<SyncPlayMessage> = mutableListOf(),
        val launchRequests: MutableList<SyncPlayLaunchRequest> = mutableListOf(),
        val membershipEdges: MutableList<Boolean> = mutableListOf(),
    )

    private companion object {
        /** A blip well inside [SyncPlayController.CONNECTIVITY_GRACE_MS] — the device's own two seconds. */
        const val BLIP_MS = 2_000L

        /**
         * How long ago a group published the position it is measured from.
         *
         * Deliberately far larger than [SyncPlayDriftMonitor.MAX_DRIFT_MS]: the whole point of the
         * honest anchor is that this interval belongs in the arithmetic, and a value the drift
         * monitor would have absorbed anyway would prove nothing.
         */
        const val GROUP_HEAD_START_MS = 20_000L
    }

    private fun loggedIn() =
        SessionState.LoggedIn(
            serverId = UUID.fromString("00000000-0000-0000-0000-0000000000e1"),
            userId = UUID.fromString("00000000-0000-0000-0000-0000000000e2"),
            userName = "casey",
            serverName = "home",
            serverVersion = "10.10.0",
        )
}
