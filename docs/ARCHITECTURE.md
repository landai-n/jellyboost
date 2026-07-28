# Architecture

Module structure, layering rules and cross-cutting mechanisms. The authoritative *plan* is
`docs/PLAN.md`; this file records what has actually been built, milestone by milestone. Per-feature
detail lives in `docs/features/<name>.md`.

Sections are appended per milestone and delimited, so parallel worktree branches can each add their
own without conflicting.

<!-- ===== Offline read path (M6) ===== -->

## Offline read path (M6)

Full detail: [`docs/features/offline-read.md`](features/offline-read.md).

**The rule the architecture enforces:** there is no offline *mode*. The UI has no idea which source
answered it, because both sources produce the identical `JellyfinItem` domain model. `BaseItemDto`
and `ItemEntity` never cross a repository boundary.

### Module additions

| module | added at M6 |
|---|---|
| `:core:database` | `ItemEntity` + `ItemSource` (`items`), `LibraryViewEntity` (`library_views`), `ItemDao`, `LibraryViewDao`, enum/list converters. Schema **v3** via `@AutoMigration(2, 3)` (purely additive, exported). |
| `:core:datastore` | `AppPreferences` + `DataStoreAppPreferences` (the `forceOffline` setting) and the singleton preferences `DataStore`. |
| `:core:network` | `connectivity/` — `ConnectivityMonitor`, `ServerReachabilityProbe`, `ServerProbeApi`, `ConnectionStateProvider`. Plus `@ApplicationScope` (process-lifetime `CoroutineScope`) and `ApiClientProvider.useAddress`. |
| `:data` | `OfflineJellyfinRepository`, `DelegatingJellyfinRepository` (**now the `JellyfinRepository` binding**), `cache/ItemEntityMapper`, `cache/BrowseCacheWriter`; `OnlineJellyfinRepository` gained write-through. |
| `:app` | `ConnectionViewModel`; `AppScaffold` hosts the app-wide `OfflineBanner`; the home overflow menu carries the offline-mode toggle. |

### Layering

```
:feature:*  ──►  JellyfinRepository  (interface, :data)
                        ▲
                        │ @Binds
              DelegatingJellyfinRepository
                   ├── OnlineJellyfinRepository ──► jellyfin-sdk ──┐
                   │            └── BrowseCacheWriter ──────────┐  │
                   └── OfflineJellyfinRepository ──► Room ◄──────┘  │
                                                                   │
              ConnectionStateProvider ◄── ConnectivityMonitor       │
                       ▲                  ServerReachabilityProbe ──┘
                       └── AppPreferences.forceOffline
```

- Features depend on the `JellyfinRepository` **interface** only; the delegation is invisible to them
  and none of them changed at M6.
- `:core:network` owns connectivity because it owns the `ApiClient` the probe re-points.
- `:core:database` DAOs stay dumb (queries only). Rules that need testing — the never-downgrade-a-
  download merge — live in `:data`, where they run on the JVM.

### Cross-cutting mechanisms introduced

- **`@ApplicationScope`** (`:core:network/di`) — a `SupervisorJob`-backed, never-cancelled
  `CoroutineScope` for work that outlives any screen: the connectivity collectors, the probe loop,
  and fire-and-forget cache writes. Injected so tests substitute a `TestScope`.
- **`ConnectionState`** — one `StateFlow`, read by the delegating repository per call and by the
  single app-wide banner. Nothing else in the app branches on connectivity.
- **SDK date handling** (`:data/SdkDateTime.kt`) — jellyfin-sdk's `LocalDateTime` fields are *local
  wall-clock* time in both directions (its `DateTimeSerializer` applies `ZoneId.systemDefault()`).
  All conversions go through one pair of helpers; see DECISIONS.md, 2026-07-28.

<!-- ===== end Offline read path (M6) ===== -->
