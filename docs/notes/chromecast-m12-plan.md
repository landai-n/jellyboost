# M12 — Chromecast (Google Cast), phone-orchestrated

## Context

The user wants Chromecast support. `docs/PLAN.md` v1 scope listed Chromecast as
"NOT v1 (don't preclude)"; this milestone is the user-approved extension
(DECISIONS 2026-07-31), gated on M11 closing. Feasibility and constraints are
confirmed:

- The official jellyfin-android app is **no reference**: its cast support is a
  Cordova-plugin JS bridge (`app/src/proprietary/.../cast/Chromecast.java` et al.)
  where jellyfin-web drives the Google Cast SDK via a reflective RPC shim and a
  fake `cast_sender.js`. There is no native sender implementation to copy, and the
  Jellyfin Cast receiver's custom-namespace JSON protocol lives only in
  jellyfin-web (undocumented, server-version-coupled).
- Media3 1.9.0 (pinned) has a matching `media3-cast` artifact; `CastPlayer` is an
  `androidx.media3.common.Player`, so it fits behind `PlayerHandle` — including
  the load-bearing contract "`selectAudioTrack`/`selectSubtitleTrack` returning
  `false` ⇒ caller renegotiates with the server", which is exactly how audio
  switches and burn-in subtitles must behave while casting.
- `PlaybackService` injects the **concrete** `ExoPlayerHandle`
  (`PlaybackService.kt:52`); `PlayerModule.kt:51` (`bindPlayerHandle`) is the
  single binding to swap — the MediaSession/notification path is untouched by a
  routing handle.

User decisions (AskUserQuestion, 2026-07-31): **phone-orchestrated architecture**
(default Google receiver, not the Jellyfin web receiver), **full parity where
feasible**, **GMS dependency direct, no flavors**, real Chromecast available for
DoD (user runs the walk).

## Gaps this fills

1. No remote-playback capability of any kind (no MediaRouter, no Output Switcher,
   no GMS anywhere in the tree).
2. `DeviceProfileBuilder` is hardware-probe-driven (`MediaCodecProbe`) — useless
   for a receiver; a cast negotiation needs its own static profile.
3. Stream URLs rely on `JellyfinAuthInterceptor` (OkHttp header) for auth — a
   receiver fetches URLs itself and needs `api_key` in the query string
   (`SdkStreamUrlFactory.trickplayTileUrl` already shows the pattern).
4. `PlaybackSourceResolver` prefers the completed download — a `file://` URI is
   unreachable from a receiver; casting must force the remote source.

## Key design decisions

1. **Routing handle**: `CastPlayerHandle : PlayerHandle` (wraps `CastPlayer`) +
   `RoutingPlayerHandle : PlayerHandle` as the Hilt binding, delegating to the
   active handle (`events` via `flatMapLatest`; `player` = ExoPlayer when local,
   `null` while casting → PlayerScreen's poster mode). Local player stops while
   casting → local media notification disappears; the Cast framework's own
   notification (`NotificationOptions`) takes over. `setActive` is driven only by
   the coordinator.
2. **Static conservative cast profile** (`CastDeviceProfile`, pure, no probe):
   H.264 High L4.2 ≤1080p + AAC/MP3 in mp4, VP8/VP9 webm direct; otherwise HLS
   **ts** H.264+AAC transcode; subtitles `vtt` external-delivery, image subs
   (PGS/DVB) burn in. 4K/HEVC detection deferred: `CastDevice` capabilities don't
   reliably expose it and a wrong guess is a black TV screen; the quality picker
   gives control.
3. **Resolution**: `PlaybackResolveRequest.castTarget: Boolean = false` joins
   `forceRemote` in skipping the disk copy; `PlaybackInfoResolver` sends the cast
   profile when set. Casting always streams from the server (local HTTP serving of
   the download is explicitly out).
4. **api_key everywhere the receiver fetches**: `StreamUrlFactory.withApiKey(url)`
   (idempotent), applied by the pure `CastSpecMapper` to the media URL and every
   subtitle URL. DIRECT_PLAY/DIRECT_STREAM URLs carry no token today (mandatory);
   `transcodingUrl` presence verified live in Phase 2 and recorded.
5. **Reporting**: `PlaybackReporter` unchanged; the ticker reads
   `RoutingPlayerHandle.snapshot()` (CastPlayer position while casting).
   `CastSessionCoordinator` runs the ticker on `@DetachedPlayerScope` when no host
   is attached, and sends the final stop report + `stopTranscoding` on session
   end. **Invariant: the coordinator reports only when no host is attached** (no
   double stop-reports). App killed mid-cast: receiver keeps playing, reporting
   stops, server session goes stale — accepted, documented; reattach is phase-2.
6. **Cast ⊕ SyncPlay**: mutually exclusive. Button hidden in-group; a session
   connected via system UI leaves the group with a message.
7. **Track parity**: custom `CastMediaItemConverter` (Media3 1.9.0's
   `DefaultMediaItemConverter` ignores `subtitleConfigurations`) builds
   `MediaInfo` + `MediaTrack`s with id = Jellyfin stream index. Subtitle select →
   `setActiveMediaTracks` for sideloaded VTT (`true`), else `false` → existing
   burn-in renegotiation. Audio select → always `false` → renegotiation with
   `audioStreamIndex`. Quality identical to local (`reopen` +
   `maxStreamingBitrate`). Speed only behind `COMMAND_SET_SPEED_AND_PITCH`.
8. **Decoder fallback bypassed while casting** — a receiver error surfaces as one
   `CastPlaybackFailed` message and stops.
9. **Cast button**: `MediaRouteButton` via `AndroidView` in a
   `ContextThemeWrapper` over an AppCompat-derived theme overlay (MediaRouter
   dialogs need AppCompat; the app is pure M3). `CastContext` initialized lazily
   from `MainActivity.onCreate` through `CastAvailability` behind a
   `GoogleApiAvailability` guard — GMS-less devices never class-load cast types
   (all GMS confined to the `cast/` package). Placement: player TopBar + AppTopBar.
10. **PlayerScreen is the remote control** while casting: poster/backdrop +
    "Casting to <device>" replaces the surface; all sheets/controls stay; no
    separate mini-controller in v1.
11. **Transfers**: local→cast = snapshot → stop report →
    `reopenSession(castTarget = true)` (existing single-coroutine ordering stops
    the outgoing transcode first, for free); cast→local on disconnect = reopen
    locally at the receiver's last position, paused.

## New package layout (`player/.../cast/`, plus neighbors)

- `cast/JellyboostCastOptionsProvider.kt` — `OptionsProvider`: default receiver id
  (`CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID`; styled receiver
  is a one-line swap later), `NotificationOptions` targeting the launch activity
  via `getLaunchIntentForPackage` (no :app compile dep — `PlaybackService.
  launchIntent()` trick), `setResumeSavedSession(true)`, expanded controller off.
- `cast/CastAvailability.kt` — `@Singleton`; `initialize(activity)` (GMS guard →
  `CastContext.getSharedInstance`); `state: StateFlow<CastDeviceState>`
  (`Unavailable | NoDevices | Available | Connecting | Connected(deviceName)`).
- `cast/CastMediaSpec.kt` — pure model: contentId/contentType/streamType,
  `CastTrackSpec(id = jellyfinIndex, uri, mimeType, label, language)`, metadata
  (title, poster URL).
- `cast/CastSpecMapper.kt` — pure `@Singleton`: `PlaybackMediaItemSpec +
  RemotePlaybackMediaSource → CastMediaSpec`; applies `withApiKey`; maps
  `"external:<index>"` subtitle ids to numeric track ids.
- `cast/CastMediaItemConverter.kt` — thin GMS assembly (`CastMediaSpec →
  MediaQueueItem`), logic-free by design (unit tests stop at the spec).
- `cast/CastPlayerHandle.kt` — `@Singleton` `PlayerHandle` over `CastPlayer`;
  events mapped to `PlayerEvent`.
- `cast/CastSessionCoordinator.kt` — `@Singleton` (SyncPlayController-shaped):
  `SessionManagerListener` → `connection: StateFlow<CastConnection>`; flips
  `RoutingPlayerHandle`; detached ticker; final stop report + `stopTranscoding`;
  `attachHost`/`detachHost` for the ViewModel.
- `session/RoutingPlayerHandle.kt` — `@Singleton`, the new `PlayerHandle` binding.
- `deviceprofile/CastDeviceProfile.kt` — `build(maxStreamingBitrate: Int?)`,
  `PROFILE_NAME = "Jellyboost Chromecast"`.
- `ui/CastRouteButton.kt` + theme overlay in `player/src/main/res/values/themes.xml`.

## Phases (each independently committable; `/verify` before each commit)

**Phase 0 — Governance (docs only, lands first).** DECISIONS entry (done),
PLAN.md M12 summary, this note, STATUS.md.

**Phase 1 — Gradle/manifest/CastContext + visible cast button (no playback).**
Catalog: `androidx.media3:media3-cast:1.9.0` (reuse `media3` ref),
`play-services-cast-framework` (pin what media3-cast 1.9.0's POM compiles
against), `androidx.mediarouter:mediarouter`, `androidx-appcompat`.
`player/build.gradle.kts` deps; `player/src/main/AndroidManifest.xml`
`OPTIONS_PROVIDER_CLASS_NAME` meta-data (merger carries to :app);
`JellyboostCastOptionsProvider`, `CastAvailability`, `CastRouteButton` + theme;
`MainActivity` one init call; `AppTopBar` button next to `ConnectionStatusAction`.
No speculative proguard rules. Tests: `CastAvailability` state mapping (faked
listener seam). Risks: MediaRouteButton theming in a non-AppCompat app (overlay
is the mitigation; budget device time), AGP 9 manifest-merger surprises (inspect
merged manifest). DoD walk 1: icon appears, chooser opens, connect/disconnect;
GMS-less device shows no icon and no crash.

**Phase 2 — Cast resolution + CastPlayerHandle + coordinator (end-to-end
playback).** New: `CastDeviceProfile`, `CastMediaSpec`, `CastSpecMapper`,
`CastMediaItemConverter`, `CastPlayerHandle`, `RoutingPlayerHandle`,
`CastSessionCoordinator`. Modified: `PlaybackResolveRequest` (+`castTarget`),
`PlaybackSourceResolver` (one `||`), `PlaybackInfoResolver` (profile branch),
`StreamUrlFactory`/`SdkStreamUrlFactory` (`withApiKey`), `PlayerModule` (bind
`RoutingPlayerHandle`; `ExoPlayerHandle` stays `@Singleton`, `PlaybackService`
untouched), `PlayerViewModel` (requests carry `castTarget =
coordinator.isCasting`; skip `DecoderFallbackHandler` while casting).
**Regression gate: every existing PlayerViewModel/controller test passes
unchanged** (routing handle with no cast session is a pure pass-through).
Tests: `CastDeviceProfileTest` (codec/container/subtitle/bitrate table),
`CastSpecMapperTest` (api_key on media+subs, idempotence, track-id mapping, all
three play methods), `RoutingPlayerHandleTest` (delegation, event switching via
Turbine, snapshot routing), `CastSessionCoordinatorTest` (connect → handle
switch, disconnect → stop report + stopTranscoding + switch back, detached
ticker start/stop), resolver test additions. **Verify against the real server +
Chromecast early**: transcodingUrl api_key presence, CAF acceptance of the HLS-ts
flavor, media3-cast converter invocation details (read the 1.9.0 source, not
docs). DoD walk 2: direct-play mp4 and forced-transcode mkv both play on TV;
dashboard shows the session; disconnect kills ffmpeg; resume position correct.

**Phase 3 — Control parity + transfers.** `PlayerViewModel` cast bridge
(possibly `PlayerCastBridge.kt`, PlayerSyncPlayBridge-shaped): collect
`coordinator.connection`; local→cast and cast→local transfers per decision 11;
leave-SyncPlay-before-cast; `attachHost`/`detachHost` on screen presence.
`PlayerUiState`: `PlayerCastState(isCasting, deviceName)`; messages
`CastPlaybackFailed`, `CastTransferred`, `CastLeftSyncPlayGroup`. Track/quality
flows: no structural change (the `false`-return contract routes through
`reopenSession`). Tests: `PlayerViewModelCastTest` against the existing fake
`PlayerHandle` (no GMS): transfer ordering (stop-report-then-reopen), fallback
bypass, audio select → renegotiation carries `audioStreamIndex` + `castTarget`,
burn-in path, quality reopen, disconnect → paused local reopen, SyncPlay
exclusivity, and the coordinator-only-reports-when-detached invariant.

**Phase 4 — Player screen as remote control.** `PlayerScreen`: casting →
poster/backdrop + "Casting to {device}" instead of `PlayerView`; disable
brightness/volume gesture layer (cast device volume rides hardware keys via
`CastContext`). `PlayerControls`: `CastRouteButton` in TopBar (hidden in-group);
hide speed when unsupported; suppress PiP while casting (and
`pipController.setPlayerState(active = false)`). Strings. Tests: UI-state
derivation in ViewModel tests (no Compose tests in the project).

**Phase 5 — Hardening, docs, close.** Minified-build cast smoke (targeted R8
keeps only if broken, with DECISIONS entry); `/document-feature` →
`docs/features/chromecast.md` + `docs/ARCHITECTURE.md` refresh; DECISIONS
entries for implementation-time observations (resolved cast-framework version,
transcodingUrl api_key finding); full DoD walk; `/verify`; tag `m12`.

**Deferred (M12-phase-2 candidates, recorded in PLAN.md):** ~~4K/HEVC per-device
profiles~~ (landed 2026-08-15, below); AC3/5.1 audio detection; reattach to a live
cast session after process death; Output Switcher; styled-receiver branding;
casting the on-disk copy via a local HTTP server; cast + SyncPlay.

## Phase-2a — 4K/HEVC per-receiver profiles (2026-08-15)

**The capability question has no API.** The sender SDK exposes no codec or
resolution capability: `CastDevice.hasCapability` covers only in/out audio/video
flags, and CAF's `canDisplayType()` runs on the receiver, which with the Default
Media Receiver is Google's code we cannot ask. Every sender that adapts to the
receiver (jellyfin-web's chromecast plugin included) does it by **model name**,
so that is the design: `CastReceiverClass.fromModelName(CastDevice.modelName)`,
an exact-match allowlist over lowercased names, with everything unknown falling
back to today's measured-safe 1080p profile.

**Classes** (published Google/NVIDIA decoder specs, not guesses):

| Class | Model names | Video ceiling added |
|---|---|---|
| `ULTRA_4K` | Chromecast Ultra, Chromecast with Google TV, Chromecast Google TV, Google TV Streamer, SHIELD Android TV, SHIELD TV, NVIDIA SHIELD | HEVC Main/Main 10 ≤3840×2160, level ≤5.1 (`153`) |
| `HEVC_1080P` | Chromecast HD | HEVC Main/Main 10 ≤1920×1080, level ≤4.1 (`123`) |
| `LEGACY_1080P` | everything else, incl. bare "Chromecast" and `null` | none — the existing profile, byte-for-byte |

**What ships enabled: direct play only.** Capable classes gain `hevc` in the
`mp4` DirectPlayProfile plus an `hevc` CodecProfile (profile `main|main 10`,
level/size per class, and `VideoRangeType ∈ SDR|HDR10|HLG` so a Dolby Vision
bitstream — which reports "Main 10" but needs a DV pipeline — still transcodes).
H.264 stays at High L4.2 ≤1080p in **all** classes: that is correct for
Chromecast Ultra (its published 4K decode is HEVC/VP9 only, H.264 tops out at
1080p60), and 4K H.264 content is too rare to justify per-class H.264 ceilings.

**What deliberately does not change:**
- **The transcode target stays H.264+AAC HLS-ts ≤1080p for every class.** CAF's
  TS demuxer is H.264-only — HEVC over HLS needs fMP4 segments, and fMP4-HLS was
  device-measured broken on the reference Ultra (load accepted, media session
  never opens; class doc of `CastDeviceProfile`). So 4K reaches the television
  exclusively as a direct play; an HEVC file the receiver cannot direct-play
  still becomes a 1080p H.264 transcode. An fMP4-HLS HEVC transcode for
  Google-TV-class receivers is a phase-2b candidate **gated on a device walk**,
  not shipped speculatively.
- **Audio ceilings are untouched in every class** — stereo AAC/MP3 is a measured
  fact on the Default Media Receiver (error 104 on >2ch AAC in every container,
  AC3/EAC3 failed too), not a conservatism to relax from a spec sheet. A 4K HEVC
  remux with TrueHD/AC3 audio therefore still full-transcodes today (video
  cannot be copied into the ts transcode container); that is the known cost of
  the measured audio floor and it is recorded, not hidden.
- Cast Auto still skips the phone-side bandwidth measurement (the phone's link
  is not the receiver's), and the quality picker remains the manual override.

**Plumbing:** `CastSessionListener.onSessionStarted` gains `modelName`
(defaulted, so fakes and call sites compile unchanged); `GmsCastSessionMonitor`
reads `castDevice?.modelName` under the same `runCatching` defense as
`friendlyName`; `CastSessionCoordinator` classifies once per session, logs
`model → class` at INFO (that log line is what turns a mis-classified 4K device
into a one-line allowlist fix), and stores the class on
`CastConnection.Connected`; `CastStatusHolder` exposes it;
`PlaybackInfoResolver` passes it to `CastDeviceProfile.build(...)`.
`PlaybackResolveRequest` is untouched.

**Owed to the device walk (user-run):** on the real Ultra — model string logged
and classified `ULTRA_4K`; a 4K HEVC Main 10 **mp4** with stereo AAC direct-plays
(dashboard: DirectPlay, no ffmpeg); an HDR10 file renders (dongle tone-maps on
SDR screens); a Dolby Vision file transcodes rather than black-screens; an mkv
or TrueHD-audio 4K still transcodes to 1080p exactly as before; and an unknown
receiver keeps today's behaviour end to end.

## Verification

- Per phase: `source "../env.sh"` && `./gradlew ktlintCheck detekt
  testDebugUnitTest` (+ assembleDebug via `/verify`).
- **Device DoD (user-run, real Chromecast + test tablet + dev server):**
  1. Cast icon on home top bar → chooser → connect; hardware volume keys move TV
     volume.
  2. Direct play (mp4/H.264/AAC): plays on TV, dashboard DirectPlay, phone shows
     poster + controls.
  3. Transcode (HEVC/mkv): dashboard Transcode (HLS); quality → 4 Mbps
     renegotiates, old ffmpeg gone.
  4. Transport from phone controls and cast notification, both land ≤ ~1 s.
  5. Text sub → sideloaded VTT, no restart; PGS sub → restart, burned in; audio
     switch → restart, correct language.
  6. Connect mid-local-playback → TV continues near phone position; disconnect →
     phone paused at TV position, resumes locally.
  7. Stop mid-film → correct resume position in jellyfin-web; no lingering
     session/ffmpeg.
  8. Background app → TV plays on, dashboard advances, notification works. Kill
     app → TV plays on (dashboard stale — expected, documented).
  9. Edges: in SyncPlay group → no cast button; system-UI connect leaves group
     with message; downloaded-only item casts as a stream; GMS-less device → no
     icon, no crash; minified build: repeat 2/3/5.

## Risks

- MediaRouteButton/dialog theming in a pure-M3 app (Phase 1; theme overlay is
  the mitigation, a custom chooser the bounded fallback).
- media3-cast 1.9.0 `MediaItemConverter`/track-selection specifics — read the
  source early in Phase 2; `RemoteMediaClient.setActiveMediaTracks` used
  directly to sidestep CastPlayer API gaps.
- CAF HLS compatibility of the server's `ts` output (verified in walk 2; fmp4 is
  the documented fallback).
- Double-report / stranded-encoder races on transfer and disconnect — the
  single-coroutine `reopen` ordering and the coordinator-only-reports-when-
  detached invariant, both unit-tested.
- First GMS dependency: keep every GMS type inside `cast/` so a GMS-less device
  never class-loads one (guarded by `CastAvailability`).

## Execution notes

- Implementation delegated to subagents per CLAUDE.md: **opus** for
  `CastPlayerHandle`/`CastSessionCoordinator`/transfer logic, **sonnet** for
  mechanical pieces (profile, mapper, gradle, UI-state plumbing); worktree
  isolation; orchestrator runs `/verify` independently before committing.
- Governance rules travel in every subagent prompt; `source "../env.sh"` first.
