# Feature: SyncPlay (group watch) — M11

Watching the same film with other people, in lockstep: play, pause, seek, and the queue itself are
the *group's*, coordinated by the server, and every member's player follows. Full scope per
`docs/notes/syncplay-m11-plan.md` — a dedicated Groups section, in-player group controls, group
queue management (next/previous, reorder, shuffle/repeat), and the one thing no other Jellyfin
client does: **a downloaded item plays from disk while the group streams**, in sync, reported to the
server like any other session.

Movies and episodes only, which is the app's scope everywhere else.

## The shape of a group session

```
SyncPlayGroupsScreen ──join──► SyncPlayController ──REST──► /SyncPlay/*
   (Routes.SyncPlay)                 │  ▲
                                     │  └── SyncPlaySocket  ◄── websocket
                                     │        GroupUpdate / SyncPlayCommand
             ┌───────────────────────┼───────────────────────────┐
             ▼                       ▼                           ▼
      SyncPlayTimeSync      SyncPlayCommandScheduler      SyncPlayDriftMonitor
      (server clock offset) (apply at the group instant)  (1 s tick, > 2 s ⇒ seek)
                                     │
                                     ▼
                               PlayerHandle  (the one shared ExoPlayer)
                                     ▲
                                     │  loadItem / snapshot
                          SyncPlayPlaybackHost = PlayerViewModel
                                     │
                    PlayerSyncPlayBridge ──► group state, messages, transport requests
```

**Nothing the user does in a group moves this player.** Play, pause, seek, skip, queue edits are all
API calls; the player moves only when the server rebroadcasts the resulting `SendCommand` to every
member (key decision 11). That is what makes "in sync" true rather than approximately true.

## Key classes

All in `player/src/main/kotlin/dev/jellyfinnative/player/syncplay/` unless stated.

| Class | Responsibility |
|---|---|
| `SyncPlayController` | The coordinator. `@Singleton` with its own supervisor scope, **not** a ViewModel: membership outlives the player screen and survives backgrounding. Owns the state machine (`Idle / Joining / InGroup`), every user intent, the join handshake, connection-loss teardown and `launchRequests`. |
| `SyncPlayState.kt` | `SyncPlayState` (`InGroup(group, queue, groupState, phase)`), `SyncPlayPhase` (`Waiting / Buffering / Playing(anchor) / Paused`), `SyncPlayMessage`, `SyncPlayLaunchRequest`. The phase is *this member's* and the `groupState` is the group's own — the difference is what the WAITING overlay shows, and what the [pause net](#when-the-group-says-stop-and-nothing-comes) is measured against. |
| `api/SyncPlayApi` + `api/SdkSyncPlayApi` | Facade over `apiClient.syncPlayApi` and `apiClient.timeSyncApi`, on `@IoDispatcher`. **The single SDK-time boundary**: the SDK's `DateTimeSerializer` treats `LocalDateTime` as local wall-clock, so every `Instant ↔ LocalDateTime` conversion happens here, through `:data`'s `SdkDateTime`. |
| `socket/SyncPlaySocket` + `socket/SdkSyncPlaySocket` | `apiClient.webSocket.subscribe<SyncPlayGroupUpdateMessage>()` / `<SyncPlayCommandMessage>()` mapped to domain events. Collected only while in a group — collecting is what opens the socket (the SDK has no `connect()`), and the SDK owns reconnect and keep-alive. |
| `SyncPlayDtoMapping.kt`, `SyncPlayEnumMapping.kt` | SDK DTO → `SyncPlayGroupEvent` / `SyncPlayCommand`, and the enum round-trips. Pure functions, densely tested. |
| `time/SyncPlayTimeSync` | NTP-style rolling estimator: a window of 8 samples, RTT outliers (> max(1 s, 3 × median)) dropped, `offset = avg((t1−t0) + (t2−t3)) / 2`. Injects the app's `java.time.Clock`. |
| `time/SyncPlayPinger` | While in a group: 3 fast samples 1 s apart, then one every 5 s → estimator → `syncPlayPing(rtt/2)`. |
| `SyncPlayCommandScheduler` | One pending-command slot; delays until the command's instant *in local time* and applies it on Main against `PlayerHandle`. Past-due unpause seeks to `position + (now − when)` first. A new command replaces the pending one; an **identical** one (same type, instant, position, slot) and one emitted **before** the last taken on are both dropped. |
| `SyncPlayDriftMonitor` | 1 s tick while playing; a gap of more than 2 s between where the group's anchor says this player should be and where it is ⇒ corrective seek. The safety net under the scheduler. |
| `SyncPlayStatusHolder` | Two facts anyone may read — `inGroup` and the minted play session id — so `PlaybackReporter` can consult SyncPlay without a DI cycle. |
| `presence/SyncPlayPresenceCoordinator` | Started from `JellyfinNativeApplication`. Holds `SyncPlayPresenceService` while the group needs it, and hands `ProcessLifecycleOwner`'s `ON_START` to `SyncPlayController.onAppForegrounded()`. See [Surviving the background](#surviving-the-background). |
| `presence/SyncPlayPresenceService` + `SyncPlayPresenceReceiver` + `syncPlayPresenceDemanded` | The `specialUse` foreground service a group without playback runs behind, its **Leave** action, and the pure start/stop rule. |
| `SyncPlayLocalSession` | The server-visible session of a **downloaded** item watched with a group: mints its play session id, and closes it when the group ends. See "Local files in a group". |
| `SyncPlayPlaybackHost` | What the controller needs of a player: `loadItem(itemId, startTicks)` (opens paused) and `snapshot()`. Implemented by `PlayerViewModel`. |
| `ui/PlayerSyncPlayBridge` (in `player/ui/`) | The player's half: group state → `PlayerSyncPlayState`, transport → requests, membership changes → reporting reconciliation. Keeps the group out of `PlayerViewModel`'s branches. |
| `ControllerSyncPlaySession` | Binds `:core:common`'s `SyncPlaySession` to the controller, so `:feature:detail` can play for the group and queue for it without depending on `:player`. |
| `ui/SyncPlayGroupsScreen` + `ui/SyncPlayGroupsViewModel` | The dedicated section (`Routes.SyncPlay`): list groups on a 10 s poll, create, join, leave; a 403 degrades to "SyncPlay is disabled for your account". |
| `ui/SyncPlayGroupSheet`, `ui/SyncPlayQueueSheet` + `ui/SyncPlayQueueViewModel` | In-player panels: participants, shuffle/repeat, leave; and the group queue with reorder/remove/play-from-here. |
| `:app` `SyncPlayLaunchViewModel`, `SyncPlayBadgeViewModel` | The NavHost's collector for `launchRequests` (the group moved on and no player is open ⇒ navigate to the player), and the badge on `AppTopBar`'s Groups action. |

## Join flow

1. `joinGroup` starts collecting the websocket **first** — a group joined before the socket is up
   would never hear its own `GroupJoined` / `PlayQueueUpdate` — then takes **one clock sample**
   (`SyncPlayPinger.sampleClock`, a plain `GET /GetUtcTime`), then `POST /SyncPlay/Join`.
   `SyncPlayTimeSync.offset` is `ZERO` until something measures it and the pinger only starts once
   the group has been entered, so without that sample the first `SendCommand` — which can arrive the
   instant the join returns — would be converted to local time against an assumed offset. A failed
   sample is logged and the join carries on.
2. `GroupJoined` + the first `PlayQueueUpdate` arrive; the controller resolves the playing entry.
3. If a player is attached, `SyncPlayPlaybackHost.loadItem` opens the item **paused** — or, when
   the host is already on that very item, `adoptOpenItem` keeps it and runs the handshake around it
   rather than reloading. If no player is attached, a `SyncPlayLaunchRequest` is emitted and `:app`
   opens one; that session opens **paused** too (`PlayerViewModel`'s init reads
   `sessionStore.playWhenReady && !syncPlay.isInGroup`), because the group decides when playback
   starts.
4. While preparing, `syncPlayBuffering`; on `PlayerEvent.Ready`, `syncPlayReady`. The **buffering**
   report carries the player's real `isPlaying` (the host's snapshot where one is attached, the
   shared player otherwise). The **ready** report is different: the controller *parks* the player
   first (`parkForReady` — a pause on the main dispatcher, a no-op if it is already stopped) and
   then reports `isPlaying = false`. That is the handshake as designed — a member answering the
   group's wait is stopped, and the server's unpause is what starts it — and the server enforces it:
   `WaitingGroupState.cs`:484-498 answers a `ready` whose `IsPlaying` is true, from a session more
   than `2 × highestPing` behind, with `AllExceptCurrentSession` — everyone *else* is told to resume
   and the reporter is sent nothing, which strands it under the WAITING overlay
   (DECISIONS.md 2026-07-31). jellyfin-web parks before reporting ready for the same reason.
5. The server flips the group out of WAITING and broadcasts an unpause with an instant; the
   scheduler converts it to local time and applies it. Everyone starts on the same frame.

Re-negotiations (track change, quality change, decoder fallback) re-enter the same handshake
automatically, because the bridge reports buffering on every reopen and the controller watches
`PlayerHandle.events`.

### `ready` is reported only when one is owed

Readiness is not news on its own. The server answers a `ready` from a group that is *not* waiting by
re-sending that group's current state command to the reporting session alone — "Client got lost,
sending current state", in `PausedGroupState` and `PlayingGroupState`. Applying that command seeks
the player, ExoPlayer emits another `STATE_READY`, and a client that reported every readiness would
report again: a closed loop, measured on device at ~13 requests a second until the group unpaused
(DECISIONS.md 2026-07-31).

So the controller keeps `readyOwedFor` — the slot the group is actually waiting on. It is set at
exactly the moments the server calls `SetAllBuffering`/`SetBuffering(session, true)`:

| moment | fallback if the player never re-buffers |
|---|---|
| an item is **loaded** for a queue slot | none — the player really is being rebuilt, so its own readiness is the answer |
| an item already open is **adopted** for a queue slot | 1.5 s — it is already prepared and may have passed its readiness before the host was attached (DECISIONS.md 2026-07-31) |
| the host reports a re-negotiation (`onHostBuffering`) | none |
| a group `Seek` is applied | 1.5 s |
| a queue update whose reason is `NewPlaylist` / `SetCurrentItem` / `NextItem` / `PreviousItem` | reported immediately; the player is already prepared |
| connectivity returned inside the grace window | 1.5 s |

A `PlayerEvent.Ready` with nothing owed is silence.

### When the group says go and nothing comes

After every `ready`, and whenever the group reports itself `Playing`, a 3 s timer is armed
(`SELF_SYNC_TIMEOUT_MS`). If it fires while the group is playing, this member is not, and a player is
attached, the controller seeks to the group's inferred position and starts playback itself, then
hands over to the drift monitor. Any applied command disarms it, and the path reports nothing.

It exists for one observed failure: after an automatic queue advance the handshake completed, the
group's state update said `Playing`, and no unpause ever arrived — leaving the member at 0:00 under
the WAITING overlay, unrecoverable by a group `Unpause` because the group already *was* playing. It
is a deliberate, bounded exception to key decision 11.

### When the group says stop and nothing comes

The mirror image, and the worse half: a `Pause` this client never receives leaves the member playing
on alone, while the phase goes quietly to `Paused` and takes the drift monitor — which runs in
`Playing` only — down with it, so nothing measures anything ever again
(`syncplay-bugreport.md`: "Pause from browser: app continues playing"). So a group state of `Paused`
arms a second 3 s timer (`PAUSE_NET_TIMEOUT_MS`), and when it fires the controller pauses this
member itself.

| | self-sync net (`Playing`) | pause net (`Paused`) |
|---|---|---|
| armed by | every `ready`, and `StateChanged(Playing)` | `StateChanged(Paused)`, and a group already paused at join |
| disarmed by | any applied command, `StateChanged(Paused/Waiting/Idle)`, teardown | any applied command, `StateChanged(Playing)`, teardown |
| needs a host | **yes** — starting a detached player would be sound from nowhere | **no** — pausing a detached player the group has paused is right |
| does | seek to the group's inferred position, `play`, then hand over to the drift monitor | `pause`, and only if the player is actually running |
| reports | nothing | nothing |

Two supporting rules come with it. The group's own state now lives in
`SyncPlayState.InGroup.groupState`, distinct from the member `phase` precisely because the phase is
what a lost command falsifies. And the WAITING hold asks the *player* whether it is running rather
than reading the phase, since a member whose phase says `Paused` over a player that is still playing
is exactly the case both nets exist for.

Leaving the player screen does **not** leave the group: the controller sends
`syncPlaySetIgnoreWait(true)` on host detach (jellyfin-web's own mechanism) so a member without a
player never gates everyone else, and a later `PlayQueueUpdate` re-launches the player.

It does not take the *player* away either. `PlaybackService` keeps the shared ExoPlayer alive and
playing across the detach, so detaching deliberately leaves the command scheduler running and leaves
the member phase alone (DECISIONS.md 2026-07-31): the group's commands go on landing on a
backgrounded player, and the drift monitor goes on guarding a phase that is still `Playing`.
Cancelling the scheduler and forcing `Paused` there — which is what it used to do — was the other
half of the free-running background member. Only a full `teardown` / `standDown` cancels the
scheduler, because only those end the timeline it is tracking. What detach *does* reset is what
belongs to the screen: the loaded slot (so a re-attach may adopt an item the host already holds) and
the skipped-slot memory.

The self-sync net's "group's inferred position" is `startPositionTicks` measured from the queue's own
`lastUpdate`, **not** from now: that position was true when the queue was published, and pairing it
with the current instant is what left a browser-initiated resume seconds short and then had the drift
monitor defend the short timeline (`syncplay-bugreport.md`).

## Losing the connection

A confirmed loss **pauses the player at once**, and — once the automatic rejoin below has failed —
**leaves the group and says so** ("Left SyncPlay — connection lost"). Nothing resumes automatically: playing on would mean drifting from the group invisibly, so
the state change is made honest and the user resumes with one tap, solo (from disk if the item is
downloaded). (Key decision 10, as amended on 2026-07-30 from the original "keep playing solo", and
again on 2026-07-31 by [taking the group back](#taking-the-group-back) first.)

Three signals confirm one, through a single `confirmLoss()`:

| signal | why it is confirmation | when |
|---|---|---|
| the socket collection ending | the SDK reconnects on its own, so a stream that *finishes* is it giving up | immediately |
| `PING_FAILURE_STREAK` (3) failed ping cycles | the REST API has stopped answering whatever the OS believes — the case where the platform cuts a backgrounded app's network and the server disposes the group | ≈ 15 s |
| connectivity offline for `CONNECTIVITY_GRACE_MS` (5 s) | the radio is genuinely gone rather than switching | 5 s |

A momentary socket flap the SDK reconnects through is not a loss and does nothing. Neither is a
short connectivity blip: going offline **freezes** playback (paused, group kept) and opens the grace
window, and connectivity returning inside it re-enters the buffering/ready handshake so the server
re-syncs this member. Freezing rather than playing on is deliberate — see DECISIONS.md 2026-07-31.

Signing out tears the membership down the same way.

## Taking the group back

Surviving a blip locally is not the same thing as keeping the membership, because the **server** does
not survive it. A dropped websocket raises `SessionEnded`, `SyncPlayManager.OnSessionEnded` calls
`LeaveGroup` for this session, and the next request arrives on a brand-new session that belongs to no
group — answered with a `SyncPlayNotInGroupUpdate` on the socket. (The SyncPlay REST endpoints return
`204` either way, so the socket update is the answer; the ping loop's five-second cadence finds it
even when nothing else is happening.) Nobody asked to leave, so the client asks for the group back
(DECISIONS.md 2026-07-31).

| | |
|---|---|
| **remembered** | the group, from the moment it is entered, until a *deliberate* exit forgets it |
| **deliberate exits** | `leaveGroup()`, sign-out, `LibraryAccessDenied`, `GroupGone`, and a `NotInGroup`/`Left` over a connection that was never in trouble — none of these ever auto-rejoin |
| **"in trouble"** | connectivity going offline, a failed ping cycle, or the socket leaving `Connected`, within `REJOIN_TROUBLE_WINDOW_MS` (30 s) — the window is there because the removal is found by the *next* request, not at the moment of the trouble |
| **a confirmed loss** | goes the same way: `confirmLoss()` hands over to the rejoin rather than ending the group. On the device this is the *usual* path — a three-second Wi-Fi drop costs ~5 s of reported-offline once association, DHCP and the reachability probe are counted, so the grace window expires before anything discovers the removal |
| **the attempt** | list the groups; if ours is still there, run the ordinary join flow — socket re-collected, join REST, handshake with a `ready`, self-sync net behind it |
| **bounds** | `REJOIN_MAX_ATTEMPTS` (3), `REJOIN_RETRY_DELAY_MS` (2 s) apart, covering a server still reaping the old session; each attempt gated on a bounded `awaitOnline()` so none is spent on a radio that is still down |
| **group not listed** | it dissolved (we were its last member) → teardown, "The SyncPlay group has ended" |
| **attempts exhausted** | teardown, "Left SyncPlay — connection lost" — and no background loop afterwards; once out, we stay out until the user comes back to the app (see [Surviving the background](#surviving-the-background)) |
| **the player** | paused throughout, and never started by the rejoin: the group's answer to this member's `ready` is what puts it back in step |
| **on success** | "Rejoined SyncPlay group" |

`SyncPlayState.Rejoining` is deliberately **not** a kind of `InGroup` — the server really does not
have this session in the group while it lasts. Membership therefore falls and rises, which is what
re-mints the server-visible session of a downloaded file (below), and what the player's control
surface reads to stop offering transport requests nobody would answer. Two smaller consequences: the
sign-out watcher lives on the controller's own scope rather than a group session (a rejoin cancels
the session scope, and a sign-out in the middle of one has to be able to abort it), and a rejoin that
lands with no player attached re-sends `setIgnoreWait(true)`, because the new server session knows
nothing of the old one's.

## Surviving the background

The user's own case is this app in a group on one half of the tablet and jellyfin-web driving it on
the other — which means the app is backgrounded *exactly* when the group matters. On the test tablet
(test tablet, the OEM ROM, Android 16) a backgrounded app with no foreground service loses its
network within about forty seconds: the ping loop starts failing, three failures confirm a loss, the
rejoin cannot reach the server either, and if the app was the group's only member the server disposes
the group. Two things answer that (DECISIONS.md 2026-07-31).

### 1. A foreground service while in a group without playback

`syncplay/presence/` holds the whole of it, and it is deliberately tiny.

| | |
|---|---|
| **when** | `SyncPlayPresenceCoordinator` combines `SyncPlayController.state` with `PlaybackServiceState.running`: `syncPlayPresenceDemanded(state, playbackRunning)` is `state !is Idle && !playbackRunning`. `Joining` and `Rejoining` are included — being killed mid-handshake is no better than being killed in a group |
| **what** | `SyncPlayPresenceService`, an ongoing "In a SyncPlay group / Waiting for the group" notification with a **Leave** action (`SyncPlayPresenceReceiver` → `controller.leaveGroup()`); the body opens the app. It does no work at all: being a foreground service *is* the work, because that is the only thing Android offers that keeps a backgrounded process's network alive |
| **released** | the moment playback takes over (`PlaybackService` is already a foreground service and already holds the network), on `Idle`, and on sign-out — a settled demand only, debounced by `DEMAND_SETTLE_MS` (400 ms) |
| **type** | `specialUse`, not `mediaPlayback` (nothing is playing), `connectedDevice` (no device, and the type demands permissions this app has no business holding) or `dataSync` (deprecated in Android 15, capped at six hours a day on targetSdk 35+). The manifest carries the `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` declaration API 34+ asks for. The network exemption is identical whichever type is declared |
| **promotion** | in `onCreate`, not `onStartCommand`: `startForegroundService` opens a deadline that a `stopService` arriving first does *not* close, and the platform kills the process for it — which it did, on the device, when a foreground re-check found its group dissolved 250 ms after asking for it |
| **best effort** | a foreground start from the background throws from API 31 on, so the start is `runCatching` like `ExoPlayerHandle`'s; the next foreground tries again |

`PlaybackServiceState` (in `player/session/`) is the seam: a `@Singleton` `StateFlow<Boolean>`
written by `PlaybackService`'s `onCreate`/`onDestroy`, because the platform offers no way to ask
"is my service running" that does not involve `ActivityManager` and a class-name comparison.

### 2. A membership re-check when the app comes back

the OEM ROM may kill the service anyway, and process death loses the singleton outright. So returning to the
foreground — `ProcessLifecycleOwner`'s `ON_START`, observed by the same coordinator — is treated as
the one moment a lost membership can actually be taken back, and `SyncPlayController.onAppForegrounded()`
does one of two things:

| state | what happens |
|---|---|
| `InGroup` | `SyncPlayPinger.sampleNow()` — an immediate ping cycle, so a connection that died off screen starts its three-failure streak now instead of up to five seconds later |
| `Idle` with a fresh `lostMembership` | the ordinary rejoin attempts, **once**, and **silently** |

The memory is the piece that had to change: `lostMembership` (group + device-clock instant) is the
one thing `teardown` deliberately does not clear, so the identity of an involuntarily lost group
outlives the fall to `Idle`. It is bounded by `FOREGROUND_REJOIN_WINDOW_MS` (10 minutes) from the
loss — the instant is never refreshed by a failed retry, so repeated foregrounding cannot walk the
deadline forward — and cleared by entering any group, by `leaveGroup()`, by sign-out, by
`GroupGone`/`RemovedFromGroup`/`LibraryAccessDenied`, and by the window expiring. The device clock is
used rather than `timeSync.serverNow()` because `teardown` resets the offset between the write and
the read.

"Silently" means `runRejoinAttempts(quiet = true)`: no "connection lost", no "the group has ended" —
a re-check that fails or finds the group gone must not put a message on screen every time the app is
opened. A success still says "Rejoined SyncPlay group". A dissolved group clears the memory; a failed
attempt keeps it, so the next foreground may try again inside the window. There is still no
background retry loop.

**Known limits.** Process death is out of scope: the singleton and its memory go with it, and the
service is `START_NOT_STICKY` so the system does not resurrect a notification with nothing behind it.
An OEM that kills the service anyway costs the group until the user comes back, which is what the
re-check is for.

## Local files in a group, and the reporting exception

Since M8 a downloaded item plays entirely from disk and tells the server **nothing** — it has no
play session by construction, and reporting offline would burn a connect timeout every five seconds.
In a group that answer is wrong: the others are watching *with* this device, and a member missing
from the dashboard is a member nobody can see stall.

So `PlaybackReporter` reports a `LocalPlaybackMediaSource` when — and only when — the device is
**online** *and* **in a group** (`SyncPlayStatusHolder.inGroup`). Concretely:

| source | online | in a group | server report | local position write |
|---|---|---|---|---|
| stream | yes | either | yes (its own play session) | yes |
| stream | no | either | no | yes |
| download | yes | **yes** | **yes** (minted play session) | yes |
| download | yes | no | no | yes |
| download | no | either | no | yes |

- **Minting.** `PlaybackInfoResolver.mintPlaySessionId(itemId, mediaSourceId)` does one
  `POST /Items/{id}/PlaybackInfo` with **no device profile** and `autoOpenLiveStream = false`, and
  reads only `playSessionId`. No stream URL is ever fetched, so no encoder can start. A failed mint
  is not an error: reporting continues with **no** session id, because the server keys the session
  on the authenticated device and an unkeyed member beats an invisible one.
- **When.** `SyncPlayLocalSession.reconcile` runs when a session opens (before the start report, so
  the id is in it) and on every membership change — which is why joining a group ten minutes into a
  downloaded film mints then, with no path of its own.
- **Leaving mid-film.** One final stop report closes the server's session, then silence; playback
  continues solo off the same file and the local resume position keeps being written.
  (`PlaybackReporter.reportGroupExitStop`, DECISIONS.md 2026-07-30.)
- **Never `stopTranscoding`.** A file on disk is direct play; there is no ffmpeg process anywhere,
  in a group or out of one.

Resolution itself needs nothing new: the controller issues an ordinary `PlaybackResolveRequest`, and
`PlaybackSourceResolver` already prefers a completed download over a stream — per queue item.

## Starting something with the group

**In a group, the browse surfaces do everything as a group** (DECISIONS.md 2026-07-31, user
decision). On the detail page that means:

| affordance | in a group |
|---|---|
| Play / Resume (header) and each episode row's play button | `ItemDetailViewModel.onPlay` sends `SetNewQueue` with the resume position and **does not navigate**. The player opens a moment later, when the server's `PlayQueueUpdate` comes back as a `SyncPlayLaunchRequest`. The header button reads "Play for &lt;group&gt;" / "Resume for &lt;group&gt;", so the changed meaning is on screen |
| *Play next* / *Add to queue* | unchanged — `syncPlayQueue` with `QueueNext` / `Queue`. They are additive and have no solo counterpart |
| anything a group cannot play (`ItemType.isPlayable` is false) | plays here, solo, exactly as it does outside a group |

There is no longer a separate *Play for group* button. Two buttons that both said "play" and meant
different things is what left a member watching alone under a "Waiting for group" overlay while the
group never heard about it (`syncplay-bugreport.md`); the way to watch something on your own is to
leave the group, which is one tap away in the player and on the Groups screen.

An episode is always sent as **the run from it to the end of its series**, because jellyfin-web
expands a single-episode group queue locally and then indexes the server's playlist by the expanded
length — a one-entry queue makes it read past the end, throw, and drop the update, so nobody's
playback starts (DECISIONS.md 2026-07-31).

## What changes in the player while in a group

- Play / pause / seek / skip / skip-intro become group requests (`PlayerSyncPlayBridge`).
- The **speed picker is disabled** and **segment auto-skip is suppressed** — both would silently
  desynchronise this member.
- A WAITING overlay is shown while the group waits on someone (including this device buffering), and
  the group entering WAITING from playing **pauses** this player — an overlay over playback that
  carries on is a member drifting ahead of a stalled group, which is what it looked like on device.
- The group icon in the controls opens `SyncPlayGroupSheet`; the queue sheet is reachable from it.
- An item ending asks the server for the next entry rather than popping the screen, so the player
  the group is about to fill is not closed a second before it is needed.

## Server endpoints used

| Call (SDK) | When |
|---|---|
| `syncPlayApi.syncPlayGetGroups` | Groups screen, every 10 s while it is open. |
| `syncPlayApi.syncPlayCreateGroup` / `syncPlayJoinGroup` / `syncPlayLeaveGroup` | Create, join, leave. |
| `syncPlayApi.syncPlayBuffering` / `syncPlayReady` | The join (and every re-negotiation) handshake. |
| `syncPlayApi.syncPlayPing` | Every 5 s while in a group (3 fast samples first). |
| `syncPlayApi.syncPlaySetIgnoreWait` | Player detached / re-attached. |
| `syncPlayApi.syncPlayPause` / `syncPlayUnpause` / `syncPlaySeek` / `syncPlayStop` | User transport, in a group. |
| `syncPlayApi.syncPlayNextItem` / `syncPlayPreviousItem` / `syncPlaySetPlaylistItem` | Queue navigation. |
| `syncPlayApi.syncPlaySetNewQueue` / `syncPlayQueue` / `syncPlayMovePlaylistItem` / `syncPlayRemoveFromPlaylist` | Queue administration (a Play tap in a group, "Play next", "Add to queue", reorder, remove). |
| `syncPlayApi.syncPlaySetShuffleMode` / `syncPlaySetRepeatMode` | The group's shuffle/repeat. |
| `timeSyncApi.getUtcTime` | Every time-sync sample. |
| websocket `SyncPlayGroupUpdateMessage`, `SyncPlayCommandMessage` | Group state, queue, and every command, while in a group. |
| `POST /Items/{id}/PlaybackInfo` | Once per in-group **downloaded** item, to mint a play session id. |
| `POST /Sessions/Playing{,/Progress,/Stopped}` | As for any session — including a downloaded item in a group. |

## Offline behaviour

**SyncPlay is online-only, and says so.** The Groups screen cannot list or create anything without a
server; a connection loss mid-group pauses playback and leaves the group (above). Nothing about a
group is *persisted*: there is no offline group state, no queued intents, and no rejoin on reconnect —
by design, since a group's whole content is "where everyone is right now". The one exception is
in-memory and bounded: a membership lost involuntarily is remembered for ten minutes so that
returning to the app can ask for it back ([Surviving the background](#surviving-the-background)); it
does not survive process death and is never written to disk.

What *does* survive offline is the item: a downloaded film keeps playing from disk after the group
is gone, and its position keeps being written locally with `toBeSynced = true`, exactly as M8's
offline sessions do.

## Test coverage

`player/src/test/kotlin/dev/jellyfinnative/player/syncplay/`, plus the player and reporter suites:

| File | What it pins |
|---|---|
| `SyncPlayControllerTest` | Join handshake, WAITING (overlay *and* pause), intents → API calls with **zero** local playback calls, `NotInGroup` / `GroupGone` / `AccessDenied` → Idle, connection loss → paused + message, no pause on a transient flap, connectivity blip vs. sustained offline, ping-failure streak, ready owed vs. silence, the self-sync net and the pause net (a paused group with no command pauses this member, the group's own pause standing the net down, no second pause on a player already stopped, a paused group at join, a WAITING hold over a phase that lies, and a self-sync cancelled when the group stops playing), sign-out teardown, ignoreWait on detach, a detached player the group still reaches (phase stays `Playing`, a later command still applies), a self-sync measured from the queue's `lastUpdate` rather than from now, buffering reports carrying the player's real `isPlaying` while a `ready` parks a running player and reports `false` (and touches an already-stopped one not at all), the clock sampled before the join call (and a failed sample not blocking it), queue reconciliation, an adopted item that reports buffering and owes its `ready` (answered by the player, or by the 1.5 s fallback when it never re-buffers), unopenable slots skipped once. Auto-rejoin: a blip-then-`NotInGroup` and a `403` both taken back (join re-run, handshake re-entered, player never started, one "Rejoined" message), membership falling and rising for the local-session re-mint, a dissolved group asked after exactly once, exhaustion at exactly 3 attempts 2 s apart then one message and silence, aborts mid-rejoin from leave and sign-out, the four exits that must never rejoin, and a confirmed loss the connection comes back from (no call spent while offline, rejoined when it returns). Foreground re-check: a membership the background cost us asked for again and got back, a group gone in the meantime forgotten without a word, a failed re-check that is silent, does not loop and is retried on the *next* foreground, a loss older than the 10-minute window dropped rather than acted on, a deliberate leave and a sign-out never taken back, and an immediate ping while still in a group. |
| `SyncPlayCommandSchedulerTest` | Future / past-due / replacement commands, seek epsilon, applied-once (identical re-send, repeated past-due, stale `emittedAt`). |
| `SyncPlayDriftMonitorTest` | Threshold either side of 2 s, no correction while paused. |
| `time/SyncPlayTimeSyncTest` | Server ahead/behind, asymmetric RTT, outlier rejection, rolling window. |
| `time/SyncPlayPingerTest` | Fast-then-steady cadence; `sampleNow()` cutting the wait, and a poke taken before the loop starts being dropped rather than skewing the next group's first cadence. |
| `presence/SyncPlayGroupPresenceTest` | The service's whole start/stop rule: in a group with nothing playing ⇒ demanded, playback ⇒ released, `Idle` (leave, sign-out) ⇒ released, `Joining` and `Rejoining` ⇒ demanded. |
| `SyncPlayDtoMappingTest` | Every `GroupUpdate` variant, and the `Instant ↔ LocalDateTime` round-trip under an explicit non-UTC zone (the M4 two-hour-bug class). |
| `api/SdkSyncPlayApiTest`, `socket/SdkSyncPlaySocketTest` | The facade's DTOs and the socket's mapping. |
| `SyncPlayLocalSessionTest` | Mint once per in-group local item, mint on join-mid-playback, failure ⇒ null, closing stop on leave (once), no closing stop when the *item* changed, forgetting on screen close. |
| `ControllerSyncPlaySessionTest` | The `:core:common` contract's mapping. |
| `feature/detail/.../ItemDetailGroupActionsTest` | The browse surface: in a group a Play tap is `playForGroup` with the series tail and the resume position and emits **no** navigation, an episode row's play button goes the same way, solo plays still navigate, something a group cannot play still opens locally, and *Play next* / *Add to queue* reach the two queue modes. |
| `ui/SyncPlayGroupsViewModelTest`, `ui/SyncPlayQueueViewModelTest` | Polling, join/create/leave, 403 copy; queue titles, reorder/remove. |
| `player/.../report/PlaybackReporterSyncPlayTest` | The reporting exception in all three terms, plus "never `stopTranscoding` for a local source" and the group-exit stop. |
| `player/.../ui/PlayerSyncPlayTest`, `PlayerSyncPlayReportingTest` | In-group transport never touches `PlayerHandle`; speed and auto-skip suppressed; a session opened while in a group opens **paused** (and solo still opens playing); reconcile-before-start ordering and the three moments it runs. |

`PlaybackReporterTest` and the solo `PlayerViewModelTest` / `PlayerTrackPickerTest` are unchanged and
green — they are the regression tests that group support altered nothing about watching alone.

**Known gaps.** Device-level lockstep skew, WAITING behaviour with a second real client, and a
minified-build websocket group-join walk are verification items rather than unit tests; the R8
serializer survival of every `SyncPlay*` message class (and the `Outbound`/`InboundWebSocketMessage`
hierarchy with its `$$serializer`s and `@JsonClassDiscriminator` impls) was verified against
`app/build/outputs/mapping/release/mapping.txt` — all kept by the existing
`-keep class org.jellyfin.sdk.model.** { *; }`, so **no new keep rules were needed**.
