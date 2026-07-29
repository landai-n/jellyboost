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
| Detail screen | *Download* enqueues; the same button then reads *Cancel* / *Remove* / *Retry* and shows live progress. |
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
        ├── DownloadEnqueuer ── DownloadApi.getFullItems([item] + parents)   ← one server call
        │        ├── ItemDao.upsert(ItemEntity(source = DOWNLOAD))           ← item AND series/season
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
| `DownloadEnqueuer` | `:data:downloads` | The full-fields re-fetch, the `source = DOWNLOAD` cache write for the item **and its parents**, and the `DownloadEntity` row. |
| `DownloadApi` / `SdkDownloadApi` | `:data:downloads` | The one SDK call the pipeline makes, behind a seam so enqueueing is unit-testable. |
| `DownloadFilePlanner` | `:data:downloads` | `BaseItemDto` → ordered `List<PlannedFile>`, with the essential/optional split. |
| `DownloadUrlFactory` / `SdkDownloadUrlFactory` | `:data:downloads` | Every URL the pipeline fetches, behind a seam (a real `ApiClient` has no base URL in a JVM test). |
| `DownloadPaths` | `:data:downloads` | Directory and file naming; pure, fully unit-tested. |
| `DownloadStorage` / `FileDownloadStorage` | `:data:downloads` | Where files live. The interface is the seam a SAF backend would go behind. |
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

Default `getExternalFilesDir(null)/downloads` — app-private external storage: no runtime permission,
wiped on uninstall, invisible to the media scanner. `DownloadStorage` is the seam a SAF-tree or
secondary-volume backend would go behind; v1 ships only the `File` implementation (DECISIONS.md,
2026-07-28 — "SAF and secondary-volume storage deferred"). The storage header walks the filesystem
rather than summing Room, so an orphaned file shows up instead of hiding.

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
| `DownloadDeleterTest` | file-before-rows ordering, the surviving-parent set, user-data prune |
| `DownloadRepositoryImplTest` | status → badge mapping, mutation ordering, reordering |
| `DownloadsViewModelTest` | tab split, grouping, actions, reorder bounds |
| `DownloadSpeedTrackerTest` | derived speed, smoothing, restarts, stopped items |

`FileDownloader` is tested against an OkHttp `Interceptor` returning canned responses — no server,
no emulator, and the four HTTP cases are directly expressible.
