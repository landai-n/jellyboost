# Feature: Playback (online) — M5

Streaming playback of a movie or episode from the server, in `:player`
(docs/PLAN.md, "Playback pipeline"). Offline playback (`LocalPlaybackResolver`) is in
`offline-playback.md`; the M9 polish pass — trickplay scrubber, segment skip, picture-in-picture,
gestures, speed, background playback — is the last section of this file.

## The shape of one playback session

```
Routes.Player(itemId, mediaSourceId?, startPositionTicks)
        │
        ▼
PlayerViewModel ──► PlaybackInfoResolver ──► POST /Items/{id}/PlaybackInfo
        │                    │                     (DeviceProfileBuilder's profile)
        │                    ▼
        │            RemotePlaybackMediaSource   (playMethod, playSessionId, tracks)
        │                    │
        │                    ▼
        │            ExoMediaSourceFactory ──► PlaybackMediaItemSpec (url + subtitles)
        │                    │
        ▼                    ▼
   PlayerHandle ────► ExoPlayer ◄──── PlaybackService : MediaSessionService
        │                                  (foreground service + notification)
        ▼
  PlaybackReporter ──► /Sessions/Playing{,/Progress,/Stopped}
                   └─► UserDataRepository.setPosition / setPlayed   (always, local-first)
```

## Key classes

| Class | Responsibility |
|---|---|
| `deviceprofile/DeviceProfileBuilder` | Builds the `DeviceProfile` the server negotiates against. `@Singleton`; the hardware probe runs once. |
| `deviceprofile/MediaCodecProbe` | Seam over `MediaCodecList`, so the profile can be unit tested against a known codec set. |
| `deviceprofile/CodecHelpers` | Android MIME type ↔ Jellyfin codec name, codec profile names, subtitle MIME types. |
| `resolve/PlaybackInfoResolver` | `POST /Items/{id}/PlaybackInfo` → `RemotePlaybackMediaSource`. Owns the dash-less quirk and the play-method decision. |
| `resolve/ExoMediaSourceFactory` | Play method → stream URL + side-loaded subtitles, as a plain `PlaybackMediaItemSpec`. |
| `api/PlayerApi` / `SdkPlayerApi` | The SDK calls playback makes, behind one mockable interface. |
| `api/StreamUrlFactory` / `SdkStreamUrlFactory` | The SDK's URL builders, behind one mockable interface (mirrors `:data`'s `ImageUrlFactory`). |
| `report/PlaybackReporter` | Start / 5-second progress / stop, `stopEncodingProcess`, and the local position write. |
| `fallback/DecoderFallbackHandler` | Renderer failure → force transcode; source failure while transcoding → lower bitrate. |
| `session/PlayerHandle` / `ExoPlayerHandle` | The player, behind a seam. Owns the one shared `ExoPlayer`. |
| `session/TrackSelectionController` | Maps Jellyfin stream indices onto ExoPlayer tracks. |
| `session/PlaybackService` | `MediaSessionService` — foreground service, media notification, media buttons. |
| `session/JellyfinAuthInterceptor` | Adds the Jellyfin `Authorization` header to media requests aimed at our server. |
| `ui/PlayerViewModel` | Sequences resolve → prepare → report → fall back → re-resolve. |
| `ui/PlayerScreen`, `PlayerControls`, `PlayerSheets` | Compose UI over a Media3 `PlayerView` surface. |

## Endpoints

| Call | When |
|---|---|
| `POST /Items/{itemId}/PlaybackInfo` | Opening an item, and on **every** re-negotiation (quality change, track change the server has to perform, decoder fallback). Posted **twice** when the first answer is a transcode that would side-load text subtitles — see *Subtitles on a transcode*. |
| `GET /Videos/{id}/stream?static=true` | Direct play. |
| `GET /Videos/{id}/stream.{container}` | Direct stream. |
| `{transcodingUrl}` (HLS) | Transcode. |
| `{deliveryUrl}` per subtitle stream | Side-loaded external subtitles (direct play and direct stream only). |
| `#EXT-X-MEDIA` rendition playlists off `{transcodingUrl}` | A transcode's text subtitles, as WebVTT segments. |
| `POST /Sessions/Playing` | Playback started or restarted. |
| `POST /Sessions/Playing/Progress` | Every 5 seconds. |
| `POST /Sessions/Playing/Stopped` | Playback ended, for any reason. |
| `DELETE /Videos/ActiveEncodings` | On stop, when transcoding. |

## The three things that are easy to get wrong

**1. The dash-less media source id.** When no media source is named, the id sent to
`PlaybackInfo` must be the item id **with the dashes stripped**. The server looks sources up by
that form, and when it cannot find the one it was asked for it does not fail — it silently ignores
the audio and subtitle stream indices. The symptom appears much later, as "the subtitle picker
does nothing". (jellyfin-android `MediaSourceResolver.kt:58`; Jellyfin `MediaInfoHelper.cs:196-201`.)

**2. Stopping the outgoing transcode before starting the next one.** Every re-negotiation goes
through `PlayerViewModel.reopen`, which calls `PlaybackReporter.stopTranscoding` on the previous
source first. Skipping it leaves one ffmpeg process per quality change running on the server.

**3. The stop report on a detached scope.** `viewModelScope` is already cancelled when
`onCleared` runs, so the final report is launched on the `@DetachedPlayerScope` `SupervisorJob`
scope instead. Getting this wrong loses both the resume position and the ffmpeg cleanup.

## Play method

Decided by `PlaybackInfoResolver`, in this order — the same order jellyfin-web and
jellyfin-android use:

1. `supportsDirectPlay` → **DIRECT_PLAY**. The server only reports this after checking the file
   against our device profile, so it outranks an also-offered transcoding URL.
2. `supportsDirectStream` → **DIRECT_STREAM** (remux, no re-encode).
3. `transcodingUrl` / `supportsTranscoding` → **TRANSCODE**, HLS only.
4. Nothing → a failure, not a guess.

The current method is shown in the player's top bar, so it can be checked without opening the
server dashboard.

## Track selection

Jellyfin numbers every stream of a file in one sequence; ExoPlayer numbers tracks per type and
only sees the streams it was given. Two bridges close the gap:

- **Side-loaded subtitles** carry the track id `external:<jellyfinIndex>`. The player reports it
  back with a `<childIndex>:` prefix — side-loading anything makes the source a
  `MergingMediaSource`, whose period re-ids every format as `"<childIndex>:<originalId>"` — so
  `jellyfinIndexOfTrackId` strips a leading numeric prefix before reading the index. Matching the
  raw id refused every downloaded sidecar as "not in the downloaded file".
- **Embedded streams** are matched by position among the embedded streams of the same type. A
  transcode's subtitle **renditions** are in this group: they are ordinary text groups of the
  transcoding master playlist, one per text stream in MediaStream-index order — the order
  `subtitleTracks` is in — and Media3 ids them `"<GROUP-ID>:<NAME>"`, which carries no Jellyfin
  index to match on.

A switch is applied locally when the track is already in the stream. When it is not — the
transcoding case, where the server sent only the track it was asked for — the source is
re-resolved with the new stream index and playback restarts at the current position.

For a **downloaded** item that re-resolve would return the same file and the same tracks, so the
request carries `forceRemote` and the item switches to streaming for the track it was asked for; the
pickers themselves show the source's full track list online and only the file's own tracks offline.
The whole rule lives in docs/features/offline-playback.md, *"…and which tracks are shown"*.

### The selection an open starts with

The server picks a track for the session before the user touches anything — the item's
`DefaultAudioStreamIndex` / `DefaultSubtitleStreamIndex`, or whatever the previous session asked
for — and that choice has to reach ExoPlayer as deliberately as a tap on the picker does.

```
resolve  →  PlaybackMediaSource.selected{Audio,Subtitle}Index   (drawn in the pickers at once)
   │
   ▼
publish  →  armed as pending                     (prepare has run; currentTracks is still empty)
   │
   ▼
PlayerEvent.TracksChanged  →  PlayerViewModel.applyPendingTrackSelections()
```

The wait is not an optimisation: at the moment the session opens the player has been prepared but
has parsed nothing, so `Player.currentTracks` is empty and there is nothing to select against.
Tracks then arrive in **stages** — a side-loaded subtitle's group lands after the container's — so
the apply is retried on every `TracksChanged` until it lands, and each half clears itself the moment
it does. A user choice made in between wins: `selectAudioTrack` / `selectSubtitleTrack` spend the
pending one first.

Two consequences worth stating:

- **"Subtitles off" is applied, not assumed.** `null` is a choice like any other. The reset below
  re-enables the text renderer, and ExoPlayer's selector will pick up a default-flagged text track
  on its own if nobody says otherwise. On a transcode that is not hypothetical: every subtitle
  rendition is `AUTOSELECT=YES` and one of them `DEFAULT=YES`.
- **A refused apply never re-resolves.** The stream *is* the one the server built for this
  selection, so the only way it can lack the track is that the server burned it in — a subtitle
  already on screen, with no text group to select. Asking again would restart playback in a loop for
  something the user can already see. That is the one place this path deliberately differs from the
  user-driven one.

### The parameter reset in `prepare`

`TrackSelectionParameters` belong to the **player**, not to the item, and this app has exactly one
`ExoPlayer` for the whole process (shared with `PlaybackService`). Left alone, an override — or the
disabled text renderer that "subtitles off" leaves behind — governs whatever is prepared next: one
film watched without subtitles would keep every later one from ever showing any. So
`ExoPlayerHandle.prepare` calls `TrackSelectionController.reset()` before `setMediaItem`, clearing
both types' overrides and re-enabling text; the session's own choice is applied afterwards, above.

## Subtitles on a transcode

Text subtitles are side-loaded for direct play and direct stream, and delivered **in the manifest**
for a transcode. That split exists because a transcode has a timeline of its own: it re-anchors to
Jellyfin's nominal `EXTINF` grid on every seek and track toggle and absorbs the sub-200 ms audio
gaps an unsignaled ffmpeg restart leaves. Side-loaded cues never pass through the `TimestampAdjuster`
that audio and video do, so they are pinned to the file's clock while the picture is not — and they
drift, progressively. In-manifest cues share the adjuster (the server emits `X-TIMESTAMP-MAP` and
transcodes with `CopyTimestamps=true`) and cannot.

Getting them requires **two `PlaybackInfo` posts**, because the server will not offer both shapes:
given an `External` and an `Hls` profile for the same format it picks External every time. So the
HLS shape has to advertise *no* text External profile — and that profile cannot be the one we always
send, since a direct-playable file with a sidecar `.srt` would then negotiate `Encode` and burn a
transcode out of nothing.

```
pass 1  ──►  normal profile
             ├─ direct play / direct stream        → done, subtitles side-loaded
             ├─ transcode, nothing side-loaded     → done
             ├─ cast target                        → done (the receiver's business)
             └─ transcode ∧ side-loaded text subs  ─┐
                                                    ▼
pass 2  ──►  same request, EMBED + {vtt, Hls} profile
             ├─ transcode with a URL   → used; cues ride in the master playlist
             └─ anything else, or a failure → pass 1's answer, unchanged
```

Neither pass starts an encoder — ffmpeg is spawned by the first *segment* fetch, and nothing here
fetches one. Two knock-on rules:

- an HLS-delivered stream is **not** `isExternal`, even when it is a sidecar file on the server,
  because a rendition has no `external:<index>` id and is matched by position;
- a subtitle the server had to burn in (`Encode`) *is* marked side-loaded, so that it takes no place
  in that positional count — Jellyfin builds renditions only for text streams, and a graphical one
  left in the count would push every text track after it onto the wrong rendition.

SSA/ASS reaching a transcode are converted to WebVTT and lose their styling; ExoPlayer's SSA
renderer ignores most of it anyway. Background and measurements:
docs/notes/subtitle-drift-hls-delivery-spike.md.

## Decoder fallback

The device profile is built from what `MediaCodecList` *claims*; some decoders accept a format and
then fail on the first frame (docs/PLAN.md, risk #5).

| Failure | Response | Budget |
|---|---|---|
| Renderer / decoder (`4xxx`, `5xxx`) | Re-resolve with `enableDirectPlay=false, enableDirectStream=false` | once |
| Source (`2xxx`, `3xxx`) while transcoding | Re-resolve one quality step down | once |
| Source while direct playing | Force a transcode (a lower bitrate would change nothing) | once |
| Anything else | Show the error | — |

The budget resets whenever playback gets going again, so an unrelated failure an hour later does
not inherit an exhausted one.

## How the M5 definition of done maps to code

| DoD | Where |
|---|---|
| Direct play | `PlaybackInfoResolver.playMethod`, `ExoMediaSourceFactory.directPlayTarget`; visible in the player's top bar and in Dashboard → Sessions. |
| Forced transcode | Quality picker → `PlayerViewModel.selectQuality(LOWEST)` → `maxStreamingBitrate = 720_000` → the server transcodes. |
| Track switching | Audio / Subtitles pickers → `TrackSelectionController`, falling back to a re-resolve. |
| Resume | `Routes.Player.startPositionTicks` from the item's `playbackPositionTicks`; written back by `PlaybackReporter` on every tick and on stop. |
| No orphaned ffmpeg | `PlaybackReporter.stopTranscoding` on stop **and** before every re-resolve. |

Since 2026-08-15, the picker's **Auto** no longer means "no cap": it negotiates with a
throughput measurement taken against the server — see `auto-quality.md` and DECISIONS.md.

## Not here yet

- Offline playback from downloads — M8. `PlaybackMediaSource` is already a sealed type with the
  local variant's shape in mind, and the `DefaultDataSource` wrapper already resolves `file://`
  and `content://` URIs.
- A persisted preference for the ASS/SSA toggle and the default quality — M9 settings. Both are
  parameters today (see DECISIONS.md, 2026-07-28).

<!-- BEGIN: Player polish (M9) -->

## M9 — polish

Everything below is additive: nothing in the M5/M8 sections changed shape, and every M9 feature is
absent-by-default rather than failing loudly when its data is missing.

### New key classes

| Class | Responsibility |
|---|---|
| `model/TrickplayTiles` | Sprite-sheet geometry + tile URIs, and `tileFor(positionMs)` → which sheet, column and row. The one implementation of that arithmetic, shared online and offline. |
| `trickplay/TrickplayResolver` | `PlaybackMediaSource` → `TrickplayTiles?`. Local: the sheets already on disk. Remote: the item's `trickplay` map, closest width, tile URLs derived from the thumbnail count. |
| `ui/TrickplayPreview` | Draws one thumbnail by sliding the whole sheet under a clipping window. |
| `segments/MediaSegment` | One intro/outro range, in milliseconds. |
| `segments/MediaSegmentLoader` | `GET /MediaSegments/{itemId}` → `List<MediaSegment>`. Server-only; every failure ends at "no segments". |
| `segments/SegmentSkipController` | Position + segments + preferences → `None` / `Offer` / `AutoSkip`. Owns the once-per-segment auto-skip rule. |
| `gesture/PlayerGestureController` | Zones, swipe distance and exclusion margins — the testable half of the gestures. |
| `ui/PlayerGestureLayer` | The touch surface: `AudioManager`, window brightness, the transient indicator. |
| `pip/PipController` | `@Singleton` seam between the player screen (publishes readiness) and `MainActivity` (arms the system). |
| `model/PlaybackSpeed` | The speed picker's steps. Session-scoped; never persisted. |

### Trickplay scrubber

Jellyfin stores scrubbing thumbnails as **sprite sheets**: `columns × rows` thumbnails per sheet,
one thumbnail every `interval` ms. There is no URL for "the frame at 23 minutes" — there is a URL
for the sheet that contains it, and a cell to cut out of it.

```
drag on the seek bar
   → TrickplayTiles.tileFor(scrubMs) → TrickplayThumbnail(uri, column, row)
   → TrickplayPreview draws the sheet at (columns × previewWidth, rows × previewHeight)
     offset by (-column × previewWidth, -row × previewHeight) inside a clipping Box
```

Drawing the sheet and clipping it — rather than transforming the bitmap — means every neighbouring
thumbnail is a Coil **cache hit**: dragging along the bar decodes nothing until the drag crosses
into the next sheet. The preview follows the thumb and is clamped to the bar's width, so it cannot
overflow on a phone or leave the film on a 2560 px tablet.

The sheet count is **derived**, never served: `ceil(thumbnailCount / (tileWidth × tileHeight))`.
Absence is ordinary — no trickplay for the item, an unreachable server, or nonsense geometry all
resolve to `null` and the seek bar simply has no preview.

### Media segments

| Step | Where |
|---|---|
| Fetch on playback start (server sources only) | `PlayerViewModel.loadPlaybackExtras` → `MediaSegmentLoader` |
| Decide what to do at a position | `SegmentSkipController.decide(positionMs, segments, modes)` |
| Draw the offer | `PlayerScreen`'s `SkipSegmentButton`, independent of the controls' visibility |
| Perform the skip | `PlayerViewModel.skipCurrentSegment()` → `seekTo(segment.endMs)` |

Per type (`INTRO`, `OUTRO`), the preference is `OFF` / `SHOW_BUTTON` (default) / `AUTO_SKIP`.
**Auto-skip fires once per segment**: a user who seeks back into an intro they were just carried out
of is telling the player they want to watch it, so the segment is downgraded to a button for the
rest of the session instead of looping. Segments shorter than one second are ignored.

A server without the Media Segments API (pre-10.10) or without a detection plugin answers 404 or
with nothing, and the feature is silently absent. A downloaded item is never asked at all.

### Up next (2026-08-21, beyond-plan feature — DECISIONS.md 2026-08-21 and 2026-08-24)

While an episode's ending plays, a card in the bottom-right corner offers the next episode;
tapping it swaps the item into the *same* session — and since 2026-08-24, an episode that plays
to its natural end solo **advances to its successor automatically** through the same path,
including from the background, where the tick-driven card may never have shown. No countdown and
no preference; the card's dismissal ("Watch credits") is the opt-out, and it holds through to the
end — a dismissed episode pops at its end exactly as before the feature, as do films, the last
episode of a series, and every non-episode.

| Step | Where |
|---|---|
| Resolve the successor (fire-and-forget, per open) | `PlayerViewModel.loadPlaybackExtras` → `UpNextResolver.resolve` |
| Decide whether the card is up at a position | `UpNextController.shouldShow(positionMs, durationMs, outro, hasNext)` |
| Draw the card | `PlayerScreen`'s `UpNextCard`, stacked above `SkipSegmentButton`, independent of the controls' visibility |
| Play the next episode (tap) | `PlayerViewModel.playNextEpisode()` → `replaceItem(playWhenReady = true)` |
| Advance at the natural end | `PlayerViewModel.onEnded()` → the same `playNextEpisode()`, *after* the detached stop report (`Main.immediate` would otherwise start the swap against an unarmed `stopReported` and report the episode twice) |

The successor is the **positional** next episode — `getSeriesEpisodes` and index + 1, cross-season
— never `getNextUpForSeries`, which answers "next unwatched" and is wrong on a rewatch. Every miss
(last episode, not an episode, either call failing) is `null`, and `null` is simply no card.
Offline, the delegating repository lists only downloads, so the card offers the next *downloaded*
episode or nothing. The prefetch write is identity-guarded against the session it resolved for
(`ActiveSession.upNext`, reset per session by construction), so a slow resolve for episode N can
never offer on episode N+1.

The window opens at the OUTRO segment's start when the server knows one, else the last 60 s of any
item longer than two minutes (raised from 30 s after the first device walk: on a library with no
segment data — the dev server's, measured — every episode takes the fallback, and streaming-drama
credits run one to two minutes, so 30 s landed deep inside them; the *right* fix on such a server
is a segment-detection plugin, which makes the outro trigger take over). A dismissal ("Watch credits") is sticky for the session; seeking back
out of the window hides the card and re-entering shows it again. While the card is up, an OUTRO
*offer* from the segment machinery is suppressed (`applySegmentDecision`) — the card is a strict
superset of that button — but OUTRO *auto-skip* still seeks, and INTRO decisions are untouched.

The swap rides the same seam SyncPlay's queue uses (`replaceItem`, the extraction of `loadItem`),
with three deliberate carry rules: the session's quality terms come along (a manual cap stays a
cap, Auto stays Auto and is re-measured); playback speed survives (`publish` reapplies it); audio
and subtitle selections deliberately reset to the new item's server defaults, because episode N's
stream indices mean nothing in episode N+1's stream list. The `advancing` flag keeps a tap that
races `Ended` from popping the route mid-swap, exactly parallel to the SyncPlay `groupContinues`
guard, and the stop-report invariant holds in both orders because `endCurrentSource` is idempotent
per source. In a SyncPlay group the whole feature is inert — no prefetch, no card — since the
server owns what everyone watches next. Casting needs no branch: the card draws over the
`CastingBackdrop` and `replaceItem` opens on the receiver like any other cast open.

Known, accepted gap (pre-existing, hit more often now): `PlayerSessionStore.itemId` is the route
argument, so process death after an in-session swap restores the *original* episode at the new
episode's position — the same limitation SyncPlay's queue has carried since M11.

### Picture-in-picture

```
PlayerViewModel  ──setPlayerState(active, w, h)──►  PipController  ──state──►  MainActivity
   (route up? playing? pref on?)                                          setPictureInPictureParams
MainActivity  ──setInPictureInPicture(…)──►  PipController  ──state──►  PlayerScreen (hide controls)
```

`MainActivity` hosts the whole app, so the three conditions are decided in the player and it only
reads one boolean. On API 31+ `setAutoEnterEnabled` gives the seamless shrink users expect;
`onUserLeaveHint` covers API 26–30 with a visible hop. The aspect ratio is clamped to Android's
1:2.39 … 2.39:1 — an unclamped 2.76:1 film throws as the user presses Home. In PiP the screen draws
bare video; transport control comes from the media notification.

### Gestures

| Gesture | Effect |
|---|---|
| Vertical swipe, left half | Brightness — a **window** attribute, so it dies with the player rather than changing the device setting |
| Vertical swipe, right half | Volume — `AudioManager.STREAM_MUSIC` |
| Double tap, outer thirds | −10 s / +30 s, matching the on-screen buttons rather than being symmetric |
| Double tap, middle third | Nothing — a fumbled play/pause must not seek |
| Single tap | Toggle the controls |

A full 0→1 sweep is 0.66 of the screen height (jellyfin-android's ratio). Swipes starting within
48 dp of the left/right edges or 64 dp of the top/bottom are left to the system, and the surface
also calls `Modifier.systemGestureExclusion()`.

The bottom bar's sheet buttons (audio/subtitles/speed/group/queue/quality) render labelled
`TextButton`s only when the measured button row is at least 840dp wide; below that they are
icon-only `IconButton`s whose `contentDescription` keeps the label for TalkBack. The five-button
worst case fit an 800dp phone-landscape bar with zero slack and was already crowding tablet
portrait (711dp), so both go icon-only; the tablet-landscape bar (capped at 1000dp) keeps its
labels (2026-07-31 phone-size sweep, DECISIONS entry).

### Controls visibility, and the panels that outlive it

The bar comes up on a tap and takes itself away again while something is playing. The rule is
`controlsAutoHide(…)` — a pure function beside the effect that uses it, so "when do the controls go
away" is `ControlsAutoHideTest` rather than a stopwatch and a tablet.

| The timer runs | The timer is suspended |
|---|---|
| controls up, something playing, no panel open, touch exploration off | controls already away · **paused** (a paused film with no controls looks like a frozen app) · **a panel is open** · **touch exploration is on** — four seconds is not a traversal (accessibility audit CR-1) |

Two properties are worth stating because both were once false (audit UI-1/UI-3, 2026-08-08):

- **Every interaction restarts the countdown.** A seek, a chip tap, a picker choice, a keyboard
  shortcut — all of them go through `PlayerActions`, which `PlayerScreen` wraps once with
  `reportingInteraction { … }`; the counter it bumps is part of the effect's key, so the running
  delay is cancelled and four fresh seconds begin. Until 2026-08-08 the key was
  `(shouldHide, timeoutMs)`, neither of which use changes, so the bar hid four seconds after it
  first *appeared* however busy the user was.
- **`PlayerScreen` hosts all seven panels**, above the bar and above the auto-hide: audio,
  subtitles, speed, quality, display, group, queue — one `openPanel: PlayerPanel?`, one exhaustive
  `when` in `PanelHost`. The first four used to be `remember`ed inside the control bar, which is
  inside the `AnimatedVisibility` the auto-hide drives, so the picker was disposed mid-selection
  within a second or two of the tap that opened it. Anything on this screen that must survive four
  seconds belongs to the screen.

The timeout itself is the system's, not a constant: `calculateRecommendedTimeoutMillis` passes
Settings → Accessibility → "time to take action" through, which is why 4 000 ms appears as a default
rather than as the answer.

### The window the player takes over

For as long as the player is composed (`ImmersiveLandscapeEffect`, suspended in picture-in-picture):
system bars hidden with `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`, `decorFitsSystemWindows = false`,
orientation handed to `SCREEN_ORIENTATION_USER` (WCAG 1.3.4 — see DECISIONS.md 2026-08-05).

On the way out it restores the **orientation** and the **brightness override** the swipe may have
written, and shows the bars again. It deliberately does *not* restore `decorFitsSystemWindows`:
`WindowCompat` has no getter for it, so the "previous value" that used to be put back was a
hardcoded `true` — the one value it may never be, since `MainActivity.enableEdgeToEdge()` sets it
false for the whole process and this is a single-activity app. Leaving the player therefore broke
edge-to-edge app-wide on API 26–34, invisibly on the API 35+ test tablet where the platform enforces
it anyway (audit UI-2, fixed 2026-08-08).

Because the bars are hidden, the top and bottom bars pad themselves with
`WindowInsets.systemBars.union(WindowInsets.displayCutout)` rather than with `systemBarsPadding()`
alone: the notch is the inset that is still there when the bars are not, and a *union* rather than
two chained paddings, which on the shared top edge would inset by both (audit UI-16).

### Playback speed

0.5×–2× in jellyfin-web's steps, applied through `PlayerHandle.setPlaybackSpeed`. Session-scoped by
design (DECISIONS.md 2026-07-29) and **re-applied after every re-resolve**, because a re-negotiation
builds a fresh media item that starts at 1×.

### Background playback

Backgrounding the app no longer pauses playback. The root cause was not the notification
permission: Media3 only manages a session once it has been **added** to the service, which normally
happens when a `MediaController` connects — and this app deliberately has none. `PlaybackService`
now calls `addSession` itself, so the service is promoted to the foreground with a media
notification and survives the app leaving the foreground. Alongside it:

- `setHandleAudioBecomingNoisy(true)` — headphones pulled out pause instead of blaring.
- `setWakeMode(C.WAKE_MODE_NETWORK)` — a partial wake lock plus a Wi-Fi lock, so a *streamed* item
  survives the screen going off.
- Audio focus was already handled (`setAudioAttributes(…, handleAudioFocus = true)`, M5).
- The session carries a launch `PendingIntent`, so tapping the notification returns to the player at
  the live position — the UI re-attaches because the composition and the ViewModel both survived.

The seek-bar poll still stops with the screen (it is cosmetic and would burn battery); progress
reporting and playback do not.

### New preferences

| Key | Type | Default |
|---|---|---|
| `segment_skip_intro` | `SegmentSkipMode` | `SHOW_BUTTON` |
| `segment_skip_outro` | `SegmentSkipMode` | `SHOW_BUTTON` |
| `pip_on_leave` | `Boolean` | `true` |

<!-- END: Player polish (M9) -->

<!-- BEGIN: SyncPlay (M11) -->
## M11 — what changes while the session is in a SyncPlay group

The whole feature is documented in [`syncplay.md`](syncplay.md); this section is only the part that
changes **this** pipeline. Outside a group nothing below applies: `PlayerSyncPlayBridge.isInGroup` is
`false` and every branch falls through to what M5–M10 built, which is what the unchanged
`PlayerViewModelTest` / `PlayerTrackPickerTest` / `PlaybackReporterTest` suites pin.

**Transport is routed, not applied.** `togglePlayPause`, `seekTo`, `seekBy`, the skip-intro button
and the item-ended handler all become requests to the server through `PlayerSyncPlayBridge`; the
player moves only when the group's rebroadcast command reaches `SyncPlayCommandScheduler`. The seek
bar deliberately does not jump ahead of it — an optimistic position would show a place this player
is not at, and would never be corrected if the group refused the seek.

**Two features are suppressed**, both because they would silently desynchronise this member:

- the **speed picker** (a request to change speed in a group is refused with a message), and
- **segment auto-skip** — an intro skipped locally puts this device ~90 s ahead of everyone.
  The manual *Skip intro* button still works, as a group seek.

**A third is added:** a WAITING overlay while the group waits on a member, and a group icon in the
controls opening `SyncPlayGroupSheet` (participants, shuffle/repeat, leave) and the queue sheet.

**Re-negotiation tells the group.** `reopenSession` — a quality change, a track change the server
has to perform, a decoder fallback — calls `syncPlay.onBuffering()` first, so the group waits rather
than playing on without this member. `PlayerEvent.Ready` then re-enters the handshake.

**The reporter's one exception.** A `LocalPlaybackMediaSource` normally tells the server nothing
(see [`offline-playback.md`](offline-playback.md)). In a group, and while online, it reports
start / progress / stop like any other session, keyed on a play session id minted by a single
profile-less `POST /Items/{id}/PlaybackInfo` (`PlaybackInfoResolver.mintPlaySessionId`, driven by
`SyncPlayLocalSession` from `PlayerViewModel.publish` — before the start report, since the id has to
be in it). A failed mint reports without an id rather than not at all. `stopTranscoding` stays
remote-only: a file on disk started no encoder. Leaving the group mid-film sends one closing stop
report and then goes quiet, while playback carries on solo. Every local position write is untouched
in all of these cases. See DECISIONS.md, 2026-07-30.
<!-- END: SyncPlay (M11) -->
