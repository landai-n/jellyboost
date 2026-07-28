# DECISIONS.md — divergence & decision log

Governance rule (see `docs/PLAN.md`): every non-trivial implementation decision is checked
against the plan. Any divergence is recorded here **before or with** the diverging change.

## Entry template

```
## YYYY-MM-DD — <short title>
- **Scope:** <files/feature affected>
- **Plan said:** <what docs/PLAN.md specifies>
- **Done instead:** <what was actually done>
- **Reason:** <why>
```

---

## 2026-07-28 — compileSdk 37 (targetSdk stays 36)
- **Scope:** `gradle/libs.versions.toml`, `build-logic/convention` (all Android modules)
- **Plan said:** "minSdk 26, compile/targetSdk 36".
- **Done instead:** `compileSdk = 37` (SDK platform `android-37.1` installed via `sdkmanager`); `targetSdk` and `minSdk` unchanged at 36 / 26.
- **Reason:** The current AndroidX stack pinned for M0 (Compose BOM 2026.06.01, `androidx.core` ≥ 1.18, `lifecycle` ≥ 2.11, `androidx.hilt` 1.4.0) publishes AAR metadata requiring `compileSdk ≥ 37`; building against 36 fails `checkDebugAarMetadata`. Holding compileSdk at 36 would have meant pinning Compose/lifecycle/core roughly a year back on day one. `compileSdk` only selects the compile-time `android.jar`, it does not change runtime behaviour — the behavioural contract (`targetSdk 36`) and device reach (`minSdk 26`) are exactly as planned.

## 2026-07-28 — androidx.hilt 1.4.0 and androidx.lifecycle 2.11.0
- **Scope:** `gradle/libs.versions.toml`
- **Plan said:** M0 brief pinned `androidx.hilt 1.3.0` ("verify latest 1.x on Google Maven") and `lifecycle 2.9.4`.
- **Done instead:** `androidx.hilt 1.4.0` and `androidx.lifecycle 2.11.0`.
- **Reason:** 1.4.0 is the current stable `androidx.hilt` on Google Maven (checked 2026-07-28). Lifecycle: Compose BOM 2026.06.01 constrains `lifecycle-runtime-compose` / `lifecycle-viewmodel-compose` to 2.11.0, which drags the whole lifecycle group up; the catalog now declares what actually resolves instead of leaving a silently-overridden 2.9.4. All other pinned versions (Media3 1.9.0 + ffmpeg-decoder 1.9.0+1, jellyfin-sdk 1.8.12, Hilt 2.60.1, Room 2.8.4, Coil 3.4.0, coroutines 1.11.0, serialization 1.11.0, work 2.10.5, activity 1.11.0, Timber 5.0.1, desugar 2.1.5) resolve exactly as pinned.

## 2026-07-28 — AGP 9 built-in Kotlin: no `org.jetbrains.kotlin.android` plugin
- **Scope:** `build-logic/convention` (`AndroidApplicationConventionPlugin`, `AndroidLibraryConventionPlugin`)
- **Plan said:** Standard Kotlin Android module setup (implied `kotlin-android` plugin per module).
- **Done instead:** Android modules apply only `com.android.application` / `com.android.library`; Kotlin support comes from AGP 9's built-in Kotlin. AGP 9.3.1 hard-fails if `org.jetbrains.kotlin.android` is also applied. Pure-JVM `:core:common` still uses `org.jetbrains.kotlin.jvm`.
- **Reason:** Forced by AGP 9. Same arrangement the reference app (jellyfin-android) uses on AGP 9.3.1.

## 2026-07-28 — Media3 pinned to 1.9.0 (ffmpeg-decoder pairing)
- **Scope:** `gradle/libs.versions.toml`, `:player`
- **Plan said:** Media3 1.10.1 **iff** `org.jellyfin.media3:media3-ffmpeg-decoder` has a matching build, otherwise pin down to the newest version with a matching decoder.
- **Done instead:** Media3 `1.9.0` + `media3-ffmpeg-decoder 1.9.0+1` (latest published decoder; verified on Maven Central 2026-07-28; 1.9.4 exists for Media3 but has no decoder build). Not pairing decoder 1.9.0+1 with Media3 1.9.4 because the decoder links against Media3-internal APIs that are not guaranteed patch-stable.
- **Reason:** This is the plan's own prescribed fallback; recorded here because the concrete version differs from the number written in the plan.

## 2026-07-28 — M2: `HomeViewModel` is not `@HiltViewModel` yet
- **Scope:** `feature/home/.../HomeViewModel.kt`
- **Plan said:** M2 delivers the Home screen + ViewModel; ViewModels are Hilt-injected throughout.
- **Done instead:** `HomeViewModel` has an `@Inject` constructor but deliberately *no* `@HiltViewModel` annotation. `HomeScreen` takes the ViewModel as a parameter instead of calling `hiltViewModel()`.
- **Reason:** M2 was built on a worktree branch in parallel with M1. `@HiltViewModel` makes the ViewModel reachable from the `:app` Hilt component, which then requires the `org.jellyfin.sdk.api.client.ApiClient` binding that lives in `:core:network` — M1's scope, absent on this branch. Verified empirically: adding the annotation fails `assembleDebug` with `[Dagger/MissingBinding] org.jellyfin.sdk.api.client.ApiClient cannot be provided`. Providing a second `ApiClient` binding from `:data` would collide with M1's at integration, so the annotation is deferred. **Integration step:** once `:core:network` provides `ApiClient`, add `@HiltViewModel` to `HomeViewModel` and wire `HomeScreen(viewModel = hiltViewModel(), …)` into the `:app` NavHost. The `@Binds JellyfinRepository → OnlineJellyfinRepository` module in `:data` is already in place and is inert until something requests it.

## 2026-07-28 — M2: `testDebugUnitTest` alias in `:core:common`
- **Scope:** `core/common/build.gradle.kts`
- **Plan said:** `/verify` runs `./gradlew ktlintCheck detekt testDebugUnitTest assembleDebug`; unit tests accompany every repository/ViewModel/mapper.
- **Done instead:** `:core:common` registers a `testDebugUnitTest` task that simply depends on `test`.
- **Reason:** `testDebugUnitTest` is an Android-variant task. `:core:common` is a pure-JVM module whose test task is `test`, so its tests were silently excluded from the quality gate — a test that never runs is worse than no test. The alias makes the existing gate command cover the module without changing the command or the convention plugins.

## 2026-07-28 — M2: home-scope repository only, no Room write-through
- **Scope:** `data/.../JellyfinRepository.kt`, `OnlineJellyfinRepository.kt`
- **Plan said:** `OnlineJellyfinRepository` — SDK calls, write-through to Room (`source=BROWSE_CACHE`).
- **Done instead:** M2 implements the four home-screen calls (`getUserViews`, `getResumeItems`, `getNextUp`, `getLatestMedia`) as pure network reads, with the missing write-through documented in the class KDoc.
- **Reason:** The Room browse cache, `OfflineJellyfinRepository` and `DelegatingJellyfinRepository` are M6 deliverables; adding a half-built cache in M2 would have to be rewritten there. No behaviour is lost — M2 is the online-only milestone.

## 2026-07-28 — Pre-approved design choices (marked [D] in the plan)
Seeded from the approved plan; listed for traceability, no divergence:
- minSdk 26 (not 21).
- Single `ItemEntity` table with structured columns + `BaseItemDto` JSON blob (not Findroid's 4 typed tables).
- `DownloadEntity` primary key = itemId (one download per item).
- Download pipeline: OkHttp + WorkManager + Room (not system `DownloadManager`).
- Default download storage: app-private `getExternalFilesDir(null)/downloads`; SAF/SD optional; storage-location change only when no downloads exist (MoveStorageWorker deferred).
- Hardware-probed DeviceProfile (jellyfin-android style), NOT Findroid's permissive "direct play all" profile; external-player and web-codec code paths dropped.
- Offline browse scope: downloaded items only (cached parents of downloaded items still open).
- User-data sync conflict: most-recent-wins (compare `lastPlayedDate`/`updatedAt`).
- Navigation: bottom nav bar (Home / Libraries / Search / Downloads), Settings behind avatar.
