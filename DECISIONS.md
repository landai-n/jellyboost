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

## 2026-07-28 — Home row limits and filters follow jellyfin-web, not the plan's row sizes
- **Scope:** `:data` (`JellyfinRepository`, `OnlineJellyfinRepository`), home rows
- **Plan said:** Home uses "`getResumeItems(limit=20)`, `getNextUp(limit=20)`" (Screens table).
- **Done instead:** Resume limit 12 and Next Up limit 24, plus request filters jellyfin-web sends and the plan does not mention: `mediaTypes=Video` on resume, and `enableResumable=false` + `nextUpDateCutoff=now-365d` (web's default "Days in Next Up") on next-up.
- **Reason:** The M2 DoD is "side-by-side vs jellyfin-web home — same rows/items/order", verified on device against the same account. With the plan's parameters the app showed 8 extra Continue Watching items (web caps at 12), and Next Up contained in-progress episodes (Malcolm S1:E2, Emily in Paris S5:E1) plus stale series (Key & Peele, Squid Game) that web filters out. Matching web's actual request parameters is the only way to satisfy the DoD by construction; the DoD outranks the plan's illustrative row sizes.

## 2026-07-28 — filter facets come from `getQueryFiltersLegacy`, not `getQueryFilters`
- **Scope:** `:data` (`JellyfinRepository.getFilterFacets`, `OnlineJellyfinRepository`), `:core:common` (`FilterFacets`), `:feature:library` filter sheet
- **Plan said:** LibraryGrid: "filter sheet `getQueryFilters`" (Screens table).
- **Done instead:** `filterApi.getQueryFiltersLegacy(parentId, includeItemTypes)`, mapped onto a new `FilterFacets(genres, years, officialRatings)` domain model.
- **Reason:** In jellyfin-sdk 1.8.12 the modern `getQueryFilters` returns `QueryFilters(genres: List<NameGuidPair>, tags: List<String>)` — no years and no official ratings. The filter sheet the plan asks for is genres **and** years, and only `QueryFiltersLegacy(genres, tags, officialRatings, years)` carries them, which is why jellyfin-web itself still calls the legacy endpoint for its year filter. One request instead of two, and the domain model hides which endpoint it came from, so moving to a future combined endpoint touches one function.

## 2026-07-28 — the library grid gets its own route rather than reusing `Routes.Library`
- **Scope:** `:core:common` (`Routes`), `:feature:library`
- **Plan said:** "Screens (type-safe Navigation Compose routes in `:core:common`)"; the routes file has carried an unused `Routes.Library(libraryId)` since M0.
- **Done instead:** Appended `Routes.LibraryGrid(libraryId: String, libraryName: String)` in an `// M3 — library & search` section at the end of `Routes`; `Routes.Library` is left untouched and still unused. Search reuses the existing top-level `Routes.Search`.
- **Reason:** Two constraints. (a) The grid's top bar must render the library name before the first page arrives; re-fetching `getUserViews` to resolve one string would put a network round trip in front of every library open, and the name is already on screen in the row the user tapped. (b) M3 and M4 are being built in parallel worktrees against the same `Routes.kt`, so this milestone's edits there are strictly append-only — rewriting `Routes.Library`'s signature in place would conflict with M4. The orchestrator can collapse `Library` into `LibraryGrid` at integration if it prefers; nothing depends on `Routes.Library`.

## 2026-07-28 — library grid asks for MOVIE and SERIES regardless of the library's collection kind
- **Scope:** `:feature:library` (`LibraryUiState.GRID_ITEM_TYPES`), `Routes.LibraryGrid`
- **Plan said:** LibraryGrid: "`getItems(parentId, includeItemTypes, recursive, …)`" — which item types is left open.
- **Done instead:** Every library is paged with `includeItemTypes = [MOVIE, SERIES]`, and the route therefore does not carry the library's `CollectionKind`.
- **Reason:** A movie library answers such a request with movies and a TV library with series, so one type list serves both and produces exactly the top-level titles jellyfin-web's library view shows (no seasons or episodes leaking in through `recursive = true`). The alternative — passing `CollectionKind` through the route — would make a `:core:common` enum part of the navigation surface for no behavioural gain.
## 2026-07-28 — M4: dedicated mark-played / favourite endpoints, `updateItemUserData` only for positions
- **Scope:** `:data` (`userdata/UserDataRepositoryImpl`)
- **Plan said:** "if online push `itemsApi.updateItemUserData(UpdateUserItemDataDto(...))`, clear flag on success" (Data layer → `UserDataRepositoryImpl`).
- **Done instead:** `setPlayed` pushes via `playStateApi.markPlayedItem` / `markUnplayedItem`, `setFavorite` via `userLibraryApi.markFavoriteItem` / `unmarkFavoriteItem`; only `setPosition` uses `itemsApi.updateItemUserData`, and it sends the item's **full** desired state (position, played, favourite, lastPlayedDate) rather than a position-only DTO.
- **Reason:** `POST /UserItems/{id}/UserData` merges the DTO it is given, and the exact null-handling of that merge is a server implementation detail we would otherwise be betting the milestone's DoD on ("mark played → appears in jellyfin-web"). The four dedicated endpoints are the ones jellyfin-web itself calls for these two actions and have unambiguous semantics. `updateItemUserData` is kept for the position write — the one operation with no dedicated endpoint outside the playback-reporting triad (M5) — and made idempotent by sending the whole state. The local-first contract, the event bus, the `toBeSynced` flag and the retry are exactly as the plan specifies; only the wire call for two of three operations differs.

## 2026-07-28 — M4: `getItem` passes no `fields` list
- **Scope:** `:data` (`OnlineJellyfinRepository.getItem`)
- **Plan said:** ItemDetail uses "full `getItem` re-fetch w/ fields incl. MEDIA_SOURCES/STREAMS/CHAPTERS/TRICKPLAY".
- **Done instead:** `userLibraryApi.getItem(itemId)` is called with no field selection at all.
- **Reason:** `/Users/{userId}/Items/{itemId}` has no `fields` parameter — it is the one endpoint that always serialises the complete field set (media sources, streams, chapters, trickplay, people, taglines, genres, overview), which is why jellyfin-web uses it to open an item. The plan's intent ("detail is full, lists are lean") is satisfied by construction; there is simply nothing to pass. Recorded so the absent parameter list is not later read as an oversight.

## 2026-07-28 — M4: `Person` / `PersonKind` added to `:core:common`
- **Scope:** `:core:common` (`model/Person.kt`, `model/JellyfinItem.kt`), `:data` (`ItemMapper`)
- **Plan said:** `:core:common` holds "domain models (`JellyfinItem`, `UserData`, `ItemQuery`, `FilterOptions`, `DownloadState`)".
- **Done instead:** Adds `Person` and `PersonKind`, plus five detail-only fields on `JellyfinItem` (`taglines`, `childCount`, `premiereDate`, `studios`, `people`) and two derived properties (`runtimeMinutes`, `remainingMinutes`).
- **Reason:** The detail screen the plan specifies needs the `PEOPLE` and `TAGLINES` item fields the plan also specifies, and the hard rule is that no `BaseItemDto` crosses a repository boundary — so the credits need a domain type. Everything added is additive with defaults, so no existing call site changes. The model list in the plan is illustrative rather than exhaustive (it already omits `LibraryView`, added at M2).

## 2026-07-28 — Downloads tab deferred to M7; bottom nav bar ships with three tabs
- **Scope:** `:app` (`AppScaffold`)
- **Plan said:** "Navigation: bottom nav bar Home / Libraries / Search / Downloads; Settings behind top-bar avatar."
- **Done instead:** The M3/M4 integration pass wires `AppScaffold`'s `NavigationBar` with only Home, Libraries and Search. `Routes.Downloads` already exists in `:core:common` (seeded at M0) but is not added as a tab.
- **Reason:** `:feature:downloads` and the download pipeline it renders are M7 scope and do not exist yet; a fourth tab pointing at nothing would either crash or need a placeholder screen nobody asked for. Adding the tab is a one-line change in `AppScaffold` once M7 lands — `Routes.Downloads` is already there waiting for it.

## 2026-07-28 — M4: Play and Download buttons raise a message instead of acting
- **Scope:** `:feature:detail` (`ItemDetailViewModel`, `res/values/strings.xml`)
- **Plan said:** ItemDetail carries "Play/Resume, Download, Mark played, Favorite".
- **Done instead:** *Mark played* and *Favorite* are fully live. *Play/Resume* and *Download* are drawn and enabled, and show a snackbar ("Playback arrives in M5." / "Downloads arrive in M7.").
- **Reason:** The milestone list puts playback in M5 and the download pipeline in M7; neither exists yet. A disabled button reads as "broken" and a silent no-op is worse, so the buttons say what is actually true. The two handlers are one line each to repoint once `:player` and `:data:downloads` land.

<!-- ===== M6 — Offline read path (append-only block) ===== -->

## 2026-07-28 — M6: the `datePlayed` timezone fix also corrects the *read* path
- **Scope:** `:data` (`SdkDateTime.kt`, `userdata/UserDataRepositoryImpl`, `mapper/ItemMapper`) and their tests
- **Plan said:** Nothing about date encoding; STATUS.md's "Known issues" asked only for the write path to be fixed (`datePlayed`/`lastPlayedDate` went out as UTC wall-clock with the device's offset appended, so a 17:22 UTC event was stored two hours early).
- **Done instead:** Both directions were fixed, behind one pair of helpers (`Instant.toSdkDateTime()` / `LocalDateTime.toSdkInstant()`). `ItemMapper` no longer reads `premiereDate` / `lastPlayedDate` as UTC either.
- **Reason:** Verified against the SDK bytecode (jellyfin-sdk 1.8.12, `org.jellyfin.sdk.model.serializer.DateTimeSerializer`): `serialize` is `value.atZone(ZoneId.systemDefault()).format(ISO_OFFSET_DATE_TIME)` and `deserialize` is `ZonedDateTime.parse(text).withZoneSameInstant(systemDefault).toLocalDateTime()`. Every SDK `LocalDateTime` is therefore **local wall-clock time**, in *both* directions — the read path carried the same off-by-the-local-offset bug, just inverted. Fixing only the write half would leave M8's most-recent-wins sync comparing a now-correct local timestamp against a server timestamp that is still two hours out, which is the precise scenario the fix exists for. The tests pin a non-UTC default zone (`Europe/Paris`) so a regression cannot pass on a UTC machine.

## 2026-07-28 — M6: the offline library grid sorts by name only
- **Scope:** `:core:database` (`ItemDao.pagingDownloaded`), `:data` (`OfflineJellyfinRepository.getItems`)
- **Plan said:** LibraryGrid — "Offline: `ItemDao.pagingDownloaded` behind same Pager", with the online grid supporting `sortBy`/`sortOrder` over six sort keys.
- **Done instead:** The offline query honours `ItemQuery.sortOrder` (ascending/descending) but always sorts on `sortName`; `SortBy.DATE_CREATED`, `PREMIERE_DATE`, `COMMUNITY_RATING`, `RUNTIME` and `RANDOM` fall back to name ordering offline.
- **Reason:** SQLite cannot bind a sort *column*, so each extra key is another `CASE WHEN … END` arm — six keys × two directions is a statement nobody can read, for a list that offline holds only the handful of items the user downloaded. Name ordering is what the grid opens with, and the sort menu still works online. Extending the query is a local change if the downloads list ever grows large enough to matter.

## 2026-07-28 — M6: offline paging reuses `ItemPagingSource`, not a Room `PagingSource`
- **Scope:** `:data` (`OfflineJellyfinRepository.getItemsPaged`), `:core:database` (`ItemDao.pagingDownloaded` returns `List<ItemEntity>`)
- **Plan said:** "Offline: `ItemDao.pagingDownloaded` behind same Pager."
- **Done instead:** The DAO exposes a plain `LIMIT`/`OFFSET` suspend query, and the offline `Pager` is fed by the same `ItemPagingSource` the online grid uses rather than by a Room-generated `PagingSource`.
- **Reason:** "Behind the same Pager" is satisfied more literally this way — one paging implementation, one set of key/offset edge cases, already covered by `ItemPagingSourceTest`. It also keeps the offline grid unit-testable on the JVM: a Room `PagingSource` can only be exercised by an instrumented test, and this milestone was built without device access. `room-paging` stays on the classpath for M7 if the download queue wants it.

## 2026-07-28 — M6: a 10-second ceiling on every online repository call
- **Scope:** `:data` (`DelegatingJellyfinRepository.ONLINE_CALL_TIMEOUT_MS`)
- **Plan said:** "Must not hang: server-down with Wi-Fi up degrades via the 3s probe, not a 30s socket timeout" — the probe is named as the mechanism.
- **Done instead:** The probe is implemented exactly as specified (3 s per candidate address, on network change / app resume / reported failure) **and** every online call is additionally wrapped in `withTimeoutOrNull(10_000)`, which on expiry reports the failure and answers from the cache.
- **Reason:** The probe can only demote a server it has had a chance to test. A call already in flight when the server dies is not covered by it and would still sit on the SDK's own 30-second socket timeout — the exact symptom the definition of done rules out. The ceiling closes that window without replacing the plan's mechanism; 10 s is far above any real list request and far below the timeout it protects against.

## 2026-07-28 — M6: force-offline toggle lives in the home top-bar overflow menu
- **Scope:** `:app` (`HomeRoute`, `ConnectionViewModel`, `res/values/strings.xml`)
- **Plan said:** "Navigation: … Settings behind top-bar avatar"; the full Settings screen is M9.
- **Done instead:** The home top bar's sign-out icon became an overflow (⋮) menu holding *Offline mode* (a switch) and *Sign out*. Both move to Settings at M9.
- **Reason:** M6 ships the preference and the state machine behind it, but `:feature:settings` is empty until M9 and the avatar affordance needs the user-image work that comes with it. A setting with no way to reach it cannot be verified on the device, and the overflow menu is where the existing temporary sign-out action already lived (DECISIONS.md 2026-07-28, "temporary Home placeholder with sign-out lives in `:app`"). Two entries to delete when Settings lands.

## 2026-07-28 — M6: the offline banner sits above the bottom navigation bar
- **Scope:** `:app` (`AppScaffold`)
- **Plan said:** "the single app-wide `OfflineBanner` in `AppScaffold`" — placement unspecified.
- **Done instead:** The banner is rendered in the outer `Scaffold`'s `bottomBar` slot, stacked directly above the `NavigationBar`.
- **Reason:** The app's inset contract (documented on `AppScaffold` since the M3/M4 integration pass) is that the outer scaffold consumes *no* system-bar insets and every screen pads its own `TopAppBar`. A top-anchored banner would therefore either draw under the status bar or push a second status-bar padding onto the screen beneath it. The bottom slot has no such interaction, is visible on every destination including the ones with no navigation bar, and keeps the notice next to the thumb.

## 2026-07-28 — M6: offline `getSimilarItems` is empty, and `AppPreferences` exposes only the offline flag
- **Scope:** `:data` (`OfflineJellyfinRepository.getSimilarItems`), `:core:datastore` (`AppPreferences`)
- **Plan said:** ItemDetail lists "`getSimilarItems`" among its calls; `:core:datastore` is described as holding "`AppPreferences` (DataStore)" with keys for Wi-Fi-only downloads, max bitrate and storage location already named in `PreferenceKeys`.
- **Done instead:** (a) `getSimilarItems` returns an empty list offline rather than approximating a recommendation from the downloaded items; (b) `AppPreferences` declares only `forceOffline`.
- **Reason:** (a) "More like this" is a server-side recommendation over the *whole* library. The two or three downloads that happen to share a genre are not that, and presenting them as such is a worse answer than an absent row — the detail screen already renders no row for an empty list. (b) The other three preferences have no consumer until M7/M9; declaring accessors nothing reads would be dead API on the one interface every module can see. The keys stay in `PreferenceKeys` as the placeholder they were.

## 2026-07-28 — M6: no Room migration test (no instrumented-test infrastructure yet)
- **Scope:** `:core:database` (schema v3, `@AutoMigration(2, 3)`)
- **Plan said:** "unit tests accompany every repository/ViewModel/mapper"; migrations are implied to be exercised.
- **Done instead:** v3 is a purely additive `@AutoMigration` with its schema exported to `core/database/schemas/…/3.json` and validated by Room at compile time, but there is no `MigrationTestHelper` test.
- **Reason:** No module in this project has an `androidTest` source set, and `MigrationTestHelper` only runs on a device. Standing that infrastructure up is a piece of work of its own; meanwhile the additive-only shape of v1→v2→v3 is exactly what Room's auto-migration verifies. Worth revisiting at M10 (release hardening), when the first non-additive schema change becomes likely.
