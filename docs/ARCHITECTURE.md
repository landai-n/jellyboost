# Architecture

Per-area notes on how the modules in `docs/PLAN.md`'s skeleton are actually wired. Each milestone
appends its own clearly delimited section; nothing above a section's marker is rewritten, so
parallel branches merge without conflicts.

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
