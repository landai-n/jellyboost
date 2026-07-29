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

<!-- BEGIN: Playback (M5) -->
## Playback (M5)

`:player` owns everything between "the user tapped Play" and "pixels on screen". It depends on
`:core:common` (routes, `AppResult`), `:core:network` (the SDK `ApiClient`), `:core:ui` and
`:data` (`JellyfinRepository` for the title, `UserDataRepository` for resume positions). Nothing
depends on `:player` except `:app`, which owns the `Routes.Player` NavHost entry.

**Module layout**

```
:player
 ├── model/         PlaybackMediaSource (sealed), PlaybackMediaItemSpec, PlaybackSnapshot, PlaybackQuality
 ├── api/           PlayerApi + StreamUrlFactory (interfaces) and their SDK implementations
 ├── deviceprofile/ DeviceProfileBuilder, MediaCodecProbe, CodecHelpers
 ├── resolve/       PlaybackInfoResolver, ExoMediaSourceFactory
 ├── report/        PlaybackReporter
 ├── fallback/      DecoderFallbackHandler
 ├── session/       PlayerHandle + ExoPlayerHandle, TrackSelectionController, PlaybackService,
 │                  JellyfinAuthInterceptor
 ├── ui/            PlayerViewModel, PlayerScreen, PlayerControls, PlayerSheets
 └── di/            PlayerModule (bindings), DetachedPlayerScope
```

**Three seams, and why they exist.** ExoPlayer, `MediaCodecList` and the SDK's `ApiClient` cannot
be exercised off a device, and between them they touch every decision in this module. Each is
hidden behind an interface — `PlayerHandle`, `MediaCodecProbe`, `PlayerApi`/`StreamUrlFactory` —
so the parts that are genuinely ours (the play-method decision, URL selection, the reporting
cadence, the fallback ladder, the re-resolve sequencing) are plain unit tests, and the parts that
are not are thin, mechanical adapters.

For the same reason `ExoMediaSourceFactory` produces a `PlaybackMediaItemSpec` — a plain data
description of a URL and its subtitles — rather than a `MediaItem`: `android.net.Uri` is a
throwing stub in local unit tests. The conversion is one function, `PlaybackMediaItemSpec.toMediaItem`.

**One shared `ExoPlayer`.** `ExoPlayerHandle` is a `@Singleton` that creates the player lazily on
first use. `PlaybackService : MediaSessionService` wraps that same instance in a `MediaSession` for
background playback and the media notification; the UI drives the instance directly rather than
through a `MediaController` (DECISIONS.md, 2026-07-28). The service is declared in `:player`'s own
`AndroidManifest.xml`, together with the foreground-service permissions, so `:app`'s manifest stays
untouched.

**Network.** Media requests use a `:player`-local `OkHttpClient` — media transfers are long-lived
and share nothing with a JSON API call's timeouts — with `JellyfinAuthInterceptor` adding the
Jellyfin `Authorization` header to requests aimed at the configured server, and only those.

**Reporting crosses into `:data`.** Every position the reporter sends to the server is also written
through `UserDataRepository`, and finishing an item goes through `setPlayed` rather than a bare
`markPlayedItem`. That is what makes resume identical online and offline, and what makes the detail
screen's watched tick flip without a refetch.

See `docs/features/playback.md` for the pipeline itself.
<!-- END: Playback (M5) -->

<!-- BEGIN: Downloads (M7) -->
## Downloads (M7)

Full detail: [`docs/features/downloads.md`](features/downloads.md).

**The rule the architecture enforces:** Room is the single source of truth for download state.
Nothing caches progress in memory, so every surface — the queue tab, the badge on every card, the
foreground notification — is a projection of the database and is correct across a process death by
construction.

### Module additions

| module | added at M7 |
|---|---|
| `:core:common` | `DownloadStatus` and `DownloadFileType` (moved up from `:data:downloads`, since `:core:database` persists them). |
| `:core:database` | `DownloadEntity` (`downloads`), `DownloadFileEntity` (`download_files`, FK-cascading), `DownloadWithFiles`, `DownloadProgress`, `DownloadDao`, two enum converters. Schema **v4** via `@AutoMigration(3, 4)` (purely additive, exported). `ItemDao.deleteDownloadsNotIn` for the cascade. |
| `:core:datastore` | `AppPreferences.downloadOverWifiOnly` (defaults **on**). |
| `:data:downloads` | The pipeline — see the table in the feature doc. Own `AndroidManifest.xml` (foreground-service permissions, the `dataSync` service-type override, the action receiver) and own `strings.xml`, so `:app`'s manifest stays untouched. |
| `:data` | `ItemEntityMapper.toDtoOrNull`; `runCatchingApi` / `toAppError` widened from `internal` to public. |
| `:feature:downloads` | `DownloadsScreen`, `DownloadsViewModel`, `DownloadSpeedTracker`, the two tab renderers. |
| `:feature:detail` | The Download button is live: enqueue / cancel / remove / retry, with live state. |
| `:feature:home` `:feature:library` `:feature:search` | One `observeStates()` subscription each, stamped onto their items. |
| `:app` | The fourth bottom-nav tab, the `Routes.Downloads` NavHost entry, and the one-shot `POST_NOTIFICATIONS` request. |

### Layering

```
:feature:downloads ──► DownloadRepository (interface, :data:downloads)
:feature:detail    ──►        ▲
:feature:home      ──►        │ @Binds
:feature:library   ──►  DownloadRepositoryImpl
:feature:search    ──►    ├── DownloadEnqueuer ──► DownloadApi ──► jellyfin-sdk
                          ├── DownloadDeleter  ──► DownloadStorage + Room
                          ├── DownloadScheduler ──► WorkManager ──► DownloadWorker
                          │                                            └── DownloadQueue
                          │                                                  ├── DownloadFilePlanner
                          │                                                  └── FileDownloader (OkHttp)
                          └── DownloadDao (Flows) ──────────────────────────────────► every badge
```

`:data:downloads` sits **above** `:data` (it reuses `ItemDao`, `ItemEntityMapper` and the error
taxonomy) and below the features. Home, library and search depend on it read-only, for
`observeStates()` alone; only `:feature:downloads` and `:feature:detail` mutate anything.

### Cross-cutting mechanisms introduced

- **`observeStates(): Flow<Map<String, DownloadState>>`** — one subscription per screen rather than
  one per card. It is `distinctUntilChanged`, so the twice-a-second progress writes only reach the
  UI when a badge actually changes. Each ViewModel also *holds* the last map, because a later load
  replaces its items and the Flow would not re-emit just because a screen refetched.
- **Two types for one concept.** `DownloadStatus` (persistence) and `DownloadState` (UI) are
  deliberately separate: the UI's `Downloading` carries a progress fraction and has no use for
  `CANCELLED`. `DownloadRepositoryImpl` is the single place they meet.
- **Seams for testability**, the same pattern `:player` uses: `DownloadApi`, `DownloadUrlFactory`,
  `DownloadStorage` and `DownloadScheduler` are interfaces because a real `ApiClient`, filesystem
  or WorkManager cannot be exercised on the JVM — which leaves the file plan, the resume logic, the
  status machine and the delete cascade as plain unit tests.
- **A download-only `OkHttpClient`** (`@DownloadHttpClient`), with no read timeout (a healthy
  multi-gigabyte transfer holds one response open for an hour) and redirects followed. Separate
  from the SDK's client and from `:player`'s.
<!-- END: Downloads (M7) -->

<!-- BEGIN: Offline playback + sync (M8) -->
## Offline playback + sync (M8)

Full detail: [`docs/features/offline-playback.md`](features/offline-playback.md).

**The rule the architecture enforces:** a completed download is played from disk *regardless of
connectivity*, and nothing above `PlaybackMediaSource` knows which half it is holding. Offline is not
a mode here either — it is which subtype the resolver returned.

### Module additions

| module | added at M8 |
|---|---|
| `:core:database` | `DownloadDao.getWithFiles(itemId)` — one download with its file rows. No schema change; **still v4**. |
| `:data:downloads` | `offline/DownloadedMediaProvider` + `offline/DownloadedMedia` — "what is on disk for this item", Room joined with a real filesystem check. |
| `:player` | `model/LocalPlaybackMediaSource`, `model/LocalTrickplay`; `resolve/LocalPlaybackResolver`, `resolve/PlaybackSourceResolver`; `ExoMediaSourceFactory` and `PlaybackReporter` and `DecoderFallbackHandler` widened to the sealed type; `PlayerUiState.isLocalPlayback`. |
| `:data` | `userdata/UserDataSyncer` (most-recent-wins), `userdata/UserDataSyncTrigger`; `UserDataSyncWorker` is no longer a stub. |
| `:app` | `JellyfinNativeApplication` starts `UserDataSyncTrigger`. |

### Layering

```
PlayerViewModel ──► PlaybackSourceResolver
                       ├── LocalPlaybackResolver ──► DownloadedMediaProvider ──► DownloadDao
                       │        (:player)                (:data:downloads)         ItemDao
                       │                                                           + File.isFile
                       └── PlaybackInfoResolver ──► jellyfin-sdk

PlaybackReporter ──► PlayerApi            (only when remote AND ConnectionState.ONLINE)
                 └─► UserDataRepository   (always)
                          └─► user_data (toBeSynced = true)
                                  ▲
ConnectionStateProvider ──► UserDataSyncTrigger ──► WorkManager ──► UserDataSyncWorker
                                                                        └── UserDataSyncer
```

`:player` still does not depend on `:core:database`; the DAO work sits in `:data:downloads`, which
already owns the download schema, and crosses the boundary as a plain `DownloadedMedia` value
(`DECISIONS.md`, 2026-07-29).

### Cross-cutting mechanisms introduced

- **`PlaybackMediaSource` finally has two implementations**, which is what the sealed type was
  declared for at M5. `withSelectedAudio` / `withSelectedSubtitle` were added to the interface so a
  track switch can be recorded without the state holder knowing the variant — the one place a
  `copy()` would have forced an online/offline branch into `PlayerViewModel`.
- **`ConnectionState` reaches `:player`.** It was previously read only by
  `DelegatingJellyfinRepository` and the banner; `PlaybackReporter` now reads it too, to skip reports
  that cannot arrive, and `PlaybackSourceResolver` to fail a no-local-copy playback fast instead of
  waiting on a socket timeout. Still one `StateFlow`, still nothing else branching on connectivity.
- **`file://` URIs as first-class sources.** `DefaultDataSource` already resolved them (wired at M5);
  M8 adds `localFileUri`, which builds them through `java.net.URI` so a `#` or a space in a filename
  named after the media survives. Local URIs bypass `StreamUrlFactory` entirely.
- **A trigger for work nobody failed at.** `UserDataSyncTrigger` is the first thing in the app that
  schedules work from a *connectivity transition* rather than from a failed operation. It is started
  from `Application.onCreate` because a device coming back online with the app backgrounded is
  precisely the case the milestone's definition of done exercises.
<!-- END: Offline playback + sync (M8) -->

<!-- BEGIN: Player polish (M9) -->
## Player polish (M9)

Three new seams, one new module edge, and one architectural correction.

### New module edge

`:player` now takes `implementation(projects.core.datastore)` (`DECISIONS.md`, 2026-07-29). Two of
M9's behaviours are gated on a user preference and the player is the only thing that can act on
them; `:data:downloads` already takes the same dependency for its Wi-Fi-only constraint. The enum
they carry, `SegmentSkipMode`, lives in `:core:common` so the store, the player and the settings
screen can all name it without seeing each other.

### New seams inside `:player`

```
PlayerViewModel ──► TrickplayResolver ──► PlayerApi        (item's trickplay geometry)
                          └──────────────► StreamUrlFactory (tile sheet URLs)
                └─────► MediaSegmentLoader ──► PlayerApi    (GET /MediaSegments/{id})
                └─────► SegmentSkipController                (pure decision, once-per-segment state)
                └─────► PipController ◄──────────────────► MainActivity
                └─────► AppPreferences                       (:core:datastore)

PlayerGestureLayer (Compose) ──► PlayerGestureController     (zones, distance, exclusions)
```

- **`PipController`** is the only new `@Singleton` and the only one shared with `:app`. Picture-in-
  picture is an *activity* capability whose conditions are entirely the player's, and `MainActivity`
  hosts every screen — without this seam it would have to reach into the player's ViewModel or
  guess. Traffic runs both ways: the player publishes readiness, the activity publishes the system's
  mode changes.
- **`SegmentSkipController` and `PlayerGestureController` hold no Android type.** Everything that has
  a right answer — which sheet a position is on, which third a tap landed in, whether a segment has
  already been auto-skipped — is a plain object with unit tests, and the composable that feeds it
  coordinates is deliberately thin.
- **`TrickplayTiles` is one type for two origins.** A downloaded item's sheets are `file://` URIs on
  disk; a streamed item's are server URLs derived from the thumbnail count. The scrubber has one code
  path, which extends M8's "the player UI is identical online and offline" to the seek bar.

### Correction to the M5/M8 session architecture

`PlaybackService` now calls `addSession(session)` in `onCreate`. Media3 only manages a session —
media notification, foreground promotion — once it has been added, which normally happens when a
`MediaController` connects; this app deliberately drives the shared `ExoPlayer` directly
(`DECISIONS.md`, 2026-07-28), so nothing ever added it and the service was never promoted. That,
not the notification permission, is why backgrounding the app stopped playback from M5 onwards
(`DECISIONS.md`, 2026-07-29).
<!-- END: Player polish (M9) -->

<!-- BEGIN: Settings + app polish (M9) -->
## Settings + app polish (M9)

One new module, one new cross-cutting signal, no changes to `:player`.

### `:feature:settings` joins the module graph

`:feature:settings` moves from an empty stub to a real screen and takes two dependency edges no
other feature module has needed before: `implementation(projects.core.network)` (for
`SessionRepository` — account info and sign-out) and `implementation(projects.core.datastore)` (for
`AppPreferences` — every preference the screen edits), plus the already-common
`implementation(projects.data.downloads)`. `:player` set the precedent for a feature-adjacent module
depending on both `core:network` and `core:datastore` directly at M9 (`DECISIONS.md`, 2026-07-29);
this is the same shape applied to a screen instead of the player.

Sign-out itself needed no new plumbing: `SettingsViewModel` calls `SessionRepository.signOut()`
directly, and the pre-existing `LogoutRedirectEffect` in `JellyfinNavHost` (watching
`SessionRepository.sessionState`) already redirects to `Routes.ServerSetup` on any transition to
`SessionState.LoggedOut`, regardless of what triggered it. The `onSignOut` callback that used to
thread `MainActivity → JellyfinNativeApp → AppScaffold → JellyfinNavHost → HomeRoute` is gone.

### A second connectivity signal: reconnect edges

`ConnectionStateProvider.state` already had one consumer that reacts to a connectivity
*transition* rather than a snapshot (`UserDataSyncTrigger`, M8). M9 adds a second, smaller one:

```
ConnectionStateProvider.state ──► reconnectEdges()   (:core:network, Flow<ConnectionState> extension)
                                        │  map{isOnline}.distinctUntilChanged().drop(1).filter{it}
                                        ▼
                                 ReconnectRefresher.reconnected: Flow<Unit>   (:data)
                                        │
              ┌─────────────┬──────────┼───────────────┬─────────────────┐
              ▼             ▼          ▼                ▼                 ▼
        HomeViewModel LibrariesVM ItemDetailVM   SearchViewModel   LibraryViewModel
          .refresh()   .refresh()  .refresh()   .retry() if query   .retryFacets() only
                                                    non-blank        (grid already self-
                                                                      refreshes, see below)
```

`ReconnectRefresher` exists so five feature modules — none of which depend on `core:network` — can
observe a reconnect edge as a bare `Flow<Unit>`, the same shape `:data` already uses to keep
`core.network` types off feature classpaths for `JellyfinRepository`. It deliberately does **not**
reuse `UserDataSyncTrigger`'s "fire on the initial value too" convention: that trigger's consumer is
idempotent and free when nothing is pending, but a ViewModel that already fetches once in `init`
would double every request on an ordinary launch if the reconnect signal fired at startup too
(`DECISIONS.md`, 2026-07-29). The two connectivity-transition consumers now answer genuinely
different questions and are not expected to converge.

`LibraryViewModel`'s paged grid needed no wiring at all — `DelegatingJellyfinRepository.
getItemsPaged` already `flatMapLatest`s a fresh `Pager` off `ConnectionState.isOnline`, so a
reconnect edge already swaps the grid's data source for free. `ReconnectRefresher` only drives that
ViewModel's filter facets, which sit outside the paged flow.

### The offline user-data push is now actually gated on connectivity

`UserDataRepositoryImpl` gained a fourth collaborator, `ConnectionStateProvider`, read once per
write inside `pushToServer`. This closes the gap between what `docs/PLAN.md` describes ("if online
push … else/on failure enqueue") and what M8 actually built (an unconditional push attempt every
time, online or not) — see `DECISIONS.md`, 2026-07-29, for why the "enqueue on failure" half is
deliberately not mirrored for the offline case: `UserDataSyncTrigger` already owns draining
everything pending on the next reconnect, so an offline write only needs to leave `toBeSynced = true`
behind, which `storeLocally` already did before this change and still does.
<!-- END: Settings + app polish (M9) -->
