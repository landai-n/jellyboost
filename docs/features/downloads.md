# Downloads (M7)

The app's differentiator: media kept on the device, visible and manageable in the same UI as
everything else. This document covers the pipeline (`:data:downloads`), the screen
(`:feature:downloads`), and the two places downloads reach into the rest of the app — the badge on
every card and the Download button on the detail page.

Downloaded **playback** is deliberately *not* here: `LocalPlaybackResolver` is M8. M7 ends at files
on disk plus Room rows; tapping Play on a downloaded item still takes the online path.

---

## What it does

| Surface | Behaviour |
|---|---|
| Detail screen | *Download* enqueues; the same button then reads *Cancel* / *Remove* / *Retry* and shows live progress. On a **season or series** it enqueues the episodes underneath (see [Containers](#containers-a-season-is-its-episodes)). |
| Every item card | `DownloadBadge` — queued, downloading (ring), paused, downloaded (tick), failed. |
| Downloads tab | *Downloaded* (grouped by show or film, sizes, delete) and *Queue* (progress, speed, pause/resume/cancel/reorder), with a storage header and the Wi-Fi-only toggle. |
| Notification | Foreground, per-item progress, Pause and Cancel actions. |
| Offline | Every downloaded item appears in the offline home / library / search, because the pipeline writes `ItemEntity(source = DOWNLOAD)` rows (M6 reads exactly those). |

---

## Pipeline

```
ItemDetailViewModel.onDownloadClick()
        │
        ▼
DownloadRepository.enqueue(itemId)
        │
        ├── DownloadEnqueuer ── a folder?  ── yes ──► DownloadApi.getEpisodeIds(seriesId, seasonId?)
        │        │                                    (one row per episode, container row deleted)
        │        ├── DownloadApi.getFullItems([items] + parents)             ← one server call
        │        ├── ItemDao.upsert(ItemEntity(source = DOWNLOAD))           ← items AND series/season
        │        └── DownloadDao.upsert(DownloadEntity(QUEUED))
        │
        └── DownloadScheduler.ensureRunning()  →  enqueueUniqueWork("downloads", KEEP)
                                                        │
                                                        ▼
                                                 DownloadWorker  (foreground)
                                                        │
                                                        ▼
                                                  DownloadQueue.drain()
                                        ┌───────────────┴────────────────┐
                              DownloadFilePlanner              FileDownloader
                              (per-item file plan)             (OkHttp + Range resume)
                                                        │
                                                        ▼
                                             DownloadDao (progress, statuses)
                                                        │
                                          Room Flows ───┴──► badges, queue tab, notification
```

### Key classes

| Class | Module | Responsibility |
|---|---|---|
| `DownloadRepository` / `DownloadRepositoryImpl` | `:data:downloads` | The only type features see. Owns the decisions (what pause means, what cancel and delete share) and the ordering of every mutation. |
| `DownloadEnqueuer` | `:data:downloads` | The full-fields re-fetch, the `source = DOWNLOAD` cache write for the item **and its parents**, the `DownloadEntity` rows — and the expansion of a season or series into one download per episode. |
| `DownloadApi` / `SdkDownloadApi` | `:data:downloads` | The SDK calls the pipeline makes (`getItems` for the full DTOs, `/Shows/{id}/Episodes` for a container's children), behind a seam so enqueueing is unit-testable. |
| `DownloadedMetadataRefresher` | `:data:downloads` | **Ongoing sync, not dead migration code.** Re-runs the enqueuer's cache write for **every** downloaded item (and its parents) once per stretch of connectivity, so a download's cached metadata keeps tracking the server's — retitles, artwork changes, corrected overviews, renumbered episodes. Healing rows an older build gutted is the first thing it happens to do, not its purpose. See `docs/features/offline-read.md`, "Downloaded metadata stays current". |
| `isFolderItem` (`FolderItems.kt`) | `:data:downloads` | The one predicate for "this is a folder, not a video" — `isFolder`, with a kind list as the fallback. |
| `DownloadFilePlanner` | `:data:downloads` | `BaseItemDto` → ordered `List<PlannedFile>`, with the essential/optional split. |
| `DownloadUrlFactory` / `SdkDownloadUrlFactory` | `:data:downloads` | Every URL the pipeline fetches, behind a seam (a real `ApiClient` has no base URL in a JVM test). |
| `DownloadPaths` | `:data:downloads` | Directory and file naming; pure, fully unit-tested. |
| `DownloadStorage` / `FileDownloadStorage` | `:data:downloads` | Where files live. The interface is the seam a SAF backend would go behind. |
| `StorageLocationManager` | `:data:downloads` | Which volume that root sits on: the stored choice if it is mounted, the primary volume otherwise. Caches the choice so the non-suspending `DownloadStorage` surface can stay non-suspending. |
| `StorageVolumeProvider` / `AndroidStorageVolumeProvider` | `:data:downloads` | Enumerates the mounted volumes from `getExternalFilesDirs` + `StorageManager`; an interface so the resolution rules are testable on the JVM. |
| `FileDownloader` | `:data:downloads` | One file over OkHttp, with HTTP `Range` resume and 64 KB progress callbacks. |
| `ProgressThrottle` | `:data:downloads` | Decides which callbacks are worth a Room write (500 ms **or** 1 %). |
| `DownloadQueue` | `:data:downloads` | One item at a time, in queue order; the status machine. |
| `DownloadWorker` | `:data:downloads` | Runs the queue as WorkManager foreground work. |
| `DownloadScheduler` / `WorkManagerDownloadScheduler` | `:data:downloads` | Unique work + constraints (UNMETERED when Wi-Fi-only, storage-not-low). |
| `DownloadNotifier` | `:data:downloads` | The foreground notification and its Pause / Cancel actions. |
| `DownloadActionReceiver` | `:data:downloads` | Backs those two actions. |
| `DownloadDeleter` | `:data:downloads` | The delete cascade. |
| `DownloadsViewModel` / `DownloadsScreen` | `:feature:downloads` | The two tabs, the storage header, the Wi-Fi-only toggle. |
| `DownloadSpeedTracker` | `:feature:downloads` | Derives bytes/second from successive Room emissions (no speed column exists). |

---

## Containers: a season is its episodes

A season and a series are **folders**. There is no file behind one, and `/Items/{id}/Download`
answers `400` if you ask — which is exactly what a tap on a season's Download button used to produce:
a single `DownloadEntity` keyed on the season, permanently `ERROR`, reading *"The server couldn't
send this download (error 400)"* (docs/POLISH.md; DECISIONS.md, 2026-07-29).

Expansion happens in **`DownloadEnqueuer`**, not in the ViewModel, so that every caller of
`DownloadRepository.enqueue` is type-safe by construction:

| Item | What is enqueued |
|---|---|
| Movie, episode | Itself. Unchanged. |
| Season | One download per episode of that season, in the server's (broadcast) order. |
| Series | One download per episode of the show, every season, one `/Shows/{id}/Episodes` request. |
| Anything else that is a folder | Refused — nothing is written. |

Rules that matter:

- **Episodes already spoken for are skipped.** Any existing row except `ERROR` is left exactly as it
  is, so re-tapping Download on a half-downloaded season adds only what is missing. `ERROR` is
  re-enqueued, keeping its queue position and the bytes a `Range` resume will pick up from.
- **Each episode row is stamped exactly as a direct tap on that episode would have stamped it** —
  the same full re-fetch, the same paths, and the one download-quality value read once for the whole
  expansion (a preference changed mid-season cannot split it across two containers).
- **The container's own row is deleted** when one exists, through the ordinary delete cascade. Those
  rows only exist on devices that predate this fix and can never succeed.
- **`DownloadFilePlanner` refuses a folder** (`NotDownloadableException`) before it builds a URL.
  With expansion in place that is unreachable through the UI; it is the guard that keeps a future
  caller's mistake from becoming an unexplained `400` halfway down the queue, and it gives the row
  copy that says what to do rather than a status code.

On the **detail screen** the button follows: a season has no row of its own, so its state is the
aggregate of its episodes' (`aggregateDownloadState`) — all downloaded is *Downloaded*, anything
transferring is *Downloading* at the season's own percentage, anything failed with nothing running is
*Retry*, and a partly-downloaded season still offers *Download* for the rest. Remove and cancel act
on the episode rows, not on the season id. A **series** page keeps the plain button (it lists seasons,
not episodes, so it has nothing to aggregate): its tap always enqueues, which is idempotent because
expansion skips what is already there.

---

## File plan

Per item, in a directory named `Series - S01E02 - Title` or `Movie (Year)` — sanitised for exFAT
(`\ / : * ? " < > |` become spaces; hyphens are kept, since the format itself uses ` - `).

Order is the contract:

1. **`IMAGE_PRIMARY`** — `primary.webp`, 480 px. First so the queue row and the notification have
   artwork within a second rather than after the two gigabytes.
2. **`MEDIA`** — the item's own filename from `path`, else `<directory>.<container>`, **when the
   download is fetched at its `ORIGINAL` quality** (the default, and the only quality this document
   otherwise describes). **Essential.**
3. **`IMAGE_BACKDROP`** — `backdrop.webp`, 1280 px (the offline detail header draws it full-bleed).
4. **`IMAGE_SERIES_PRIMARY`** — `series-primary.webp`, 300 px; lets a downloaded episode render its
   show offline without the series being downloaded.
5. **`SUBTITLE`** — `subtitle.<index>.<lang>.<ext>`, one per *external text* stream. Embedded tracks
   are skipped (they travel inside the media file); bitmap formats (PGS, VobSub) are skipped because
   ExoPlayer cannot play them from a sidecar file.
6. **`TRICKPLAY_TILE`** — `trickplay.<width>.<index>.jpg`, only the widest resolution the server
   generated. The tile count is derived: `ceil(thumbnailCount / (tileWidth × tileHeight))`.

**Essential vs optional.** Only the media file is essential. Its failure marks the item
`ERROR`; any other file failing marks *that file* `ERROR` and the item still finishes `DOWNLOADED` —
a film without its backdrop is still a film.

**A download can also ask for less than the original file.** Everything in this section describes
the `ORIGINAL` quality — the plan's own behaviour and still the default. When the user's *download
quality* setting is `HIGH`/`MEDIUM`/`LOW`, the `MEDIA` entry is a server-side transcode instead: a
different URL, a different (fixed) file name, no exact size and no `Range` resume. That path, its
reasoning and its tests are documented separately in
[`docs/features/download-quality.md`](download-quality.md) rather than duplicated here.

### Endpoints

| File | Call |
|---|---|
| media | `libraryApi.getDownloadUrl(itemId)` |
| media (fallback) | `videosApi.getVideoStreamUrl(itemId, static = true, mediaSourceId)` |
| media (transcoded, non-`ORIGINAL` quality) | `videosApi.getVideoStreamByContainerUrl(...)` — see [`docs/features/download-quality.md`](download-quality.md) |
| images | `imageApi.getItemImageUrl(itemId, type, tag, format = WEBP, fillWidth)` |
| subtitles | `subtitleApi.getSubtitleUrl(itemId, mediaSourceId, streamIndex, format)` |
| trickplay | `trickplayApi.getTrickplayTileImageUrl(itemId, width, tileIndex)` |
| enqueue re-fetch | `itemsApi.getItems(ids, fields = [MEDIA_SOURCES, MEDIA_STREAMS, PATH, OVERVIEW, GENRES, CHAPTERS, TRICKPLAY, PEOPLE, STUDIOS, TAGLINES, PRIMARY_IMAGE_ASPECT_RATIO])` |
| container expansion | `tvShowsApi.getEpisodes(seriesId, seasonId?, fields = [], enableImages = false, enableUserData = false, isMissing = false)` — ids only; the re-fetch above then fetches the real DTOs |

The download-policy fallback is applied **on a 403**, not from a policy flag: the server refusing
`/Items/{id}/Download` is the authoritative answer, and the same bytes are reachable as a static
video stream. See DECISIONS.md, 2026-07-28.

**URLs are rebuilt on every run**, never read back from the row: `ServerReachabilityProbe` rotates
the base URL between LAN and remote, so an item queued at home and run elsewhere must be fetched
from the address that answers *now*.

---

## Resume

The partial file **is** the bookmark — no separate state survives a process death, and no state can
disagree with the bytes on disk.

| Response | Meaning | Action |
|---|---|---|
| `206 Partial Content` | Server honoured `Range: bytes=<length>-` | Seek to the offset and append |
| `200 OK` | Server ignored `Range` (some proxies do) | Truncate and rewrite from zero |
| `416 Range Not Satisfiable` | File already complete | Transfer nothing, report complete |
| anything else | failure | `DownloadHttpException(code)` — `403` triggers the media fallback |

Cancelling (pause, a lost network, a killed process) leaves the partial file exactly where it is and
puts the row back to `QUEUED`. Nothing in the engine ever deletes a file.

Two things make the resume actually land on the *same* file after a process death:

- **The file plan is persisted, not re-derived.** When `download_files` rows exist for an item, the
  queue reuses their `fileName` and identity and rebuilds only the URL (the base address rotates
  between LAN and remote). Re-planning from the DTO is reserved for a first attempt, because
  `DownloadPaths.mediaFileName` prefers the server's own filename from `BaseItemDto.path` and that
  field is not present in every shape of the cached DTO — a retry that re-planned once renamed a
  1.38 GB partial from `Backrooms.2026…-BATGirl.mkv` to `Backrooms (2026).mkv` and started over.
  Room holds the plan; Room wins.
- **The session is restored before the first URL is built.** `DownloadSessionGate` runs at the top
  of every drain: on a cold start WorkManager beats the UI to it, and without the gate the SDK threw
  `Required value baseUrl is null` and the item went `ERROR`. No session at all → `Result.retry()`,
  rows left `QUEUED` ("Waiting"), nothing marked failed.

An `ERROR` row's message is user copy from `DownloadErrorCopy` (mapped through `AppError`), never an
exception's text.

Progress: `FileDownloader` reports every 64 KB of *accumulated* bytes (OkHttp hands back 8 KB
segments, so the callback counts bytes rather than reads), and `ProgressThrottle` lets through a
Room write every 500 ms **or** 1 %. Room is the single source of truth; the queue tab, the badges
and the notification are all Flows over it.

---

## Queue semantics

| Status | Meaning |
|---|---|
| `QUEUED` | waiting for its turn |
| `DOWNLOADING` | the one item transferring (the queue runs strictly one at a time) |
| `PAUSED` | a *user* decision; survives a process death |
| `DOWNLOADED` | every essential file on disk |
| `ERROR` | an essential file failed |
| `CANCELLED` | transient — a cancelled row is deleted |

- **Ordering** is `queuePosition`, renumbered from zero on every move so gaps left by completed
  items cannot make "position" mean something other than "place in the list".
- **Interrupted rows** (`DOWNLOADING` from a dead process) are put back to `QUEUED` when the worker
  starts. That is what lets `nextRunnable` tell "mine" from "someone else's".
- **Pause** stops the work (the only way to interrupt a transfer in flight) and restarts it, so the
  queue moves on to the next item.
- **A network loss is not a pause**: the row stays `QUEUED` and WorkManager's constraint resumes it.

---

## Delete cascade

`DownloadRepository.delete` (the same call for *Cancel* in the queue and *Delete* in the list):

1. `scheduler.stop()` — the downloader must not hold a handle to a file about to be removed.
2. `storage.deleteItemDirectory(...)` — **files first**; the other order can leave orphaned
   gigabytes if the process dies in between. Returns the bytes actually freed.
3. `downloadDao.delete(itemId)` — `download_files` follows through the foreign key.
4. Prune orphaned `ItemEntity(source = DOWNLOAD)` rows: the surviving set is every remaining
   download **plus each one's series and season**, so deleting one episode does not blank the series
   page its siblings still open from.
5. `deleteSyncedUserData(itemId)` — drops the local user-data row **unless** `toBeSynced`, which is
   the only copy of a change the server has not seen.
6. `scheduler.ensureRunning()` — something else may still be queued behind it.

---

## Storage

`<app-specific dir on the chosen volume>/downloads` — app-private external storage: no runtime
permission, wiped on uninstall, invisible to the media scanner. The storage header walks the
filesystem rather than summing Room, so an orphaned file shows up instead of hiding.

### Choosing the volume

Settings → Downloads → *Storage location* lists every **mounted** volume `getExternalFilesDirs(null)`
reports — internal storage, and the SD card when one is in. The group is hidden entirely on a device
with only one. Each option is an app-specific directory, which is what keeps the whole pipeline on
plain `java.io.File`: picking the SD card needs no permission, no persisted URI grant and no
`DocumentFile`. Choosing an *arbitrary* folder still needs SAF, which is still deferred behind the
`DownloadStorage` seam (DECISIONS.md 2026-07-29).

```
AppPreferences.downloadStorageVolumeId   ("primary" | volume UUID | absent = default)
        │
        ▼
StorageLocationManager.resolve(id) ── StorageVolumeProvider.volumes()   (mounted volumes)
        │                                     │
        │  chosen volume if mounted,          └─ getExternalFilesDirs + StorageManager
        │  primary volume otherwise
        ▼
   activeRoot() = <volume app dir>/downloads  ──▶  FileDownloadStorage  ──▶  every path in the pipeline
```

Three rules are worth stating outright:

- **The stored value is a token, not a position.** A volume UUID (or `"primary"`), never an index —
  indices reorder when a card is pulled — and never a path, which is only stable while mounted.
- **A missing volume falls back rather than fails.** Take the card out and downloads go to internal
  storage again; Settings says so in red instead of the app writing nowhere. The default is stored
  as an *absent* key, so "never chose" and "chose the built-in volume" stay indistinguishable.
- **Switching is delete-all-and-switch, or nothing** (docs/PLAN.md's v1 policy; `MoveStorageWorker`
  is still deferred). `DownloadRepository.setStorageLocation` counts the download rows and refuses
  unless the caller passed `deleteExistingDownloads`; the Settings picker asks first, and switches
  immediately when the device is empty. The reason is mechanical: `DownloadFileEntity.path` is
  absolute and is only re-resolved when an item is (re-)enqueued, so files left behind on the old
  volume would keep playing until the card came out and then silently fall back to streaming.
  Re-selecting the volume already being written to is the one switch that deletes nothing — it moves
  no files, and it is how a user clears a stale choice once the card is gone.

The primary volume resolves to exactly the path used before the picker existed
(`getExternalFilesDir(null)/downloads`), so an install that never touches the setting needs no
migration.

---

## Schema (Room v4)

`downloads` — pk `itemId` (one download per item):

| column | note |
|---|---|
| `userId` | owner; the delete cascade needs it |
| `status` | `DownloadStatus` |
| `mediaSourceId` | the version actually on disk — what M8 resolves against |
| `bytesDownloaded` / `bytesTotal` | the single source of truth for progress |
| `queuePosition` | ordering |
| `directoryName`, `itemName`, `seriesName` | denormalised: the queue renders before the item row exists, and the cascade needs the directory *after* it is gone |
| `errorMessage` | so the queue tab can say *why* |
| `createdAt` / `updatedAt` | |

`download_files` — surrogate `id`, FK `itemId` → `downloads` **ON DELETE CASCADE**, unique index on
`(itemId, type, streamIndex, tileIndex)` (which makes re-planning idempotent), plus `type`,
`tileWidth`, `fileName`, `path`, `url`, `bytesDownloaded`, `bytesTotal`, `status`.

v3 → v4 is a purely additive `@AutoMigration`; an existing install keeps its cached items and its
pending user-data rows.

---

## Offline behaviour

Everything on this screen is Room-only — it never touches the network and behaves identically
online and offline. Enqueueing needs a server (it re-fetches the item); pause, resume, reorder and
delete do not.

---

## Tests

| Suite | Covers |
|---|---|
| `DownloadPathsTest` | naming, exFAT sanitising, the ` - ` separator, filename fallbacks |
| `DownloadFilePlannerTest` | plan order, essential split, subtitle/trickplay selection, the 403 fallback URL |
| `FileDownloaderTest` | `200` / `206` / `416` / error, the `Range` header, the auth header, resume offsets, cancellation leaving the partial file |
| `ProgressThrottleTest` | the 500 ms-or-1 % rule, unknown totals |
| `DownloadQueueTest` | status machine, essential vs optional failure, the 403 retry, cancellation re-queue, plan reuse across retries, the session gate, deletion mid-transfer |
| `DownloadSessionGateTest` | cold-start restore, no-session parking, a token-less client |
| `DownloadErrorCopyTest` | every failure maps to user copy; SDK internals never reach the row |
| `DownloadEnqueuerTest` | full re-fetch, parent caching, `source = DOWNLOAD`, queue position, re-enqueue |
| `DownloadedMetadataRefresherTest` | when it fires (app start online, the return of the connection, never while offline, once per stretch, re-armed by losing it, no API call with nothing downloaded); what it writes (`source = DOWNLOAD`, parents, batching at 50, `cachedAt` preserved for an existing row and stamped for a new one); what it survives (a failing fetch, one failing batch of several, a remotely deleted item, a failing parent fetch, no session, an unreadable table, a failing write) |
| `DownloadDeleterTest` | file-before-rows ordering, the surviving-parent set, user-data prune |
| `DownloadRepositoryImplTest` | status → badge mapping, mutation ordering, reordering |
| `DownloadsViewModelTest` | tab split, grouping, actions, reorder bounds |
| `DownloadSpeedTrackerTest` | derived speed, smoothing, restarts, stopped items |

`FileDownloader` is tested against an OkHttp `Interceptor` returning canned responses — no server,
no emulator, and the four HTTP cases are directly expressible.
