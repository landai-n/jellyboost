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

### What it builds

`LocalPlaybackResolver` turns that into a `LocalPlaybackMediaSource` — the second variant of the
sealed `PlaybackMediaSource`, so nothing above it branches on online/offline:

| Field | Source |
|---|---|
| `mediaUri` | `DownloadFileEntity.path` of the `MEDIA` file, as a `file://` URI |
| `playMethod` | `DIRECT_PLAY`, by construction — there is nothing to remux or transcode |
| `runTimeTicks` | the cached `MediaSourceInfo`, falling back to the item's own runtime |
| `audioTracks` / `subtitleTracks` | the cached blob's `mediaStreams`, through the **same** `toTrack` mapper the online resolver uses |
| `externalSubtitles` | the downloaded sidecars, MIME type from the stream's codec (or the file's extension) |
| `trickplay` | the downloaded tile sheets plus the server's geometry |

Subtitle tracks are filtered: every *embedded* stream is offered (ExoPlayer reads them out of the
container) but an *external* one only when its sidecar is on disk. A default subtitle index that is
not in the offered set is dropped rather than selected.

An item whose cached blob no longer decodes still plays — with no track lists. Refusing to open a
film because its metadata is unreadable would be the worse failure.

### URIs

`localFileUri` builds `file:///…` through `java.net.URI("file", "", path, null, null)`, which
percent-encodes the path. Downloaded files are named after the media (`A Movie (2026)/A Movie #1.mkv`),
and a bare `#` in a concatenated URI is parsed as a fragment and truncates the path.
`ExoMediaSourceFactory` passes both the media URI and the sidecar URIs through **unchanged** —
running them through `StreamUrlFactory.absoluteUrl` would produce `https://serverfile:///…`.

Sidecars keep the same `external:<jellyfinIndex>` track-id convention as online, so
`TrackSelectionController` needs no branch at all.

### The one UI difference

`PlayerUiState.isLocalPlayback` hides the quality picker, which caps a *streaming* bitrate and has
nothing to act on for a local file. Everything else — track pickers, subtitle sheet, play-method
badge, transport controls, seek bar — is identical (`DECISIONS.md`, 2026-07-29).

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
| `PlaybackSourceResolver` | `:player` | Local vs server, per playback |
| `LocalPlaybackResolver` | `:player` | Builds a `LocalPlaybackMediaSource` from what is on disk |
| `LocalPlaybackMediaSource` / `LocalTrickplay` | `:player` | The offline half of the sealed source type |
| `ExoMediaSourceFactory` | `:player` | Now handles both variants; local URIs pass through untouched |
| `PlaybackReporter` | `:player` | Skips the server triad offline; always writes locally |
| `DownloadedMediaProvider` / `DownloadedMedia` | `:data:downloads` | "What is on disk for this item", Room + filesystem |
| `DownloadDao.getWithFiles` | `:core:database` | One download with its file rows |
| `UserDataSyncer` / `SyncOutcome` | `:data` | Most-recent-wins, per pending row |
| `UserDataSyncWorker` | `:data` | Maps a drain onto a WorkManager result |
| `UserDataSyncTrigger` | `:data` | Enqueues on app start and on reconnection |

## Tests

| Class | Tests | Covers |
|---|---|---|
| `DownloadedMediaProviderTest` | 15 | The playable/not-playable gate against **real temp files**, `file://` encoding, dash-insensitive media-source matching, sidecar and tile filtering, and the seek-index repair being asked for the media file in milliseconds — but never for an item that is not playable anyway |
| `LocalPlaybackResolverTest` | 14 | Track lists, withheld external subtitles, MIME-type fallback, explicit track choices, trickplay addressing |
| `PlaybackSourceResolverTest` | 8 | The full selection matrix incl. the forced-transcode exception and the immediate offline failure |
| `PlaybackReporterTest` | +7 | Local and offline sessions report nothing and still write every position |
| `UserDataSyncerTest` | 17 | The whole most-recent-wins matrix, push ordering, the timezone round trip, partial batch failure |
| `UserDataSyncTriggerTest` | 7 | App start, reconnection edges, the pending guard, idempotent `start()` |
| `ExoMediaSourceFactoryTest` | +2 | Local URIs and sidecars pass through unprefixed |
| `PlayerViewModelTest` | +5 | `isLocalPlayback`, the inert quality picker, local track switching, the detached stop |
| `ItemDetailViewModelTest` | +1 | An offline position turns the button into *Resume* with no refetch |

## Offline behaviour

Everything in this document *is* the offline behaviour. The one thing that still needs a network is
an item that was never downloaded: offline the Play button fails fast with "Can't reach your server",
rather than hanging.
