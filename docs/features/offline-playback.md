# Feature: Offline playback + sync — M8

Playing a downloaded item without a network, and getting what happened during that session back to
the server once there is one. This is the milestone that closes the loop the product's differentiator
is built on: downloaded media is not a separate mode, it is the same player, the same detail screen
and the same resume position.

**Definition of done (docs/PLAN.md):** airplane-mode playback to 50 % → reconnect → the server shows
a 50 % resume position.

## The two halves

```
              ┌── LocalPlaybackResolver ──► DownloadedMediaProvider ──► Room + disk
PlayerViewModel ─► PlaybackSourceResolver ─┤
              └── PlaybackInfoResolver ───► POST /Items/{id}/PlaybackInfo

PlaybackReporter ─► (server, when online and remote)  reportStart/Progress/Stopped
                 └► (always)  UserDataRepository.setPosition / setPlayed
                                     └─► user_data row, toBeSynced = true
                                              ▲
ConnectionState → ONLINE ─► UserDataSyncTrigger ─► UserDataSyncWorker ─► UserDataSyncer
                                                                            └─► most-recent-wins
```

## 1. Resolving a downloaded item — `:player`

### The selection rule

`PlaybackSourceResolver` is the only thing `PlayerViewModel` asks:

| Situation | Result |
|---|---|
| The item has a **completed** download | The local file — **whatever the connection is doing** |
| No local copy, online | `PlaybackInfoResolver`, exactly as M5 |
| No local copy, offline | `AppError.Network` **immediately** — no request, no timeout, no spinner |
| `enableDirectPlay = false` (the decoder-fallback retry) | Skips the local copy; those are the bytes that just failed |

"A download always wins" is the milestone's differentiator, not an optimisation — see `DECISIONS.md`,
2026-07-29. It also means the offline code path is the everyday code path rather than a rarely
exercised branch.

### What counts as playable locally

`DownloadedMediaProvider` (`:data:downloads`) answers `null` — the player then streams — unless
**all** of these hold:

- the `downloads` row is `DOWNLOADED`;
- its `MEDIA` file row is `DOWNLOADED`;
- that file is still on disk (`File.isFile`). Rows survive an external-storage wipe; bytes do not,
  and a `file://` URI pointing at nothing surfaces as an ExoPlayer source error several seconds into
  a blank screen.

Optional files are filtered the same way, one at a time: a subtitle sidecar that failed or vanished
is simply not offered. That is the plan's "optional-file failure → item still playable", one language
short.

Being the one gate every offline playback passes through also makes it the place a **transcoded**
download is made seekable. Those files land without the `SeekHead` their `Cues` need, so every drag
of the seek bar used to restart the item from zero; `MatroskaSeekIndexRepair` writes the missing 26
bytes into the Void the muxer reserved for them, here rather than in the download pipeline, because
this is the only path that also reaches downloads made before the fix — see
docs/features/download-quality.md, *"No seek index — until the client writes one"*. It is idempotent,
costs two twelve-byte reads for a file that is already indexed, and never changes a file's length.

**Audio sidecars need none of this.** `MatroskaSeekIndexRepair` only ever runs against the `MEDIA`
file — an `audio.<index>.<lang>.m4a` sidecar is not Matroska at all and is not passed through it. It
does not need to be: the sidecar is produced *locally*, by a Media3 `Transformer` that writes the
whole file's `moov` up front because it already has every byte of the fetched mkv on disk before it
starts (unlike the server's own live encode, which streams before it can know where `mdat` ends). A
sidecar is therefore natively seekable from the moment the strip finishes, with no repair gate of any
kind standing between it and playback — the gate that exists for a sidecar is the strip itself
(`docs/features/download-quality.md`, *"Every other audio language"*): fail there and the row simply
has no sidecar, rather than an unseekable one.

### What it builds

`LocalPlaybackResolver` turns that into a `LocalPlaybackMediaSource` — the second variant of the
sealed `PlaybackMediaSource`, so nothing above it branches on online/offline:

| Field | Source |
|---|---|
| `mediaUri` | `DownloadFileEntity.path` of the `MEDIA` file, as a `file://` URI |
| `playMethod` | `DIRECT_PLAY`, by construction — there is nothing to remux or transcode |
| `runTimeTicks` | the cached `MediaSourceInfo`, falling back to the item's own runtime |
| `audioTracks` / `subtitleTracks` | the cached blob's `mediaStreams` **filtered to what the file can play**, through the **same** `toTrack` mapper the online resolver uses |
| `allAudioTracks` / `allSubtitleTracks` | the same streams *unfiltered* — every track the source has |
| `externalSubtitles` | the downloaded sidecars, MIME type from the stream's codec (or the file's extension) |
| `trickplay` | the downloaded tile sheets plus the server's geometry |

### Which tracks are offered

The cached blob describes the **source**. The file on disk may not be the source, so its stream list
is filtered rather than trusted:

| Track | Offered when |
|---|---|
| Audio, `ORIGINAL` download | always — the file is the source file |
| Audio, transcoded download | the stream `DownloadEntity.bakedAudioStreamIndex` names, **plus one more for every audio sidecar on disk** (`DownloadedMedia.audio`) — every language the transcode did not bake in still got its own `.m4a`, offline multi-track Phase 2 (DECISIONS.md, 2026-07-31) |
| Subtitle, any quality | **its sidecar is on disk** — whatever the stream's own `isExternal` says |
| Subtitle, embedded, no sidecar | only in a download the server did not re-encode |

A transcoded download's audio picker is therefore no longer capped at one entry: a two-language film
lists both languages offline, exactly as it would streaming. A track the transcode never fetched at
all — because the strip failed, or because the row predates Phase 2 — is genuinely not on disk and
follows the ordinary rule below: not offered offline, streamed from the server when there is one.

Three consequences worth stating outright:

- **An embedded subtitle can be offered from a sidecar.** Since the phase-0 planner change, a
  transcoded download fetches an extracted `.srt` for every embedded text subtitle the server will
  hand over (`docs/features/downloads.md`, file plan). Those tracks are marked `isExternal = true` on
  the `PlaybackTrack` even though the *stream* is embedded, because that flag describes how the track
  reaches ExoPlayer: `TrackSelectionController` matches side-loaded groups by their
  `external:<index>` id and counts everything else by position among the container's text groups — of
  which a transcode has none. `toTrack(defaultIndex, sideLoaded = …)` is where that is decided.
- **The baked audio track is read, not guessed.** For a row written before schema v8 the column is
  `NULL`; those downloads named no `audioStreamIndex` either, so the server used the source's
  `defaultAudioStreamIndex` and the resolver falls back to exactly that — then to the first audio
  stream. See `docs/features/download-quality.md`, *"One audio track, and which one"*.
- **An extra audio language is offered from a sidecar, the same way.** `LocalPlaybackResolver` lists
  the baked track first, then one entry per `DownloadedAudio` on disk in ascending `streamIndex`
  order (`DownloadedMedia.audio`, itself sorted that way) — **this order is the contract**: it is
  exactly the order `ExoPlayerHandle.prepare` builds its `MergingMediaSource` children in, and the
  only thing that lets a later selection find its way back to the right one (below).

### Merged playback and the child-order contract

A transcoded download with any audio sidecars is not opened with `setMediaItem` — `ExoPlayerHandle
.prepare` wraps the primary source and every sidecar in a `MergingMediaSource`
(`adjustPeriodTimeOffsets = false`, `clipDurations = true`, which absorbs the few milliseconds of
drift between a re-encoded video and its separately transcoded audio). `MediaItem` has no audio
analogue of `SubtitleConfiguration`, so unlike a subtitle a sidecar cannot simply ride along on the
item; this is the one place `:player` assembles a `MediaSource` by hand rather than through
`ExoMediaSourceFactory`'s ordinary decision table (DECISIONS.md, 2026-07-31).

**Element `i` of `PlaybackMediaItemSpec.audioSidecars` becomes merge child `i + 1`** — child `0` is
always the primary source — and `TrackSelectionController.selectAudio` reads that position back:
the *k*-th external audio track maps to the ExoPlayer group whose track-group id carries merge-child
prefix `k + 1`, and container (non-sidecar) tracks are counted only among the groups that carry no
sidecar prefix at all. With no sidecars both paths collapse to the same positional match the picker
always used.

**A merge can double-prefix an id.** `MergingMediaPeriod` re-ids every child's track groups as
`"<childIndex>:<originalId>"`. A downloaded item with both audio sidecars and embedded-subtitle
sidecars is merged *twice* — `DefaultMediaSourceFactory` already wraps the main item in its own merge
for the subtitle `SubtitleConfiguration`s, and `ExoPlayerHandle` merges that whole thing again for the
audio files — so a subtitle id such as `external:2` can come back as `0:1:external:2` rather than the
single-prefixed `1:external:2` phase 1 pinned. `withoutMergePrefixes()` strips a leading *run* of
numeric `<digits>:` prefixes rather than just one, which is what keeps `jellyfinIndexOfTrackId` correct
whichever of the two merges — or both — is in play.

A default subtitle index that is not in the offered set is dropped rather than selected, and a
requested audio or subtitle index the file cannot supply falls back to the default rather than
leaving the picker pointing at nothing.

An item whose cached blob no longer decodes still plays — with no track lists. Refusing to open a
film because its metadata is unreadable would be the worse failure.

### …and which tracks are *shown*, which is not the same question

The table above is what the **file** can play. What the picker offers depends on whether there is a
server to fall back on, and `PlayerViewModel` derives it from (source, `ConnectionStateProvider`):

| | audio / subtitle picker shows | a row the file cannot play does |
|---|---|---|
| Downloaded item, **online** | `allAudioTracks` / `allSubtitleTracks` — everything the item has | reopen with `forceRemote`, streaming that track from the server |
| Downloaded item, **offline** | `audioTracks` / `subtitleTracks` — only what the file and its sidecars hold | cannot be offered; if one is tapped anyway it is refused |
| Streamed item | its own lists, unchanged since M5 | re-resolve, exactly as M5 |

The connection is **collected**, not read once: dropping the network with the audio sheet open
withdraws the rows that just became unreachable, and getting it back restores them.

`PlaybackResolveRequest.forceRemote` is what makes the streaming half possible. A plain re-resolve
of a downloaded item hits rule 1 above and returns the same file with the same tracks — the loop
`PlayerMessage.TrackUnavailableOffline` exists to stop — so the request carries an explicit "skip the
local copy" that `PlaybackSourceResolver` honours. It is deliberately distinct from
`enableDirectPlay = false`, which says *these bytes cannot be decoded* and therefore also forbids the
server's own direct play. The reopen goes through the usual path, so the outgoing transcode is
stopped first and the position is carried over.

Three consequences that are decisions rather than mechanics:

- **The flag is sticky for the session.** `PlayerViewModel.forcedRemote` is carried into every later
  re-negotiation, so changing quality — or a decoder fallback — while streaming a downloaded item
  does not silently drop back to the file and lose the track that was gone to the server for.
- **Choosing a track the file *does* hold goes home.** The file is offline-proof and free, so a
  forced-remote session that no longer needs the server reopens without the flag and
  `PlaybackSourceResolver` picks the download again. `PlayerViewModel` keeps the last
  `LocalPlaybackMediaSource` of the session precisely to answer "could the file have done this".
- **A server that is not there does not cost the session.** Between the picker being drawn and the
  request going out the network can die — and a *server* that died reads as online until a probe
  says otherwise, so the connectivity check cannot catch every case. A failed forced-remote resolve
  therefore reopens the local file rather than showing an error over playback that was fine a second
  ago, with the same `TrackUnavailableOffline` message a refused offline switch gets.

`refuseLocalTrackChange` remains the offline backstop: with the picker no longer offering
unplayable rows it should be unreachable from the UI, and it stays the honest answer for a selection
restored from a previous session, a container that disagrees with the cached blob, or a tap that
lands in the moment the network drops.

### URIs

`localFileUri` builds `file:///…` through `java.net.URI("file", "", path, null, null)`, which
percent-encodes the path. Downloaded files are named after the media (`A Movie (2026)/A Movie #1.mkv`),
and a bare `#` in a concatenated URI is parsed as a fragment and truncates the path.
`ExoMediaSourceFactory` passes both the media URI and the sidecar URIs through **unchanged** —
running them through `StreamUrlFactory.absoluteUrl` would produce `https://serverfile:///…`.

Sidecars keep the same `external:<jellyfinIndex>` track-id convention as online, so
`TrackSelectionController` needs no branch at all — including for a sidecar extracted from an
*embedded* stream, which is precisely why the resolver marks such a track side-loaded.

### The one UI difference

`PlayerUiState.isLocalPlayback` hides the quality picker, which caps a *streaming* bitrate and has
nothing to act on for a local file. Everything else — track pickers, subtitle sheet, play-method
badge, transport controls, seek bar — is identical (`DECISIONS.md`, 2026-07-29). The pickers'
*contents* vary with the connection, as above, but their shape does not, and no row is marked or
decorated: a track that will restart playback as a stream looks like any other, and the restart is
announced afterwards in the snackbar (`PlayerMessage.StreamingForTrackChange`, "That track isn't in
the download — streaming it from your server"). That is exactly how the quality picker communicates
its own restart, and the player deliberately has no other idiom for "this control reloads".

One visible knock-on, and it is the right one: `PlayerControls` only draws the audio button when
there is more than one track and the subtitle button when there is at least one, so a transcoded
download — one baked audio track — grows an audio button when the app is online and loses it again
when it goes offline.

### Trickplay

`LocalTrickplay` carries the tile `file://` URIs in tile order plus the geometry, and
`tileFor(positionMs)` resolves a position to a sheet, a column and a row. Nothing draws it yet —
M9's scrubber does, for the online and offline cases at once (`DECISIONS.md`, 2026-07-29).

## 2. Reporting while offline — `:player`

`PlaybackReporter` skips the **server** half of every report when the source is local, or when
`ConnectionStateProvider` says the app is offline:

| | server report | `stopEncodingProcess` | local write |
|---|---|---|---|
| Remote source, online | yes | when transcoding | yes |
| Remote source, offline | **no** | **no** | **yes** |
| Local source | **no** | **no** | **yes** |

The local write is what the milestone depends on and it is unconditional: `setPosition` on every
five-second tick and on a mid-item stop, `setPlayed` on ENDED. Each goes through
`UserDataRepositoryImpl`, which writes the row with `toBeSynced = true` *before* attempting any push,
so an airplane-mode session accumulates exactly the pending rows the worker drains later.

The stop report still runs on the detached scope. Offline it sends nothing, but it is the only thing
that records where the user got to when the screen closes.

## 3. Draining the pending rows — `:data`

`UserDataSyncWorker` is real from M8 (it was a documented stub through M4–M7). The worker itself is
three lines of mapping; the rule lives in `UserDataSyncer`, which runs on the JVM.

### Most-recent-wins, per row

```
server = GET /Users/{userId}/Items/{itemId} → userData
```

| Server state | Decision |
|---|---|
| `lastPlayedDate` **after** `row.updatedAt` | **adopt** — upsert the server's values with `toBeSynced = false`, publish on the event bus |
| exactly equal | **adopt** — the server already holds this instant; adopting is idempotent |
| `lastPlayedDate` **before** `row.updatedAt` | **push**, then `clearPendingSync(itemId, userId, updatedAt)` |
| `lastPlayedDate` is `null` | **push** — a server that never played it cannot outrank a local change |
| no `userData` at all | **push** |
| transport failure (fetch or push) | **keep the flag**, drain returns `Result.retry()` |
| `404` | **abandon** — clear the flag, log; the item is gone from the server |

The comparison is the local `updatedAt` against the server's `lastPlayedDate`, deliberately not the
two `lastPlayedDate`s: a favourite toggle never touches `lastPlayedDate`, so comparing those would
make every offline favourite lose to a film watched last week. Both instants go through
`SdkDateTime`'s helpers — the SDK's `LocalDateTime` is local wall-clock time in both directions.

One row failing never abandons the rest. A drain in which *any* row failed returns
`SyncOutcome.RETRY` → `Result.retry()` → WorkManager's exponential backoff; the rows that succeeded
are already clear and do not come back.

### What a push sends

Three calls, in this order, mirroring `UserDataRepositoryImpl`'s wire choices:

1. `playStateApi.markPlayedItem(datePlayed = lastPlayedDate)` or `markUnplayedItem`
2. `userLibraryApi.markFavoriteItem` or `unmarkFavoriteItem`
3. `itemsApi.updateItemUserData` with the **full** desired state

Order matters: `markPlayedItem` clears the server's resume position, so the position has to be
asserted after it. The worker asserts the whole row rather than guessing which operation produced it,
because an offline session batches several into one pending row.

### When the worker runs

| Trigger | Owner |
|---|---|
| A local write whose push failed | `UserDataRepositoryImpl` (M4) |
| App start, when rows are already pending | `UserDataSyncTrigger` |
| Every transition back to `ConnectionState.ONLINE` | `UserDataSyncTrigger` |

`UserDataSyncTrigger` collects `ConnectionStateProvider.state`; the flow replays its current value, so
the first collection *is* the app-start check and every later `false → true` edge is the reconnect
one. A `countPendingSync()` guard keeps a normal launch at one indexed `COUNT(*)` and no scheduled
work. `JellyfinNativeApplication.onCreate` starts it — from the `Application`, not a ViewModel,
because a device coming back online with the app backgrounded is the case that matters.

Scheduling itself is unchanged from M4: unique work named `user-data-sync`, `ExistingWorkPolicy.KEEP`
(the worker drains whatever is pending when it runs, so a burst of failed toggles must not push the
one scheduled run further out), `NetworkType.CONNECTED`, exponential backoff from 30 s.

## 4. The detail screen offline

Unchanged, and that is the point. `ItemDetailViewModel` asks `JellyfinRepository`, the delegating
repository serves the cached blob from Room, and the Play/Resume button's target and start position
come from `JellyfinItem.userData` — which offline is the *local* row, overlaid by
`OfflineJellyfinRepository`. A position written by the player five seconds ago arrives on the
`UserDataEventBus` and turns the button into *Resume* with no request at all.

## Key classes

| Class | Module | Responsibility |
|---|---|---|
| `PlaybackSourceResolver` | `:player` | Local vs server, per playback; `forceRemote` is how a caller overrides it |
| `LocalPlaybackResolver` | `:player` | Builds a `LocalPlaybackMediaSource` from what is on disk |
| `LocalPlaybackMediaSource` / `LocalTrickplay` | `:player` | The offline half of the sealed source type |
| `ExoMediaSourceFactory` | `:player` | Now handles both variants; local URIs pass through untouched |
| `ExoPlayerHandle.prepare` / `toMergedSource` | `:player` | Builds the `MergingMediaSource` over the primary source and every audio sidecar, when the spec has any |
| `TrackSelectionController` | `:player` | Maps a merge child back to a Jellyfin stream index (`mergeChildIndex`), for both audio and subtitle sidecars |
| `PlaybackReporter` | `:player` | Skips the server triad offline; always writes locally |
| `DownloadedMediaProvider` / `DownloadedMedia` / `DownloadedAudio` | `:data:downloads` | "What is on disk for this item", Room + filesystem; `DownloadedMedia.audio` lists the on-disk sidecars in merge-child order |
| `AudioSidecarExtractor` | `:data:downloads` | Transmuxes a fetched mkv into the m4a a sidecar row names (Media3 `Transformer`, no re-encode) |
| `DownloadDao.getWithFiles` | `:core:database` | One download with its file rows |
| `UserDataSyncer` / `SyncOutcome` | `:data` | Most-recent-wins, per pending row |
| `UserDataSyncWorker` | `:data` | Maps a drain onto a WorkManager result |
| `UserDataSyncTrigger` | `:data` | Enqueues on app start and on reconnection |

## Tests

| Class | Tests | Covers |
|---|---|---|
| `DownloadedMediaProviderTest` | 23 | The playable/not-playable gate against **real temp files**, `file://` encoding, dash-insensitive media-source matching, sidecar and tile filtering, the baked audio index carried through (and absent on a pre-v8 row), the seek-index repair being asked for the media file in milliseconds — but never for an item that is not playable anyway — and `DownloadedMedia.audio` listing each on-disk `AUDIO` row ascending by stream index, existence-gated exactly like subtitles |
| `LocalPlaybackResolverTest` | 39 | Track lists, withheld external subtitles, MIME-type fallback, explicit track choices, trickplay addressing; the baked audio index driving the transcoded picker (with the legacy-`NULL` and unknown-index fallbacks); an embedded subtitle offered from its sidecar and flagged side-loaded, one without a sidecar still withheld; the source's full lists carried alongside the playable ones and labelled from the source's own defaults; every downloaded audio sidecar offered after the baked track, in ascending stream-index order, and excluded when its stream is missing from the source or duplicates the baked one |
| `PlaybackSourceResolverTest` | 12 | The full selection matrix incl. the forced-transcode exception, `forceRemote` skipping the local resolver, and the immediate offline failure |
| `PlaybackReporterTest` | +7 | Local and offline sessions report nothing and still write every position |
| `UserDataSyncerTest` | 17 | The whole most-recent-wins matrix, push ordering, the timezone round trip, partial batch failure |
| `UserDataSyncTriggerTest` | 7 | App start, reconnection edges, the pending guard, idempotent `start()` |
| `ExoMediaSourceFactoryTest` | 16 | Local URIs and sidecars pass through unprefixed; a spec with audio sidecars building sources for the primary item plus each sidecar |
| `PlaybackMediaItemSpecTest` | 7 | `audioSidecars` carried on the spec; `jellyfinIndexOfTrackId` stripping one merge-child prefix, stripping *two* for a doubly-merged (audio + subtitle) id, and leaving a container track's own numeric id alone |
| `TrackSelectionControllerTest` | 17 | With no sidecars, audio and subtitle selection are byte-for-byte the old positional match; with sidecars, the *k*-th external audio track maps to the group whose id carries merge-child prefix `k + 1`, and container tracks are counted only among the non-sidecar groups |
| `PlayerViewModelTest` | +3 | `isLocalPlayback`, the inert quality picker, the detached stop |
| `PlayerTrackPickerTest` | 13 | The whole picker rule: full list online / playable list offline, the live connectivity flip, a missing track streamed with `forceRemote` at the current position, a track the file holds going back to the download, the flag surviving a quality change, the offline refusals, and the fallback when the server turns out not to be there |
| `ItemDetailViewModelTest` | +1 | An offline position turns the button into *Resume* with no refetch |

## Offline behaviour

Everything in this document *is* the offline behaviour. Two things still need a network: an item that
was never downloaded — offline the Play button fails fast with "Can't reach your server" rather than
hanging — and a track a downloaded file does not contain, which offline is simply not offered.
