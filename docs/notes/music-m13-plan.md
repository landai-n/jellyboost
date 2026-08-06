# M13 — Music (full-featured: browse, queue, background playback, downloads, Instant Mix, lyrics)

## Context

The user wants Jellyboost to be usable as a first-class music player — like
Spotify/YouTube Music — against their Jellyfin server. `docs/PLAN.md` v1 scope
listed music as "NOT v1 (don't preclude)"; this milestone is the user-approved
extension (DECISIONS 2026-08-05), the third after M11/M12. M11/M12 device DoDs
are still owed; **M13 lands code but tags `m13` only after M11 and M12 close.**

User decisions (AskUserQuestion, 2026-08-05): scope = core browsing
(artists/albums/playlists) + background playback with notification/lock-screen
controls + local queue with shuffle/repeat + search, **plus** offline music
downloads, Instant Mix/radio, and lyrics. Playlist *editing* is out
(view-only). Android Auto is out but the session layer must leave a
`MediaLibraryService` browse tree as a small follow-up. Navigation: music
libraries surface as **library tiles** in Home/Libraries (no dedicated
bottom-nav tab).

Confirmed feasibility:

- `PlaybackService` is a real `MediaSessionService` + `MediaSession` with a
  working media notification and background playback; audio focus,
  becoming-noisy and wake locks are already configured on the shared
  `ExoPlayerHandle`. The session wraps `SyncPlayAwareForwardingPlayer`, a
  pass-through when not in a SyncPlay group.
- SDK 1.8.12 carries `ArtistsApi`, `InstantMixApi`, `LyricsApi`,
  `PlaylistsApi`, `MusicGenresApi` (verified in the jar); the dev server is
  10.11 (lyrics need 10.9+).
- `DeviceProfileBuilder` already emits audio direct-play profiles; the
  download engine is file-type agnostic; `HomeSectionType` already decodes
  `RESUME_AUDIO` ("Continue Listening") from server display preferences.

## Gaps this fills

1. `ItemType` has no AUDIO / MUSIC_ALBUM / MUSIC_ARTIST / PLAYLIST;
   `ItemMapper.toItemType()` collapses them to UNKNOWN (test-asserted);
   `CollectionKind.SUPPORTED` = {MOVIES, TVSHOWS} and `toLibraryView()` drops
   music libraries before they reach the DB or UI.
2. No audio stream path: `StreamUrlFactory` is `videosApi`-only, and the
   video resolver's `ExoMediaSourceFactory.transcodeTarget()` hard-rejects
   non-HLS while the audio TranscodingProfile is mp3/HTTP — a music transcode
   through that path would land in `UnsupportedSource`.
3. No queue anywhere: `ExoPlayerHandle.prepare()` is single-item
   (`setMediaItem`); no next/previous/shuffle/repeat, no queue state that
   survives navigation; `PlayerEvent` has no media-item-transition event.
4. No music UI: no now-playing screen, mini-player, queue sheet,
   album/artist/playlist screens; `PlayerScreen` is immersive video.
5. Downloads cannot expand music containers (`DownloadEnqueuer` stops at
   SERIES/SEASON) or plan audio files; quality machinery is video-shaped.
6. Music is filtered out at every query layer (`GRID_ITEM_TYPES`,
   `SEARCH_ITEM_TYPES`, `LIBRARY_COUNT_TYPES`, offline equivalents,
   `getResumeItems` pins `MediaType.VIDEO`, `HomeScreen` skips
   `RESUME_AUDIO`).
7. Reporting has no repeat/shuffle vocabulary
   (`PlaybackReporter` hard-codes `REPEAT_NONE`/`DEFAULT`).

## Key design decisions

1. **Queue = ExoPlayer's native playlist; orchestration = a `@Singleton`
   `MusicPlaybackController`** (`player/.../music/`, own `@MusicSessionScope`)
   — the SyncPlayController/CastSessionCoordinator precedent for state that
   outlives screens. Native `setMediaItems` is load-bearing twice: the
   MediaSession derives notification/lock-screen prev/next from the wrapped
   player's playlist commands (zero notification code), and the session
   timeline is the real queue — exactly what a MediaLibraryService/Android
   Auto follow-up needs. **`PlayerHandle` is untouched** (fattening it means
   three implementations — Exo/Cast/Routing — and drags Cast into scope). A
   new internal seam `MusicPlayerPort` (impl `ExoMusicPlayerAdapter` over
   `ExoPlayerHandle.requirePlayer()`, reusing its service start/stop
   plumbing) gives the controller setQueue/transport/shuffle/repeat/
   move/remove/snapshot plus an event flow that adds `MediaItemTransition`
   via its own `Player.Listener`. The controller is unit-tested against a
   fake port.
2. **Control surface published through `:core:common`** (the SyncPlaySession
   precedent): `MusicController` interface + `MusicPlaybackState`
   (`Idle | Active(queue: List<JellyfinItem>, currentIndex, isPlaying,
   positionMs, durationMs, shuffle, repeat)`) live in `:core:common`;
   `:player` implements and Hilt-binds; `:feature:music` and `:app`'s
   mini-player inject the interface. No feature↔feature dependency appears.
3. **Video ⇄ music handoff via `PlaybackHandover`** (`@Singleton` arbiter):
   the current owner of the shared player registers a `relinquish()`
   callback (stop report → `stopTranscoding` → player stop); a claimant's
   `claim(kind)` awaits it before touching the player. **Invariant: exactly
   one stop report per session, issued by the owner at handover, before the
   new owner prepares** — unit-tested like M12's
   coordinator-only-reports-when-detached invariant. The music queue
   survives a video interruption as a paused snapshot (queue/index/position)
   and re-prepares on resume from the mini-player. `ExoMusicPlayerAdapter`
   flips audio attributes on claim (`AUDIO_CONTENT_TYPE_MUSIC` ↔ `MOVIE`) —
   verify early that focus survives the live flip; the bounded fallback is a
   player rebuild at handover (release + lazy rebuild is a supported path).
4. **Streaming via `/Audio/{id}/universal`** (jellyfin-web's and Finamp's
   audio path), resolved by a pure `MusicStreamResolver` — the video
   resolver is deliberately not reused. URL construction is deterministic
   and local (direct-play container list incl. flac;
   `transcodingContainer=ts`, `transcodingProtocol=hls`, `audioCodec=aac`,
   `maxStreamingBitrate=384_000` — the number `DeviceProfileBuilder` already
   uses — and a fresh `playSessionId` per queue entry), so a whole album
   becomes one `setMediaItems` without N PlaybackInfo round-trips — the
   property the queue design leans on. It also sidesteps the HLS-only
   transcode gate and the mp3/HTTP audio TranscodingProfile entirely: if the
   server transcodes, it transcodes to HLS, which ExoPlayer plays natively.
   `PlayMethod` for reporting is inferred client-side from the container set
   (we told the server the set; verify against the dashboard early). Auth
   rides `JellyfinAuthInterceptor` like every stream this app opens.
   Offline: the resolver checks `DownloadedMediaProvider` first and emits
   `file://`. New `StreamUrlFactory.audioUniversalUrl(...)` — verify early
   the exact SDK 1.8.12 builder shape. ARCHITECTURE.md's "/Videos not
   /Audio" note is about `audioStreamIndex` on multi-stream video sidecars;
   a music track has one audio stream, so `/Audio` is safe here (recorded).
5. **Data model: extend, don't parallel.** The "UI never sees DTO/Entity;
   online and offline produce identical `JellyfinItem`" contract makes a
   parallel music model strictly worse (two mini-players, two card kinds,
   two offline paths). `JellyfinItem` gains defaulted fields: `album`,
   `albumId`, `albumArtist`, `artists: List<String>`,
   `artistRefs: List<ArtistRef(id, name)>` (navigation needs artist ids,
   carried by `dto.artistItems`/`dto.albumArtists`). Track/disc numbers
   reuse existing `indexNumber`/`parentIndexNumber`. `ItemType` gains the
   four music kinds; `isPlayable` adds AUDIO. `ItemEntity` v9 adds two
   nullable indexed **query-only** columns (`albumId`, `albumArtistId`) so
   offline can answer "tracks of album X in disc/track order" and "albums of
   artist Y"; domain items still rebuild from the stored DTO blob. Additive
   nullable columns keep the all-`@AutoMigration` history clean (8→9).
6. **UI shell.** `CollectionKind.MUSIC` joins `SUPPORTED` (flipped in Phase
   2, with the screens, so no dead tiles appear). New **`:feature:music`**
   module: `MusicLibraryScreen` (Albums/Artists/Playlists tabs, adaptive
   grid), `AlbumDetailScreen` (header + disc-grouped track list +
   play/shuffle/download), `ArtistDetailScreen` (albums + top tracks),
   `PlaylistDetailScreen` (view-only), `NowPlayingScreen` (artwork,
   transport, shuffle/repeat, favorite, queue sheet, lyrics pane; two-pane
   ≥560dp — the test device is a tablet). New `:core:ui` cards following the
   `MediaCardArtwork`/`mediaCardSemantics` pattern: `AlbumCard` (1:1) and
   `ArtistCard` (1:1, circular clip) — no square card exists today. New
   routes: `MusicLibrary(libraryId, libraryName)`, `AlbumDetail(albumId)`,
   `ArtistDetail(artistId)`, `PlaylistDetail(playlistId)`, `NowPlaying`.
   Nav-level branching in `JellyfinNavHost` on `collectionType == MUSIC` and
   on music item types (features never see each other). **Mini-player lives
   in `:app` chrome** (docked above `GlassBottomNav` / bottom edge under
   `GlassTopNav`), observes `MusicController.state`, folds its height into
   `LocalAppChromePadding`, visible when Active and not on
   Player/NowPlaying; tap → NowPlaying. Search adds music kinds with
   sectioned results (Artists/Albums/Songs/Playlists).
7. **MediaSession composition (Android Auto readiness).** The session keeps
   wrapping `SyncPlayAwareForwardingPlayer` (pass-through out-of-group), so
   notification transport reaches the real playlist untouched.
   **SyncPlay ⊕ music: mutually exclusive** — `MusicController.play()` in a
   group surfaces a message and refuses (M12's Cast⊕SyncPlay precedent).
   **Cast ⊕ music: music always plays locally in M13** — the controller
   drives `ExoPlayerHandle` directly (never `RoutingPlayerHandle`) and
   `CastDeviceProfile` is video-only; casting music is a recorded deferred
   item. Notification metadata comes free from `MediaItem.mediaMetadata`
   (set by `MusicQueueSpecFactory`). Shuffle/repeat in the notification via
   MediaSession custom command buttons (`MusicSessionCallback` given to the
   session builder) — verify early what Media3 1.9.0's
   `DefaultMediaNotificationProvider` renders for custom layouts (read the
   source); if hostile, shuffle/repeat stay in-app only (one-line DECISIONS
   note). The MediaLibraryService follow-up is then a base-class swap plus
   `onGetLibraryRoot`/`onGetChildren` over `JellyfinRepository` — nothing
   here fights it because no internal `MediaController` ever exists.
8. **Repository surface ×3** (Online/Offline/Delegating; the reflective
   delegation test ARCH-09 enforces completeness): `getAlbumTracks(albumId)`
   (online: items under parent, types=[AUDIO], sorted
   ParentIndexNumber,IndexNumber; offline: `albumId` column),
   `getArtistAlbums(artistId)` (online: albumArtistIds query; offline:
   `albumArtistId` column), `getArtistTopTracks(artistId, limit)`,
   `getPlaylistItems(playlistId)` (offline: downloaded members only),
   `getResumeAudioItems(limit)` (online: resume query with
   `mediaTypes=[AUDIO]`; offline: downloaded audio with resumable user
   data), `getInstantMix(itemId, limit)` (offline: `AppError.Offline` —
   Instant Mix is a server feature), `getLyrics(itemId): AppResult<Lyrics>`
   (new `Lyrics(lines: List<LyricLine(startTicks: Long?, text)>, isSynced)`
   in `:core:common`; offline failure in M13 — lyric sidecar download is a
   recorded deferred item). Album/artist/playlist grids reuse
   `getItemsPaged` with the new item types.
9. **Reporting & user data.** `PlaybackReporter`'s hard-coded
   `repeatMode`/`playbackOrder` become defaulted parameters (video path and
   every existing test compile unchanged); a light `MusicReportTarget` path
   reports start on each `MediaItemTransition`, 10 s progress ticks on
   `@MusicSessionScope` (screen-independent by construction — no
   detached/attached split needed, unlike video), stop on
   transition/stop/handover, with the same `UserDataRepository`
   write-through so Continue Listening works offline. Shuffle maps to
   `PlaybackOrder.SHUFFLE`, repeat to `RepeatMode.*`. Favorites reuse the
   existing item-id-shaped toggle for tracks/albums/artists.
10. **Music downloads: originals only.** `DownloadEnqueuer` expands
    MUSIC_ALBUM (tracks in order), PLAYLIST (playlist order), MUSIC_ARTIST
    (recursive audio, grouped per album). `FolderItems.FOLDER_KINDS` already
    classifies these as folders — untouched. `DownloadFilePlanner` audio
    branch: media file via the existing `mediaUrl`
    (`libraryApi.getDownloadUrl` → `/Items/{id}/Download` — verify early it
    serves audio originals; fallback recorded if not) + one album-art image;
    no subtitles, no trickplay, no sidecars, no `TranscodeSizeProjector`
    (size = `mediaSources[0].size`). `DownloadQuality` does **not** apply to
    audio — originals-only is the v1 policy (audio transcode downloads are a
    recorded deferred item), keeping the video-shaped quality machinery
    closed. Album + artist parent rows upserted as `ItemSource.DOWNLOAD`
    (the M7 series/season precedent) so offline browse walks up. Embedded
    art is ignored — the downloaded album-art image file is the offline
    artwork source, matching every other offline card.
11. **Instant Mix & lyrics surfacing.** "Start radio" on AlbumDetail,
    ArtistDetail, track overflow, NowPlaying overflow → `getInstantMix` →
    `MusicController.play(mix)`. Lyrics: NowPlaying pane (tab in compact,
    right pane in expanded); synced lyrics highlight/auto-scroll the active
    line from the position ticker; unsynced render static; no lyrics = hidden
    affordance. New `:data` seam: `data/.../music/MusicApi.kt` +
    `SdkMusicApi.kt` wrapping `InstantMixApi`/`LyricsApi` (the
    PlayerApi/SdkPlayerApi pattern).
12. **Home.** `HomeViewModel` fetches `getResumeAudioItems` and `HomeScreen`
    renders the already-decoded `RESUME_AUDIO` section ("Continue
    Listening"); tap resumes the track (single-item queue at the saved
    position). `LIBRARY_COUNT_TYPES` and offline equivalents count music
    kinds for music libraries.

## New package/module layout

```
core/common/.../music/MusicController.kt        — interface + MusicPlaybackState + enums
core/common/.../music/Lyrics.kt                 — Lyrics, LyricLine
core/common/.../model/ArtistRef.kt
core/ui/.../component/AlbumCard.kt, ArtistCard.kt

player/.../music/MusicPlaybackController.kt     — @Singleton, implements MusicController
player/.../music/MusicPlayerPort.kt             — internal seam (+ MusicPlayerEvent)
player/.../music/ExoMusicPlayerAdapter.kt       — internal, over ExoPlayerHandle
player/.../music/MusicStreamResolver.kt         — pure: item (+downloads) → uri/playSessionId/playMethod
player/.../music/MusicQueueSpecFactory.kt       — pure: track → MediaItem + MediaMetadata
player/.../music/MusicSessionCallback.kt        — shuffle/repeat custom commands
player/.../music/di/MusicModule.kt, MusicSessionScope.kt
player/.../session/PlaybackHandover.kt          — video⇄music arbiter
player/.../api/StreamUrlFactory.kt (+Sdk)       — + audioUniversalUrl(...)
player/.../report/PlaybackReporter.kt           — parameterised repeat/order + MusicReportTarget

data/.../music/MusicApi.kt, SdkMusicApi.kt      — InstantMixApi/LyricsApi wrappers
data/.../JellyfinRepository.kt (+3 impls)       — members from decision 8

feature/music/                                  — MusicLibrary/AlbumDetail/ArtistDetail/
                                                  PlaylistDetail/NowPlaying screens + VMs,
                                                  QueueSheet, LyricsPane
app/.../MiniPlayer.kt                           — + AppScaffold/AppChrome integration
```

## Phases (each independently committable; `/verify` before each commit)

**Phase 0 — Governance (docs only, lands first).** DECISIONS entry, PLAN.md
M13 summary + DoD, this note, STATUS.md.

**Phase 1 — Domain + mappers + DB (mechanical, wide; sonnet).** `ItemType`
(+4 values, `isPlayable`), `JellyfinItem` music fields + display branches,
`ArtistRef`, `ItemMapper` (music fields; collection-kind mapping ready but
`SUPPORTED` unflipped), `QueryMapper` mirror, `ItemEntity` v9 (`albumId`,
`albumArtistId`, indexes, `@AutoMigration(8,9)`), `ItemEntityMapper`,
`ItemDao` music queries, `LIBRARY_COUNT_TYPES` + offline equivalent. Tests:
`ItemMapperTest` UNKNOWN assertions become positive assertions, entity
round-trip, DAO tests. No visible behaviour change.

**Phase 2 — Browse UI + repository surface (sonnet).** `settings.gradle.kts`
+ `:feature:music` skeleton; `Routes` additions; repository members
`getAlbumTracks/getArtistAlbums/getArtistTopTracks/getPlaylistItems` ×3;
`AlbumCard`/`ArtistCard`; four browse screens + ViewModels;
`JellyfinNavHost` wiring + music-type branch in `onItemClick`; search types +
sections; `CollectionKind.MUSIC` joins `SUPPORTED`. Tests: ViewModels
(Turbine), online/offline repository members, search sectioning. Verify
disc/track sort (`ParentIndexNumber,IndexNumber`) against the dev server
early. DoD slice: browse a music library online, tablet layouts correct;
nothing plays yet.

**Phase 3 — Queue + background playback (the heart; opus).**
`MusicController`/`MusicPlaybackState` in `:core:common`; port/adapter
(playlist mode, audio-attributes switch, service start/stop reuse);
`MusicStreamResolver` + `audioUniversalUrl`; `MusicQueueSpecFactory`;
`MusicPlaybackController` (queue state, shuffle/repeat, transitions, SyncPlay
refusal); `PlaybackHandover` integrated into `PlaybackSessionController.open`
(video side) and the controller (music side); `PlaybackReporter`
parameterisation + `MusicReportTarget` + per-track reporting +
`UserDataRepository` write-through; `MusicSessionCallback` (best-effort).
Wire browse screens' play/shuffle/track-tap. **Regression gate: every
existing PlayerViewModel/controller/reporter test passes unchanged** — an
edit to an existing player test is a design smell to escalate, not patch.
Tests: controller (queue lifecycle, shuffle/repeat mapping, transition
reporting, SyncPlay refusal, handover snapshot/restore), resolver (direct vs
transcode inference, offline file, playSessionId uniqueness), handover
ordering invariant, reporter field mapping. **Verify early against dev
server + tablet:** universal-URL SDK builder shape, HLS transcode of an
unsupported container, dashboard PlayMethod, audio-focus survival across the
attributes switch, notification prev/next from playlist commands,
custom-layout rendering in 1.9.0 source. DoD slice: tap a track → album
queue plays in order; background + lock-screen controls; shuffle/repeat;
video⇄music handoffs are clean single sessions on the dashboard.

**Phase 4 — NowPlaying, queue sheet, mini-player, Continue Listening
(sonnet).** `NowPlayingScreen` (+route), `QueueSheet` (jump/remove/reorder —
local queue only), `MiniPlayer` in `:app` + `LocalAppChromePadding`
integration + visibility rules, `RESUME_AUDIO` home row +
`getResumeAudioItems` ×3, favorite toggles, tablet two-pane NowPlaying.
Tests: NowPlaying/home ViewModels, resume-audio repository members,
mini-player state derivation. DoD slice: mini-player docks on every tab,
survives navigation, opens NowPlaying; Continue Listening resumes at
position.

**Phase 5 — Music downloads + offline parity (opus for enqueuer/planner).**
`DownloadEnqueuer` album/playlist/artist expansion; `DownloadFilePlanner`
audio branch (original + album art); verify `/Items/{id}/Download` for audio
(fallback recorded if not); parent upsert of album/artist rows;
`DownloadedMetadataRefresher` audio awareness; offline repository music
members over the new DAO queries; offline playback via the resolver;
Downloads screen grouping for albums; `DownloadDeleter` cascade for
album/artist parents. Tests: enqueuer expansion order, planner audio plan,
offline repo, deleter cascade. DoD slice: download an album; airplane mode →
browse artist→album→tracks, play with full queue controls, art present.

**Phase 6 — Instant Mix + lyrics + hardening + docs.** `MusicApi`/
`SdkMusicApi`; `getInstantMix`/`getLyrics` ×3; radio actions; `LyricsPane`
with synced highlight; minified-build music smoke; `/document-feature` →
`docs/features/music.md` + `docs/ARCHITECTURE.md` refresh; DECISIONS entries
for implementation-time findings (universal URL details, notification
custom-layout outcome, `/Items/Download` audio finding); full DoD walk;
**tag `m13` only after M11/M12 device DoDs close**. Deferred list recorded
in PLAN.md: Android Auto browse tree (MediaLibraryService swap), casting
music, offline lyrics/instant-mix, audio download transcoding,
gapless/crossfade tuning, playlist editing, **offline playlist membership**.

**Offline playlist membership** (deferred in Phase 5; DECISIONS.md,
2026-08-06). `OfflineJellyfinRepository.getPlaylistItems` stays permanently
empty. An honest answer needs a new Room table (playlist id, item id, ordinal),
the schema bump that comes with it, and a sync path keeping it current with the
server's ordering — and the M13 DoD's offline walk is artist → album → tracks,
which does not go through a playlist. Downloading *from* a playlist is fully
supported: it expands to the playlist's audio members, which land under their
own albums and artists offline. The playlist's own row is deliberately **not**
cached as a download, so no offline library entry exists whose track list could
only ever be empty.

## Verification

- Per phase: `source "../env.sh"` && `./gradlew ktlintCheck detekt
  testDebugUnitTest :app:lintDebug` (+ assembleDebug via `/verify`).
- **Device DoD (user-run walk, test tablet + dev server 10.11, both
  orientations):**
  1. Music library tile on Home/Libraries → Albums/Artists/Playlists tabs,
     square album cards, correct in both orientations.
  2. Album → tracks in disc/track order; tap track 3 → starts there, next
     advances through the album; artist page shows albums + top tracks;
     playlist opens view-only.
  3. Background the app, screen off → playback continues;
     lock-screen/notification shows art/title/artist with working
     prev/next/pause; headset unplug pauses.
  4. Shuffle randomises (dashboard reports shuffle); repeat-one loops;
     repeat-all wraps the album.
  5. Mini-player docked above the bottom nav on every tab, survives
     navigation, tap → NowPlaying; queue sheet jumps/removes/reorders.
  6. flac → dashboard DirectPlay; unsupported container → Transcode, audio
     still plays.
  7. Start a video while music plays → music stops with a clean server
     session (no double session on the dashboard); back to music, play →
     queue resumes where it was. Reverse direction likewise.
  8. Search "artist / album / song" → sectioned results, each navigates.
  9. Download an album → airplane mode → browse artist→album→tracks
     offline, play with queue/shuffle, art shows; delete cleans up.
  10. Continue Listening resumes at position (online, and offline for a
      downloaded track).
  11. Album "Start radio" → Instant Mix plays; NowPlaying lyrics pane shows
      synced lyrics scrolling in time on a track that has them.
  12. Edges: in a SyncPlay group → music refused with message; favorite a
      track/album/artist → reflected in jellyfin-web; kill app mid-music →
      clean relaunch; minified build: repeat 3/6/9.

## Risks

- **Universal endpoint specifics** (SDK 1.8.12 builder name/params,
  playSessionId propagation, HLS output flavour) — Phase 3 verify-early;
  bounded fallback: per-track PlaybackInfo for the current item only,
  universal URLs for the rest of the queue.
- **Media3 1.9.0 custom-layout rendering** for shuffle/repeat buttons — read
  the source, not docs; dropping them costs one DoD sub-item, nothing
  structural.
- **Audio-attributes flip on a live player** — verify the MUSIC↔MOVIE flip
  keeps focus and the session; fallback is rebuilding the player at handover.
- **`/Items/{id}/Download` for audio originals** — believed kind-agnostic;
  verified in Phase 5 before the planner branch commits.
- **Audio resume positions on 10.11** (`mediaTypes=[AUDIO]`) — if the server
  doesn't record/serve them, Continue Listening degrades to recently-played
  (DECISIONS note).
- **Handover races** (double stop-report, stranded transcode) — the
  single-owner arbiter invariant, unit-tested.
- **Large queues** (a 500-track artist shuffle) — cap Instant-Mix/artist
  queues (~300) and note it.

## Execution notes

- Subagent delegation per CLAUDE.md: **opus** for Phase 3
  (controller/port/handover/reporting — the concurrency-sensitive core) and
  Phase 5's enqueuer/planner surgery; **sonnet** for Phases 1, 2, 4, 6
  mechanical work. Worktree isolation (main tree is shared with parallel
  sessions); governance rules + `source "../env.sh"` travel in every
  subagent prompt; the orchestrator runs `/verify` independently before
  every commit.
- Phase 3's regression gate (existing player tests unchanged) is the cheap
  proof that video playback, SyncPlay and Cast are untouched.
