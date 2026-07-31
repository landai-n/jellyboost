# M11 — SyncPlay (group watch), full scope

## Context

The user wants SyncPlay (Jellyfin's server-coordinated group watch) in Jellyboost, **including when the item is a downloaded local file while the device is online** — play from disk, stay in lockstep with the group. Feasibility is confirmed:

- The pinned SDK (`org.jellyfin.sdk:jellyfin-core` **1.8.12** — no version bump needed) has the complete surface: `apiClient.syncPlayApi` (all 22 operations, incl. queue management), `apiClient.timeSyncApi.getUtcTime()`, and `apiClient.webSocket: SocketApi` with `subscribeSyncPlayCommands(...)` / `subscribe<SyncPlayGroupUpdateMessage>()`, reconnect + keep-alive built in.
- The "online but playing a local file" state is already first-class plumbing (`PlayerViewModel.isOnline`/`localSource`/`forcedRemote`, connectivity-aware track picker).
- The official jellyfin-android app has **no native SyncPlay** (WebView-only; it disables ExoPlayer during SyncPlay) — no prior art to copy; we build from the protocol using the SDK.

User decisions (AskUserQuestion): **full feature** (incl. in-app group queue management, next/previous, shuffle/repeat), **dedicated SyncPlay section** in the UI plus player integration, **plan now as M11, implement after M10 closes**. Movies & episodes only (app scope).

## Gaps this fills

1. No WebSocket code exists anywhere in the app.
2. No server clock offset exists (`TimeSyncApi` unused).
3. No `MediaController` — commands must land on `PlayerHandle` directly (a `@Singleton`, like `ExoPlayerHandle`), so they work while backgrounded under `PlaybackService`.
4. `PlaybackReporter.reportsToServer()` (`player/.../report/PlaybackReporter.kt:225`) returns null for any `LocalPlaybackMediaSource` → local playback reports nothing today; `LocalPlaybackMediaSource` has no `playSessionId` by construction.

## Key design decisions

1. **Placement**: new package `player/src/main/kotlin/dev/jellyboost/player/syncplay/` — no new module; `:player` already has `PlayerHandle`, resolvers, reporter, and depends on `:core:network`/`:data`.
2. **Cross-feature contract** `SyncPlaySession` in `:core:common` (activeGroup StateFlow, playForGroup, addToGroupQueue) so `:feature:detail`/`:feature:home` don't depend on `:player`.
3. **WebSocket connected only while in a group**; the dedicated section's group list is REST + 10 s poll. Battery + simplicity; SDK handles reconnect.
4. **`SyncPlayController` is a `@Singleton`** with its own supervisor scope driving `PlayerHandle` directly — commands apply while backgrounded; `PlayerViewModel` (886 lines already) gets only a thin bridge (~100 lines).
5. **Group membership survives leaving the player screen**: on host detach send `syncPlaySetIgnoreWait(true)` (jellyfin-web's own mechanism) so we never gate the group; a later `PlayQueueUpdate` re-launches the player via a `launchRequests` flow collected in the app NavHost.
6. **Time quirk (critical)**: SDK 1.8.12 `DateTimeSerializer` treats `LocalDateTime` as **local wall-clock** (`ZoneId.systemDefault()`), not UTC — the M4 two-hour-bug class. All `LocalDateTime ↔ Instant` conversion happens in exactly one file (the SyncPlay API facade), reusing `data/.../SdkDateTime.kt` **made `public`** (currently `internal`). `SendCommand.when`/`emittedAt` must go through it.
7. **Drift correction v1**: 1 s monitor while playing; corrective seek beyond 2 s drift. Rate-nudge (SpeedToSync via `setPlaybackSpeed`) is a flagged stretch goal.
8. **Downloaded-file-in-group resolution is free**: controller issues a normal `PlaybackResolveRequest(itemId, startTicks)`; `PlaybackSourceResolver` (`player/.../resolve/PlaybackSourceResolver.kt:57-79`) already prefers the completed download. Per queue item: local file if downloaded, else stream.
9. **Local-in-group playback DOES report** start/progress/stop (group members should appear in the dashboard): mint a `playSessionId` via one `PlaybackInfo` POST at load time (no stream URL fetched → no encoder starts); tolerate mint failure with `playSessionId = null`. SyncPlay *membership* doesn't need a playSessionId (server keys groups on the authenticated session).
10. **Connection drop mid-group → pause playback, leave group, manual resume** (user decision 2026-07-30, amended from "keep playing solo"): pause the player, message "Left SyncPlay — connection lost", and let the user resume manually — resuming plays solo (from disk if local). Rationale: group watch is social; silently continuing means silently drifting from the group, while an explicit pause + one-tap resume makes the state change honest. Rejoining the group is manual via the groups UI.
11. **In-group user transport never acts locally**: play/pause/seek/next/previous/queue edits become API calls; only the server's rebroadcast `SendCommand`/`PlayQueueUpdate` moves the player. Speed picker + segment auto-skip disabled in-group; skip-intro routes through requestSeek.

## New package layout (`player/.../syncplay/`)

- `api/SyncPlayApi.kt` + `api/SdkSyncPlayApi.kt` — facade over `apiClient.syncPlayApi` + `timeSyncApi`, `@IoDispatcher`, mirrors the existing `api/PlayerApi.kt → SdkPlayerApi.kt` seam. Speaks `Instant` + domain models only — the single SDK-time boundary.
- `socket/SyncPlaySocket.kt` + `socket/SdkSyncPlaySocket.kt` — `apiClient.webSocket` subscriptions mapped to domain events (`groupUpdates: Flow<SyncPlayGroupEvent>`, `commands: Flow<SyncPlayCommand>`).
- `model/SyncPlayModels.kt` — GroupSummary, QueueEntry(itemId, playlistItemId), GroupQueue, SyncPlayCommand(type, when: Instant, positionTicks, playlistItemId), sealed SyncPlayGroupEvent (Joined, StateChanged, QueueChanged, UserJoined/Left, NotInGroup, GroupGone, LibraryAccessDenied), TimeSyncSample.
- `time/SyncPlayTimeSync.kt` — NTP-style rolling estimator: 8 samples, drop RTT outliers (> max(1 s, 3×median)), offset = avg((t1−t0)+(t2−t3))/2; injects the existing `java.time.Clock` (`data/.../di/UserDataModule.kt:43`).
- `time/SyncPlayPinger.kt` — while in group: 3 fast samples (1 s apart) then every 5 s: getUtcTime → estimator → `syncPlayPing(rtt/2)`.
- `SyncPlayCommandScheduler.kt` — one pending-command slot; delay until local `when`; on Main against `PlayerHandle`: PAUSE→seek+pause, SEEK→seek (stay paused), UNPAUSE→seek-if-off-anchor then play, past-due UNPAUSE→seek to pos+(now−when) then play, STOP→stop.
- `SyncPlayDriftMonitor.kt` — 1 s tick while Playing; |expected − actual| > 2000 ms → corrective seek.
- `SyncPlayStatusHolder.kt` — tiny `@Singleton` read by `PlaybackReporter` (breaks DI cycle): `inGroup: StateFlow<Boolean>`, minted playSessionId.
- `SyncPlayPlaybackHost.kt` — interface PlayerViewModel implements: `suspend loadItem(itemId, startTicks): Boolean` (open session paused), `snapshot()`.
- `SyncPlayController.kt` — `@Singleton` state machine: `Idle | Joining | InGroup(group, queue, phase: Waiting|Buffering|Playing(anchor)|Paused)`; user intents (create/join/leave, requestPause/Unpause/Seek/Next/Previous/SetPlaylistItem, setNewQueue, addToQueue, move/remove, shuffle/repeat); attachHost/detachHost (+ignoreWait); `launchRequests: SharedFlow`; collects `PlayerHandle.events` for buffering→ready handshake and onEnded→nextItem; observes `ConnectionStateProvider` + session state for solo-fallback and sign-out teardown.
- `di/SyncPlayModule.kt` — binds + `@SyncPlayScope` supervisor scope (modeled on `DetachedPlayerScope` in `player/di/PlayerModule.kt`).
- `ui/` — `SyncPlayGroupsScreen.kt` + ViewModel (dedicated section), `SyncPlayGroupSheet.kt` (in-player: participants, shuffle/repeat, leave), `SyncPlayQueueSheet.kt` (queue view/edit).

**Join flow**: joinGroup REST → socket collect → Joined + QueueChanged → resolve current entry (host attached ? `host.loadItem` : emit launchRequest) → send `syncPlayBuffering` while preparing → on `PlayerEvent.Ready` send `syncPlayReady` → server WAITING→PLAYING → scheduled UNPAUSE at converted local instant. Re-negotiations (track/quality change, decoder fallback) re-enter the handshake automatically because the controller watches `PlayerHandle.events`.

## Phases (each independently committable; `/verify` before each commit)

**Phase 0 — Governance (docs only, can land now).** PLAN.md: append M11 milestone (full scope incl. queue mgmt, dedicated section, local-file-in-group, DoD = two-client device verification incl. minified-build websocket check — minification arrives in M10, the R8 polymorphic-serializer verification for `OutboundWebSocketMessage` lands here). DECISIONS.md via `/diverge`: SyncPlay absent from plan v1 scope, user-approved addition; record decisions 5/9/10/11. STATUS.md note.

**Phase 1 — Protocol plumbing (no behavior change).** New `api/`, `socket/`, `model/`, `time/SyncPlayTimeSync.kt`, `di/`. Modify `data/.../SdkDateTime.kt` `internal`→`public`. First task: verify exact SDK 1.8.12 subscribe signatures/DTO fields from sources. Tests: time-sync math (server ahead/behind, asymmetric RTT, outliers, rolling window); Instant↔LocalDateTime round-trip with an explicit non-UTC zone; socket mapper tests.

**Phase 2 — Coordinator core (not yet user-reachable).** Controller, scheduler, drift monitor, pinger, status holder, host interface. Tests (virtual time, FakePlayerHandle, fake Clock, Turbine): happy path join→ready→scheduled unpause; WAITING pauses; scheduler future/past-due/replacement; drift thresholds; in-group intents → API calls, zero local playback calls; NotInGroup/GroupGone/AccessDenied → Idle; confirmed connection loss mid-group → Idle, player paused, "Left SyncPlay — connection lost" surfaced, manual resume works solo (local source untouched on disk); no pause on a transient blip the socket recovers from; sign-out teardown; ping cadence; ignoreWait on detach. **Verify protocol subtleties against the real server early in this phase.**

**Phase 3 — Player integration.** Modify `PlayerViewModel.kt` (thin bridge: host attach/detach; transport routes to controller when in-group; disable speed; collect state), `PlayerUiState.kt`, `PlayerControls.kt` (group icon), `PlayerScreen.kt` (WAITING overlay), new `SyncPlayGroupSheet.kt`. Tests: in-group transport goes to controller not PlayerHandle; solo behavior unchanged (existing tests pass untouched); auto-skip suppressed in-group.

**Phase 4 — Group queue management.** New `SyncPlayQueueSheet.kt`; controller queue intents + QueueChanged reconciliation (playing entry changed → loadItem; resolver picks disk copy per item); `core/common/.../syncplay/SyncPlaySession.kt`; `feature/detail` "Play for group"/"Add to group queue"/"Play next" when a group is active; queue titles via `JellyfinRepository.getItems`. Tests: intents → correct DTOs; reconciliation; detail VM actions; non-video entries skipped with message.

**Phase 5 — Dedicated SyncPlay section.** `SyncPlayGroupsScreen.kt` + ViewModel (list groups, 10 s poll, participants, join/create/leave, 403 → "SyncPlay is disabled for your account"); `Routes.SyncPlay` + NavHost wiring; NavHost collects `launchRequests` → navigate to player; home top-bar Groups icon + active badge via `SyncPlaySession.activeGroup`. Tests: groups VM (Turbine), launch-request navigation.

**Phase 6 — Local-file reporting, resilience, docs, close.** `PlaybackReporter.kt`: replace `RemotePlaybackMediaSource?` narrowing with a `ServerReportTarget` (remote+online as today, **plus** local+online+inGroup; `stopTranscoding` stays remote-only). `PlaybackInfoResolver.kt`: expose `mintPlaySessionId(itemId, mediaSourceId)`. R8/minified websocket smoke on device. `/document-feature` → `docs/features/syncplay.md`, ARCHITECTURE.md refresh, DECISIONS entry for the reporter exception. Tests: local+in-group+online reports (minted id / null id); local not-in-group still silent; offline still silent; all existing reporter tests unchanged and green.

## Verification

- Per phase: `source "../env.sh"` && `./gradlew ktlintCheck detekt testDebugUnitTest` (+ assembleDebug via `/verify`).
- **Device DoD (test tablet + jellyfin-web on desktop, same server)**: lockstep play/pause/seek both directions (<~1 s skew); **downloaded item plays from disk on tablet while web streams — dashboard shows both sessions, no stream traffic for the tablet, commands from web still land**; WAITING overlay when the other client stalls; queue add/reorder/remove/next/previous/shuffle from tablet visible on web; commands applied while app backgrounded; Wi-Fi kill mid-group with local file → playback pauses + "Left SyncPlay — connection lost"; manual resume continues solo from disk, no stream traffic; sign-out leaves group; minified build receives GroupUpdates; SyncPlay-disabled account degrades gracefully.

## Risks

- SDK LocalDateTime = local wall-clock (single-boundary + explicit-zone tests mitigate; getting it wrong shifts every scheduled command by the UTC offset).
- Server protocol subtleties (ready/buffering gating, ignoreWait) — verified against the real server early in Phase 2.
- R8 stripping polymorphic websocket serializers — explicit Phase 6 check (proguard already keeps `org.jellyfin.sdk.model.**`).
- PlaybackInfo mint could return a transcode plan — harmless (URL never fetched), stop report closes the session.
- Drift threshold needs device tuning; the 1 s monitor is the safety net.

## Execution notes

- Implementation is **gated on M10 closing** (user decision). Phase 0 (docs) can land immediately.
- Per project convention, implementation phases are delegated to subagents (opus for controller/scheduler/time-sync and player integration; sonnet for UI screens, facades, and tests from spec), each prompt carrying the governance rules; orchestrator runs `/verify` before every commit.
