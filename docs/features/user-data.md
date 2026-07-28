# Feature: User data (watched / favourite / resume) — M4

Every watched, favourite and resume-position write in the app goes through one local-first path.
This is the piece that makes the online/offline UI feel like one product: a toggle is durable and
on screen before the network is involved at all, and it works identically with no network.

## The contract

`UserDataRepository` (`:data`, `dev.jellyfinnative.data.userdata`) does the same four things for
every operation, in this order (docs/PLAN.md, "Data layer"):

1. **Upsert Room** — `UserDataEntity` with `toBeSynced = true` and a fresh `updatedAt`.
2. **Publish** on `UserDataEventBus` — every list ViewModel patches its items in place.
3. **Push** to the server.
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

Marking watched clears the resume position locally because the server does the same — not
mirroring it would leave a progress bar on a watched card until the next sync overwrote it.

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

## `UserDataEventBus` — patch, never refetch

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

## `UserDataSyncWorker` — stubbed until M8

The milestone list says M4 delivers "local-first writes + EventBus; **sync worker stubbed**".
Everything around the worker is real — `NetworkType.CONNECTED`, exponential backoff,
`enqueueUniqueWork(KEEP)` (the worker drains whatever is pending when it runs, so a burst of failed
toggles must not push the one scheduled run further out), and `UserDataDao.getPendingSync()`.
`doWork` logs the pending count and returns success; M8 fills in most-recent-wins conflict
resolution.

`WorkManagerUserDataSyncScheduler` swallows and logs an enqueue failure: losing the scheduled retry
must never break the local write that triggered it — the row keeps `toBeSynced = true` and the next
successful push clears it.

### Integration required in `:app`

`UserDataSyncWorker` is a `@HiltWorker`, so before it can actually run, `:app` needs:

- `implementation(libs.androidx.work.runtime.ktx)` + `implementation(libs.androidx.hilt.work)` and
  `ksp(libs.androidx.hilt.compiler)`;
- `JellyfinNativeApplication : Configuration.Provider` returning a `Configuration` built with the
  injected `HiltWorkerFactory`;
- the default WorkManager initialiser removed from the manifest
  (`<provider android:name="androidx.startup.InitializationProvider" tools:node="remove">`, or the
  `androidx.work.WorkManagerInitializer` entry) so the Hilt-aware configuration wins.

Until that lands the enqueue is harmless (it is caught and logged) and the worker body is a no-op,
so nothing is lost at M4.

## Key classes

| Class | Module | Responsibility |
|---|---|---|
| `UserDataRepository` / `UserDataRepositoryImpl` | `:data` | The local-first write path |
| `UserDataEventBus` / `UserDataChange` | `:data` | App-wide broadcast of local changes |
| `UserDataSyncScheduler` / `WorkManagerUserDataSyncScheduler` | `:data` | Retry scheduling |
| `UserDataSyncWorker` | `:data` | Drains pending rows (stub until M8) |
| `UserDataEntity` / `UserDataDao` | `:core:database` | The `user_data` table |
| `InstantConverter` | `:core:database` | `Instant` ↔ epoch millis |

## Offline behaviour

Already correct: the write path never requires a network. With no connectivity the Room row is
written, the UI patches, the push fails, the flag stays set and the worker is enqueued behind
`NetworkType.CONNECTED`. What is missing until M8 is the drain itself.

## Verification

M4 DoD walked on the test tablet (2026-07-28): mark-watched sent
`POST /UserPlayedItems/{id}`, jellyfin-web-visible (`Played=True` server-side), and the
home card patched via the event bus with zero network requests; favorite toggle
round-tripped `POST`/`DELETE /UserFavoriteItems/{id}` with server state confirmed both
ways. All test toggles were reverted.
