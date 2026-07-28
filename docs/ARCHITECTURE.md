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
