# Feature: Playback (online) — M5

Streaming playback of a movie or episode from the server, in `:player`
(docs/PLAN.md, "Playback pipeline"). Offline playback (`LocalPlaybackResolver`), media segments
and the trickplay scrubber are **not** here — they are M8 and M9.

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
| `POST /Items/{itemId}/PlaybackInfo` | Opening an item, and on **every** re-negotiation (quality change, track change the server has to perform, decoder fallback). |
| `GET /Videos/{id}/stream?static=true` | Direct play. |
| `GET /Videos/{id}/stream.{container}` | Direct stream. |
| `{transcodingUrl}` (HLS) | Transcode. |
| `{deliveryUrl}` per subtitle stream | Side-loaded external subtitles. |
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

- **Side-loaded subtitles** carry the track id `external:<jellyfinIndex>`.
- **Embedded streams** are matched by position among the embedded streams of the same type.

A switch is applied locally when the track is already in the stream. When it is not — the
transcoding case, where the server sent only the track it was asked for — the source is
re-resolved with the new stream index and playback restarts at the current position.

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

## Not here yet

- Offline playback from downloads — M8. `PlaybackMediaSource` is already a sealed type with the
  local variant's shape in mind, and the `DefaultDataSource` wrapper already resolves `file://`
  and `content://` URIs.
- Media segments (intro/outro skip), trickplay scrubber, PiP, gestures, playback speed — M9.
- A persisted preference for the ASS/SSA toggle and the default quality — M9 settings. Both are
  parameters today (see DECISIONS.md, 2026-07-28).
