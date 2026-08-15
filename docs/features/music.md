# Feature: Music (M13)

A full music experience against the Jellyfin server: browse artists/albums/playlists, background
playback with a real notification and lock-screen controls, a local queue with shuffle/repeat,
music search, offline downloads, server-generated Instant Mix ("Start radio"), and synced lyrics.
Playlist *editing* is out of scope (view-only). Full plan: `docs/notes/music-m13-plan.md`;
governance entry: DECISIONS.md 2026-08-05.

Music libraries surface as ordinary library tiles on Home/Libraries — there is no dedicated
bottom-nav tab (user decision).

## What it is: the shared player, a second queue on top

There is exactly one `ExoPlayer` in this app (`ExoPlayerHandle`), the same one video already plays
through. Music does not get a player of its own; it gets a second, independent **orchestrator** —
`MusicPlaybackController` — that takes turns owning that player with video through an arbiter,
`PlaybackHandover`. Nothing about how video plays changed: `PlayerHandle` is untouched, and every
pre-M13 player test still passes unchanged.

```
        ┌── AlbumDetail / ArtistDetail / PlaylistDetail / NowPlaying / mini-player ──┐
        │                                                                            │
        ▼                                                                            │
  MusicPlaybackViewModel (:app) ──────────► MusicController (:core:common) ◄─────────┘
                                                     │  (bound in :player)
                                                     ▼
                                        MusicPlaybackController  (@Singleton, @MusicSessionScope)
                                          │        │         │            │
                                          ▼        ▼         ▼            ▼
                                MusicStreamResolver  MusicQueueSpecFactory  PlaybackReporter
                                          │                                     │
                                          ▼                                     │
                                 AudioStreamUrlFactory / DownloadedMediaProvider │
                                                                                 │
                                          MusicPlayerPort ◄── claim/relinquish ──┤
                                                │                          PlaybackHandover
                                                ▼                                │
                                     ExoMusicPlayerAdapter                       │
                                                │                                │
                                                ▼                                ▼
                                     ExoPlayerHandle (shared ExoPlayer) ◄── PlaybackSessionController
                                                │                              (video side)
                                                ▼
                                     PlaybackService (MediaSession) ──► notification / lock screen
```

## Key classes

`player/src/main/kotlin/dev/jellyboost/player/music/` unless stated.

| Class | Responsibility |
|---|---|
| `MusicController` (`:core:common`) + `MusicPlaybackState` | The published contract — `SyncPlaySession`'s precedent. `state: StateFlow<Idle \| Active(queue, currentIndex, isPlaying, positionMs, durationMs, shuffle, repeat)>`, `messages: Flow<MusicMessage>` for one-shot refusals/failures, and the transport verbs (`play`, `togglePlayPause`, `next`, `previous`, `seekTo`, `setShuffle`, `cycleRepeat`, `jumpTo`, `removeAt`, `moveItem`, `stop`). Lives in `:core:common` so `:feature:music` and `:app`'s mini-player can drive playback without ever depending on `:player`. |
| `MusicPlaybackController` | The `@Singleton` implementation, on its own `@MusicSessionScope` (`Dispatchers.Default.limitedParallelism(1)` — the whole synchronization mechanism; no locks). A queue is not one playback session but one **per track**: each `MusicQueueEntry` carries its own `playSessionId`, and a track transition stops the outgoing session before opening the incoming one. Claims the shared player through `PlaybackHandover` before touching it, and hands `PlaybackHandover` its own `relinquish` callback — closing the open session, snapshotting position, and releasing the player — for when video claims it back. |
| `MusicPlayerPort` (interface) + `ExoMusicPlayerAdapter` | A second, narrower seam over `ExoPlayerHandle`, deliberately **not** added to `PlayerHandle` itself (that would mean three implementations — Exo/Cast/Routing — and drag Cast into scope). Drives the shared player's **native playlist** (`setMediaItems`), which is load-bearing twice: the media session derives notification prev/next from the wrapped player's own playlist commands (zero notification code), and the session timeline *is* the queue — exactly the shape a `MediaLibraryService`/Android Auto follow-up needs. `claim()` flips `AudioAttributes` to `AUDIO_CONTENT_TYPE_MUSIC`; `release()` flips back to `MOVIE`. Emits `MusicPlayerEvent` (`ItemTransition`, `IsPlayingChanged`, `Ended`, `Error`) via its own `Player.Listener`. |
| `MusicStreamResolver` | Pure resolution: downloaded copy first (`DownloadedMediaProvider`, `playSessionId = null` — nothing was negotiated with the server, so nothing is reported for it), else a fresh `/Audio/{id}/universal` URL per queue entry via `AudioStreamUrlFactory`, with `PlayMethod` **inferred client-side** from the container the item reports (a container in the direct-play set → `DirectPlay`, else `Transcode`) since the universal endpoint negotiates nothing server-side up front. |
| `AudioStreamUrlFactory` + `SdkAudioStreamUrlFactory` (`player/api/`) | Deliberately separate from the video-only `StreamUrlFactory`. Builds `/Audio/{id}/universal` with direct-play containers (`opus, mp3, aac, m4a, flac, webma, webm, wav, ogg`), `audioCodec=aac`, `transcodingContainer=ts`, `transcodingProtocol=HLS`, `maxStreamingBitrate=384_000`, `maxAudioChannels=2` (stereo — a phone, headphones, or a Bluetooth speaker), and a `PlaySessionId` appended by hand (the SDK builder has no such parameter; jellyfin-web does the same). One URL construction is local and deterministic, which is what lets a whole album become one `setMediaItems` call instead of N `PlaybackInfo` round trips. |
| `MusicQueueSpecFactory` | Pure `JellyfinItem + MusicStream → MusicQueueEntry` (title, artist, album, artwork, track/disc numbers, `playSessionId`, `playMethod`). This class is the whole of "the notification shows the right thing." |
| `MusicSessionCallback` | Grants two custom `SessionCommand`s (`ACTION_TOGGLE_SHUFFLE`, `ACTION_CYCLE_REPEAT`) and renders them as `CommandButton`s using Media3 1.9.0's predefined shuffle/repeat icons, dispatching back into `MusicController`. Verified against the `media3-session` 1.9.0 artifact rather than assumed. |
| `session/PlaybackHandover` | The video⇄music arbiter, `@Singleton`. **Invariant: exactly one stop report per session, issued by the outgoing owner, completed before `claim()` returns.** `claim(kind, relinquish)` runs under a `Mutex`: if a different kind currently owns the player, it invokes (and clears, before invoking — so it cannot fire twice) that owner's stored `relinquish` callback, then records the new owner. A re-claim by the *same* kind just replaces the callback (its own close-and-reopen, which it reports for itself, not a handover). `releaseNow(kind)` is a non-suspending variant for teardown paths (`tryLock`, skips rather than blocks if a handover is already in flight). |
| `report/PlaybackReporter` (parameterised) + `MusicReportTarget` | Video's `reportStart`/`reportProgress` gained defaulted `repeatMode`/`playbackOrder` params (`REPEAT_NONE`/`DEFAULT`) so the video path and every pre-M13 test read exactly as before. New `reportMusicStart`/`reportMusicProgress`/`reportMusicStop` take a `MusicReportTarget(itemId, mediaSourceId, playMethod, playSessionId, runTimeTicks)` — a downloaded track's `playSessionId == null` short-circuits every server call, matching the resolver's own "nothing to report" answer. |
| `di/MusicModule` + `MusicSessionScope` | Binds `MusicPlayerPort ← ExoMusicPlayerAdapter`, `MusicController ← MusicPlaybackController`, `AudioStreamUrlFactory ← SdkAudioStreamUrlFactory`. `MusicController` is the one binding that leaves `:player` — available to `:app`'s mini-player and `:feature:music` without either depending on the player module. |
| `data/.../music/MusicApi` + `SdkMusicApi` (`:data`) | The `PlayerApi`/`SdkPlayerApi` thin-wrapper pattern, for the two SDK operation groups Instant Mix and lyrics need: `getInstantMix(itemId, limit): List<BaseItemDto>` (`InstantMixApi.getInstantMixFromItem` — the generic from-item endpoint covers every seed kind M13 surfaces "Start radio" on) and `getLyrics(itemId): LyricDto` (`LyricsApi.getLyrics`). Raw SDK DTOs at this layer; `OnlineJellyfinRepository` maps them. |
| `data/.../mapper/LyricsMapper.kt` | `LyricDto.toDomain(): Lyrics` — see "Lyrics" below for the mapping rule. |
| `feature/music/nowplaying/LyricsPane` | The lyrics UI: highlighted/auto-scrolling when synced, plain scrollable text otherwise. |

## Data model: extended, not paralleled

`JellyfinItem` gained defaulted music fields (`album`, `albumId`, `albumArtist`, `artists`,
`artistRefs: List<ArtistRef>`) rather than a second, music-specific model — "UI never sees DTO,
online and offline produce identical `JellyfinItem`" is the contract every other feature already
relies on, and a parallel model would mean two mini-players, two card kinds, two offline paths.
`ItemType` gained `AUDIO`/`MUSIC_ALBUM`/`MUSIC_ARTIST`/`PLAYLIST`; `ItemEntity` (Room v10) gained two
nullable, indexed, **query-only** columns (`albumId`, `albumArtistId`) so offline can answer "tracks
of this album" and "albums of this artist" — the domain item itself still rebuilds from the stored
DTO blob, so the migration stayed a plain additive `@AutoMigration(8, 9)`.

## Streaming: `/Audio/{id}/universal`, not `/Videos`

`docs/ARCHITECTURE.md`'s "`/Videos` not `/Audio`" note (M4/M5) is about `audioStreamIndex` being
ignored on non-video requests for a *video's* audio sidecar — a music track has exactly one audio
stream, so that constraint does not apply here, and the universal endpoint is what both
jellyfin-web and Finamp use for audio. Going through it sidesteps two dead ends the video path
would otherwise hit: the video resolver's `ExoMediaSourceFactory.transcodeTarget()` hard-rejects
anything that isn't HLS, and the server's plain audio `TranscodingProfile` is mp3-over-HTTP, which
that gate would reject outright.

## Reporting

A light `MusicReportTarget` path reports a start on every track transition, progress every 10 s on
`@MusicSessionScope` (screen-independent by construction — a music queue has no
detached/attached split to worry about, unlike video), and a stop on transition, explicit stop, or
handover. Shuffle maps to `PlaybackOrder.SHUFFLE`; repeat maps to `RepeatMode.REPEAT_ALL` /
`REPEAT_ONE` / `REPEAT_NONE`. The same `UserDataRepository` write-through as every other playback
path keeps Continue Listening correct offline.

## Video ⇄ music handoff

Starting a video while music plays claims the player for `PlaybackKind.VIDEO`; `PlaybackHandover`
runs music's `relinquish` callback — one stop report, a paused snapshot preserved for the
mini-player, then the player handed over — *before* `PlaybackSessionController.open()` prepares the
video. The reverse direction is symmetric: `PlaybackSessionController.endVideoSession()` calls
`releaseNow(VIDEO)` as part of the screen's own teardown, so a later `MusicController.play()` does
not re-report a stop for an already-closed video session. Exactly one stop report ever crosses a
handover in either direction — the same invariant M12's Cast coordinator enforces for its own
transfers, verified the same way (unit-tested, not merely reasoned about).

## Downloads: originals only, container-aware expansion

`DownloadEnqueuer` expands `MUSIC_ALBUM`/`MUSIC_ARTIST`/`PLAYLIST` into their tracks in the order
the matching screen shows them (disc/track for an album, album-by-album for an artist, playlist
order for a playlist), and best-effort caches the album/album-artist parent rows as
`ItemSource.DOWNLOAD` so the offline artist→album→tracks walk resolves. A playlist's own row is
**not** cached — its offline detail page could only ever show an empty track list (see "Offline
playlist membership" below), so caching it would be a permanently-broken library entry.
`DownloadFilePlanner`'s audio branch plans exactly two files per track: the album's cover image,
then the original media file — **no transcode, ever** (`DownloadQuality` is never consulted; every
audio row is stamped `ORIGINAL` at enqueue), no subtitles, no sidecars, no trickplay.

## Online/offline behaviour

Every music repository member follows the same per-call online/offline decision every other read in
this app does (`DelegatingJellyfinRepository`). Two members have no offline substitute at all and
say so honestly rather than faking one:

| member | online | offline |
|---|---|---|
| `getAlbumTracks` / `getArtistAlbums` / `getArtistTopTracks` | server query | `ItemDao` query over the `albumId`/`albumArtistId` columns; top tracks become a documented local-playcount approximation |
| `getPlaylistItems` | `/Playlists/{id}/Items` (order-preserving) | **always empty** — Room has no playlist-membership table; see "Deferred" |
| `getResumeAudioItems` | `/Users/{id}/Items/Resume?mediaTypes=Audio` | downloaded audio with a local resume position |
| `getInstantMix` | `InstantMixApi.getInstantMixFromItem`, capped at 200 | `AppError.Network` — the same "no network, no offline substitute" answer `PlaybackSourceResolver` gives for an undownloaded track with no connection |
| `getLyrics` | `LyricsApi.getLyrics`; `AppError.NotFound` when the server has none | `AppError.Network` |

## Instant Mix ("Start radio")

Album, artist and NowPlaying each expose a "Start radio" action. All three call
`MusicPlaybackViewModel.startRadio(item)` (`:app`) — the same indirection `play`/`shuffle` already
go through, so no feature module ever depends on `MusicController` or `JellyfinRepository` beyond
what it already needs: `startRadio` fetches `getInstantMix(item.id)` and hands a non-empty result
straight to `MusicController.play(mix)`; a failed fetch or an empty mix surfaces as
`MusicMessage.RadioFailed(itemName)` through the same one-shot `messages` channel that already
carries SyncPlay refusals and unplayable-track notices to the chrome's snackbar
(`AppScaffold.MusicMessageEffect`). `TrackRow` has no overflow affordance of its own — a "Start
radio" action was not added there rather than inventing one; it is reachable from the album/artist
it belongs to, or from NowPlaying once it is playing.

## Lyrics

`LyricDto.metadata` is non-null on the wire (verified against the SDK 1.8.12 model jar) and carries
its own nullable `isSynced` flag; `LyricDto.lyrics` is a list of `LyricLine(text, start: Long?)` in
100ns ticks. The mapping (`LyricsMapper.kt`) trusts `metadata.isSynced` first, and only when the
source left it unset infers sync from whether any line carries a `start` — a lyric file with one
timed line is synced enough to drive the highlight.

`NowPlayingViewModel` fetches lyrics for the current track (keyed on track id, not queue index, so
a `moveItem` reorder does not re-fetch), caches per-track-id for the session, and clears the cache
on `MusicPlaybackState.Idle`. The pane is hidden entirely — no empty state, no error — whenever
there is nothing to show (idle, still fetching, or a 404 from the server), which is the same
"absence is silence" rule `AlbumTransportRow`'s download button and the mini-player's visibility
rule already follow elsewhere in this app. `NowPlayingUiState.activeLyricLineIndex` derives the
highlighted line from `positionMs` — the last line whose `start` has been reached, staying on the
last one once playback runs past it rather than dropping the highlight, the same "stay on the last
one" rule the queue itself follows when it runs out. Placement: a toggle button in NowPlaying's
compact overlay nav swaps the artwork square for the lyrics list (same footprint, everything else
unchanged); the wide two-pane layout gets a Queue/Lyrics tab next to the existing inline queue,
appearing only once there is something to switch to.

## What is deliberately not supported

| not supported | why, and where it is recorded |
|---|---|
| **Android Auto browse tree** | The session layer is built to make this a small follow-up rather than a redesign: `MusicPlayerPort` drives the shared player's native playlist, so the session timeline already *is* the queue, and the follow-up is a `MediaLibraryService` base-class swap plus `onGetLibraryRoot`/`onGetChildren` over `JellyfinRepository` — no internal `MediaController` exists anywhere to fight it. No stub exists yet. |
| **Casting music** | Music always plays locally in M13; `CastDeviceProfile` is video-only, and `MusicPlaybackController` never touches `RoutingPlayerHandle`. |
| **Offline Instant Mix / lyrics** | Both are server compute with no local substitute; both refuse honestly with `AppError.Network` rather than faking an answer. |
| **Offline playlist membership** | `OfflineJellyfinRepository.getPlaylistItems` stays permanently empty — an honest answer needs a new Room table (playlist id, item id, ordinal), a schema bump, and a sync path keeping it current with server ordering, and the M13 offline DoD walk (artist → album → tracks) does not exercise it. Downloading *from* a playlist is fully supported: it expands to the playlist's tracks, which land under their own albums and artists offline. (DECISIONS.md, 2026-08-06.) |
| **Audio download transcoding** | Downloads are originals-only by policy; `DownloadQuality` never reaches an audio row. |
| **Gapless playback / crossfade** | Not tuned in M13; the shared `ExoPlayer`'s default gap-handling is what ships. |
| **Playlist editing** | View-only by user decision (AskUserQuestion, 2026-08-05) — reordering, adding, and removing playlist members are all out of scope. |
| **Casting a downloaded music file** | Same reasoning as video casting: no local HTTP server is run to serve a `file://` track to a receiver, and this is moot anyway since casting music itself is out of scope. |

## Key files

- `core/common/src/main/kotlin/dev/jellyboost/core/common/music/MusicController.kt`,
  `Lyrics.kt`
- `core/common/src/main/kotlin/dev/jellyboost/core/common/model/ArtistRef.kt`
- `core/ui/src/main/kotlin/dev/jellyboost/core/ui/component/AlbumCard.kt`, `ArtistCard.kt`
- `player/src/main/kotlin/dev/jellyboost/player/music/` — `MusicPlaybackController.kt`,
  `MusicPlayerPort.kt`, `ExoMusicPlayerAdapter.kt`, `MusicStreamResolver.kt`,
  `MusicQueueSpecFactory.kt`, `MusicSessionCallback.kt`, `di/MusicModule.kt`,
  `di/MusicSessionScope.kt`
- `player/src/main/kotlin/dev/jellyboost/player/session/PlaybackHandover.kt`
- `player/src/main/kotlin/dev/jellyboost/player/api/AudioStreamUrlFactory.kt`,
  `SdkAudioStreamUrlFactory.kt`
- `player/src/main/kotlin/dev/jellyboost/player/report/PlaybackReporter.kt`
- `data/src/main/kotlin/dev/jellyboost/data/music/MusicApi.kt`, `SdkMusicApi.kt`
- `data/src/main/kotlin/dev/jellyboost/data/mapper/LyricsMapper.kt`
- `data/src/main/kotlin/dev/jellyboost/data/JellyfinRepository.kt` (+ `Online`/`Offline`/
  `Delegating` implementations)
- `data/downloads/src/main/kotlin/dev/jellyboost/data/downloads/impl/DownloadEnqueuer.kt`,
  `plan/DownloadFilePlanner.kt`
- `feature/music/` — `MusicLibraryScreen.kt`, `AlbumDetailScreen.kt`, `ArtistDetailScreen.kt`,
  `PlaylistDetailScreen.kt`, `TrackRow.kt`, `nowplaying/NowPlayingScreen.kt`,
  `nowplaying/NowPlayingViewModel.kt`, `nowplaying/QueueSheet.kt`, `nowplaying/LyricsPane.kt`
- `app/src/main/kotlin/dev/jellyboost/app/MusicPlaybackViewModel.kt`, `MiniPlayer` (in
  `AppScaffold.kt`), `JellyfinNavHost.kt`

## Test coverage

Densest in `player/src/test/kotlin/dev/jellyboost/player/music/` (controller queue lifecycle,
shuffle/repeat mapping, transition reporting, SyncPlay refusal, handover snapshot/restore against a
fake `MusicPlayerPort`; resolver direct-vs-transcode inference, offline file, `playSessionId`
uniqueness) and `data/src/test/kotlin/dev/jellyboost/data/` (`LyricsMapperTest`, online/offline/
delegating repository routing for every member above, the reflective ARCH-09 test that walks
`JellyfinRepository`'s whole interface by reflection so a member added and forgotten in the
delegate cannot compile). `feature/music/src/test/.../nowplaying/`: `NowPlayingUiStateTest`
(`activeLyricLineIndex` edge cases — before the first line, exactly on a start, between two lines,
past the last line, an untimed separator line inside a synced set), `NowPlayingViewModelTest`
(fetch-on-track-change, cache reuse across a reorder, cache clear on idle, hidden-when-absent).
`app/src/test/.../MusicPlaybackViewModelTest` covers `startRadio`'s three outcomes (non-empty mix,
empty mix, fetch failure).

**Known gaps.** Everything needing a real device is a DoD-walk item rather than a unit test:
notification/lock-screen rendering, audio-focus survival across the `AudioAttributes` flip,
dashboard `PlayMethod` for a real transcode, and the minified build. See
`docs/notes/music-m13-dod-walk.md`.
