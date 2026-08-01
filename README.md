# Jellyboost

**Jellyboost** is a 100% native Android client for [Jellyfin](https://jellyfin.org) — no
WebView anywhere. It is built with Jetpack Compose and Material 3 on a Findroid-style multi-module
architecture (Hilt, Room, Media3/ExoPlayer with the Jellyfin ffmpeg decoder, jellyfin-sdk-kotlin,
WorkManager, OkHttp), and reproduces the look and information architecture of jellyfin-web's dark
theme. Its differentiator is **seamless online/offline integration in a single UI**: downloaded
movies and episodes appear and play inside the same Home, Library, Detail and Search screens as
streamed content, with in-app download queue and progress tracking, download management (sizes,
storage location, delete) and most-recent-wins user-data sync when the server comes back. v1 scope
is movies and TV shows.

The approved architecture and milestone plan lives in [`docs/PLAN.md`](docs/PLAN.md); every
divergence from it is recorded in [`DECISIONS.md`](DECISIONS.md), and the current state of work is
in [`STATUS.md`](STATUS.md).

## Highlights

- **Downloads with a quality selector** — pick the quality/bitrate for a download (the original
  file, or a smaller server-side re-encode) right from the download action, so a season that would
  not fit on the device at full quality still can.
- **SyncPlay (watch-together)** — join or create Jellyfin SyncPlay groups for synchronized playback
  with other Jellyfin clients: play, pause, seek and the queue itself are the group's, coordinated by
  the server, with every member's player following along.
- **SyncPlay works with downloaded media** — a movie or episode already downloaded for offline use
  can play from local storage while still taking part in a SyncPlay session, staying in lockstep with
  everyone else and reporting progress to the server like any other member.
- **Chromecast support** — cast to the default Google Cast receiver with the controls that matter:
  play/pause/seek, audio and subtitle selection, the quality picker, resume, and progress reporting,
  all orchestrated from the phone.

## Building

Requirements: JDK 21 and the Android SDK (compileSdk 36, minSdk 26).

```bash
# Sets JAVA_HOME (openjdk@21) and ANDROID_HOME
source ../env.sh

./gradlew assembleDebug          # build the debug APK
./gradlew installDebug           # install onto a connected device/emulator
./gradlew ktlintCheck detekt     # formatting + static analysis
./gradlew testDebugUnitTest      # unit tests (JUnit 5)
```

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
| `:feature:*` | `auth`, `home`, `library`, `detail`, `search`, `downloads`, `settings` screens |

Build conventions are shared through the `build-logic` included build
(`jellyboost.android.application`, `.android.library`, `.android.library.compose`,
`.android.feature`, `.android.hilt`, `.android.room`, `.kotlin.library`).

## Development

Jellyboost is being built with AI-assisted development. Contributions are welcome — issues and
pull requests are appreciated.
