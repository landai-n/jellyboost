# Feature: Chromecast (Google Cast) — M12

Sending the film to a television and keeping every control that matters: play/pause/seek, audio and
subtitle selection, the quality picker, resume, progress reporting to the server, and moving a
part-watched film from the phone to the receiver and back. Full scope per
`docs/notes/chromecast-m12-plan.md`.

Movies and episodes only, which is the app's scope everywhere else.

## What it is: phone-orchestrated, on Google's default receiver

The television runs the **default media receiver**, not the Jellyfin web receiver. Everything
Jellyfin-shaped happens on the phone: it negotiates `PlaybackInfo` with a Cast-specific device
profile, builds the stream URLs, hands the receiver a URL and a list of subtitle files, and reports
progress to the server itself. The receiver is a video player and nothing more.

That is a deliberate choice (DECISIONS.md 2026-07-31, "M12 Chromecast milestone approved", decision
1). The Jellyfin Cast receiver speaks an undocumented custom-namespace JSON protocol that lives only
in jellyfin-web and is coupled to the server version; the official jellyfin-android app is no
reference either, since its cast support is a Cordova plugin driving that protocol from web code.
Media3's `CastPlayer` is an `androidx.media3.common.Player`, so it fits behind the `PlayerHandle`
seam the whole player is already written against — including the contract that carries the
milestone: **a track selection that returns `false` makes the caller re-negotiate with the server.**

```
        ┌── AppTopBar / PlayerControls ──► CastRouteButton ──► MediaRouter chooser
        │                                        │
        │                                 CastAvailability  (the only CastContext, GMS guard)
        │                                        │
        │                                CastSessionMonitor  (SessionManagerListener)
        │                                        ▼
        │                             CastSessionCoordinator ──► CastStatusHolder (isCasting)
        │                                  │          │
        │                    setActive(Cast)│          │attach / detach, transfer snapshots
        │                                  ▼          ▼
   PlayerViewModel ──► PlaybackSessionController ──► RoutingPlayerHandle ──┬─► ExoPlayerHandle
        │  (castTarget = isCasting)              (the Hilt PlayerHandle)   └─► CastPlayerHandle
        │                                                                        │
        └──► CastMetadataHolder (title / subtitle / poster) ──────────────────────┘
                                                                                 ▼
                                            CastSpecMapper ─► CastMediaSpec ─► CastMediaItemConverter
                                                                                 ▼
                                                                            MediaQueueItem → receiver
```

## Key classes

All in `player/src/main/kotlin/dev/jellyboost/player/cast/` unless stated.

| Class | Responsibility |
|---|---|
| `JellyboostCastOptionsProvider` | The framework's configuration, instantiated **reflectively** from the `OPTIONS_PROVIDER_CLASS_NAME` meta-data in `:player`'s manifest (the merger carries it into `:app`). Default receiver id, `setResumeSavedSession(true)`, `NotificationOptions` targeting the launcher activity resolved at runtime (`getLaunchIntentForPackage`, so `:player` needs no dependency on `:app`), and deliberately **no** expanded-controller activity — the app's own player screen is the remote control. |
| `CastAvailability` | The single door to Google Cast. Owns the process-wide `CastContext`, created once from `MainActivity.onCreate` behind a `GoogleApiAvailability` guard, and publishes `CastDeviceState` (`Unavailable / NoDevices / Available / Connecting / Connected(name)`) — a GMS-free view the UI can observe on a device that has no Cast stack at all. `castDeviceStateOf` is the pure mapping, tested on its own. |
| `CastSessionMonitor` + `GmsCastSessionMonitor` | "A receiver appeared", "it went away", with no Cast type in the signature — the seam that makes the coordinator unit-testable. Waits for `CastAvailability` before registering, reports an **already-connected** session as a start (the framework does not replay it, and connect-then-play is the everyday case), and folds `onSessionResumed` into the same event. `onSessionSuspended` (a Wi-Fi blip) is surfaced as its own event rather than ignored. |
| `CastSessionCoordinator` | `SyncPlayController`-shaped `@Singleton`, started from `JellyboostApplication`: flips `RoutingPlayerHandle`, stops the player being left, publishes `CastStatusHolder`, keeps the progress ticker running on `@DetachedPlayerScope` when no screen is attached, and sends the final stop report (which kills the transcode) when a session ends with nobody watching. A start for an already-connected session (a resumed/suspended one, or the monitor's start-time replay) is dropped rather than re-run as a transfer (audit CAST-05); a detaching screen's source is only remembered while a session is live, so a failed cast attempt cannot stop-report a film that was never cast (audit CAST-02); and the cast player is stopped once the session ends, clearing its listener and stale media (audit CAST-08). It also judges a receiver that **drops the item** while the session stays up: `PlayerEvent.RemoteItemMissing` starts a 10 s grace period (`ITEM_LOST_GRACE`), `RemoteItemMissingCleared` cancels it, and at the end of it the attached screen is told (`CastPlaybackHost.onCastItemLost`) or — with none — the coordinator sends the stop report itself and stops its ticker (see *Who reports to the server*). A suspension marks the connection `suspended` until the framework's resume, which arrives as a repeated start. |
| `CastStatusHolder` | The one fact the rest of the app needs — `isCasting`, plus the device name and whether the session is `suspended` — modelled on `SyncPlayStatusHolder`. It is what keeps every `com.google.android.gms` type out of `PlayerViewModel`. A suspended session is still `Connected`: the receiver plays on and the next open still casts. |
| `CastMetadataHolder` | The other direction: what the *television* should say this is. A `PlaybackInfo` response names nothing, so the ViewModel's item fetch publishes title, episode line and poster here, keyed by media id, and `CastPlayerHandle` reads it at prepare. |
| `CastPlaybackHost` / `CastPlaybackCoordinator` / `NoCastPlaybackCoordinator` | The public attach/detach seam between the coordinator and a screen, plus the two transfer callbacks. Names only `PlaybackMediaSource` and `PlaybackSnapshot`. |
| `session/RoutingPlayerHandle` | **The Hilt `PlayerHandle` binding.** Delegates to whichever player is live; `events` through `flatMapLatest`, `player` (the video surface) `null` while casting, `stopInactive()` to silence the one being left. With no cast session it is a pass-through with no branch in it — which is what makes "casting changed nothing about playing alone" a property of the code. The cast handle arrives through a `Provider` so a GMS-less device never constructs one. |
| `CastPlayerHandle` | `PlayerHandle` over media3-cast's `CastPlayer`. No surface and no `PlaybackService` (the framework publishes its own media session and notification). `selectAudioTrack` always `false`; `selectSubtitleTrack` uses `RemoteMediaClient.setActiveMediaTracks` for a side-loaded VTT — claiming `true` only when the receiver's own `MediaStatus` still offers the track, and logging async rejections (audit CAST-03) — and `false` otherwise; `snapshot()` is only valid while the receiver still holds our item (audit CAST-01); `playWhenReady` is the receiver's intent, readable when the snapshot is not; `supportsPlaybackSpeed` asks the receiver. Every reading it takes — from `snapshot()` and at the end of every callback batch — goes through `RemoteItemPresence`. |
| `RemoteItemPresence` | Pure. Turns the handle's readings into the two edges `PlayerEvent.RemoteItemMissing(lastHeld)` / `RemoteItemMissingCleared`. **Armed only once the receiver has held the item in `READY` (or `ENDED`) since the last load**, so the several seconds after every `prepare` in which the reading is invalid — and a placeholder that briefly claims the item — never raise it. |
| `CastSpecMapper` | Pure. `PlaybackMediaItemSpec + RemotePlaybackMediaSource + CastMetadata → CastMediaSpec`: the `ApiKey` on every URL the receiver fetches, the content type it will not sniff, and subtitle ids renumbered onto Jellyfin stream indices. All the decisions live here, which is why this is what the tests cover. |
| `CastMediaSpec` / `CastTrackSpec` / `CastMetadata` | The plain data in between — no GMS type appears in it. |
| `CastMediaItemConverter` | Mechanical `MediaInfo` / `MediaTrack` / `MediaQueueItem` assembly. Media3's `DefaultMediaItemConverter` ignores `subtitleConfigurations` entirely, which is most of what casting a Jellyfin item is. |
| `deviceprofile/CastDeviceProfile` | The static, conservative profile a cast `PlaybackInfo` is negotiated against. |
| `ui/CastRouteButton` (in `player/ui/`) | `MediaRouteButton` in a `ContextThemeWrapper` over an AppCompat theme overlay, sourcing its own state; draws nothing and loads no GMS class while `Unavailable`, and is drawn in every other state — `NoDevices` included, so the action cluster keeps its shape and a tap opens the chooser. Placed on `AppTopBar` and in the player's controls. |
| `ui/PlayerCastBridge` (in `player/ui/`) | The player's half: `isCasting`, the state the screen draws, the two transfer edges. It **is** the `CastPlaybackHost` — `PlayerViewModel` is public and cannot implement it directly. |

## The negotiation, end to end

1. **`castTarget`.** `PlayerViewModel` stamps `PlaybackResolveRequest.castTarget = cast.isCasting`
   on every resolve — read fresh each time, because a session can start or end at any point in a
   film. `PlaybackSourceResolver` treats it like `forceRemote`: **the copy on disk is skipped**, since
   a `file://` URI means nothing on the other side of the network (serving the download over a local
   HTTP server is explicitly out of scope). `PlaybackSessionController.open` re-reads `isCasting`
   after the resolve returns and, if a session started or ended while it was on the wire,
   re-negotiates with the corrected flag instead of preparing a stream on the wrong player
   (audit CAST-04).
2. **The cast profile.** `PlaybackInfoResolver` sends `CastDeviceProfile.build(maxStreamingBitrate)`
   instead of the `MediaCodecProbe`-derived one: H.264 High ≤ L4.2, ≤ 1080p, AAC/MP3 in `mp4` and
   VP8/VP9 in `webm` direct; anything else an HLS **ts** transcode to H.264 + AAC. Subtitles: WebVTT
   external, everything else burned in.
3. **URLs the receiver can actually fetch.** Every stream this app opens is authorised by
   `JellyfinAuthInterceptor`'s header; a receiver has its own network stack with nothing of ours in
   it. `CastSpecMapper` therefore runs the media URL and every subtitle URL through
   `StreamUrlFactory.withApiKey`, which is idempotent — probed against the dev server, a transcode's
   `TranscodingUrl` and every subtitle `DeliveryUrl` already carry `ApiKey`, while the SDK-built
   direct-play/direct-stream URLs do not. The **poster is deliberately not signed** (audit CAST-06):
   Jellyfin's image endpoints answer without credentials, and every URL handed to the receiver is
   republished in its `MediaStatus`, so the token goes only where the fetch needs it.

   **Accepted risk — the token in the media and subtitle URLs.** The `ApiKey` those URLs carry is
   the account's long-lived session token, and any sender on the same network can join the Default
   Media Receiver's session (`CC1AD845`) and read it out of the published `MediaStatus`; on a plain
   HTTP server it also crosses the LAN in clear. This is unavoidable with what Jellyfin offers
   today: the server has no scoped or short-lived stream credential — no signed/expiring URLs, no
   per-session media keys — and jellyfin-web's own cast sender embeds the same token the same way.
   Closing it would need server-side support for short-lived, media-scoped stream tokens. Recorded
   here so the exposure is a decision rather than an accident (audit CAST-06).
4. **The spec rides inside the `MediaItem`.** media3-cast hands a converter a `MediaItem` and nothing
   else, so `CastMediaSpec` travels as `localConfiguration.tag` and `CastMediaItemConverter` unpacks
   it. Everything decidable was settled one step earlier, in data a JVM test can read.
5. **Track ids are Jellyfin stream indices.** Cast lets the sender choose them, so choosing the ones
   the rest of the app already speaks means a subtitle the picker asks for goes straight to
   `setActiveMediaTracks` with no lookup table in between.

### What the receiver is told it is playing

`MediaInfo` metadata — the title on the television and in the Cast notification — comes from the
item, and a `PlaybackInfo` response carries none of it. `PlayerViewModel` already fetches the item
for the top bar and the casting backdrop, so it publishes `CastMetadata(title, subtitle, posterUrl)`
into `CastMetadataHolder` under the item's id, and `CastPlayerHandle.prepare` reads it back under the
spec's `mediaId`. A **cast** open waits for that fetch before it negotiates (`openSession`); a local
open never does. The reason is asymmetric: a title that arrives a moment after the first frame is
invisible here, while a receiver is loaded exactly once — metadata that arrived afterwards could only
be applied by loading the film a second time (DECISIONS.md 2026-07-31, "the receiver is told what it
is playing"). The id key is what stops a queue that has moved on from captioning the new item with
the old one's title.

## Transfers

| edge | what happens |
|---|---|
| **local → cast** | The coordinator takes a snapshot off the player that is *still* playing, flips the routing handle, stops the local player, and hands the snapshot to the screen. The screen closes the outgoing session — one stop report, which carries the `stopTranscoding` — and then negotiates the item again with `castTarget = true`, resuming at that position and playing if the phone was playing. Both halves run in **one coroutine** so the new `PlaybackInfo` cannot overtake the encoder kill. |
| **cast → local** | The last snapshot is read off the cast player *before* the routing goes home, and the film reopens on this device **paused** at that position. Paused because a disconnect is not a request to watch: the user pulled the plug or left the room. |
| **in a SyncPlay group** | The group is left first, and its message wins the snackbar — moving to a television is visible a second later, while being thrown out of a group is not. |

Both go through `openSession(..., endingAt = snapshot)` rather than `reopenSession`, because a
re-negotiation reads the *current* player for its resume position and `playWhenReady`, and across a
routing flip both readings are the wrong player's.

## Who reports to the server

**Exactly one stop report per source**, and the rule that guarantees it is: *the coordinator reports
only while no host is attached.*

| situation | ticker | stop report |
|---|---|---|
| Player screen open, casting | `PlayerViewModel`, reading `RoutingPlayerHandle.snapshot()` (the receiver's position) | the screen |
| Screen backed out of, receiver plays on | `CastSessionCoordinator`, on `@DetachedPlayerScope` | the coordinator, when the session ends |
| Screen closes while casting | — | **neither**: `releaseSession` skips the stop report *and* `stop()`/`release()`, because a television is not the screen's to end |
| Receiver drops the item, screen open | stopped by the screen's `endCurrentSource` | the screen, once, at the last held position (`onCastItemLost`) |
| Receiver drops the item, no screen | stopped by the coordinator | the coordinator, once, at the last held position; `detachedSource` is cleared as it is sent, so the session's later end finds nothing to report |

A screen that goes away after its session's stop was already reported — the film played to its end,
or the receiver dropped it — hands the coordinator **no** source (`PlayerCastBridge.castSource` is
`null` once `stopReported`), so the session's eventual end cannot report it a second time.

**A receiver that drops the item.** Observed on a Chromecast Ultra: the receiver unloaded the media
while the session stayed connected, and for five minutes the detached ticker logged "Skipping a
progress tick" until the television closed its idle session. Now `CastPlayerHandle` notices the item
going (`RemoteItemPresence`) and the coordinator, after the 10 s grace period, decides it is gone.
With a screen attached the item is **not** reloaded — whoever pressed Stop on the television meant it
— the snackbar says "Playback stopped on <device>", the screen goes paused at the last held position
(the scrubber still works, and moves that position), and Play re-sends the item to the receiver from
there. With no screen, the stop report goes out and the ticker stops; the connection itself is left
alone, so the next Resume still casts while the session is up, and opens locally once it has ended.

One guard sits under all three rows (audit CAST-01): a reading taken off the cast player is only
*valid* while the receiver still holds our item (`PlaybackSnapshot.isValid`). A Stop pressed on the
television or another sender loading its own media leaves the session alive and `CastPlayer`
answering zero — or the other app's position; `PlaybackReporter` skips such a progress tick
entirely, and a stop with an invalid reading still closes the server session and kills the encoder
but carries no position and writes nothing locally, so the ticker's last valid write stays the
resume position.

## What the player screen becomes

While casting, `PlayerScreen` draws the item's backdrop under a scrim with a "Casting to <device>"
chip 88 dp above centre instead of the video surface, and keeps every control and sheet. The vertical
**swipes** (brightness, volume) are left out — one is inaudible and the other dims a still image —
while the tap and double-tap handler stays, since a single tap is the only way to bring the controls
back once they auto-hide. Picture-in-picture is disarmed (there is nothing to float), the speed
picker is shown only if the receiver reports `COMMAND_SET_SPEED_AND_PITCH` (re-read at
`PlayerEvent.Ready`, because a `CastPlayer` only learns its receiver's commands once something is
loaded), and hardware volume keys move the *television's* volume through `CastContext`.

**The transport never trusts an invalid reading.** For several seconds after every load (and
whenever the receiver holds nothing of ours) the cast snapshot is invalid — position, duration and
`isPlaying` all zero. Play/pause therefore reverses the player's **intent** (`PlayerHandle.playWhenReady`,
forwarded by `RoutingPlayerHandle`), not `snapshot.isPlaying`; ±10/30 s skips measure from the last
valid position and clamp to the last known duration (never to an unknown one's zero); and the UI
tick drops an invalid reading whole rather than publishing zeroes. The local handle implements the
same `playWhenReady` — in steady state identical to `isPlaying`, and the right answer while
rebuffering.

**Buffering is shown, for a receiver as for a local stream.** The shared `playerEventListener` emits
`PlayerEvent.Buffering` from `onEvents` — `STATE_BUFFERING && playWhenReady`, on change only — for
both handles. It drives `PlayerUiState.isBuffering`, which puts the (polite-live-region) buffering
disc in place of the Play/Pause button, or the spinner when the chrome is hidden; the old "not while
casting" gate is gone. A receiver can sit there for minutes; a local rebuffer, previously never
shown at all, gets the same indicator.

**Reconnecting.** While the session is suspended the backdrop's chip reads "Reconnecting to <device>…"
(a polite live region while it says so), and returns to "Casting to <device>" on resume.

Cast messages (`CastTransferred`, `CastLeftSyncPlayGroup`, `CastPlaybackFailed`, `CastPlaybackStopped`) are plain
`PlayerMessage` entries; the screen formats each with the receiver's name off `PlayerUiState.cast`,
falling back to "your TV".

## What is deliberately not supported

| not supported | why, and where it is recorded |
|---|---|
| **Cast + SyncPlay together** | Mutually exclusive by decision. The button is hidden while in a group, and a session connected from system UI leaves the group with a message. (DECISIONS.md 2026-07-31, milestone entry, decision 4.) |
| **4K / HEVC beyond direct play** | *Partially lifted 2026-08-15 (M12 phase-2a).* Receivers are classified by **model name** (`CastReceiverClass` — the only capability signal a sender with the Default Media Receiver has; `CastDevice`'s flags say nothing about codecs). The Ultra / Google TV / SHIELD class direct-plays HEVC Main/Main 10 in mp4 up to 4K level 5.1 (Dolby Vision excluded via a `VideoRangeType` condition — it reports "Main 10" but needs a DV pipeline); "Chromecast HD" gets the same at 1080p; every unknown model keeps the old conservative profile byte-for-byte, and the session-start log line records `model → class` so a misclassified 4K device is a one-line allowlist fix. **The transcode target is still H.264+AAC HLS-ts in every class**: CAF's TS demuxer is H.264-only, and the fMP4 segments HEVC would need were device-measured broken on the reference Ultra — an HEVC fMP4 transcode for Google-TV-class receivers is phase-2b, gated on a device walk. (DECISIONS.md 2026-08-15; `CastDeviceProfile`, `CastReceiverClass`.) |
| **Surround audio (AAC 5.1, AC3/EAC3 passthrough)** | Device-measured, not assumed: a real Chromecast Ultra's Default Media Receiver rejects any AAC track above 2 channels with CAF error 104 in every container tried, and AC3/EAC3 5.1 passthrough fails outright (`LOAD_FAILED`). The profile caps AAC at stereo on both the transcode (`TranscodingProfile.maxAudioChannels`) and direct play (`CodecProfile` on `VIDEO_AUDIO` and `AUDIO`). A per-device-profile revisit is deferred to M12 phase 2 alongside the 4K/HEVC row above. (DECISIONS.md 2026-08-01; `CastDeviceProfile`.) |
| **Reattaching to a live session after process death** | If the app is killed mid-cast the receiver keeps playing and reporting simply stops; the server session goes stale until its own timeout. Accepted and documented for v1. (Milestone entry, decision 6.) |
| **Casting the copy on disk** | A downloaded item is re-resolved *remotely* and streamed from the server. Serving the local file to a receiver would mean running an HTTP server in the app. (Milestone entry, decision 7; `PlaybackSourceResolver`.) |
| **The decoder fallback ladder** | Every rung of it diagnoses *this device's* decoders. A receiver error surfaces as one message and stops. (Milestone entry, decision 5; DECISIONS.md 2026-07-31, "a cast playback failure reuses `PlayerMessage.PlaybackFailed`".) |
| **A mini-controller, a styled receiver, the Output Switcher** | Not in v1. The player screen is the remote control; the receiver id is a one-line change in `JellyboostCastOptionsProvider`. |
| **HLS-fMP4 transcode segments (`SegmentContainer=mp4`)** | Tried and ruled out, not merely unused. On the tested Chromecast Ultra it accepts the `LOAD` but never opens a media session — no playback, no error either — at both 2ch and 6ch. It is not a workaround candidate for the surround-audio row above; MPEG-TS is what stays. (DECISIONS.md 2026-08-01.) |

## The subtitle profile: WebVTT and nothing else

A `SubtitleProfile`'s format list decides what the server **converts a stream into**, not what it
accepts. Probed against the dev server (2026-07-31, item `e1a3302888b0d5fa1dfcc68a09a0208b`): with
`srt,subrip,vtt` declared, a `subrip` stream comes back as `…/Subtitles/4/0/Stream.subrip`; with only
`vtt`/`webvtt` declared, the very same stream comes back as `…/Stream.vtt`. The Cast Application
Framework parses WebVTT and TTML and has **no SRT parser**, so the wider list is strictly worse — it
hands the receiver a file it silently ignores.

Two things follow. The profile declares `vtt` and `webvtt` only, as `SubtitleDeliveryMethod.EXTERNAL`;
and `CastSpecMapper` announces every side-loaded track as `text/vtt` regardless of the codec the
local spec named, because that spec's MIME type is derived from the *source* stream — right locally,
wrong for a URL that now serves `.vtt`. Image subtitles (PGS, DVB) are never external in any profile
and are burned in by the server, reached through the same `false`-return renegotiation an
unsupported audio track takes. (DECISIONS.md 2026-07-31, "the cast profile asks for WebVTT only".)

## Devices with no Google Play services

One APK ships everywhere. Every `com.google.android.gms` type in the app lives inside
`player/.../cast/`, behind `CastAvailability`'s `GoogleApiAvailability` guard: on a device without
Play services `CastDeviceState` stays `Unavailable`, `CastRouteButton` returns before composing
anything, `GmsCastSessionMonitor` never registers, and `RoutingPlayerHandle`'s `Provider` is never
asked for a cast handle — so not one Cast class is ever loaded.

## Release build

`assembleRelease` was verified with R8 in full mode (2026-07-31): the reflectively-instantiated
`dev.jellyboost.player.cast.JellyboostCastOptionsProvider` survives **unrenamed** in the release dex
under the exact name the merged manifest's `OPTIONS_PROVIDER_CLASS_NAME` meta-data gives, kept by
the Cast framework's own consumer rules, and the merged release manifest still carries the meta-data.
**No cast-specific keep rule was needed**, which is why `app/proguard-rules.pro` has none — the file's
own rule is that a rule belongs there only when it was shown to be missing.

## Test coverage

`player/src/test/kotlin/dev/jellyboost/player/`:

| File | What it pins |
|---|---|
| `cast/CastSpecMapperTest` | The three things a cast session can get wrong invisibly: a token on the media URL and on every subtitle URL (and idempotence where the server already signed one) — and **not** on the poster, which needs none (audit CAST-06); an `external:<index>` id becoming the Jellyfin stream index the picker speaks, and an unaddressable id dropped rather than invented; the MIME type per play method (mp4 / webm / HLS) and the forced `text/vtt`; runtime, resume position and the live-source case; metadata passing through with its words untouched. |
| `cast/CastMetadataHolderTest` | Published metadata read back under its own id, nothing under another's, replacement when the queue moves on, and case-insensitive UUIDs. |
| `cast/CastDeviceStateTest` | The `CastState` int → `CastDeviceState` table, including the unknown-code case. |
| `cast/CastSessionCoordinatorTest` | Connect → routing flip + status; disconnect → stop report, `stopTranscoding` and the flip back; the detached ticker starting only when nobody is attached, and stopping when a screen takes over. A dropped item: the grace period respected (nothing at 9.9 s, nothing if the item comes back), the detached stop report sent once with the last held reading and the ticker cancelled, no second report when the session later ends, the attached screen told instead, the host read at the end of the grace period. Suspension published, cleared by the resume without re-running the transfer, ignored with nothing connected. |
| `cast/RemoteItemPresenceTest` | The edges only, once each; never armed by a load's invalid window or a placeholder glimpse, only by the item held while ready; the resume point is the last held reading; a new load clears and disarms. |
| `session/PlayerEventBridgeTest` | `Buffering` only while buffering *and* meaning to play, cleared by a pause while still buffering and by `READY`, said on change only. |
| `ui/PlayerTransportTest` | The local sibling: the toggle follows `playWhenReady` (a tap while rebuffering pauses), skips are relative and clamped to a known duration, an unknown duration is not clamped to zero, and a local rebuffer reaches the UI state without an `IsPlayingChanged(false)` undoing it. |
| `deviceprofile/CastDeviceProfileTest` | The codec/container/subtitle/bitrate table, the stereo AAC cap on the transcode and on both direct-play shapes (`VIDEO_AUDIO`, `AUDIO`), and the bitrate cap being the only thing `build` changes. |
| `session/RoutingPlayerHandleTest` | Delegation of every method, event switching through `flatMapLatest` (Turbine), snapshot routing, `stopInactive` touching only the handle that is not in charge, and a switch leaving the handle it left alone. |
| `ui/PlayerViewModelCastTest` | The system property, assembled from a real `RoutingPlayerHandle`, a real coordinator and a fake monitor: **exactly one stop report per source** across both transfers; stop-report-then-resolve ordering; `castTarget` on every re-negotiation (audio, subtitle, quality); a side-loaded subtitle never reaching the server; the fallback ladder bypassed; SyncPlay exclusivity; PiP disarmed; the speed picker following the receiver; the backdrop chain; the receiver's metadata published, a cast open waiting for it and a local open not. With an invalid receiver reading: pause pressed sends pause, a skip moves from the last valid position, nothing on screen changes; a receiver's buffering reaches the screen. A dropped item: waited out, announced, not reloaded, stop reported once at the last held position, Play re-sends it from there (or from where the scrubber was moved), and the detached case reported once across the session's end; a natural end is not reported again by the coordinator; a Wi-Fi blip shows as reconnecting and clears on resume. |
| `resolve/PlaybackResolveCastTargetTest` | The two things `castTarget` changes: the copy on disk is skipped, and the cast profile is the one sent. |

Every pre-M12 player test passes **unchanged** — that was the milestone's regression gate, and it is
what "a routing handle with no cast session is a pass-through" means in practice.

**Known gaps.** Everything that needs a real Chromecast is a device-verification item rather than a
unit test: CAF's acceptance of the server's HLS-ts flavour, the chooser dialog's theming, hardware
volume keys, the framework's own notification, and the minified build on a receiver. They are the DoD
walk in `docs/notes/chromecast-m12-plan.md` § Verification. `CastMediaItemConverter`,
`GmsCastSessionMonitor` and `CastPlayerHandle`'s GMS half are untested by construction — they are the
mechanical assembly the decisions were deliberately lifted out of.

Gaps specific to the drop/buffering/reconnect handling (2026-09-27):
- **Device walk owed**: Stop from the television mid-film (screen open and backed out of), a
  receiver buffering a slow transcode, a Wi-Fi blip — none has been seen end to end since the fix.
- **The grace period and the "held while ready" arming are reasoned, not measured.** If a receiver
  takes longer than 10 s to re-report an item it merely blinked out of, the screen would call it
  stopped; the Play button then re-sends it.
- **A load that never lands is not detected** — only an item the receiver was seen playing can be
  "dropped". A failed load normally arrives as a receiver error (`CastPlaybackFailed`).
- **The buffering disc is not tappable** (unchanged from local playback, where it covers the open):
  a receiver buffering for minutes cannot be paused from the screen, only from the keyboard shortcut
  or by disconnecting.
- **While reconnecting, taps are still dropped** — the chip now says why. The Cast notification's
  own Play/Pause state during buffering is the framework's, and not addressed here.
