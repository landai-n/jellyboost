# Feature: User data (watched / favourite / resume) — M4

Every watched, favourite and resume-position write in the app goes through one local-first path.
This is the piece that makes the online/offline UI feel like one product: a toggle is durable and
on screen before the network is involved at all, and it works identically with no network.

## The contract

`UserDataRepository` (`:data`, `dev.jellyfinnative.data.userdata`) does the same four things for
every operation, in this order (docs/PLAN.md, "Data layer"):

1. **Upsert Room** — `UserDataEntity` with `toBeSynced = true` and a fresh `updatedAt`.
2. **Publish** on `UserDataEventBus` — every list ViewModel patches its items in place.
3. **Push** to the server — *only while online* (see "The offline push gate" below).
4. **Settle** — on success clear `toBeSynced`; on failure leave it set and enqueue
   `UserDataSyncWorker`.

Steps 1–2 are the contract; steps 3–4 are best effort. Consequently the returned `AppResult`
describes the **local** write: `Success` means the change is durable and visible, whether or not
the server has heard about it. A failed push is a logged warning plus a scheduled retry, never a
failed operation.

`AppResult.Failure` therefore only happens when the change could not be recorded at all:

| Failure | Cause |
|---|---|
| `AppError.Unauthorized` | No signed-in session, so there is no `userId` to key the row on |
| `AppError.NotFound` | The item id is not a UUID |
| `AppError.Storage` | The Room write itself failed |

## Operations and the calls behind them

| Operation | Local effect | Server call |
|---|---|---|
| `setPlayed(itemId, played)` | `played`, clears the resume position and stamps `lastPlayedDate` when marking watched | `playStateApi.markPlayedItem` / `markUnplayedItem` |
| `setFavorite(itemId, favorite)` | `isFavorite` | `userLibraryApi.markFavoriteItem` / `unmarkFavoriteItem` |
| `setPosition(itemId, ticks)` | `playbackPositionTicks`, `lastPlayedDate` | `itemsApi.updateItemUserData` with the item's **full** desired state |

The plan specifies `updateItemUserData` for all three; using the dedicated endpoints for played and
favourite is logged in `DECISIONS.md` (2026-07-28).

## The offline push gate (M9)

`UserDataRepositoryImpl` injects `ConnectionStateProvider` and checks it once, at the top of
`pushToServer`. When `state.value.isOnline` is `false` the push is **not attempted at all**: one
`Timber.d` line is logged and the method returns.

Why: `PlaybackReporter` calls `setPosition` every five seconds, so an offline session fired one
request that could only fail — plus a `Timber.w` stack — per tick (STATUS.md, "Known issues": *while
offline, `UserDataRepositoryImpl` still attempts one doomed push per position tick*). The request
bought nothing, because the row is left pending either way.

The offline path deliberately also skips `syncScheduler.enqueue()`. That is not a gap:
`UserDataSyncTrigger` already drains **every** pending row on each `OFFLINE → ONLINE` edge and at app
start, so a per-write enqueue while offline is redundant work whose only effect is to re-schedule the
same drain a few hundred times during a film.

What does **not** change:

- Steps 1 and 2 happen before `pushToServer` is even called, so the Room row (`toBeSynced = true`)
  and the `UserDataChange` event are identical online and offline.
- The return value is still `AppResult.Success`. A skipped push is no more a failure than a failed
  one — the contract above is unconditional.
- **The online branch is untouched, byte for byte**: push, clear the flag on success, `Timber.w` plus
  `syncScheduler.enqueue()` on failure. `UserDataRepositoryImplTest` pins this by leaving its fixture
  at `ConnectionState.ONLINE` for every test that is not explicitly about the gate.

Marking watched clears the resume position locally because the server does the same — not
mirroring it would leave a progress bar on a watched card until the next sync overwrote it.

## Reads refresh the mirror — the other half of the contract

`setPosition` sends the item's **full** desired state (position, played, favourite,
`lastPlayedDate`), because `POST /UserItems/{id}/UserData` merges what it is given and a partial DTO
would risk resetting the rest. That is only safe if the local row it is built from is honest.

It was not. Rows were written *only* by local writes, so a row went stale the moment the same user
changed the item from another client, and the next five seconds of playback pushed the stale state
back — an item unwatched in jellyfin-web came back watched (STATUS.md, "Known issues").

So the read path now maintains the mirror too. `BrowseCacheWriter.writeItems` — which every
successful `OnlineJellyfinRepository` read already funnels through — adopts each DTO's `userData`
block into `user_data`:

| Local row | What a server read does |
|---|---|
| absent | creates it from `dto.userData` |
| `toBeSynced = false` | overwrites it — **the server is authoritative** |
| `toBeSynced = true` | **nothing at all** — the pending local write wins until it drains |
| any, but `dto.userData == null` | nothing — silence is not "unwatched, not favourite" |
| any, but signed out | nothing — there is no `userId` to key rows on |

The pending case is the whole subtlety: that row is the only copy of a change the server has not
seen, and reconciling the two versions is most-recent-wins in `UserDataSyncWorker` (M8), not a cache
write's business.

Timestamps when adopting server state (`UserItemDataDto.toEntity`):

- `lastPlayedDate` — the server's value **verbatim**, `null` included. It is the *server* half of
  most-recent-wins; inventing one from the read time would make an unplayed item look freshly
  watched.
- `updatedAt` — the moment this device learned the server's state. It is the *local* half, and it
  only ever decides anything for a `toBeSynced = true` row, which a refresh never produces (the
  worker's work list is `toBeSynced = 1`, and any later local write re-stamps it from the clock).
  So it records when the mirror was refreshed without ever claiming the local row is newer than the
  server's.
- `toBeSynced` — always `false`: an adopted row is a copy of server state, not a debt the server
  owes.

The write path is untouched: `UserDataRepositoryImpl` is still local-first always. See
`DECISIONS.md`, 2026-07-28 "server reads refresh `user_data` rows that are not pending sync".

## Room: `user_data` (schema v2)

Composite primary key `(itemId, userId)` — multi-user ready without a separate scoping mechanism.

| Column | Notes |
|---|---|
| `itemId`, `userId` | UUID, composite pk |
| `played`, `isFavorite` | |
| `playbackPositionTicks` | Jellyfin ticks |
| `lastPlayedDate` | Epoch millis, nullable |
| `toBeSynced` | Indexed; `true` while the server has not accepted this row |
| `updatedAt` | Epoch millis; the local half of most-recent-wins |

`Instant` columns are stored as **epoch milliseconds**, not ISO-8601 text, because the sync path
compares timestamps in SQL and `Instant.toString()` is not lexicographically ordered
(`…T10:00:00.500Z` sorts before `…T10:00:00Z`). `InstantConverter` owns that mapping.

v1 → v2 is a Room `@AutoMigration`: the change adds a table and touches nothing existing. As with
every other entity in `:core:database`, there is **no token column** anywhere — tokens live only in
`SecureCredentialStore`.

`UserDataDao.clearPendingSync` is guarded on the timestamp
(`WHERE … AND updatedAt <= :syncedAt`): if the user toggled the same item again while the push was
in flight, the newer row keeps its flag instead of being declared synced.

`UserDataDao.getPendingSyncIds` + `upsertAll` are the pair the read refresh below uses: ask which of
a page's items still owe the server a write, then batch-write the rest. The filtering itself lives
in `BrowseCacheWriter` rather than in a `@Transaction` DAO method, for the same reason the
download-demotion rule does — so it is JVM-unit-testable instead of device-only.

## `UserDataEventBus` — patch first, and never refetch for the patch's own sake

A `@Singleton` `SharedFlow<UserDataChange>` (`itemId` + the new `UserData`), replay-free and
buffered so publishing never suspends. It is the Swiftfin pattern the plan adopts: marking an
episode watched on its detail page updates the home rows behind it with **no request at all**.

Collectors today:

- `HomeViewModel` → `HomeUiState.withUserData(...)` patches *Continue watching*, *Next up* and
  every *Latest* row. Rows that do not contain the item are returned unchanged, so Compose skips
  them.
- `ItemDetailViewModel` → `ItemDetailUiState.withUserData(...)` patches the item itself plus the
  seasons, episodes, next-up and similar rows. This is also how the detail page's own toggles
  reflect optimistically — the button flips from the local write, not from a round-trip.

Replay is deliberately off: a screen that loads after a toggle reads the current value from its own
request, and a replayed stale change would fight with it.

### What a patch cannot do — and what home does about it

A patch rewrites the `userData` of a card **that is already on screen, under that exact id**. Three
things fall outside that, and all three were visible as "changing watched state doesn't update the
home screen":

| Case | Why the patch misses it |
|---|---|
| Marking a movie watched | The card should *leave* *Continue watching*, not restyle |
| Marking an episode watched | *Next up* should advance to the **next** episode — an item the screen has never seen |
| *Mark watched* on a series/season page | The change is published under the **container's** id, which no episode card matches |

`HomeViewModel` answers these in two layers (`docs/features/home.md` has the screen-side detail):

1. **Instantly, with no request.** `withUserData` treats *Continue watching* and *Next up* as rows
   of unfinished items: a change that says `played` evicts the item rather than patching it. This
   is the first case, and it works offline, where nothing else can.
2. **Once the toggling stops**, a debounced (1.5 s) silent re-fetch of just those two rows —
   `getResumeItems()` + `getNextUp()`, online only, no spinner, no error state, *Latest* untouched
   because "recently added" is not a function of what was watched. This covers the other two cases
   and un-marking, and one pass covers a whole season's worth of writes.

This is a deliberate softening of "patch, **never** refetch" (DECISIONS.md, 2026-07-29): the patch
is still the only thing the user waits for, and the refetch exists solely for row *membership*,
which is a server-side question. Two rules keep it honest:

- **The read must not undo the write.** `UserDataRepositoryImpl` publishes *before* it pushes, so a
  refetch can overtake its own write — and a push that failed is queued for `UserDataSyncWorker`
  and may stay pending for a while. The ViewModel therefore keeps the last value it published per
  item and re-applies it on top of whatever comes back (`HomeUiState.mergeLocalUserData`), which is
  the same rule `StaleUserDataRegressionTest` pins in `:data`: a server read is authoritative
  unless a local write is still waiting.
- **Only `played` triggers it.** Position writes do not, even though they reorder *Continue
  watching*: `PlaybackReporter` writes one every five seconds, so a position-triggered refresh
  would be a poll for the length of a film. A finished item is marked played by that same reporter,
  so returning from the player still corrects the row.

## `UserDataSyncWorker` — real since M8

The milestone list said M4 delivers "local-first writes + EventBus; **sync worker stubbed**". M8
filled in the drain; everything around the worker was already real and is unchanged —
`NetworkType.CONNECTED`, exponential backoff from 30 s, `enqueueUniqueWork(KEEP)` under the name
`user-data-sync` (the worker drains whatever is pending when it runs, so a burst of failed toggles
must not push the one scheduled run further out), and `UserDataDao.getPendingSync()`.

The worker itself is three lines of mapping — `SyncOutcome.NOTHING_PENDING`/`DRAINED` →
`Result.success()`, `RETRY` (and any unexpected throwable) → `Result.retry()`, never
`Result.failure()`. The rule lives in **`UserDataSyncer`**, which runs on the JVM and is where
most-recent-wins is actually decided.

### Most-recent-wins, per pending row

The server's `userData` is fetched (`GET /Users/{userId}/Items/{itemId}`) and compared against the
row:

| Server state | Decision |
|---|---|
| `lastPlayedDate` **after** `row.updatedAt` | **adopt** — upsert with `toBeSynced = false`, publish on the event bus |
| exactly equal | **adopt** — the server already holds this instant; adopting is idempotent |
| `lastPlayedDate` **before** `row.updatedAt` | **push**, then `clearPendingSync(itemId, userId, updatedAt)` |
| `lastPlayedDate` is `null` | **push** — a server that never played it cannot outrank a local change |
| no `userData` at all | **push** |
| transport failure | **keep the flag**; the drain returns `Result.retry()` |
| `404` | **abandon** — clear the flag and log; the item is gone from the server |

The comparison is the local `updatedAt` against the server's `lastPlayedDate`, deliberately not the
two `lastPlayedDate`s: a favourite toggle never touches `lastPlayedDate`, so comparing those would
make every offline favourite lose to a film watched last week. Both go through `SdkDateTime`.

A push sends the **whole row** — `markPlayedItem`/`markUnplayedItem`, then the favourite endpoints,
then `updateItemUserData` with the full desired state, in that order (`markPlayedItem` clears the
server's resume position, so the position has to be asserted after it). The worker cannot know which
operation produced a pending row, because an offline session batches several into one. See
`DECISIONS.md`, 2026-07-29.

One row failing never abandons the rest; the rows that succeeded are already clear and do not come
back on the retry.

### When it runs

| Trigger | Owner |
|---|---|
| A local write that was **online** and whose push failed anyway | `UserDataRepositoryImpl` (M4, gated on connectivity since M9) |
| App start, when rows are already pending | `UserDataSyncTrigger` (M8) |
| Every transition back to `ConnectionState.ONLINE` | `UserDataSyncTrigger` (M8) |

The last two are what deliver M8's definition of done: on an airplane-mode session there is no failed
push to enqueue anything, and `NetworkType.CONNECTED` only re-runs work that was enqueued in the
first place. `UserDataSyncTrigger` collects `ConnectionStateProvider.state` on the application scope
— started from `JellyfinNativeApplication.onCreate`, so it works with no screen showing — and guards
the enqueue on `countPendingSync() > 0`.

`WorkManagerUserDataSyncScheduler` swallows and logs an enqueue failure: losing the scheduled retry
must never break the local write that triggered it — the row keeps `toBeSynced = true` and the next
successful push clears it.

The `:app` integration the worker needs (`HiltWorkerFactory` in a `Configuration.Provider`, the
WorkManager work-runtime and hilt-work dependencies, the default initialiser removed from the
manifest) has been in place since M0.

## Key classes

| Class | Module | Responsibility |
|---|---|---|
| `UserDataRepository` / `UserDataRepositoryImpl` | `:data` | The local-first write path |
| `UserDataEventBus` / `UserDataChange` | `:data` | App-wide broadcast of local changes |
| `UserDataSyncScheduler` / `WorkManagerUserDataSyncScheduler` | `:data` | Retry scheduling |
| `UserDataSyncWorker` | `:data` | Maps a drain onto a WorkManager result |
| `UserDataSyncer` / `SyncOutcome` | `:data` | Most-recent-wins, per pending row (M8) |
| `UserDataSyncTrigger` | `:data` | Enqueues on app start and on reconnection (M8) |
| `UserDataEntity` / `UserDataDao` | `:core:database` | The `user_data` table |
| `InstantConverter` | `:core:database` | `Instant` ↔ epoch millis |
| `BrowseCacheWriter` | `:data` | Refreshes non-pending rows from every server read |

## Offline behaviour

The write path never requires a network. With no connectivity the Room row is written, the UI
patches, the push is skipped outright (M9 — see "The offline push gate"), the flag stays set, and the
drain runs on the next return to `ONLINE`. See
[`docs/features/offline-playback.md`](offline-playback.md) for the playback half of that loop.

## Verification

M4 DoD walked on the test tablet (2026-07-28): mark-watched sent
`POST /UserPlayedItems/{id}`, jellyfin-web-visible (`Played=True` server-side), and the
home card patched via the event bus with zero network requests; favorite toggle
round-tripped `POST`/`DELETE /UserFavoriteItems/{id}` with server state confirmed both
ways. All test toggles were reverted.
