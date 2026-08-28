# Jellyboost

**Jellyboost** is a 100% native Android client for [Jellyfin](https://jellyfin.org) — no
WebView anywhere. Every screen is Jetpack Compose and Material 3, carrying Jellyboost's own dark
design language, built on a multi-module architecture (Hilt, Room, Media3/ExoPlayer with the
Jellyfin ffmpeg decoder, jellyfin-sdk-kotlin, WorkManager, OkHttp). Current scope: movies, TV
shows, and music.

What sets Jellyboost apart is that **your library is one library, online or offline**. Downloaded
movies, episodes and albums live in the same Home, Library, Search and Detail screens as streamed
content — marked with a badge, played from disk automatically, and kept honest by
most-recent-wins watch-state sync when the server comes back. There is no separate "downloads
app" hiding inside the app.

The approved architecture and milestone plan lives in [`docs/PLAN.md`](docs/PLAN.md); every
divergence from it is recorded in [`DECISIONS.md`](DECISIONS.md), and the current state of work is
in [`STATUS.md`](STATUS.md).

## Highlights

- **Everything plays.** A full playback ladder — direct play, direct stream, then server
  transcode — backed by a real device capability profile, the Jellyfin ffmpeg decoder, and
  automatic decoder-failure fallback. If your server can serve it, Jellyboost plays it, and the
  player tells you which method it's using.
- **Downloads with a quality selector.** Pick the quality for a download — the original file or a
  smaller server-side re-encode — right from the download action, so a season that would not fit
  on the device at full quality still can. The queue has pause, resume, reorder, per-item progress
  with speed and ETA, and storage management down to the volume.
- **Offline that behaves like online.** Resume positions, next-up, search, trickplay scrubbing,
  subtitles and multiple audio languages all work from disk exactly as they do streaming — and
  your watch state syncs back with most-recent-wins conflict resolution, so neither device nor
  server clobbers the other.
- **SyncPlay (watch-together).** Join or create Jellyfin SyncPlay groups for synchronized playback
  with other Jellyfin clients: play, pause, seek and the queue itself are the group's, coordinated
  by the server, with drift correction keeping every member in lockstep. And uniquely: **an
  episode you've already downloaded plays from local storage while staying in the group** —
  zero streaming bandwidth, full membership.
- **Chromecast.** Cast with the controls that matter: play/pause/seek, audio and subtitle
  selection, the quality picker, resume, local↔cast handoff in both directions, and progress
  reporting, all orchestrated from the phone.
- **Music, properly.** Artists, albums and playlists; background playback with lock-screen
  controls; a queue with shuffle and repeat; Instant Mix; synced lyrics; and album downloads that
  play offline like everything else.
- **A player with the details right.** Skip-intro/credits with per-type auto-skip, an Up Next
  card, trickplay seek thumbnails (online *and* offline), Picture-in-Picture, brightness/volume
  gestures, playback speed, and next-episode auto-advance.
- **Built to be dependable.** ~2,900 unit tests densest on the download and sync pipeline, an
  instrumented TalkBack accessibility suite gating CI, 69 languages, baseline profiles compiled
  into the release build, and a public decision log for every architectural divergence.

## Building

Requirements: JDK 21 and the Android SDK (compileSdk 37.1, minSdk 26).

```bash
# Sets JAVA_HOME (openjdk@21) and ANDROID_HOME
source ../env.sh

./gradlew assembleDebug          # build the debug APK
./gradlew installDebug           # install onto a connected device/emulator
./gradlew ktlintCheck detekt     # formatting + static analysis
./gradlew testDebugUnitTest      # unit tests (JUnit 5)
```

This repository builds with plain `./gradlew` as above. Project tooling instead invokes
`gradlew-remote`, a thin local wrapper (kept outside the repo) that adds the environment
setup and optional delegation of the build to another machine — outside contributors
don't need it.

The debug build uses the application id `dev.jellyboost.app.debug`, so it can be installed
alongside a release build.

## Module layout

| Module | Responsibility |
|---|---|
| `:app` | Application, MainActivity, NavHost, app scaffold |
| `:core:common` | Pure Kotlin domain models, `AppResult`/`AppError`, type-safe nav routes |
| `:core:ui` | Theme and the shared Compose design system |
| `:core:network` | Jellyfin SDK wiring, OkHttp, connectivity/reachability |
| `:core:database` | Room database, entities, DAOs |
| `:core:datastore` | Preferences (DataStore) and the encrypted credential store |
| `:data` | Jellyfin repositories (online/offline/delegating), mappers, user-data sync |
| `:data:downloads` | Download queue, worker, file downloader, storage |
| `:player` | Device profile, playback resolution, ExoPlayer, playback service |
| `:feature:*` | `auth`, `home`, `library`, `detail`, `search`, `downloads`, `music`, `settings` screens |

Build conventions are shared through the `build-logic` included build
(`jellyboost.android.application`, `.android.library`, `.android.library.compose`,
`.android.feature`, `.android.hilt`, `.android.room`, `.kotlin.library`).

## Development

Jellyboost is being built with AI-assisted development. Contributions are welcome — issues and
pull requests are appreciated.
