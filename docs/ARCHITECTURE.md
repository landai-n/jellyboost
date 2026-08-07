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
| `:app` | `ConnectionViewModel`; `AppScaffold` + the floating chrome it draws over the nav host — `GlassBottomNav` (<560dp), `GlassTopNav` (≥560dp), `AppActionCluster` (the compact layout's app-wide actions), `AppActions`/`ConnectionStatus`/`AppChrome`. The chrome reserves no space; it publishes its footprint as `core/ui`'s `LocalAppChromePadding`, which top-level screens add to their scrollable `contentPadding`. |

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
 ├── deviceprofile/ DeviceProfileBuilder, MediaCodecProbe, CodecHelpers, CastDeviceProfile
 ├── resolve/       PlaybackInfoResolver, ExoMediaSourceFactory
 ├── report/        PlaybackReporter
 ├── fallback/      DecoderFallbackHandler
 ├── session/       PlayerHandle + ExoPlayerHandle + RoutingPlayerHandle (the binding),
 │                  TrackSelectionController, PlaybackService, PlaybackServiceState,
 │                  JellyfinAuthInterceptor
 ├── cast/          CastAvailability + the Google Cast sender; the app's only GMS types
 │                  (docs/features/chromecast.md)
 ├── syncplay/      SyncPlayController + the group protocol; presence/ holds the foreground
 │                  service a group without playback runs behind (docs/features/syncplay.md)
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

**…behind a routing seam, since M12.** The `PlayerHandle` Hilt binding is `RoutingPlayerHandle`, which
delegates to `ExoPlayerHandle` or to `CastPlayerHandle` depending on whether a television has the
film; `CastSessionCoordinator` is the only thing allowed to move the pointer. With no cast session
every method is a single delegation with no branch in it, which is what makes "casting changed nothing
about playing alone" a property of the code rather than of the tests. `PlaybackService` deliberately
keeps injecting the concrete `ExoPlayerHandle`: it owns the *local* media session, which is exactly
what should disappear while the Cast framework publishes its own.

**A second foreground service, for a group with nothing playing.** `SyncPlayPresenceService`
(`syncplay/presence/`, `specialUse`) exists only to keep a backgrounded process's network alive while
a SyncPlay group is held and no playback is running — the OEM behaviour that used to cost the user
their group (DECISIONS.md, 2026-07-31). The two never run together: `PlaybackServiceState` publishes
`PlaybackService`'s own lifecycle and `syncPlayPresenceDemanded` releases the presence service the
moment playback takes over. Its coordinator is also where `ProcessLifecycleOwner` is observed, which
is why `:player` depends on `androidx.lifecycle:lifecycle-process`.

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
| `:app` | `JellyboostApplication` starts `UserDataSyncTrigger`. |

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
directly — from `@ApplicationScope` rather than `viewModelScope`, because leaving the screen must
not cancel a teardown that is half done (see docs/features/settings.md, *"A sign-out the screen
cannot lose"*) — and the pre-existing `LogoutRedirectEffect` in `JellyfinNavHost` (watching
`SessionRepository.sessionState`) already redirects to `Routes.ServerSetup` on any transition to
`SessionState.LoggedOut`, regardless of what triggered it. The `onSignOut` callback that used to
thread `MainActivity → JellyboostApp → AppScaffold → JellyfinNavHost → HomeRoute` is gone.

### A second connectivity signal: connectivity changes

`ConnectionStateProvider.state` already had one consumer that reacts to a connectivity
*transition* rather than a snapshot (`UserDataSyncTrigger`, M8). M9 adds a second, smaller one —
and, after a fix later the same milestone, one that fires on *both* the online→offline and the
offline→online edge, not only the latter:

```
ConnectionStateProvider.state ──► onlineStateChanges()   (:core:network, Flow<ConnectionState> extension)
                                        │  map{isOnline}.distinctUntilChanged().drop(1)
                                        ▼
                            ConnectivityRefresher.connectivityChanged: Flow<Unit>   (:data)
                                        │
              ┌─────────────┬──────────┼───────────────┬─────────────────┐
              ▼             ▼          ▼                ▼                 ▼
        HomeViewModel LibrariesVM ItemDetailVM   SearchViewModel   LibraryViewModel
          .refresh()   .refresh()  .refresh()   .retry() if query   .retryFacets() only
                                                    non-blank        (grid already self-
                                                                      refreshes, see below)
```

`ConnectivityRefresher` exists so five feature modules — none of which depend on `core:network` —
can observe a connectivity change as a bare `Flow<Unit>`, the same shape `:data` already uses to keep
`core.network` types off feature classpaths for `JellyfinRepository`. It deliberately does **not**
reuse `UserDataSyncTrigger`'s "fire on the initial value too" convention: that trigger's consumer is
idempotent and free when nothing is pending, but a ViewModel that already fetches once in `init`
would double every request on an ordinary launch if the connectivity signal fired at startup too
(`DECISIONS.md`, 2026-07-29). The two connectivity-transition consumers now answer genuinely
different questions and are not expected to converge.

The signal originally fired only on the offline→online edge (`filter{it}` after the
`distinctUntilChanged`), which turned out to be half the bug it was meant to fix: switching to
offline mode, or simply losing the network, left an already-loaded screen showing online rows the
app could no longer play, right next to the offline banner saying so. Dropping the `filter{it}` so
`onlineStateChanges()` emits the new online-ness — `true` or `false` — on every change fixes that
symmetrically, with every consumer below running the exact same reload on either edge (DECISIONS.md,
2026-07-29).

`LibraryViewModel`'s paged grid needed no wiring at all — `DelegatingJellyfinRepository.
getItemsPaged` already `flatMapLatest`s a fresh `Pager` off `ConnectionState.isOnline`, so a
connectivity change already swaps the grid's data source for free, in either direction.
`ConnectivityRefresher` only drives that ViewModel's filter facets, which sit outside the paged flow.

### The offline user-data push is now actually gated on connectivity

`UserDataRepositoryImpl` gained a fourth collaborator, `ConnectionStateProvider`, read once per
write inside `pushToServer`. This closes the gap between what `docs/PLAN.md` describes ("if online
push … else/on failure enqueue") and what M8 actually built (an unconditional push attempt every
time, online or not) — see `DECISIONS.md`, 2026-07-29, for why the "enqueue on failure" half is
deliberately not mirrored for the offline case: `UserDataSyncTrigger` already owns draining
everything pending on the next reconnect, so an offline write only needs to leave `toBeSynced = true`
behind, which `storeLocally` already did before this change and still does.
<!-- END: Settings + app polish (M9) -->

<!-- BEGIN: Download quality (M9) -->
## Download quality (M9)

Full detail: [`docs/features/download-quality.md`](features/download-quality.md).

**The rule the architecture enforces:** the quality a download was fetched at is a property of the
*row*, not of a live preference read — the same "Room holds the plan, Room wins" rule the file name
already followed, and for the same corruption reason (DECISIONS.md, 2026-07-29).

One preference threads through the whole module graph, each module doing exactly one job with it:

| module | role |
|---|---|
| `:core:common` | `model/DownloadQuality.kt` — the four-entry enum (`ORIGINAL`/`HIGH`/`MEDIUM`/`LOW`), its bitrate/height ladder, and `isTranscoded`. Lives here for the reason `DownloadStatus` and `SegmentSkipMode` already do: the preference store, the pipeline and the settings screen all need to see it and none of them see each other. |
| `:core:datastore` | `AppPreferences.downloadQuality` (DataStore key `download_quality`), defaulting to `ORIGINAL`. Read exactly once per item, by `DownloadEnqueuer`, at the moment of the tap. |
| `:core:database` | `DownloadEntity.quality`, `DownloadQualityConverter`. Schema **v5** via `@AutoMigration(4, 5)` — one `NOT NULL` column with SQL default `'ORIGINAL'`, purely additive, exported. This is the module graph's **current** database version. |
| `:data:downloads` | the plan branch: `DownloadFilePlanner.media()` picks `DownloadUrlFactory.mediaUrl()` or `.transcodedVideoUrl()` on `quality.isTranscoded`; `DownloadQueue.reconcile` always plans from `download.quality`, never from the live preference; the 403 download-policy fallback is skipped for a transcoded row. |
| `:feature:settings` | `DownloadQualityGroup`, a `SettingsChoiceGroup` in the Downloads section, one row per entry with its bitrate in the label. |

```
:feature:settings ──► AppPreferences.downloadQuality (:core:datastore)
                              │
                              │ read once, on tap
                              ▼
                     DownloadEnqueuer (:data:downloads) ──► DownloadEntity.quality (:core:database, v5)
                              │
                              │ every later drain reads the ROW
                              ▼
                     DownloadQueue.reconcile ──► DownloadFilePlanner.media(quality) ──► DownloadUrlFactory
```

No new module edges: `:data:downloads` already depended on `:core:datastore` (the Wi-Fi-only
constraint) and on `:core:database`, so the quality preference travels along dependency edges that
already existed.
<!-- END: Download quality (M9) -->

<!-- BEGIN: Batch selection (post-M9) -->
## Batch selection (post-M9)

Full detail: [`docs/features/batch-selection.md`](features/batch-selection.md).

**The cross-module pattern this introduces:** a list-selection mode is *state plus one intent
lambda*, and both live above the feature modules so two screens cannot drift into two different
selection modes. No feature module gained a dependency on another; the two screens still do not see
each other.

| module | role |
|---|---|
| `:core:common` | `selection/ItemSelection.kt` — the id-keyed immutable selection, the `SelectionIntent` / `SelectionAction` vocabulary, and `runBatch` + `BatchOutcome` / `BatchReport`. Pure Kotlin and Android-free, so it unit-tests without a device and can be held in any `ViewModel`. Also `DownloadState.isDownloadable`, the "would enqueueing this do anything" predicate a batch needs and a single tap never did. |
| `:core:ui` | `SelectionAppBar` (the contextual M3 bar), `batchOutcomeText` (the summary copy), the selection scrim/indicator on `MediaCardArtwork`, and `Modifier.selectableCardClick` (tap + haptic long press). This is also the module's **first `res/`** — the shared bar needs its own strings, and duplicating "Mark watched" per feature is how two screens end up wording one action two ways. |
| `:feature:library`, `:feature:detail` | each exposes `val selection: StateFlow<ItemSelection>` and `fun onSelection(intent: SelectionIntent)`, and composes its batch from the single-item repository calls it already made. |

```
:core:common   ItemSelection / SelectionIntent / runBatch      DownloadState.isDownloadable
      ▲                        ▲                                        ▲
      │                        │                                        │
:core:ui    SelectionAppBar ───┘   batchOutcomeText                      │
      ▲                                                                 │
      ├───────────── :feature:library  LibraryViewModel.selection ───────┤
      └───────────── :feature:detail   ItemDetailViewModel.selection ────┘
                                   │
                                   ├─► UserDataRepository.setPlayed   (:data, local-first)
                                   └─► DownloadRepository.enqueue     (:data:downloads)
```

**Two rules the layering enforces**, both worth keeping if a third surface joins:

1. **The selection is its own `StateFlow`, never a field of the screen's ui state.** A grid cell has
   to read it to draw its indicator; reading it out of `LibraryUiState` would subscribe every visible
   cell to the sort key, the filters and the snackbar message as well.
2. **A batch is composed, never a new repository method.** `runBatch` takes a suspend lambda and the
   `ViewModel` hands it the existing single-item call, so there is no second code path to keep
   correct — the same reasoning `:feature:downloads`' *Pause all* / *Cancel all* follow
   (DECISIONS.md, 2026-07-29).

The one place a batch is *not* a plain fan-out is *Download*, and only because
`DownloadEnqueuer`'s idempotence is asymmetric: it skips already-downloaded children when it expands
a container, but a **single** item handed to it is always written back as `QUEUED`. The batch filters
on `DownloadState.isDownloadable` before calling, which is why that predicate lives in `:core:common`
next to the state it reads rather than in either feature.
<!-- END: Batch selection (post-M9) -->

<!-- BEGIN: SyncPlay (M11) -->
## SyncPlay (M11)

Full detail: [`docs/features/syncplay.md`](features/syncplay.md).

**No new module.** SyncPlay is a new package inside `:player`, because it needs exactly what
`:player` already has — `PlayerHandle`, the resolvers, the reporter, and `:core:network`'s
`ApiClient`, `SessionStateHolder` and `ConnectionStateProvider`. A module of its own would have had
to depend on all of them and be depended on by `:player` in turn.

```
:player/syncplay
 ├── api/     SyncPlayApi + SdkSyncPlayApi        (the single SDK-time boundary)
 ├── socket/  SyncPlaySocket + SdkSyncPlaySocket  (the app's only websocket)
 ├── model/   SyncPlayModels.kt                   (domain events, commands, queue)
 ├── time/    SyncPlayTimeSync, SyncPlayPinger    (server clock offset)
 ├── ui/      SyncPlayGroupsScreen + ViewModel, SyncPlayGroupSheet, SyncPlayQueueSheet + ViewModel
 ├── di/      SyncPlayModule, SyncPlayScope
 └──          SyncPlayController, SyncPlayCommandScheduler, SyncPlayDriftMonitor,
              SyncPlayStatusHolder, SyncPlayLocalSession, SyncPlayPlaybackHost, SyncPlayState,
              ControllerSyncPlaySession, SyncPlayDtoMapping / SyncPlayEnumMapping
```

**A coordinator that is not a screen.** `SyncPlayController` is a `@Singleton` with its own
supervisor scope (`@SyncPlayScope`, modelled on `DetachedPlayerScope`) driving `PlayerHandle`
directly. Group membership outlives the player screen and survives backgrounding, which a ViewModel
cannot; `PlayerViewModel` gets a thin `PlayerSyncPlayBridge` and none of the protocol.

**Three cross-cutting mechanisms this introduces.**

| mechanism | where | why it is shaped that way |
|---|---|---|
| `SyncPlaySession` + `SyncPlayGroupHandle` | `:core:common` `syncplay/` | The cross-feature contract. `:feature:detail` plays for the group (its Play button, in a group) and queues for it, and `:app` draws the active-group badge, without either depending on `:player`; `ControllerSyncPlaySession` binds it in `:player`. It speaks `String` ids and a participant count — no `:player` model escapes. (`:core:common` promotes `kotlinx-coroutines-core` to `api` for the `StateFlow`.) |
| `SyncPlayStatusHolder` | `:player/syncplay` | Breaks a would-be DI cycle. `PlaybackReporter` must know whether this session is in a group; the controller must be able to drive playback, which reaches the reporter's world. Both depend on this two-field holder instead. |
| `launchRequests` → NavHost | `:app` `SyncPlayLaunchViewModel` | "The group moved on and no player is open." The app collects it at the NavHost and navigates to `Routes.Player`, which is what lets a member back out of the player and still be pulled back in when the group starts the next episode. `:app` also owns `Routes.SyncPlay` and the Groups action on `AppTopBar` (DECISIONS.md 2026-07-30). |

**The one edge that changed outside SyncPlay** is the reporter's rule: a `LocalPlaybackMediaSource`
now reports to the server when the device is online **and** in a group, keyed on a play session id
minted by one profile-less `PlaybackInfo` POST (`SyncPlayLocalSession` +
`PlaybackInfoResolver.mintPlaySessionId`). `stopTranscoding` stays remote-only. Everything else about
M8's local-first reporting — the unconditional local position write above all — is untouched.
See DECISIONS.md, 2026-07-30.
<!-- END: SyncPlay (M11) -->

<!-- BEGIN: Offline multi-track Phase 2 (post-M10) -->
## Offline multi-track Phase 2 (post-M10)

Full detail: [`docs/features/download-quality.md`](features/download-quality.md), *"Every other audio
language, as its own file"*, and [`docs/features/offline-playback.md`](features/offline-playback.md),
*"Merged playback and the child-order contract"*. Design study:
[`docs/notes/offline-multitrack-design.md`](notes/offline-multitrack-design.md).

**The rule the architecture enforces:** a transcode still bakes in exactly one audio track (the
server API's own ceiling), but every other language of the source is fetched and stored as its own
file and merged back with the primary one at playback — offline stops meaning "down to one language".

### Module additions

| module | added |
|---|---|
| `:core:common` | `DownloadFileType.AUDIO` — no schema change, an unrecognised stored name already degraded safely. |
| `:data:downloads` | `engine/AudioSidecarExtractor` + `TransformerAudioSidecarExtractor` (a new engine stage, bound in `DownloadsModule`), a new `androidx.media3:media3-transformer` dependency (transmuxing only — no codec, no decoder); `DownloadUrlFactory.audioStreamUrl`; `DownloadFilePlanner.audioSidecars`; `DownloadQueue.downloadOne`/`strip` grown a second file shape (fetch to `.part.mkv`, strip to the row's `.m4a`); `offline/DownloadedMedia.audio` / `DownloadedAudio`. |
| `:player` | `LocalPlaybackResolver` offers one track per on-disk sidecar after the baked one; `PlaybackMediaItemSpec.audioSidecars`, `AudioSidecarSpec`; `ExoPlayerHandle.prepare`/`toMergedSource` builds a `MergingMediaSource` when the spec has any; `TrackSelectionController.selectAudio` maps a merge child back to a Jellyfin stream index; `jellyfinIndexOfTrackId`'s prefix strip widened from one merge level to a run of them. |

### Why `MediaSource` assembly moved into `ExoPlayerHandle`

Every other side-loaded thing in this app — subtitles — rides along on the `MediaItem` itself, as a
`SubtitleConfiguration`, which is why `ExoMediaSourceFactory`'s decision table could stay pure and
testable on the JVM (`docs/features/offline-playback.md`, M8). `MediaItem` has no audio analogue: an
extra audio track can only be added by wrapping a second `MediaSource` around the first one in a
`MergingMediaSource`, and that type only exists where a `MediaSourceFactory` is available to build the
children from. So `ExoPlayerHandle.prepare` is the one place in `:player` that assembles a
`MediaSource` by hand instead of handing a `PlaybackMediaItemSpec` to the factory — a real, acknowledged
dent in "URL selection is pure and testable", scoped to exactly this one call (DECISIONS.md,
2026-07-31). Everything decidable — which sidecars exist, and in what order — stays in the pure spec;
the handle performs only mechanical assembly.

### Why the fetch goes through `/Videos`, not `/Audio`

The design study's one unverified assumption was that `/Audio/{id}/stream.mka?audioStreamIndex=N`
would honour the index. It does not, on server 10.11: `EncodingHelper.AttachMediaSourceInfo`
hard-codes the parameter to `null` for any non-video request. `/Videos/{id}/stream.mkv` does honour
it, so `:data:downloads` fetches an extra language through the video endpoint with a cheap junk video
track (h264, 50 kbps, 4 fps, 144p) and a new engine stage, `AudioSidecarExtractor`, strips that video
off locally — a Media3 `Transformer` transmux, no re-encode — once the whole file is on disk. The
sidecar this produces is `.m4a`, not the `.mka` the design study picked: a `Transformer` that already
holds every byte before it starts writes a complete `moov` up front, which is exactly the property
`DownloadQuality.CONTAINER`'s own KDoc explains a *server's* live encode cannot have (see
`docs/features/download-quality.md`, *"Why the container is mkv and not mp4"*) — so the sidecar needs
no seek-index repair at all, unlike the media file it rides alongside.
<!-- END: Offline multi-track Phase 2 (post-M10) -->

<!-- BEGIN: Chromecast (M12) -->
## Chromecast (M12)

Full detail: [`docs/features/chromecast.md`](features/chromecast.md).

**No new module, and one new dependency direction.** Casting is a package inside `:player` for the
same reason SyncPlay is: it needs the resolvers, the reporter and `PlayerHandle`, all of which live
there. What is new is that `:player` is the **only** module that may name a
`com.google.android.gms` type, and inside it only `cast/` may — `:app` calls
`CastAvailability.initialize(this)` from `MainActivity.onCreate` and knows nothing else about it.

```
:player/cast
 ├── JellyboostCastOptionsProvider   framework config; instantiated reflectively from the manifest
 ├── CastAvailability                the one CastContext, the GoogleApiAvailability guard,
 │                                   CastDeviceState for the UI
 ├── CastSessionMonitor + GmsCastSessionMonitor   "a receiver appeared / went away", GMS-free seam
 ├── CastSessionCoordinator          routing, the detached ticker, the final stop report
 ├── CastStatusHolder                isCasting + device name, for everyone outside cast/
 ├── CastMetadataHolder              title / episode line / poster, for the receiver's own screen
 ├── CastPlaybackHost                the attach/detach + transfer seam a screen implements
 ├── CastPlayerHandle                PlayerHandle over media3-cast's CastPlayer
 └── CastSpecMapper → CastMediaSpec → CastMediaItemConverter   decide in pure data, assemble on device
```

**The seam that made it cheap.** `androidx.media3.cast.CastPlayer` is an
`androidx.media3.common.Player`, so a receiver fits behind the `PlayerHandle` interface the player was
already written against — including the contract that a track selection returning `false` sends the
caller back to the server, which is exactly how an audio switch and a burned-in subtitle have to
behave while casting. `RoutingPlayerHandle` (in `session/`) becomes the binding and everything above
it is unchanged.

**Three cross-cutting mechanisms this introduces.**

| mechanism | where | why it is shaped that way |
|---|---|---|
| `RoutingPlayerHandle` | `:player/session` | One binding, one seam, a pointer underneath. `PlayerViewModel` never learns which player it is driving; `PlaybackService` keeps the concrete local one so the *local* notification disappears while the framework publishes its own. The cast handle arrives as a `Provider` so a GMS-less device never constructs one. |
| `CastStatusHolder` / `CastMetadataHolder` | `:player/cast` | The two facts that cross the boundary in each direction — "are we casting" (read by every resolve) and "what should the television say this is" (written by the ViewModel's item fetch). Both are Cast-free by construction, which is what lets a ViewModel test build one on a machine with no Cast stack. Modelled on `SyncPlayStatusHolder`. |
| `CastSessionCoordinator` on `@DetachedPlayerScope` | `:player/cast` | A cast session outlives every screen, so a `@Singleton` started from `JellyboostApplication` owns it — and reports to the server **only while no screen is attached**, which is what keeps it to exactly one stop report per source. |

**The edges that changed outside `cast/`.** `PlaybackResolveRequest.castTarget` joins `forceRemote` in
skipping the copy on disk (a `file://` URI is unreachable from a receiver); `PlaybackInfoResolver`
sends `CastDeviceProfile` instead of the probed one; `StreamUrlFactory.withApiKey` puts the token in
the URL for a fetcher that is not this app; `PlayerHandle` gained a `prepare` overload carrying the
resolved source and a `supportsPlaybackSpeed` property, both defaulted so no existing implementation
or test double changed. `:player` also gained `androidx.appcompat` and `androidx.mediarouter` — the
cast button is an AppCompat view, which is why `MainActivity` is a `FragmentActivity` and the app
theme is AppCompat-derived (DECISIONS.md 2026-07-31).
<!-- END: Chromecast (M12) -->

<!-- BEGIN: Error copy (audit H8) -->
## Error copy

Full detail on the translation machinery: [`docs/features/localization.md`](features/localization.md).

One mapping from the `AppError` taxonomy to the sentence a user reads, in `:core:ui`. Before this
there were five — `LibraryErrorMessage`, `SearchErrorMessage`, and one apiece inside
`HomeViewModel`, `ItemDetailViewModel` and `PlayerViewModel` — and the last three returned English
Kotlin literals, which `MissingTranslation` cannot see. Home, detail and playback therefore showed
untranslated error copy on all 68 non-English locales while the i18n gate reported green
(audit H8 = DUP-1 = CPX-13; the debt was logged as "M9 polish" in DECISIONS.md, 2026-07-28).

| piece | where | role |
|---|---|---|
| `UiText` | `:core:ui` `text/UiText.kt` | `Res(id, args)` — a resource id a `ViewModel` can decide on and a screen resolves at draw time; `Raw(value)` for wording that came from outside the app (an ExoPlayer or Cast error) and has no resource. |
| `AppErrorCopy` | `:core:ui` `error/AppErrorCopy.kt` | The three branches a screen may override: `unknown` (required), `notFound`, `server`/`serverWithCode`. |
| `AppError.toUiText(copy)` | same file | The mapping. Network, unauthorized, storage and the item wording of not-found are `:core:ui` resources no screen can override. |

```
:core:common   AppError (the taxonomy — no copy, no Android)
      ▲
:core:ui       toUiText(AppErrorCopy) ──► UiText ──► UiText.resolve()  @Composable
      ▲                  ▲                                    ▲
      │   HomeErrorCopy ─┤ DetailErrorCopy   PlayerErrorCopy   │ screens resolve at draw time
      └── LibraryErrorCopy   SearchErrorCopy                   │
```

**What counts as an override.** Only copy that genuinely differs, and each difference is load-bearing:

- **`unknown` is always the screen's own** — an unclassified failure can only be described by naming
  what was being done ("loading this library", "starting playback"), and only the screen knows that.
- **not-found has two wordings**, item and library, because home and the libraries tab asked about a
  library and a 404 there is not a missing title. Both are `:core:ui` resources
  (`error_not_found_item` / `error_not_found_library`), so the two screens that need the library
  wording share one translation rather than keeping two.
- **the player overrides both server branches.** A server that answered and refused to *open a
  stream* is a different failure from a browse request coming back with an error, with a different
  remedy (try another quality, not pull-to-refresh).

Everything else — "can't reach your server", "your session expired", "couldn't read local data" —
is one sentence, translated once. `AppError.ServerResolution` deliberately shares the network copy:
to a user, an address that answers nothing usable and a server that is unreachable are the same dead
end with the same first thing to try.

**The one place that still holds English in Kotlin** is `DownloadErrorCopy` (`:data:downloads`), and
for a reason that does not apply here: its message is written to Room at failure time and read back
days later, so it could not be re-resolved against the device's current locale anyway. Moving it
needs the row to store a key rather than a sentence — a schema migration, not a copy change.
<!-- END: Error copy (audit H8) -->
