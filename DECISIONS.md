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

## 2026-07-28 — no `getCurrentUser` round-trip after authentication
- **Scope:** `:core:network` (`AuthRepository.loginWithPassword` / `loginWithQuickConnect`)
- **Plan said:** Login screen: "password: `authenticateUserByName`; Quick Connect: … `authenticateWithQuickConnect`; **then `getCurrentUser`**".
- **Done instead:** The user is taken straight from `AuthenticationResult.user` (a full `UserDto`, including `policy`); `getCurrentUser` is not called.
- **Reason:** In SDK 1.8.12 `AuthenticationResult` already carries the complete `UserDto` — id, name, `primaryImageTag` and `policy.enableContentDownloading`, which is the one field M1's DoD needs (risk #4). A second call would return the same object at the cost of a round-trip on the slowest screen in the app (first contact with a possibly-remote server). `getCurrentUser` stays available for later refreshes (e.g. re-checking policy at session restore).

## 2026-07-28 — sign-out keeps server/user rows in Room
- **Scope:** `:core:network` (`SessionRepository.signOut`)
- **Plan said:** Settings: "sign out (clears SecureCredentialStore, optional delete downloads)".
- **Done instead:** Sign-out reports the session ended to the server (best effort), clears `SecureCredentialStore`, drops the token from the `ApiClient` and sets `SessionState.LoggedOut`. `ServerEntity` / `ServerAddressEntity` / `UserEntity` rows are left in place.
- **Reason:** The plan only mandates clearing the credential store, and those rows hold no secrets (`UserEntity` has no token column by design). Keeping them means re-signing-in on the same server skips discovery entirely, and keeps any `DOWNLOAD`-sourced item rows' foreign keys intact for the "optional delete downloads" path. Session restore treats a stored token whose rows are missing as inconsistent and discards the token, so the reverse case is still safe.

## 2026-07-28 — the resolved server travels between auth screens in a holder, not in the route
- **Scope:** `:feature:auth` (`PendingServerStore`, `ServerSetupViewModel`, `LoginViewModel`), `:core:common` (`Routes.Login` left argument-free)
- **Plan said:** "Screens (type-safe Navigation Compose routes in `:core:common`)" — the implied mechanism for passing data between destinations is a route argument.
- **Done instead:** `Routes.Login` stays a parameterless `@Serializable data object`. `ServerSetupViewModel` writes the successfully resolved `ResolvedServer` into an auth-feature-internal `@Singleton PendingServerStore`, and `LoginViewModel` reads it on init (navigating back to ServerSetup when it is empty).
- **Reason:** `ResolvedServer` is a `:core:network` model carrying a `java.util.UUID`; making it a route argument would either force a `kotlinx.serialization` dependency and a `NavType` onto a network model, or push four loose primitives (`serverId`, `name`, `version`, `address`) into `:core:common` — leaking transport details into the shared routes module and putting a full server URL into the back-stack `Bundle`/saved state. The holder keeps `Routes` clean, keeps the two auth screens the only code that knows about `ResolvedServer`, and is cleared on successful sign-in. The trade-off (state not restored across process death mid-login) is acceptable: an un-restored login simply bounces back to ServerSetup, which is the same thing a re-probe would do.

## 2026-07-28 — temporary Home placeholder with sign-out lives in `:app`
- **Scope:** `:app` (`HomePlaceholderScreen`)
- **Plan said:** Home is a `:feature:home` screen (M2); sign-out belongs to the Settings screen (M9).
- **Done instead:** `:app` hosts a `HomePlaceholderScreen` composable showing the signed-in user/server/version plus a temporary "Sign out" button wired to `SessionRepository.signOut()`.
- **Reason:** M1's definition of done requires session restore and sign-out to be exercisable on device, but neither `:feature:home` nor `:feature:settings` exists yet. Putting the placeholder in `:app` (rather than pre-empting `:feature:home`'s design) keeps the throwaway code in the module that will keep the NavHost anyway; it is deleted when `:feature:home` lands in M2 and sign-out moves to Settings in M9. KDoc on the composable records that.

## 2026-07-28 — M1 auth screens styled with plain Material 3, `:core:ui` frozen
- **Scope:** `:feature:auth` (ServerSetup/Login screens), `:core:ui` (untouched)
- **Plan said:** `:core:ui` provides the design system — "Theme (`#101010` bg, `#202020` surface, `#00A4DC` primary, `#AA5CC3→#00A4DC` gradient)" plus shared components — and M2 is "Design system + Home (online)"; feature screens build on it.
- **Done instead:** The M1 auth screens are self-contained inside `:feature:auth` using plain Material 3 defaults (dark colors hardcoded locally where needed); nothing in `:core:ui` is created or modified. Existing read-only usage of `JellyfinTheme` from M0 stays.
- **Reason:** User directive (2026-07-28): the design system is being built on a parallel M2 branch, and touching `:core:ui` from M1 would create merge conflicts. The auth screens will be restyled onto the design system at M2 integration.

## 2026-07-28 — SLF4J binding added for the jellyfin SDK, and cleartext/user-CA network policy
- **Scope:** `gradle/libs.versions.toml`, `core/network/build.gradle.kts`, `build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/res/xml/network_security_config.xml`
- **Plan said:** Neither point is mentioned; the plan lists `:core:network` dependencies and assumes discovery/login simply work.
- **Done instead:** (a) `runtimeOnly("uk.uuid.slf4j:slf4j-android:2.0.17-0")` on `:core:network`; (b) `:app` declares `android:networkSecurityConfig="@xml/network_security_config"` with `cleartextTrafficPermitted="true"` and `system` + `user` trust anchors, mirroring jellyfin-android's own file.
- **Reason:** Both were found by running M1's screens on the test tablet, and both silently broke the milestone's DoD:
  - jellyfin-sdk 1.8.12 logs through `io.github.oshai:kotlin-logging` 7.x, whose Android variant delegates to SLF4J. With no binding on the runtime classpath, `LocalServerDiscoveryKt.<clinit>` threw `NoClassDefFoundError: org.slf4j.LoggerFactory`, so UDP discovery produced nothing at all. `uk.uuid.slf4j:slf4j-android` is the maintained SLF4J-2.x Android binding and routes SDK logs to Logcat; it is `runtimeOnly`, so no SLF4J API leaks into our source.
  - `targetSdk 36` forbids cleartext by default, so probing the discovered `http://192.168.1.10:8096` failed with `UnknownServiceException: CLEARTEXT communication ... not permitted`. Self-hosted Jellyfin servers are overwhelmingly plain HTTP on a LAN (or HTTPS behind a user-installed CA), which is exactly why jellyfin-android ships the same configuration.
- **Follow-up:** the SLF4J binding calls `android.util.Log`, which is a throwing stub in local unit tests, and MockK's own SLF4J logger goes down with it. The root `build.gradle.kts` therefore excludes `uk.uuid.slf4j:slf4j-android` from every `*UnitTestRuntimeClasspath`; SLF4J then falls back to its no-op provider there, which is what unit tests want anyway.
