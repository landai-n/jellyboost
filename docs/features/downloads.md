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
| Downloads tab | *Downloaded* (three ordered sections — MOVIES, SERIES, MUSIC — see [Sections and folding](#sections-and-folding)) and *Queue* (the same three sections, never folded; progress, speed, ETA, pause/resume/cancel/reorder per row, plus a *Pause all* / *Resume all* / *Cancel all* bar above the list), with a storage header and the Wi-Fi-only toggle. |
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
| `SubtitleSidecarTopUp` | `:data:downloads` | Fetches the subtitle sidecars a **finished** download is missing — an older row whose file plan predates a planner change, or an optional file that failed. Driven by `DownloadedMetadataRefresher`; never touches the media file. See [Repairing a finished download](#repairing-a-finished-download). |
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
| `DownloadRows.etaSeconds` | `:feature:downloads` | Whole seconds left from the smoothed speed and `displayTotalBytes`; hidden when the speed is unknown, the transfer is stalled, or the estimate exceeds 24 h. Worded `~X left` on a `CEILING` total, since the ETA is exactly as approximate as the size behind it. A queue/downloaded row whose `quality.isTranscoded` also appends a `Transcoded` marker to its status/size line. |
| `formatDurationSeconds` | `:core:common` | The shared duration formatter (`45 s` / `3 min` / `1 h 20 min`, minutes ceil'd), beside `formatBytes` per ARCH-11. |

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
5. **`SUBTITLE`** — `subtitle.<index>.<lang>.<ext>`, one per text stream the server will hand over
   separately (`MediaStream.supportsExternalStream`). Which streams those are depends on the
   quality:
   - an **external** stream is its own file already and is fetched at every quality;
   - an **embedded** stream is fetched **only for a transcoded download**, because the transcoder
     drops it and the sidecar becomes the only copy. At `ORIGINAL` it is skipped: the file being
     fetched already contains it, so a sidecar would be a duplicate — and a second route to one
     picker entry.

   Bitmap formats (PGS, VobSub) are skipped at every quality because ExoPlayer cannot play them
   from a sidecar file; they survive in an `ORIGINAL` download and nowhere else.
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

### Repairing a finished download

The file plan is not fixed for all time, and an optional file is allowed to fail. Both leave a
finished download poorer than the same item downloaded today — an older transcoded row holds fewer
subtitles than a fresh one, and there is nothing in the queue that ever revisits a `DOWNLOADED` row.

`SubtitleSidecarTopUp` closes that, driven by `DownloadedMetadataRefresher` (once per stretch of
connectivity, with the fresh DTOs that pass already fetched). For each **finished** row it re-plans
at *the row's own quality*, keeps the `SUBTITLE` entries whose file is not on disk, and fetches
those and nothing else.

It is deliberately not "put the row back in the queue". The queue walks every file of the plan, and
the media file of a transcoded row cannot be resumed — the server ignores `Range` on a live
transcode, answers `200`, and `FileDownloader` truncates and rewrites from zero. Re-queueing to
collect a 40 KB subtitle would spend the whole download again.

Guarantees: a complete sidecar is never re-fetched (so a pass over a healthy device costs one Room
read per item); the media file is never touched; a row still in the queue is left entirely to the
queue; and nothing it does can throw — a failure leaves exactly the gap that was already there, and
the next connectivity edge tries again.

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
  queue moves on to the next item. It is offered on `ORIGINAL` rows only: a transcode cannot resume,
  so pausing one would throw the whole transfer away (`DownloadItem.isPausable`, and
  docs/features/download-quality.md, *"No resume"*).
- **A network loss is not a pause**: the row stays `QUEUED` and WorkManager's constraint resumes it.

### Queue-wide actions

A bar above the queue list (`DownloadsScreen.QueueActionsBar`, drawn only while the queue is
non-empty) applies the row actions to the whole queue. Each one composes the existing per-item
repository call — there is no bulk path through `DownloadRepository`, and therefore no second
cascade to keep correct.

| Action | Acts on | Does not touch |
|---|---|---|
| *Pause all* | every `QUEUED` / `DOWNLOADING` row that is **pausable** (`DownloadItem.isPauseTarget`) | transcodes (they cannot resume, so pausing one discards it), and rows already `PAUSED`/`ERROR` |
| *Resume all* | every `PAUSED` or `ERROR` row, transcoded or not (`isResumeTarget`) | rows already moving |
| *Cancel all* | every row on the tab — `QUEUED`, `DOWNLOADING`, `PAUSED`, `ERROR` | **`DOWNLOADED` rows, by construction**: the queue list is `toQueue()`, which excludes them |

`isPauseTarget` / `isResumeTarget` (`DownloadsUiState.kt`) are the *same* predicates the per-row
buttons branch on, so a bulk action can never act on something its own row refuses to.

- *Pause all* and *Resume all* are **disabled** when they have no targets — a queue of nothing but
  transcodes offers no *Pause all* at all — rather than hidden, so the bar does not reshuffle under
  the finger as the queue changes.
- When *Pause all* skips transcodes, a snackbar says so ("Paused 2 — 1 transcode keeps downloading");
  otherwise it is silent, because the rows themselves change to *Paused*.
- *Cancel all* **confirms** (`DownloadsUiState.showCancelAllConfirmation`, a plural title carrying
  the count), unlike a single row's *Cancel*: one tap otherwise discards every partial transfer on
  the device. The dialog copy states that finished downloads are not affected — the same rule as
  cancelling a season (DECISIONS.md, 2026-07-29).
- Below 480dp of viewport width a queue row stacks in **two tiers** — artwork + title/progress/
  status take the full row width, and the four action buttons sit end-aligned underneath at full
  48dp touch-target size. In the single-row layout the actions left a 360dp phone ~64dp of title
  ("Hous…" — 2026-07-31 phone-size sweep, DECISIONS entry). The flag is measured once at screen
  level (`queueRowCompact`), not per row; `DownloadedRow` (one delete icon) never needed it.

---

## Sections and folding

The *Downloaded* tab is three sections in a fixed order — **MOVIES, SERIES, MUSIC** — each drawn
under an uppercase kind label. The label appears only when **more than one** kind is present: with
nothing but films on the tab, the rows are already all of one sort and a heading over them says
nothing. A kind with nothing downloaded gets no section at all.

Inside a section:

- **Films** are one group with no heading of its own: a film's heading would repeat its own row's
  title verbatim ("Dune" over "Dune"), and the MOVIES label already names them.
- **Series and albums** are one group each, and each starts **collapsed** to a single header line
  carrying the title, the item count and the size ("8 episodes · 6.1 GB"). The whole header is the
  toggle — `Role.Button`, an expand/collapse click label, an expanded/collapsed `stateDescription`,
  and a chevron rotated by `animateFloatAsState`.
- An **album** header also carries the cover and the artist, and its track rows then draw no artwork
  of their own: every one of them was the same cover the header already shows. Both are taken from
  the first row that has one (`DownloadGroup.subtitle` / `artworkUrl`), so a track still waiting on
  its metadata refresh cannot blank the header.
- A **series** header carries the same two slots, filled from the season instead: the **season
  poster** and the **season name**. Its episode rows keep their own artwork, which is the difference
  from an album — an episode still differs per row and belongs to that row, while a track's cover
  was the header's all along. The name is the server's own `seasonName`, never a `"Season $n"`
  composed in the client: the server has already localised it, and composing one would be a string
  invisible to the `MissingTranslation` gate and owed a 69-locale pass. A group is keyed by
  **series**, so it can hold several seasons — the distinct names are then joined with `·` in season
  order (`parentIndexNumber`, unnumbered last) and the poster is the **lowest-numbered** season's.
  Deliberately not "whichever season the list opens with": the rows below are ordered by episode
  title, so that rule would hand the header a different poster whenever an episode is added or
  deleted. A group no row's season reached keeps exactly the one-line header it
  had before. Both thumbnails are drawn at one square size, so a portrait season poster is
  centre-cropped rather than giving the header a second geometry.
- The season's poster is **not** on the episode item (`primaryImageUrl` there is the still). It is
  resolved at the data layer, by `DownloadRepositoryImpl`'s `SeasonArtworkCache`, onto
  `DownloadItem.seasonArtworkUrl` — one batched lookup over the cached season rows that
  `DownloadedMetadataRefresher` and `DownloadEnqueuer` both write, memoised on `items.revisedAt`
  exactly like the item join beside it, so a transfer's two-to-six emissions a second cost one extra
  narrow `getCacheKeys` and no blob parse. A list with no episode in it makes no season query at all,
  and a season row that has left the cache degrades to a header without a poster, never to a wrong
  one.
- The memo's key is **`revisedAt`, not `cachedAt`** — the distinction is the whole reason the column
  exists (v13, see DECISIONS 2026-08-28). `cachedAt` orders the offline "recently downloaded" rows,
  so both the metadata refresh and the browse write-through deliberately carry a row's old value
  across an in-place rewrite; a memo keyed on it would never notice the commonest delivery path of
  all, where the enqueue caches a lean season row and the refresh later replaces it with the real
  artwork. `revisedAt` is stamped by every upsert path — all three route through
  `ItemEntityMapper.toEntity` — so it records when a blob was actually replaced.
- Loose tracks in the catch-all keep their own art, having no header to carry it. The header
  thumbnail is decorative — the merged header already speaks the title, the subtitle and the count.
- A series or album row whose heading identity is missing joins a **headerless catch-all** at the
  end of its own section, drawn like the films group. No download may drop out of the only list it
  is deletable from.

Group identity is `DownloadItem.groupKey` — the server's series/album id where the row has one, else
`"KIND:title"` — never the heading text alone: two shows of the same name, or an album and a series
sharing one, must stay two groups.

The fold state is `DownloadsUiState.expandedGroups`, a set of **expanded** keys, so the default empty
set *is* the collapsed default. It lives in `LocalState` beside the selected tab, which means it is
**in-memory only**: it survives a rotation and the `WhileSubscribed` projection stopping, and resets
on a cold start. It cannot live on `DownloadGroup` — `DownloadGroupCache` hands back the same list
instance while the finished rows are unchanged, and a per-group flag folded into it would defeat that
memoisation and recompose every finished row on the queue's progress ticks. A key whose group is gone
simply stays in the set; nothing reads it.

The storage header sums **every** section, folded or not: it reports what is on disk, not what is
unfolded.

The *Queue* tab takes the **same three sections in the same order**, under the same kind label and by
the same one-kind-needs-no-label rule (`DownloadsUiState.queueSections`, `showQueueKindHeaders`).
Nothing there folds — a transfer in flight must never be hidden behind a header — which is why a
queue section is a flat `QueueSection` rather than a `DownloadGroup`. The flat `queue` list stays
exactly as it was: the queue stats, the bulk-action targets and the reorder arithmetic all index into
it.

Reorder is therefore **within a section**: up and down move a row to the nearest neighbour *of its
own kind*, and a row with no such neighbour in that direction does not move at all. Swapping with a
neighbour of another kind would leave the row exactly where it was drawn and reorder another section
instead.

That neighbour reaches `DownloadRepository.move` as an **id, never as an index**. There are two queue
orderings, and they are not the same list: the screen's is every unfinished row (`toQueue()`, failed
and cancelled included), while the one `move` renumbers is `DownloadDao.pending()` — only the rows
the engine can pick up. One failed row above the target is enough to make the same integer name two
different rows, which turned a move into a no-op in one direction and aimed it at a stranger in the
other. `move` therefore resolves the target's index against **its own** `pending()` snapshot, and the
id doubles as the guard: a target that finished, failed or was cancelled in between is no longer in
that snapshot, there is no place left to take, and nothing is written.

The invariant that holds is: **the moved row ends up where the named row was, and the rows the
renumber covers keep their relative order.** It does not extend to rows outside that set — a failed
row keeps its old `queuePosition` while the pending rows are renumbered from zero, so its place
relative to them can shift. That predates the sections and is unchanged by them.

A queue row stacks **title / progress / status** at both widths; only the type scale differs. The
status is the size·speed·ETA string, long enough to starve the title down to a few characters when
the two shared a line on a portrait tablet — which is wide enough to get the non-compact tier. The
row's `clearAndSetSemantics` description carries title, percent and status as one sentence.

---

## Screen scrolling: pinned chrome vs. one page

The screen's chrome — title, storage/queue summary, tab row — is **pinned above an inner-scrolling
list only when the window is both wide and tall** (`chromePinned(maxWidth, maxHeight)`: not
`queueRowCompact`, *and* at least 480dp of height — the same figure as the width breakpoint, applied
to the other axis). Tablets in either orientation are pinned and look exactly as they did.

Everywhere else — every phone, portrait and landscape — the screen is **one `LazyColumn`**: the
chrome is its leading item, followed by the compact bulk-action bar (queue tab only) and the
selected tab's rows, so the whole page scrolls together. Height has to count because width alone
does not: a landscape phone is wide enough for the tablet summary but only ~360dp tall, so pinning
put the entire window under chrome and the queue could not be reached at all; in portrait it pinned
about half the screen and scrolled a short list inside the rest.

Both layouts emit their rows through the same `LazyListScope` extensions (`downloadedRows` — which
is where the kind labels, group headers and the fold check live, so both layouts fold identically —
and `queueRows`) and the same `DownloadsChrome` composable, so nothing about a row's or the header's
appearance depends on which layout is in play; only the delete-confirmation state is hoisted to the
screen (a `LazyListScope` extension cannot `remember`).

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

## Schema (Room v12)

`downloads` — pk `itemId` (one download per item):

| column | note |
|---|---|
| `userId` | owner; the delete cascade needs it |
| `status` | `DownloadStatus` |
| `mediaSourceId` | the version actually on disk — what M8 resolves against |
| `bytesDownloaded` / `bytesTotal` | the single source of truth for progress |
| `queuePosition` | ordering |
| `directoryName`, `itemName`, `seriesName` | denormalised: the queue renders before the item row exists, and the cascade needs the directory *after* it is gone |
| `itemType`, `albumName`, `artistName`, `groupId` | what the row **is** and where it files, each fact in its own column (v11 and v12). All nullable with no default: `NULL` is every row written before them, and the read path folds those back onto the cached item |
| `errorMessage` | so the queue tab can say *why* |
| `createdAt` / `updatedAt` | |

`DownloadedMetadataRefresher` fills those four in on rows that predate them, through **SQL-guarded**
`UPDATE`s rather than a read-then-write: the pass runs while the queue is writing. The grouping
columns go through `WHERE itemType IS NULL`; `artistName` needs a **second** statement guarded on
`WHERE artistName IS NULL`, because it is younger than the first guard and a row stamped in between
has an `itemType` the grouping statement would refuse. Both fill a column once and never overwrite an
enqueue's value.

`download_files` — surrogate `id`, FK `itemId` → `downloads` **ON DELETE CASCADE**, unique index on
`(itemId, type, streamIndex, tileIndex)` (which makes re-planning idempotent), plus `type`,
`tileWidth`, `fileName`, `path`, `url`, `bytesDownloaded`, `bytesTotal`, `status`.

Every bump on this table is purely additive and therefore an `@AutoMigration`; an existing install
keeps its cached items, its pending user-data rows and its download queue.

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
| `DownloadFilePlannerTest` | plan order, essential split, subtitle/trickplay selection, the 403 fallback URL, the embedded-subtitle sidecars a transcode adds (and an `ORIGINAL` download does not), `supportsExternalStream = false` and bitmap codecs excluded at every quality, the baked `audioStreamIndex` |
| `FileDownloaderTest` | `200` / `206` / `416` / error, the `Range` header, the auth header, resume offsets, cancellation leaving the partial file |
| `ProgressThrottleTest` | the 500 ms-or-1 % rule, unknown totals |
| `DownloadQueueTest` | status machine, essential vs optional failure, the 403 retry, cancellation re-queue, plan reuse across retries, the session gate, deletion mid-transfer |
| `DownloadSessionGateTest` | cold-start restore, no-session parking, a token-less client |
| `DownloadErrorCopyTest` | every failure maps to user copy; SDK internals never reach the row |
| `DownloadEnqueuerTest` | full re-fetch, parent caching, `source = DOWNLOAD`, queue position, re-enqueue |
| `SubtitleSidecarTopUpTest` | a finished transcode gaining the sidecars it was downloaded without; the media file never being among the fetches; the fetched file recorded as downloaded with its bytes; a failed sidecar retried on its existing row and under its existing name; a row whose file vanished re-fetched; a complete sidecar left alone; an `ORIGINAL` download given nothing; a row still in the queue left to the queue; a bitmap track never repaired; one sidecar failing not costing the next; an unavailable volume and a folder item survived |
| `DownloadedMetadataRefresherTest` | when it fires (app start online, the return of the connection, never while offline, once per stretch, re-armed by losing it, no API call with nothing downloaded); what it writes (`source = DOWNLOAD`, parents, batching at 50, `cachedAt` preserved for an existing row and stamped for a new one, `revisedAt` advanced on that same preserved row); what it survives (a failing fetch, one failing batch of several, a remotely deleted item, a failing parent fetch, no session, an unreadable table, a failing write); and the file top-up (each pass offering its fresh DTOs, parents excluded, nothing offered when the fetch failed, a failing top-up not costing the metadata write) |
| `DownloadDeleterTest` | file-before-rows ordering, the surviving-parent set, user-data prune |
| `DownloadRepositoryImplTest` | status → badge mapping, mutation ordering, reordering, the season join (a wiped season row answering `null`, a season written later reaching rows already on screen, no episode meaning no query) |
| `DownloadsViewModelTest` | tab split, sectioning (films first, series after, albums in their own section), folding (every group starts collapsed, one toggle moves one key, an unfolded group survives the projection stopping, the storage figure ignores what is unfolded), actions, reorder within a section (a different-kind neighbour skipped in both directions, a no-op at either section edge), the queue-wide actions (which statuses each one touches, the transcode message, the cancel-all confirmation) |
| `DownloadsUiStateTest` | queue aggregates and the precomputed chrome; the fixed MOVIES/SERIES/MUSIC order on both tabs, empty sections omitted, `showKindHeaders` / `showQueueKindHeaders` off for a single kind, the flat queue left untouched, in-section queue order preserved, the one non-folding films group, an album header's artist and cover (and one row missing them not blanking it, and neither the artist column nor a row's own artwork reaching a series header), a series header's season line and season poster (the row keeping its own still, several seasons joined in season order with the lowest-numbered one's poster, an unnumbered season last, one row missing its season not blanking it, the poster taken from the first row of its season that has one, a cached-away season costing the poster but not the line, and no season at all keeping the one-line header), a series and an album sharing a name staying two groups, two same-named shows told apart by their heading id, the headerless catch-all, and a legacy row with no type still appearing under its series |
| `DownloadGroupCacheTest` | the same list instance back while only queue rows moved; every field a row draws from invalidating it — including the three a header draws and nothing else does: an album's artist, and a series' season name and season poster |
| `DownloadRowsTest` | the size shown and how it is worded, ETA, pause/resume eligibility, playback start position, row titles (the group prefix dropped inside a group, for albums as well as series), `artistLine`'s column → cached-item order, the announced percentage |
| `SchemaMigrationTest` | every bump purely additive over the last, and each new column nullable-or-defaulted — which is what keeps it an `@AutoMigration` |
| `DownloadSpeedTrackerTest` | derived speed, smoothing, restarts, stopped items |

`FileDownloader` is tested against an OkHttp `Interceptor` returning canned responses — no server,
no emulator, and the four HTTP cases are directly expressible.
