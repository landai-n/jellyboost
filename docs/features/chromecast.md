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
| `JellyboostCastOptionsProvider` | The framework's configuration, instantiated **reflectively** from the `OPTIONS_PROVIDER_CLASS_NAME` meta-data in `:player`'s manifest (the merger carries it into `:app`). Default receiver id, `setResumeSavedSession(true)`, `NotificationOptions` targeting **`CastNotificationActivity`** (the trampoline below, never the launcher activity itself), and deliberately **no** expanded-controller activity — the app's own player screen is the remote control. |
| `CastNotificationActivity` + `CastNotificationIntents` | The Cast notification's tap target: a `Theme.NoDisplay` trampoline with its own `taskAffinity`, `noHistory` and `excludeFromRecents`, so the framework's `CLEAR_TASK` clears only its own task. It starts the launcher activity (resolved at runtime with `getLaunchIntentForPackage`, so `:player` needs no dependency on `:app`) with `NEW_TASK | SINGLE_TOP` and `ACTION_OPEN_CASTING_PLAYER`, and finishes. `CastNotificationIntents` is the contract `MainActivity` reads. See *Getting back to the television*. |
| `CastNowPlaying` / `CastingItem` | **The only cast surface `:app` reads.** A GMS-free `StateFlow<CastingItem?>` — non-`null` exactly while a session is connected and the coordinator holds a detached source — with the title/artwork captured at detach, the device name, `isReconnecting`, the receiver's intent (`playWhenReady`), `isBuffering`, and a position polled once a second while anyone is collecting (`WhileSubscribed`). Plus `togglePlayPause()` and `current()` (read fresh, for a notification tap). |
| `CastAvailability` | The single door to Google Cast. Owns the process-wide `CastContext`, created once from `MainActivity.onCreate` behind a `GoogleApiAvailability` guard, and publishes `CastDeviceState` (`Unavailable / NoDevices / Available / Connecting / Connected(name)`) — a GMS-free view the UI can observe on a device that has no Cast stack at all. `castDeviceStateOf` is the pure mapping, tested on its own. |
| `CastSessionMonitor` + `GmsCastSessionMonitor` | "A receiver appeared", "it went away", with no Cast type in the signature — the seam that makes the coordinator unit-testable. Waits for `CastAvailability` before registering, reports an **already-connected** session as a start (the framework does not replay it, and connect-then-play is the everyday case), and folds `onSessionResumed` into the same event. `onSessionSuspended` (a Wi-Fi blip) is surfaced as its own event rather than ignored. |
| `CastSessionCoordinator` | `SyncPlayController`-shaped `@Singleton`, started from `JellyboostApplication`: flips `RoutingPlayerHandle`, stops the player being left, publishes `CastStatusHolder`, keeps the progress ticker running on `@DetachedPlayerScope` when no screen is attached, and sends the final stop report (which kills the transcode) when a session ends with nobody watching. A start for an already-connected session (a resumed/suspended one, or the monitor's start-time replay) is dropped rather than re-run as a transfer (audit CAST-05); a detaching screen's source is only remembered while a session is live, so a failed cast attempt cannot stop-report a film that was never cast (audit CAST-02); and the cast player is stopped once the session ends, clearing its listener and stale media (audit CAST-08). It also judges a receiver that **drops the item** while the session stays up: `PlayerEvent.RemoteItemMissing` starts a 10 s grace period (`ITEM_LOST_GRACE`), `RemoteItemMissingCleared` cancels it, and at the end of it the attached screen is told (`CastPlaybackHost.onCastItemLost`) or — with none — the coordinator sends the stop report itself and stops its ticker (see *Who reports to the server*). A suspension marks the connection `suspended` until the framework's resume, which arrives as a repeated start. Since the casting bar: it publishes the detached source (`detached`, with the item's metadata captured as the screen went), answers `heldSourceFor(itemId)` for a screen that may **adopt** it, reports an **orphan**'s stop in `attachHost` (a detached source the attaching screen did not adopt), follows the receiver's buffering, and remembers the last valid reading taken for the detached source (`readReceiver`). |
| `CastStatusHolder` | The one fact the rest of the app needs — `isCasting`, plus the device name and whether the session is `suspended` — modelled on `SyncPlayStatusHolder`. It is what keeps every `com.google.android.gms` type out of `PlayerViewModel`. A suspended session is still `Connected`: the receiver plays on and the next open still casts. |
| `CastMetadataHolder` | The other direction: what the *television* should say this is. A `PlaybackInfo` response names nothing, so the ViewModel's item fetch publishes title, episode line and poster here, keyed by media id, and `CastPlayerHandle` reads it at prepare. |
| `CastPlaybackHost` / `CastPlaybackCoordinator` / `NoCastPlaybackCoordinator` | The public attach/detach seam between the coordinator and a screen, plus the two transfer callbacks. Names only `PlaybackMediaSource` and `PlaybackSnapshot`. |
| `session/RoutingPlayerHandle` | **The Hilt `PlayerHandle` binding.** Delegates to whichever player is live; `events` through `flatMapLatest`, `player` (the video surface) `null` while casting, `stopInactive()` to silence the one being left. With no cast session it is a pass-through with no branch in it — which is what makes "casting changed nothing about playing alone" a property of the code. The cast handle arrives through a `Provider` so a GMS-less device never constructs one. |
| `CastPlayerHandle` | `PlayerHandle` over media3-cast's `CastPlayer`. No surface and no `PlaybackService` (the framework publishes its own media session and notification). `selectAudioTrack` always `false`; `selectSubtitleTrack` uses `RemoteMediaClient.setActiveMediaTracks` for a side-loaded VTT — claiming `true` only when the receiver's own `MediaStatus` still offers the track, and logging async rejections (audit CAST-03) — and `false` otherwise; `snapshot()` is only valid while the receiver still holds our item (audit CAST-01); `playWhenReady` is the receiver's intent, readable when the snapshot is not; `supportsPlaybackSpeed` asks the receiver. Every reading it takes — from `snapshot()` and at the end of every callback batch — goes through `RemoteItemPresence`. |
| `RemoteItemPresence` | Pure. Turns the handle's readings into the two edges `PlayerEvent.RemoteItemMissing(lastHeld)` / `RemoteItemMissingCleared`. **Armed only once the receiver has held the item in `READY` since the last load**, so the several seconds after every `prepare` in which the reading is invalid — and a placeholder that briefly claims the item — never raise it. Also judges the receiver's `IDLE`/`FINISHED` status (`onFinished`): the armed item's finish is an end — `PlayerEvent.Ended` once, and an ended reading until the next load — never a drop. |
| `CastSpecMapper` | Pure. `PlaybackMediaItemSpec + RemotePlaybackMediaSource + CastMetadata → CastMediaSpec`: the `ApiKey` on every URL the receiver fetches, the content type it will not sniff, and subtitle ids renumbered onto Jellyfin stream indices. All the decisions live here, which is why this is what the tests cover. |
| `HlsSegmentSnap` | Pure. The server's HLS segment length for a frame rate (`ceil(3 × fps) / fps`) and the load start that stays within 1 s of its segment's start — see *A start late in a segment*. |
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
   VP8/VP9 in `webm` direct; anything else an HLS **ts** transcode to H.264 + stereo AAC. Subtitles:
   WebVTT external, everything else burned in. The H.264/HEVC level and size caps are codec
   profiles with **no container** (as in `DeviceProfileBuilder`), so they bound the `ts` transcode
   as well as the `mp4` direct play — the transcode comes out at ≤ 1920×1080, level ≤ 4.2 whatever
   the source was. Beside the profile, a cast `PlaybackInfoDto` sends
   **`allowVideoStreamCopy = false`**: a cast transcode always re-encodes its video (the server
   appends `allowVideoStreamCopy=false` to the `TranscodingUrl` itself; direct play and direct
   stream never consult it; audio copy is untouched). Why is under "Known gaps / measured" below.

   **Auto on a television.** A cast Auto request is not measured (the receiver's link is not this
   device's), so pass 1 goes out uncapped — the profile's 120 Mbps. A direct play keeps that. A
   **transcode** is re-negotiated once at `PlaybackQuality.HIGH`'s 20 Mbps rung, exactly as local
   Auto is (`PlaybackInfoResolver.negotiateUnderTranscodeCeiling`); without it the transcode URL
   asked the encoder for `VideoBitrate=119616000`. A hand-picked quality is never touched.
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
| **cast → local** | The last snapshot is read off the cast player *before* the routing goes home, and the film reopens on this device **paused** at that position — or, when that snapshot is invalid (the receiver had already gone, as after a Disconnect from the Cast notification), at the session's last valid reading (see *Who reports to the server*). Paused because a disconnect is not a request to watch: the user pulled the plug or left the room. |
| **in a SyncPlay group** | The group is left first, and its message wins the snackbar — moving to a television is visible a second later, while being thrown out of a group is not. |

Both go through `openSession(..., endingAt = snapshot)` rather than `reopenSession`, because a
re-negotiation reads the *current* player for its resume position and `playWhenReady`, and across a
routing flip both readings are the wrong player's.

## Getting back to the television

Leaving the player while casting leaves the television playing — the coordinator's detached ticker
keeps the server informed — and three things lead back to it. (DECISIONS.md 2026-09-27, "a casting
bar in the chrome, reattach instead of reload, and a notification that keeps the back stack".)

**The casting bar.** Whenever the coordinator holds a detached source (a session is connected and no
player screen is attached), `AppScaffold` docks a bar in the music mini-player's slot: the item's
artwork and title, "Casting to <device>" ("Reconnecting to <device>…" while suspended), a progress
line, one play/pause button that drives the receiver, and a tap that opens the player for the item.
It is the `MiniPlayer`'s own surface and row (`MiniPlayerSurface` / `MiniPlayerRow`, shared), fed by
`CastNowPlaying` instead of the music queue — no new visual design. It is hidden on the player (that
screen is the remote control) and on Now Playing, as the music bar is. **Cast wins the slot**: music
never casts, but it can play on this device once the film's screen has closed, and then only the
casting bar shows (`showsMiniPlayer(castingBarShown = …)`). The button follows the player's honest
transport: it reverses the receiver's **intent**, and buffering keeps a Pause action with a ring
round it. Spoken as one sentence ("<title>, Casting to <device>", with the tap "Open player") plus
the separate button; the button says "Buffering" as a polite live region while buffering, and the
row is a polite live region while reconnecting.

While the receiver is **letting go of the item** (the 10 s grace period below,
`CastSessionCoordinator.isLosingItem`), the receiver's intent belongs to whatever it holds now, which
may be another sender's media. The coordinator refuses the bar's toggle then. `CastingItem.receiverLetGo`
makes the button "Play", with its click labelled "Open player" (`CastingBarAction.OPEN_PLAYER`), and a
tap opens the player. Nothing is adopted during the grace, so the player sends the film back from the
bar's position, playing. **When the bar goes on its own**, the chrome's snackbar names why
(`castingStopped`, `CastingStoppedEffect`), a polite live region: the receiver dropping the film, the
item going missing, or the cast session simply ending, all say "Playback stopped on <device>"; the
receiver playing the film to its end on its own says "Finished on <device>" instead
(`CastExitReason`, set on `CastSessionCoordinator.lastDetachExit` at the same three sites that clear
the detached source with no screen open, read once from `CastNowPlaying.lastExitReason()` at the
instant the bar's item turns `null`). Opening the player is the one silent exit.

**Reattach instead of reload.** Opening the player for the item the receiver already holds — from
the bar, Resume, the detail page or the notification — no longer negotiates it again. Before, a new
`PlaybackInfo` and a second `prepare` stopped the receiver, rebuffered it and started a second
server transcode. `PlayerViewModel` first asks `heldSourceFor(itemId)`; **"the receiver holds X"**
means: the coordinator's detached source *is* X (compared as a `UUID`, so case never matters), the
session is connected, no dropped-item grace period is running, and the receiver's reading is valid
**or** it is buffering. Then the screen adopts that source (`adoptCastSession`): no `PlaybackInfo`,
no `prepare`, no start or stop report; it attaches with that source as its `castSource`, which
`attachHost` recognises as **the same load** (`PlaybackMediaSource.isSameLoadAs`: item, media source and
play session — not the selected tracks, which a track chosen in place changes on a copy), stops the coordinator's ticker and hands reporting to the
screen's under the same play session id. The position shown is the receiver's live reading. Anything
else — another item, a receiver that let go, nothing held — opens as before, and the source it
replaces on the receiver is an **orphan**: `attachHost` reports its stop exactly once, at the last
valid reading anyone took for it (seeded as the screen left, refreshed by the detached ticker and
the bar), since by then the receiver's reading belongs to the new item.

**The notification.** The framework fires the notification's content intent with
`NEW_TASK | CLEAR_TASK | TASK_ON_HOME`; aimed at `MainActivity` that wiped the back stack (the
player screen included) and landed on Home. It now targets `CastNotificationActivity`, whose own
task affinity confines the `CLEAR_TASK`; it reopens the app as it was left (`NEW_TASK | SINGLE_TOP`)
with `ACTION_OPEN_CASTING_PLAYER`. `MainActivity` turns that into `MainViewModel.openCastingPlayerRequested`
(in `onCreate` on a fresh start — a Recents relaunch is ignored — and in `onNewIntent`), a state
rather than an event so a tap during the splash waits for the graph. `AppScaffold` routes it
(`castNotificationRoute`, pure): the casting item's player (reattaching as above), left alone if a
player for it is already on top and replacing a player for another item; nothing if the player on
top is the attached one — it *is* the remote control; Home if nothing is cast; nothing while signed
out.

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
| Receiver plays the film to its end, screen open | stopped by the screen's `onEnded` | the screen, once, **as ended** (played on the server); up next advances on the receiver, or the screen closes |
| Receiver plays the film to its end, no screen | stopped by the coordinator | the coordinator, once, **as ended** (`onReceiverFinished`); `detachedSource` is cleared as it is sent and the bar goes |
| Screen reopened for the item the receiver holds (reattach) | handed from the coordinator to the screen, same play session | **none** — the session continues; the screen reports it later like any other |
| Screen opened for anything else while a source is detached | the coordinator's stops at attach | the coordinator, once, for the **orphan**, at its last valid reading, in `attachHost` |
| Session ends with a screen attached, receiver already gone (Disconnect from the Cast notification) | stopped by the screen's `endCurrentSource` | the screen, once, at the session's **last valid reading** (`ActiveSession.lastValidReading`, seeded from the coordinator on reattach); the film reopens locally, paused, at that same position, and its start report carries it |
| Session ends with no screen, receiver already gone | stopped by the coordinator | the coordinator, once, at the **last valid reading** held for the source (`readReceiver`, or the screen's own last reading handed over at detach) |
| Any stop with no valid reading ever taken | — | carries no position and is sent **`failed = true`**, so the server leaves the user's data alone |

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
The screen does not wait out the grace period to stop acting on the receiver. From
`RemoteItemMissing` on (`ActiveSession.receiverLetGo`) the label says Play, and Play re-sends the film,
first closing the old session at its last vouched reading. A skip moves only the resume point, and the
receiver's buffering is not drawn. `RemoteItemMissingCleared`, or any new load, ends this.

**A film played to its end is an end, not a drop (2026-09-27, second review).** media3-cast 1.9.0's
`RemoteCastPlayer.fetchPlaybackState` only ever produces IDLE, BUFFERING and READY — never
`STATE_ENDED` — so `PlayerEvent.Ended` never fired for a cast, and a finished film left the queue
exactly like a stopped one: the grace period ran out, the screen said "Playback stopped on <device>"
and offered a Play that re-sent the film, up next never advanced, and the stop went out positioned
rather than ended. `CastPlayerHandle` now reads the receiver's own `MediaStatus` (through
`RemoteMediaClient`, since `CastPlayer` folds it into IDLE): `PLAYER_STATE_IDLE` with
`IDLE_REASON_FINISHED`. `RemoteItemPresence.onFinished` decides whether that finish is **ours**: the
item must have been armed (held while ready) since the last load — a receiver keeps saying `FINISHED`
for the film before while the next one loads, and that can never arm — and the status must name the
loaded item (its content id or URL is the load's stream URL), or name nothing while the item was still
held. Then `PlayerEvent.Ended` goes out once and every reading until the next load is the ended one,
at the item's duration: valid, so presence never reports it missing (a drop noticed a moment before
the finish status is cleared first). With a screen attached, `PlayerViewModel.onEnded` does what it does
for a local film (ended stop, up next); with none, the coordinator's `onReceiverFinished` sends the one
stop, at the ended reading, and forgets the source. Not device-verified yet: whether a Default Media
Receiver's finished status still carries `mediaInfo` decides which of the two naming rules applies.

One guard sits under all three rows (audit CAST-01): a reading taken off the cast player is only
*valid* while the receiver still holds our item (`PlaybackSnapshot.isValid`). A Stop pressed on the
television or another sender loading its own media leaves the session alive and `CastPlayer`
answering zero — or the other app's position; `PlaybackReporter` skips such a progress tick
entirely, and a stop with an invalid reading still closes the server session and kills the encoder
but carries no position and writes nothing locally.

**A stop never goes out positionless when anyone read a valid position (2026-09-27, data-loss
fix).** The server does *not* treat a stop without `PositionTicks` as "position unknown": with
`Failed = false` it takes it as played to the end and resets the item's resume position to **0**
(the server logs "Stopped at unknown"). Found on a device: a reattached film disconnected from the
Cast notification at about 27:20 had its resume position wiped. So, in order: every stop is sent at
the last **valid** reading taken for its source — the screen keeps one per session
(`ActiveSession.lastValidReading`, fed by the UI tick and the reporting ticker, read synchronously
as the session ends, seeded from the coordinator's `CastReceiverHold.lastValidReading` on
reattach and handed back via `CastPlaybackHost.lastValidReading` at detach), the coordinator keeps
one for the detached source (`readReceiver`); only a session that never produced a valid reading at
all sends a positionless stop, and `PlaybackReporter.reportStop` then flags it `failed = true` so
the server writes nothing. The film brought home after a session ends reopens at that same last
valid position — never at the source's `startPositionTicks`, which for a reattached film is where
it was first sent, not where it is — and a re-negotiation while the receiver's reading is invalid
resumes from it too.

**…and a zero is not a valid reading just because it says so (2026-09-27, second device fix).** The
rule above did not survive the device: the reattached film still came home "at 0 ticks" and the stop
carried 0. The final reading was not *invalid* but a **valid-looking zero**. media3's
`RemoteCastPlayer` handles a session ending in `setCastSession(null)`, which drops its
`RemoteMediaClient` **without** updating its timeline or state, so `CastPlayerHandle.holdsLoadedItem()`
still finds our item and `currentPosition` falls back to `lastReportedPositionMs`, the last progress
callback (zero once the receiver has stopped). The idle local player routing falls back to answers a
valid zero as well. Three layers now:
- `CastPlayerHandle.reading()` is invalid once the handle has no `RemoteMediaClient`: nothing a
  torn-down player says is live.
- **The zero rule** (`PlaybackSnapshot.contradicts`): a valid, not-ended reading at position 0, after
  a valid reading further in, is treated as invalid — **only where no one can vouch for the reading**
  (narrowed after the wave's review). In the screen (`PlayerViewModel.vetted`) that is a **receiver's**
  reading for a session opened on the receiver (`ActiveSession.onReceiver`); the screen's own `seekTo`
  moves the anchor, so a seek to 0 stands. A reading from **the other player** (the idle local player
  after routing fell back, whatever its position, or a receiver's first readings after a transfer to it)
  is invalid outright. A **local** player playing its own session is never second-guessed: SyncPlay
  commands and media-session / notification / Bluetooth seeks move it without passing through `seekTo`,
  and its zero is real. In the coordinator the rule applies only to the **session end's** final read and
  a detached dropped item's last held reading. The detached ticker and the casting bar record a receiver
  restarted from the television's remote as it is.
- The last valid reading is preferred, as above.

**The detached source's readings must be about it (2026-09-27, review).** The cast handle judges
validity against its *newest* load. A new screen's open loads its own film on the receiver before it
attaches: `PlayerViewModel.publish` suspends in the start report before `cast.attach()`. In that window
the bar's poll and the detached ticker used to record (and report) the new film's position as the
held one's, and the orphan's stop carried it. `PlayerHandle.preparedSource` (tracked by
`CastPlayerHandle`, delegated by `RoutingPlayerHandle`) names the source behind the handle's readings.
`CastSessionCoordinator.readReceiver` takes a reading as the detached source's only when that is the
load the coordinator holds (`isSameLoadAs`, as `attachHost` compares), and a buffering receiver holds
the detached source only on the same condition.

**The same load, not the same instance (2026-09-27, second review).** Both comparisons were identity
(`===`) at first. A subtitle turned off or a side-loaded subtitle picked while casting succeeds *in
place* (`CastPlayerHandle.selectSubtitleTrack` returns `true`), and `PlayerViewModel` then replaces
`session.source` with a `withSelectedSubtitle` copy while `preparedSource` keeps the loaded instance.
The screen leaving handed the coordinator the copy, and from then on every detached reading was
invalid: the casting bar and the detached ticker froze at the detach position, `heldSourceFor` answered
`null` (a reopen reloaded the film and rewound the television), `attachHost` reported the adopted source
as an orphan, and the session's end reported the stale position, moving the server's resume position
backwards. `isSameLoadAs` compares what a load *is* — item, media source, play session (for a file on
disk, its URI) — and leaves out the selected tracks, so an in-place change is the same load while any
re-negotiation (always a new play session) is not. The audio path has the same shape; it cannot occur
today (`selectAudioTrack` is always `false` on a receiver) and is covered anyway.

**Loads carry the intent, and the transport's label is the tap's rule (2026-09-27, review).** A
re-negotiation (`reopenSession`), its failure recovery (`onResolveFailed`) and the local→cast handover
load with the player's **intent** (`playWhenReady && !isSettledPaused`, the handover's read by the
coordinator off the local player before routing moves and passed as `onCastStarted(…, playWhenReady)`),
never `snapshot().isPlaying`. That is `false` during a receiver's invalid window and while anything
buffers, and `openForCast` honours the flag it is given. The label follows the same rule as the tap
(`tapPlays`, which `togglePlayWhenReady` runs). The casting bar gets `CastingItem.isSettledPaused` and
`castingBarAction(…, isSettledPaused)`. The player screen gets `PlayerUiState.receiverSettledPaused`,
so a receiver settled paused under a stale `playWhenReady = true` shows Play, the action its tap takes.
Since review 2, the screen also carries `PlayerUiState.playWhenReady`, and `showsPlaying` is exactly
`!tapPlays(playWhenReady, receiverSettledPaused)`, or Play while the receiver has let go. It never reads
`isPlaying`, which disagreed with the tap during a transient audio-focus loss and during the item-lost
grace period.
Both cast status lines (the bar's row and the player's backdrop label) stay a polite live region from
the first reconnect on (`rememberCastStatusIsLive`), so the return to "Casting to <device>" is announced
too.

What the `POST /UserItems/{id}/UserData` after every progress tick is: `PlaybackReporter.reportProgress`
also calls `UserDataRepository.setPosition`, which writes the local row (`toBeSynced`) and immediately
pushes the whole row (`pushFullState`: position, played, favourite, last played) to the server. It writes
whatever position the tick carried, so a zero tick wrote zero twice, once as progress and once as user
data. Vetted ticks never carry that zero now, and a home reopen starts at the last valid position, so
its own ticks carry that position.

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
rebuffering. One exception, shared by the screen and the casting bar (`togglePlayWhenReady`): a
receiver that reports itself `PAUSED` under a `playWhenReady` still `true` (a `play` dropped
mid-load) is sent play, not a no-op pause; and a cast open sets `playWhenReady` *before*
`setMediaItem`, because `CastPlayer`'s load takes its autoplay from the value current at that call.

**Buffering is shown, for a receiver as for a local stream.** The shared `playerEventListener` emits
`PlayerEvent.Buffering` from `onEvents` — `STATE_BUFFERING && playWhenReady`, on change only — for
both handles. It drives `PlayerUiState.isBuffering` (set on open only when the open means to play),
which draws a progress ring round the transport's **Pause** button — still a working button, since
a receiver can sit there for minutes and must stay pausable from the screen. It speaks "Pause" with
the state "Buffering", the state announced through a polite live region. With the chrome hidden the
centre spinner shows instead (non-interactive; a tap brings the controls back). The old "not while
casting" gate is gone, and a local rebuffer, previously never shown at all, gets the same treatment.

**Reconnecting.** While the session is suspended the backdrop's chip reads "Reconnecting to <device>…"
(a polite live region while it says so), and returns to "Casting to <device>" on resume.

Cast messages (`CastTransferred`, `CastLeftSyncPlayGroup`, `CastPlaybackFailed`, `CastPlaybackStopped`) are plain
`PlayerMessage` entries; the screen formats each with the receiver's name off `PlayerUiState.cast`,
falling back to "your TV".

## What is deliberately not supported

| not supported | why, and where it is recorded |
|---|---|
| **Cast + SyncPlay together** | Mutually exclusive by decision. The button is hidden while in a group, and a session connected from system UI leaves the group with a message. (DECISIONS.md 2026-07-31, milestone entry, decision 4.) |
| **4K / HEVC beyond direct play** | *Partially lifted 2026-08-15 (M12 phase-2a).* Receivers are classified by **model name** (`CastReceiverClass` — the only capability signal a sender with the Default Media Receiver has; `CastDevice`'s flags say nothing about codecs). The Ultra / Google TV / SHIELD class direct-plays HEVC Main/Main 10 in mp4 up to 4K level 5.1 (Dolby Vision excluded via a `VideoRangeType` condition — it reports "Main 10" but needs a DV pipeline); "Chromecast HD" gets the same at 1080p; every unknown model keeps the old conservative profile byte-for-byte, and the session-start log line records `model → class` so a misclassified 4K device is a one-line allowlist fix. **The transcode target is still H.264+AAC HLS-ts in every class** — since 2026-09-27 always a full video re-encode at ≤ 1080p / level 4.2, never a stream copy (see "Known gaps / measured" below): CAF's TS demuxer is H.264-only, and the fMP4 segments HEVC would need were device-measured broken on the reference Ultra — an HEVC fMP4 transcode for Google-TV-class receivers is phase-2b, gated on a device walk. (DECISIONS.md 2026-08-15; `CastDeviceProfile`, `CastReceiverClass`.) |
| **Surround audio (AAC 5.1, AC3/EAC3 passthrough)** | Device-measured, not assumed: a real Chromecast Ultra's Default Media Receiver rejects any AAC track above 2 channels with CAF error 104 in every container tried, and AC3/EAC3 5.1 passthrough fails outright (`LOAD_FAILED`). The profile caps AAC at stereo on both the transcode (`TranscodingProfile.maxAudioChannels`) and direct play (`CodecProfile` on `VIDEO_AUDIO` and `AUDIO`). A per-device-profile revisit is deferred to M12 phase 2 alongside the 4K/HEVC row above. (DECISIONS.md 2026-08-01; `CastDeviceProfile`.) |
| **Reattaching to a live session after process death** | If the app is killed mid-cast the receiver keeps playing and reporting simply stops; the server session goes stale until its own timeout. The in-process reattach above does not cover it: the detached source dies with the process, so after a restart there is no casting bar and the notification opens Home. Accepted and documented for v1. (Milestone entry, decision 6.) |
| **Casting the copy on disk** | A downloaded item is re-resolved *remotely* and streamed from the server. Serving the local file to a receiver would mean running an HTTP server in the app. (Milestone entry, decision 7; `PlaybackSourceResolver`.) |
| **The decoder fallback ladder** | Every rung of it diagnoses *this device's* decoders. A receiver error surfaces as one message and stops. (Milestone entry, decision 5; DECISIONS.md 2026-07-31, "a cast playback failure reuses `PlayerMessage.PlaybackFailed`".) |
| **A styled receiver, the Output Switcher** | Not in v1. The receiver id is a one-line change in `JellyboostCastOptionsProvider`. (The mini-controller that used to share this row landed as the casting bar, 2026-09-27.) |
| **HLS-fMP4 transcode segments (`SegmentContainer=mp4`)** | Tried and ruled out, not merely unused. On the tested Chromecast Ultra it accepts the `LOAD` but never opens a media session — no playback, no error either — at both 2ch and 6ch. It is not a workaround candidate for the surround-audio row above; MPEG-TS is what stays. (DECISIONS.md 2026-08-01.) |

## Known gaps / measured: stream copy and restarted transcodes

**Measured 2026-09-27, Jellyfin 10.11.11, Chromecast Ultra on the Default Media Receiver.** A typical
library file — mkv, H.264 1080p, EAC3 5.1 — cannot be direct-played by the cast profile, so the
server builds an HLS-ts transcode. Left to itself it **stream-copies the video** and converts only
the audio. For a copy, the server lays `main.m3u8` out on the file's real keyframes (uneven segments,
roughly 1.4–14 s). But once ffmpeg is (re)started mid-file with `-ss` — every resume, every
local↔cast transfer, every seek past the encoded range — it cuts at `-hls_time` counted from its
restart point, so the segment files **stop matching the playlist**: some listed segments are never
produced (a request is answered with a later segment's content), others have a different duration
and start than listed. The receiver trusts the playlist and sits in `BUFFERING` forever; this was
reproduced four times, at four different positions.

Requesting the same stream with no video stream copy, a 20 Mbps video bitrate and a 1920 max width
makes the server re-encode (hardware, many times realtime on the test server), and the playlist
becomes fixed-length segments that match the files exactly, even when starting mid-film. "Fixed" means
a whole number of frames, so the length **depends on the frame rate**: `ceil(3 × fps) / fps` — 3.000 s at
24 fps (72 frames) and 25 fps (75), 3.003 s at 23.976 fps (72); restarts land on that grid (`-ss N × 3.003` for a 23.976 fps film, with `-g 72` either way). That is
what every cast transcode now asks for (DECISIONS.md 2026-09-27). The cost: a transcode that could
have been a cheap copy now occupies the encoder, and a server without hardware encoding may not
keep up with 1080p — `PlaybackQuality` below High is the lever there. Local playback is unchanged:
ExoPlayer was never measured stalling on the same shape. **Owed:** a device walk on a real
Chromecast confirming resume, transfer and seek on such a file (STATUS.md).

## Known gaps / measured: a start late in a segment

**Measured 2026-09-27, same setup.** Even on the re-encoded grid above, a load whose start falls in the
last ~0.5 s of a segment stalls in `BUFFERING` forever. A 24 fps film (3.000 s segments): starts
2.881 s and 2.590 s into their segments stalled; starts 0.648 s, 0.711 s (an episode), 1.307 s, 1.59 s
and 1.88 s in played — the last two were −10 s seeks that recovered the stalls. The server restarts
ffmpeg at the segment's start (`-ss N × segment -start_number N`) and those segments are correct; the
job the receiver starts first (for `0.ts`, no `-ss`) was probed at a +83 ms timestamp shift against the
restarted ones, which likely pushes a late target past the first restarted segment's audio.

**The fix: a transcoded HLS load starts at most 1 s into its segment** (`HlsSegmentSnap`, applied by
`loadOnReceiver`): `offset = pos mod segment; start = pos − offset + min(offset, 1 s)`, so a load is
never later than asked and at most ~2 s earlier (never at 0 unless asked, which the zero rule would
read as a torn-down receiver). The same snapped start goes into the load and the queue item's start
time. It applies only when `CastSpecMapper` can name the grid (`CastMediaSpec.hlsSegmentMs`): a
`TRANSCODE` HLS source with a known runtime whose `TranscodingUrl` carries the server's
`allowVideoStreamCopy=false` (a copy is laid out on keyframes, not a grid) and no `Framerate` /
`MaxFramerate` of its own, and whose video stream has a known frame rate
(`RemotePlaybackMediaSource.videoFrameRate`: `RealFrameRate`, else `ReferenceFrameRate`, else
`AverageFrameRate`). The segment length is `ceil(nominal × fps) / fps`, nominal 3 s or the URL's
`SegmentLength`. Direct play, direct stream and an unknown frame rate load exactly where asked. No
extra request is made before the load. (DECISIONS.md 2026-09-27, "a cast transcode starts early in its
segment".)

**Reported positions stay honest.** The player's position tracker opens at the requested start, and
from the receiver's first valid reading on everything — scrubber, progress, `lastValidReading`, the
stop — follows the receiver, which is at the snapped start: up to ~2 s earlier than asked, and where
the film really is. Nothing compares a reading with the requested start.

**Not snapped: seeks.** An app-initiated seek while casting a transcode goes to the receiver as asked.
No seek was measured stalling (the two measured seeks were the recoveries, landing early in their
segments), and a seek inside the encoded range does not restart ffmpeg. **Follow-up:** a device walk
seeking far ahead (past the encoded range) to a position late in a segment; if it stalls, apply the
same snap in `CastPlayerHandle.seekTo` for a spec with `hlsSegmentMs`.

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
| `cast/CastSpecMapperTest` | The three things a cast session can get wrong invisibly: a token on the media URL and on every subtitle URL (and idempotence where the server already signed one) — and **not** on the poster, which needs none (audit CAST-06); an `external:<index>` id becoming the Jellyfin stream index the picker speaks, and an unaddressable id dropped rather than invented; the MIME type per play method (mp4 / webm / HLS) and the forced `text/vtt`; runtime, resume position and the live-source case; metadata passing through with its words untouched; the server's `allowVideoStreamCopy=false` surviving on the transcode URL the receiver gets. |
| `cast/HlsSegmentSnapTest` | The segment length at 24, 25, 23.976, 29.97 and 59.94 fps (the 23.976 grid checked against the server's own `-ss` restarts) and none for an unknown rate; the two measured stalls moved to 1 s into their segments; early starts, exact boundaries (0, 1 s, 1 s + 1 ms, the last millisecond, the next segment), the 3.003 s grid used rather than 3.000 s; never later, never ≥ 2 s earlier, never 0; no segment length, no snap. |
| `cast/CastSpecMapperTest` › a start late in an HLS segment | The segment grid only for a re-encoded HLS transcode with a known frame rate and runtime (not direct play, not without `allowVideoStreamCopy=false`, not with a URL frame rate), `SegmentLength` honoured; a transcode load late in a segment carries the snapped start in both the player's position and the queue item; an early transcode start and a direct-play start load where asked. |
| `resolve/PlaybackInfoResolverFrameRateTest` | The source's `videoFrameRate` is the video stream's `RealFrameRate`, else its average; none when nothing plausible is reported (0, an average of 1000 fps, no video stream). |
| `cast/CastMetadataHolderTest` | Published metadata read back under its own id, nothing under another's, replacement when the queue moves on, and case-insensitive UUIDs. |
| `cast/CastDeviceStateTest` | The `CastState` int → `CastDeviceState` table, including the unknown-code case. |
| `cast/CastSessionCoordinatorTest` | Connect → routing flip + status; disconnect → stop report, `stopTranscoding` and the flip back; the detached ticker starting only when nobody is attached, and stopping when a screen takes over. A dropped item: the grace period respected (nothing at 9.9 s, nothing if the item comes back), the detached stop report sent once with the last held reading and the ticker cancelled, no second report when the session later ends, the attached screen told instead, the host read at the end of the grace period. Suspension published, cleared by the resume without re-running the transfer, ignored with nothing connected. |
| `cast/CastSessionCoordinatorTest` › reattach and orphans | "Holds" pinned from every side: a valid reading, the id in any case, a buffering receiver (held) vs an invalid, non-buffering one (not), another item, the drop grace period, no session or a screen attached (not). Adoption sends no report and stops the ticker; an orphan — another item, or the same item renegotiated — gets exactly one stop at its last valid reading (the freshest `readReceiver`, not the detach one) and none again at session end. The detached item's metadata is captured at detach and survives the holder being overwritten; it goes with a drop and with the session; the bar's toggle acts only with no screen attached, and plays a receiver settled paused under a stale intent; receiver buffering is followed while casting only. |
| `cast/CastNowPlayingTest` | The bar's state over a real coordinator: nothing without a detached source or with a screen attached; title, artwork, device, intent and position; the position following the receiver while collected and keeping the last one over an invalid reading; the toggle reflected at once; buffering and reconnecting; gone on reattach and on session end; `current()` equal to the published state. |
| `cast/CastNotificationIntentsTest` | The trampoline's action is recognised, a normal launch and a Recents relaunch are not, and the reopen flags carry `NEW_TASK`/`SINGLE_TOP` and never `CLEAR_TASK`/`CLEAR_TOP`. |
| `ui/PlayerViewModelCastReattachTest` | Reopening the held film: **zero** resolves, prepares and transport calls, zero start/stop reports; one ticker (the screen's) reporting the very same source; the live position, playing state and duration shown; a pause pauses rather than reloads; a buffering receiver is adopted and shown buffering, at the coordinator's last valid reading; leaving again and ending the session reports once. Another item: one resolve, one prepare, **one** stop for the orphan at the television's position and none again at session end; a receiver that let go is not adopted and its old session is closed once. The session ending with the receiver gone: home at the screen's last reading (or the coordinator's, when the screen never read one), stop and start reports at it, **no report carrying 0 or an invalid reading**; a screen leaving as the receiver stops answering hands its reading to the coordinator's detached stop. |
| `ui/PlayerViewModelCastEndTest` | A non-reattached session ended with an invalid final snapshot comes home at the last valid reading, stop and start reported there and never at 0; one that never read a valid position comes home at its start with a positionless stop; a quality change during an invalid reading resumes from the last valid position. The zero rule: a torn-down receiver's valid zero at the end brings the film home at the last valid reading; a zero reading reaches neither the scrubber nor the ticker; a seek to 0 makes zero the position; a dropped item reported at a stale zero is closed at the last valid reading. |
| `ui/PlayerViewModelCastReattachTest` › device walk | The device sequence: reattach, follow the television to 663 s, then the receiver answers a valid zero before the routing switch and the idle local player a valid zero after it. The screen's ticker firing in between reads nothing valid, the film comes home at 663 s, and the stop is reported there. A torn-down zero read before the end is not remembered either. |
| `cast/CastSessionCoordinatorTest` › receiver gone | The detached end with an invalid final snapshot reports at the last `readReceiver` reading, never an invalid one; a screen's last valid reading seeds the detached stop when the receiver answers nothing at detach; a buffering hold carries the last valid reading. The zero rule: a torn-down zero at the end (with the local player idle at zero) is closed at the last valid reading; a restart from the television's remote while detached is recorded as it is; a detached dropped item reported at a stale zero is closed at the last valid reading. Another film loaded on the receiver before its screen attaches: its reading is not the detached one's, the orphan's stop is not taken from it, and a receiver buffering it does not hold the detached source; the very source loaded is still read. The handover tells the screen the phone meant to play even while buffering. |
| `ui/PlayerViewModelZeroRuleTest` | The zero rule does not touch a local player playing its own session: a SyncPlay seek to 0 and a media-session seek to 0 while paused are where the film is, on the scrubber and in the stop. |
| `ui/PlayerViewModelCastIntentTest` | A re-negotiation during an invalid reading reloads the receiver playing, a receiver settled paused under a stale intent reloads paused; a phone buffering toward play hands the film over playing; a receiver settled paused under a stale intent shows Play and its tap plays. |
| `ui/PlayerViewModelCastReattachTest` › the review's window | Another film loaded before its screen attaches: the bar's reading is invalid, and the orphan's stop carries the replaced film's own last position. |
| `cast/CastSpecMapperTest` › `loadOnReceiver` | The handle's whole load: the `MediaItem` the player is given carries a spec whose autoplay is the open's `playWhenReady` (false and true), set before the item, then prepared. |
| `cast/CastNowPlayingTest` › stale intent, `cast/CastStatusAnnouncementTest`, `:app` `CastingBarActionTest` | The bar's item carries `isSettledPaused` and its tap plays; the label equals `tapPlays` in every intent/paused combination; the status line is live while reconnecting and after it, not before. `CastingBarSemanticsTest` (instrumented, compiled) adds the recovery announcement and Play under a stale intent. |
| `model/PlaybackSnapshotTest` | `contradicts`: a valid zero after a later reading contradicts it; no vouched reading, a vouched zero, a nonzero reading, an ended or invalid reading do not. |
| `report/PlaybackReporterTest` | (Cast rows) an invalid stop carries no position, writes nothing locally and is flagged `failed`; an ended item's positionless stop is not. |
| `:app` `CastingBarTest` | `showsCastingBar` (hidden on Player and Now Playing, and with nothing cast), **cast wins the slot** over an active music queue and gives it back, `castingBarAction` (buffering keeps Pause), and `castNotificationRoute` (the casting item's player, the same player left alone in any case, another player replaced, the attached player left alone, Home with nothing cast, nothing signed out). |
| `:app` androidTest `CastingBarSemanticsTest` | One merged sentence with the tap and no loose text nodes, the button as its own stop, "Buffering" + polite live region, polite live region while reconnecting. Compiled; device run owed. |
| `cast/RemoteItemPresenceTest` | The edges only, once each; never armed by a load's invalid window or a placeholder glimpse, only by the item held while ready; the resume point is the last held reading; a new load clears and disarms. A finish: the held item's is an end at its duration, said once, never missing afterwards (a drop noticed just before is cleared, then ended); an unnamed finish counts only while the item was still held; the film before's leftover finish, another sender's, and an unnamed one after a drop do not. |
| `model/PlaybackMediaSourceLoadTest` | `isSameLoadAs`: a track chosen in place is the same load; a new play session, another media source or item, or no load is not. |
| `ui/PlayerViewModelCastInPlaceTrackTest` | A subtitle turned off in place while casting, then the screen leaves: the bar and the detached ticker keep following the television, a reopen adopts without resolving or loading, and the session's end reports the live position; the audio twin keeps following too. |
| `ui/PlayerViewModelCastFinishTest` | A film finishing on the receiver: with a screen, it ends as played (one ended stop, no "stopped" message, no positioned stop, even with a drop noticed first and the grace period run); an episode advances to the next on the receiver; with no screen, one ended stop from the finish itself, the bar cleared, nothing more at the session's end. |
| `cast/CastSessionCoordinatorLoadTest` | A copy with a track chosen in place is read as the detached source and adopted without an orphan report; a detached finish is closed once at its ended reading (and cancels a loss already under way); with a screen attached, or for another film loaded since, the coordinator reports nothing. |
| Review 2: `ui/PlayerTransportTest`, `ui/PlayerViewModelCastGraceTest`, `cast/CastSessionCoordinatorTest`, `cast/CastNowPlayingTest`, `:app` `CastingBarActionTest` / `CastingStoppedTest` | The transport's label equals its tap in every reachable playWhenReady/settled-paused/buffering state, including an audio-focus loss. Inside the grace period the screen labels Play, and its tap re-sends the film (the old session closed once first) without pausing the receiver. A skip there leaves the receiver alone, and a film that comes back is paused as labelled. The coordinator refuses the bar's toggle during the grace and accepts it once the item is back. `CastingItem.receiverLetGo` is set, and cleared, with buffering hidden. `castingBarAction` gives `OPEN_PLAYER` whatever the intent. `castingStopped` announces the item going (named or unnamed device), stays silent when the player opens, and ignores changes that are not an exit. `CastingBarSemanticsTest` (instrumented, compiled) adds the "Play" button whose click is labelled "Open player" and opens the player. |
| `session/PlayerEventBridgeTest` | `Buffering` only while buffering *and* meaning to play, cleared by a pause while still buffering and by `READY`, said on change only. |
| `ui/PlayerTransportTest` | The local sibling: the toggle follows `playWhenReady` (a tap while rebuffering pauses), skips are relative and clamped to a known duration, an unknown duration is not clamped to zero, a local rebuffer reaches the UI state without an `IsPlayingChanged(false)` undoing it, and a rebuffer is drawn as — and answered by — a working Pause. |
| `ui/PlayerControlsTest` › `BufferingGateTest` | `transportControl`: **buffering keeps a Pause action** with the ring; outside buffering, plain Play/Pause; the ring and spinner gates, receivers included. |
| `deviceprofile/CastDeviceProfileTest` | The codec/container/subtitle/bitrate table, the stereo AAC cap on the transcode and on both direct-play shapes (`VIDEO_AUDIO`, `AUDIO`), and the bitrate cap being the only thing `build` changes; the H.264/HEVC video caps carrying no container in every class (so they bound the `ts` transcode) while no VP8/VP9 condition exists to constrain webm. |
| `session/RoutingPlayerHandleTest` | Delegation of every method, event switching through `flatMapLatest` (Turbine), snapshot routing, `stopInactive` touching only the handle that is not in charge, and a switch leaving the handle it left alone. |
| `ui/PlayerViewModelCastTest` | The system property, assembled from a real `RoutingPlayerHandle`, a real coordinator and a fake monitor: **exactly one stop report per source** across both transfers; stop-report-then-resolve ordering; `castTarget` on every re-negotiation (audio, subtitle, quality); a side-loaded subtitle never reaching the server; the fallback ladder bypassed; SyncPlay exclusivity; PiP disarmed; the speed picker following the receiver; the backdrop chain; the receiver's metadata published, a cast open waiting for it and a local open not. With an invalid receiver reading: pause pressed sends pause, a skip moves from the last valid position, nothing on screen changes; a receiver's buffering reaches the screen. A dropped item: waited out, announced, not reloaded, stop reported once at the last held position, Play re-sends it from there (or from where the scrubber was moved), and the detached case reported once across the session's end; a natural end is not reported again by the coordinator; a Wi-Fi blip shows as reconnecting and clears on resume. |
| `resolve/PlaybackResolveCastTargetTest` | The four things `castTarget` changes: the copy on disk is skipped, the cast profile is the one sent, `allowVideoStreamCopy = false` is sent (and not for local; direct play still wins), and an Auto transcode is re-negotiated at 20 Mbps while an Auto direct play stays uncapped and a hand-picked cap is sent as picked; a missing cap counts as over the ceiling for cast only. |

Every pre-M12 player test passes **unchanged** — that was the milestone's regression gate, and it is
what "a routing handle with no cast session is a pass-through" means in practice.

**Known gaps.** Everything that needs a real Chromecast is a device-verification item rather than a
unit test: CAF's acceptance of the server's HLS-ts flavour, the chooser dialog's theming, hardware
volume keys, the framework's own notification, and the minified build on a receiver. They are the DoD
walk in `docs/notes/chromecast-m12-plan.md` § Verification. `CastMediaItemConverter`,
`GmsCastSessionMonitor` and `CastPlayerHandle`'s GMS half are untested by construction — they are the
mechanical assembly the decisions were deliberately lifted out of.

Gaps specific to the casting bar, reattach and notification routing (2026-09-27):
- **Device walk owed**: leave the player while casting → the bar shows; tap it → the player
  reattaches with no reload on the television; the notification tap from inside the player and from
  Home → the player, with the back stack intact. The trampoline's task behaviour (and the background
  activity-start allowance it relies on) is device-only.
- **The orphan's stop position can be up to one ticker interval stale** when nothing read the receiver
  since the last tick (the bar reads it every second while visible); the server keeps that tick's
  position either way.
- **Metadata is captured at detach.** A film whose item fetch never answered shows "Casting to
  <device>" as its title line.

Gaps specific to the drop/buffering/reconnect handling (2026-09-27):
- **Device walk owed**: Stop from the television mid-film (screen open and backed out of), a
  receiver buffering a slow transcode, a Wi-Fi blip — none has been seen end to end since the fix.
- **The grace period and the "held while ready" arming are reasoned, not measured.** If a receiver
  takes longer than 10 s to re-report an item it merely blinked out of, the screen would call it
  stopped; the Play button then re-sends it.
- **A load that never lands is not detected** — only an item the receiver was seen playing can be
  "dropped". A failed load normally arrives as a receiver error (`CastPlaybackFailed`).
- **While reconnecting, taps are still dropped** — the chip now says why. The Cast notification's
  own Play/Pause state during buffering is the framework's, and not addressed here.
