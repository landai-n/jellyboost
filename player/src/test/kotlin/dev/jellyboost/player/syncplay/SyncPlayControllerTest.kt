package dev.jellyboost.player.syncplay

import dev.jellyboost.core.network.ConnectionState
import dev.jellyboost.core.network.model.SessionState
import dev.jellyboost.player.model.PlaybackSnapshot
import dev.jellyboost.player.model.millisToTicks
import dev.jellyboost.player.session.PlayerEvent
import dev.jellyboost.player.syncplay.model.SyncPlayCommandType
import dev.jellyboost.player.syncplay.model.SyncPlayGroupEvent
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

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
internal class SyncPlayControllerTest : SyncPlayControllerTestBase() {
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
    fun `the sign-out hook leaves the group on the server while the token still works`() =
        runTest {
            // `SyncPlaySignOutHook` runs this *before* SessionRepository revokes the token
            // (audit NET-03): the server leave has to travel on a credential that still works.
            val fixture = fixture()
            joinWithQueue(fixture)
            fixture.api.clearCalls()

            fixture.controller.leaveBeforeSignOut()
            runCurrent()

            fixture.api.callsOf<SyncPlayCall.LeaveGroup>().size shouldBe 1
            fixture.controller.state.value shouldBe SyncPlayState.Idle
            fixture.status.inGroup.value shouldBe false
        }

    @Test
    fun `the LoggedOut transition tears down locally without chasing a revoked token`() =
        runTest {
            // By the time the state flips to LoggedOut the token is revoked; a server leave from
            // here could only 401 (audit NET-03), so only the local session may be torn down.
            val fixture = fixture()
            joinWithQueue(fixture)
            fixture.api.clearCalls()

            fixture.session.value = SessionState.LoggedOut
            runCurrent()

            fixture.api.callsOf<SyncPlayCall.LeaveGroup>() shouldBe emptyList()
            fixture.controller.state.value shouldBe SyncPlayState.Idle
            fixture.status.inGroup.value shouldBe false
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
}
