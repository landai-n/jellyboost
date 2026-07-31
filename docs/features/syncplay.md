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
| `SyncPlayState.kt` | `SyncPlayState` (`InGroup(group, queue, phase)`), `SyncPlayPhase` (`Waiting / Buffering / Playing(anchor) / Paused`), `SyncPlayMessage`, `SyncPlayLaunchRequest`. The phase is *this member's*, deliberately not the group's `SyncPlayGroupState` — the difference is what the WAITING overlay shows. |
| `api/SyncPlayApi` + `api/SdkSyncPlayApi` | Facade over `apiClient.syncPlayApi` and `apiClient.timeSyncApi`, on `@IoDispatcher`. **The single SDK-time boundary**: the SDK's `DateTimeSerializer` treats `LocalDateTime` as local wall-clock, so every `Instant ↔ LocalDateTime` conversion happens here, through `:data`'s `SdkDateTime`. |
| `socket/SyncPlaySocket` + `socket/SdkSyncPlaySocket` | `apiClient.webSocket.subscribe<SyncPlayGroupUpdateMessage>()` / `<SyncPlayCommandMessage>()` mapped to domain events. Collected only while in a group — collecting is what opens the socket (the SDK has no `connect()`), and the SDK owns reconnect and keep-alive. |
| `SyncPlayDtoMapping.kt`, `SyncPlayEnumMapping.kt` | SDK DTO → `SyncPlayGroupEvent` / `SyncPlayCommand`, and the enum round-trips. Pure functions, densely tested. |
| `time/SyncPlayTimeSync` | NTP-style rolling estimator: a window of 8 samples, RTT outliers (> max(1 s, 3 × median)) dropped, `offset = avg((t1−t0) + (t2−t3)) / 2`. Injects the app's `java.time.Clock`. |
| `time/SyncPlayPinger` | While in a group: 3 fast samples 1 s apart, then one every 5 s → estimator → `syncPlayPing(rtt/2)`. |
| `SyncPlayCommandScheduler` | One pending-command slot; delays until the command's instant *in local time* and applies it on Main against `PlayerHandle`. Past-due unpause seeks to `position + (now − when)` first. A new command replaces the pending one; an **identical** one (same type, instant, position, slot) and one emitted **before** the last taken on are both dropped. |
| `SyncPlayDriftMonitor` | 1 s tick while playing; a gap of more than 2 s between where the group's anchor says this player should be and where it is ⇒ corrective seek. The safety net under the scheduler. |
| `SyncPlayStatusHolder` | Two facts anyone may read — `inGroup` and the minted play session id — so `PlaybackReporter` can consult SyncPlay without a DI cycle. |
| `SyncPlayLocalSession` | The server-visible session of a **downloaded** item watched with a group: mints its play session id, and closes it when the group ends. See "Local files in a group". |
| `SyncPlayPlaybackHost` | What the controller needs of a player: `loadItem(itemId, startTicks)` (opens paused) and `snapshot()`. Implemented by `PlayerViewModel`. |
| `ui/PlayerSyncPlayBridge` (in `player/ui/`) | The player's half: group state → `PlayerSyncPlayState`, transport → requests, membership changes → reporting reconciliation. Keeps the group out of `PlayerViewModel`'s branches. |
| `ControllerSyncPlaySession` | Binds `:core:common`'s `SyncPlaySession` to the controller, so `:feature:detail` can offer "Play for group" / "Add to queue" without depending on `:player`. |
| `ui/SyncPlayGroupsScreen` + `ui/SyncPlayGroupsViewModel` | The dedicated section (`Routes.SyncPlay`): list groups on a 10 s poll, create, join, leave; a 403 degrades to "SyncPlay is disabled for your account". |
| `ui/SyncPlayGroupSheet`, `ui/SyncPlayQueueSheet` + `ui/SyncPlayQueueViewModel` | In-player panels: participants, shuffle/repeat, leave; and the group queue with reorder/remove/play-from-here. |
| `:app` `SyncPlayLaunchViewModel`, `SyncPlayBadgeViewModel` | The NavHost's collector for `launchRequests` (the group moved on and no player is open ⇒ navigate to the player), and the badge on `AppTopBar`'s Groups action. |

## Join flow

1. `joinGroup` starts collecting the websocket **first** — a group joined before the socket is up
   would never hear its own `GroupJoined` / `PlayQueueUpdate` — then `POST /SyncPlay/Join`.
2. `GroupJoined` + the first `PlayQueueUpdate` arrive; the controller resolves the playing entry.
3. If a player is attached, `SyncPlayPlaybackHost.loadItem` opens the item **paused**; if not, a
   `SyncPlayLaunchRequest` is emitted and `:app` opens one.
4. While preparing, `syncPlayBuffering`; on `PlayerEvent.Ready`, `syncPlayReady`.
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
| an item is loaded or adopted for a queue slot | none — the player's own readiness is the answer |
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

Leaving the player screen does **not** leave the group: the controller sends
`syncPlaySetIgnoreWait(true)` on host detach (jellyfin-web's own mechanism) so a member without a
player never gates everyone else, and a later `PlayQueueUpdate` re-launches the player.

## Losing the connection

A confirmed loss **pauses the player, leaves the group, and says so** ("Left SyncPlay — connection
lost"). Nothing resumes automatically: playing on would mean drifting from the group invisibly, so
the state change is made honest and the user resumes with one tap, solo (from disk if the item is
downloaded). Rejoining is manual, through the Groups screen. (Key decision 10, as amended on
2026-07-30 from the original "keep playing solo".)

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
| `syncPlayApi.syncPlaySetNewQueue` / `syncPlayQueue` / `syncPlayMovePlaylistItem` / `syncPlayRemoveFromPlaylist` | Queue administration ("Play for group", "Add to queue", reorder, remove). |
| `syncPlayApi.syncPlaySetShuffleMode` / `syncPlaySetRepeatMode` | The group's shuffle/repeat. |
| `timeSyncApi.getUtcTime` | Every time-sync sample. |
| websocket `SyncPlayGroupUpdateMessage`, `SyncPlayCommandMessage` | Group state, queue, and every command, while in a group. |
| `POST /Items/{id}/PlaybackInfo` | Once per in-group **downloaded** item, to mint a play session id. |
| `POST /Sessions/Playing{,/Progress,/Stopped}` | As for any session — including a downloaded item in a group. |

## Offline behaviour

**SyncPlay is online-only, and says so.** The Groups screen cannot list or create anything without a
server; a connection loss mid-group pauses playback and leaves the group (above). Nothing about a
group is persisted: there is no offline group state, no queued intents, and no rejoin on reconnect —
by design, since a group's whole content is "where everyone is right now".

What *does* survive offline is the item: a downloaded film keeps playing from disk after the group
is gone, and its position keeps being written locally with `toBeSynced = true`, exactly as M8's
offline sessions do.

## Test coverage

`player/src/test/kotlin/dev/jellyfinnative/player/syncplay/`, plus the player and reporter suites:

| File | What it pins |
|---|---|
| `SyncPlayControllerTest` | Join handshake, WAITING (overlay *and* pause), intents → API calls with **zero** local playback calls, `NotInGroup` / `GroupGone` / `AccessDenied` → Idle, connection loss → paused + message, no pause on a transient flap, connectivity blip vs. sustained offline, ping-failure streak, ready owed vs. silence, the self-sync net, sign-out teardown, ignoreWait on detach, queue reconciliation, unopenable slots skipped once. |
| `SyncPlayCommandSchedulerTest` | Future / past-due / replacement commands, seek epsilon, applied-once (identical re-send, repeated past-due, stale `emittedAt`). |
| `SyncPlayDriftMonitorTest` | Threshold either side of 2 s, no correction while paused. |
| `time/SyncPlayTimeSyncTest` | Server ahead/behind, asymmetric RTT, outlier rejection, rolling window. |
| `time/SyncPlayPingerTest` | Fast-then-steady cadence. |
| `SyncPlayDtoMappingTest` | Every `GroupUpdate` variant, and the `Instant ↔ LocalDateTime` round-trip under an explicit non-UTC zone (the M4 two-hour-bug class). |
| `api/SdkSyncPlayApiTest`, `socket/SdkSyncPlaySocketTest` | The facade's DTOs and the socket's mapping. |
| `SyncPlayLocalSessionTest` | Mint once per in-group local item, mint on join-mid-playback, failure ⇒ null, closing stop on leave (once), no closing stop when the *item* changed, forgetting on screen close. |
| `ControllerSyncPlaySessionTest` | The `:core:common` contract's mapping. |
| `ui/SyncPlayGroupsViewModelTest`, `ui/SyncPlayQueueViewModelTest` | Polling, join/create/leave, 403 copy; queue titles, reorder/remove. |
| `player/.../report/PlaybackReporterSyncPlayTest` | The reporting exception in all three terms, plus "never `stopTranscoding` for a local source" and the group-exit stop. |
| `player/.../ui/PlayerSyncPlayTest`, `PlayerSyncPlayReportingTest` | In-group transport never touches `PlayerHandle`; speed and auto-skip suppressed; reconcile-before-start ordering and the three moments it runs. |

`PlaybackReporterTest` and the solo `PlayerViewModelTest` / `PlayerTrackPickerTest` are unchanged and
green — they are the regression tests that group support altered nothing about watching alone.

**Known gaps.** Device-level lockstep skew, WAITING behaviour with a second real client, and a
minified-build websocket group-join walk are verification items rather than unit tests; the R8
serializer survival of every `SyncPlay*` message class (and the `Outbound`/`InboundWebSocketMessage`
hierarchy with its `$$serializer`s and `@JsonClassDiscriminator` impls) was verified against
`app/build/outputs/mapping/release/mapping.txt` — all kept by the existing
`-keep class org.jellyfin.sdk.model.** { *; }`, so **no new keep rules were needed**.
