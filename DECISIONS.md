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

## 2026-07-28 — M3 DoD: >500-item paging verified at 184 items on device + 520 items by unit test
- **Scope:** M3 milestone verification (no code change)
- **Plan said:** "**M3 Library grid + Search** (Paging 3, sort/filter). Verify: >500-item library scrolls clean, one request per page."
- **Done instead:** The scroll-clean/one-request-per-page property was walked on the device against the largest library the test server has — Films, 184 items (test-server' Séries library has 28 top-level items): full scroll to the bottom produced exactly one request per page at offsets 0/50/100/150, each requested once (logcat-verified). The >500-item scale itself is pinned by `OnlineJellyfinRepositoryPagingTest`, which drives the same `ItemPagingSource`/`Pager` configuration through a fake 520-item library and asserts exactly 11 page requests with no duplicates.
- **Reason:** No library with more than 500 top-level items exists on the only available server, and inflating the user's real library with dummy media to satisfy the letter of the DoD would mutate their data (out of bounds for verification). The device walk proves the end-to-end request discipline; the unit test proves the same code path holds at the DoD's scale.

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

<!-- BEGIN M5 (playback) — appended by the M5 worktree; keep as one block when merging -->

## 2026-07-28 — M5: the player UI drives the shared ExoPlayer directly, not a MediaController
- **Scope:** `:player` (`session/ExoPlayerHandle`, `session/PlaybackService`, `ui/PlayerViewModel`)
- **Plan said:** "hosted in `PlaybackService : MediaSessionService` (background/PiP/notification), Compose `PlayerScreen` **via MediaController**".
- **Done instead:** `ExoPlayerHandle` is a `@Singleton` owning one `ExoPlayer`. `PlaybackService : MediaSessionService` wraps *that same instance* in a `MediaSession` — so background playback, the media notification, media buttons and audio focus are all still the service's job, exactly as planned. What changed is the UI side: `PlayerViewModel` talks to the player in-process through the `PlayerHandle` seam instead of connecting a `MediaController` to the session.
- **Reason:** `MediaItem.toBundle()` deliberately omits `localConfiguration` — the URI, the MIME type and the subtitle configurations. A `MediaController` therefore cannot be handed a resolved Jellyfin stream; the supported pattern is to resolve inside `MediaSession.Callback.onSetMediaItems` and push everything the UI needs (play method, track lists, quality) back out over custom commands and session extras. For Jellyfin that is a large amount of extra surface, because *every* track switch, quality change and decoder fallback is a fresh `PlaybackInfo` round trip whose result the UI has to render. Sharing the player instance is legitimate for a single-process app and keeps the milestone's riskiest logic in one testable place. **Revisit at M9/M10** if PiP, Android Auto or a second player host makes a real controller boundary worth the cost; the `PlayerHandle` interface is the seam that change would go behind.

## 2026-07-28 — M5: finishing an item marks it played through `UserDataRepository`
- **Scope:** `:player` (`report/PlaybackReporter`, `api/PlayerApi`)
- **Plan said:** "`reportPlaybackStart/Progress/Stopped`, **`markPlayedItem` on ENDED**, `stopEncodingProcess(...)` when transcoding".
- **Done instead:** `PlaybackReporter.reportStop` calls `userDataRepository.setPlayed(itemId, true)` when the item ended; `PlayerApi` has no `markPlayed` at all.
- **Reason:** `UserDataRepositoryImpl.setPlayed` already *is* `markPlayedItem` — plus the local Room write, the `UserDataEventBus` emission that flips the detail screen's watched tick without a refetch, and the `toBeSynced` retry when the push fails. Calling both would send the same request twice; calling only the bare endpoint would leave the local row stale until the next sync, contradicting the plan's own "**Always also** `userDataRepository.setPosition(...)` locally" rule for the neighbouring case. Same server call, strictly more correct local state.

## 2026-07-28 — M5: the ASS/SSA toggle and the bitrate cap are parameters, not stored preferences
- **Scope:** `:player` (`deviceprofile/DeviceProfileBuilder`), `:core:datastore` (untouched)
- **Plan said:** DeviceProfileBuilder reimplements jellyfin-android's, which reads `appPreferences.exoPlayerDirectPlayAss`; "[D: … bitrate overridable from quality picker]".
- **Done instead:** `getDeviceProfile(maxStreamingBitrate, directPlayAss)` takes both as arguments, defaulting to `null` (the profile's own 120 Mbps ceiling) and `true`. Nothing is persisted. The quality picker holds the bitrate for the session; ASS/SSA is not user-visible yet.
- **Reason:** The settings screen is M9, and `:core:datastore` is owned by the parallel M6 branch — adding a preference key here would conflict at merge for no behaviour gained. The plumbing is one argument away: M9 reads the preference and passes it in. Recorded because a reader who knows the reference implementation would expect a preference read.

## 2026-07-28 — M5: `ExoMediaSourceFactory` returns a description, not a `MediaSource`
- **Scope:** `:player` (`resolve/ExoMediaSourceFactory`, `model/PlaybackMediaItemSpec`, `session/MediaItems`)
- **Plan said:** "`ExoMediaSourceFactory`: DIRECT_PLAY → …; TRANSCODE → HLS `createUrl(transcodingUrl)`; external subs as `SubtitleConfiguration` …" — i.e. the factory produces Media3 objects.
- **Done instead:** The factory produces a plain `PlaybackMediaItemSpec` (URL, MIME type, list of subtitle descriptors carrying the `external:<index>` ids). A one-function extension, `PlaybackMediaItemSpec.toMediaItem()`, converts it; the player then hands the `MediaItem` to an `ExoPlayer` configured with a `DefaultMediaSourceFactory`, which selects the HLS or progressive source itself.
- **Reason:** `MediaItem.Builder.setUri` goes through `android.net.Uri.parse`, a throwing stub in local unit tests — the URL-selection table, which is the single most breakage-prone piece of this milestone, would have been untestable without an emulator. The decision table and the `external:<index>` convention are byte-identical to the plan; only the type crossing the boundary changed. `DeviceProfileBuilder`'s `MediaCodecProbe` seam exists for exactly the same reason.

## 2026-07-28 — M5: Play on a series or season resolves to an episode
- **Scope:** `:feature:detail` (`ItemDetailUiState.playTarget`, `playbackStartTicks`)
- **Plan said:** ItemDetail carries "Play/Resume"; which item a container plays is unspecified.
- **Done instead:** A movie or episode plays itself. A **series** plays its *Next up* episode, falling back to the first episode the page loaded. A **season** plays its first unwatched episode, falling back to its first. Playback starts at the target's `playbackPositionTicks` when it is resumable, otherwise at 0.
- **Reason:** Play on a container has to mean something, and this is the order jellyfin-web uses. Resolving it in the detail screen rather than in the navigation callback is deliberate: only that screen knows which rows it loaded.

## 2026-07-28 — M5: the M4 Play/Resume stub is resolved, and its test removed
- **Scope:** `:feature:detail` (`ItemDetailViewModel.onPlayClick`, `UserMessage.PlaybackNotAvailableYet`, `detail_message_playback_unavailable`, `ItemDetailViewModelTest`)
- **Plan said:** n/a — this closes the M4 entry "Play and Download buttons raise a message instead of acting".
- **Done instead:** `onPlayClick`, the `PlaybackNotAvailableYet` message, its string resource and the test `play is honest about playback landing in M5` are all deleted. The Play/Resume button now navigates to `Routes.Player`, and episode rows gained their own play button.
- **Reason:** The stub's subject no longer exists, so the test asserting the stub could not be kept. Recorded rather than done silently because the governance rule forbids deleting tests. Coverage went **up**, not down: five new tests in `ItemDetailViewModelTest` pin what Play actually plays for a movie, a partly-watched movie, a watched movie, a series and a season. The *Download* stub is untouched and still says "Downloads arrive in M7."

<!-- END M5 (playback) -->

## 2026-07-28 — Server reads refresh `user_data` rows that are not pending sync

- **Scope:** `:data` (`cache/BrowseCacheWriter`, `userdata/UserDataMapper`), `:core:database`
  (`UserDataDao` — two new queries, **no schema change**)
- **Plan said:** "`UserDataRepositoryImpl` — **local-first always**: upsert Room
  (`toBeSynced=true`) → emit on `UserDataEventBus` (SharedFlow; …) → if online push
  `itemsApi.updateItemUserData(UpdateUserItemDataDto(...))`, clear flag on success; else/on failure
  enqueue `UserDataSyncWorker` … Sync conflict: **most-recent-wins** — worker fetches server
  userData, compares `lastPlayedDate` vs local `updatedAt`, pushes only if local is newer, otherwise
  adopts server value and clears flag." The read path is described only as "`OnlineJellyfinRepository`
  — SDK calls, write-through to Room (`source=BROWSE_CACHE`)" — i.e. item metadata, with all
  `user_data` reconciliation left to M8's worker.
- **Done instead:** `BrowseCacheWriter.writeItems`, which every successful online read already goes
  through, now also adopts each DTO's `userData` block into the `user_data` table — but **only** for
  rows that do not exist or have `toBeSynced = false`. A pending row is left byte-for-byte alone.
  The write path is not touched at all: it is still local-first always.
- **Reason:** This is the fix for the corruption bug in STATUS.md's "Known issues". `setPosition`
  deliberately pushes the item's *full* desired state (DECISIONS.md, 2026-07-28, "M4: dedicated
  mark-played / favourite endpoints"), built from the local row — and that row was only ever written
  by local writes, never by reads. It therefore went stale the instant the same user changed the
  item from another client, and the player's 5-second position writes pushed the stale state back:
  confirmed on the test server, an item unmarked via the API came back `Played=true` after a few
  seconds of in-app playback. Sending the full state is right (the endpoint merges what it is
  given), so the row it is built from has to be honest — and the only place the app ever learns the
  truth is a read.

  Deferring this to M8's worker was the alternative and is wrong on two counts: the worker only ever
  looks at `toBeSynced = 1` rows, so it would never visit a stale *synced* row at all; and the app
  already holds the authoritative answer in every response it parses, so spending a round trip to
  re-ask for it would be worse in every respect. Nothing here pre-empts M8 — reconciling a *pending*
  row against the server is still most-recent-wins in the worker, and this refresh is defined to
  leave those rows untouched precisely so that it cannot.
- **Timestamp semantics when adopting server state** (the judgment call inside this decision):
  `lastPlayedDate` is copied from the server verbatim, `null` included — it is the *server* half of
  most-recent-wins and must never be fabricated from the read time. `updatedAt` is set to the moment
  of the refresh; it is the *local* half, and it can only mislead a comparison on a
  `toBeSynced = true` row, which a refresh never produces (and any later local write re-stamps it
  from the clock anyway). `toBeSynced` is always `false`: an adopted row is a copy of server state,
  not a debt the server owes.
- **Known limitation:** the check-then-write is not atomic against a concurrent local write — the
  window is one Room read wide, both writes are already fire-and-forget, and the worst case is a
  refreshed row that the next read corrects. No server-side change can be lost that way, because a
  local write that reached the server has by definition already reached it.

<!-- BEGIN M7 (downloads) — appended by the M7 worktree; keep as one block when merging -->

## 2026-07-28 — M7: `DownloadStatus` and `DownloadFileType` live in `:core:common`
- **Scope:** `:core:common` (`model/DownloadStatus.kt`, `model/DownloadFileType.kt`), `:data:downloads` (the M0 `DownloadStatus` stub deleted)
- **Plan said:** `:data:downloads` owns "`DownloadRepository`, `DownloadQueue`, `DownloadWorker`, …"; M0 seeded `DownloadStatus` there, and `:core:common` is described as holding "domain models (`JellyfinItem`, `UserData`, `ItemQuery`, `FilterOptions`, `DownloadState`)".
- **Done instead:** Both enums live in `:core:common`; `:data:downloads`'s stub file is deleted.
- **Reason:** `:core:database` persists both (they are `DownloadEntity.status` and `DownloadFileEntity.type`, with Room converters) and it sits **below** `:data:downloads` in the module graph, so it cannot see them there. The alternatives were storing the columns as bare strings and converting in `:data:downloads` — which throws away Room's type safety and makes every DAO predicate a magic literal — or duplicating the enums. `:core:common` is where every module can see a type, which is exactly the situation. `DownloadState` (the UI type) already lived there, and the two are now neighbours, which makes their deliberate separation visible.

## 2026-07-28 — M7: the download-policy fallback triggers on a 403, not on a policy flag
- **Scope:** `:data:downloads` (`engine/DownloadQueue.downloadEssential`, `engine/FileDownloader.DownloadHttpException`, `plan/DownloadFilePlanner.plan(downloadAllowed = …)`)
- **Plan said:** File plan — "then MEDIA via `libraryApi.getDownloadUrl(itemId)` (fallback `getVideoStreamUrl(static=true)` **if download policy denied**)".
- **Done instead:** The plan is always built with the download endpoint. If the server answers `403` for the media file, that one file is re-planned onto `getVideoStreamUrl(static = true)` and retried once. The planner's `downloadAllowed` parameter exists and is unit-tested, but the queue derives it from the response rather than from `UserDto.policy.enableContentDownloading`.
- **Reason:** The policy flag is a *snapshot* — it is read at sign-in (M1) and `SessionState` does not carry it, so plumbing it through would mean either widening the session model or re-fetching the user on every enqueue. The server's `403` is the authoritative and current answer to the same question, it costs nothing when the policy is on (which it is for this project's account, checked at M1), and it also covers the cases a policy flag would miss: an admin revoking downloads while items sit in the queue, or a per-library restriction. The plan's *behaviour* — the same bytes over the stream route — is unchanged.

## 2026-07-28 — M7: SAF and secondary-volume storage deferred; `DownloadStorage` ships File-only
- **Scope:** `:data:downloads` (`storage/DownloadStorage.kt`, `storage/FileDownloadStorage`), `:core:datastore` (`DOWNLOAD_STORAGE_URI` still unused)
- **Plan said:** Storage — "default `getExternalFilesDir(null)/downloads` [D]; **optional SAF tree or secondary `getExternalFilesDirs` volume (SD)**. `DownloadStorage` interface hides File vs DocumentFile."
- **Done instead:** The `DownloadStorage` interface exists and everything in the pipeline goes through it, but the only implementation is `FileDownloadStorage` over the plan's default location. There is no storage-location picker, and `PreferenceKeys.DOWNLOAD_STORAGE_URI` stays unread. The interface's currency is `java.io.File`.
- **Reason:** The plan marks the alternative locations "optional", and the seam — the part that is expensive to add later — is in place: swapping in a `DocumentFile`-backed implementation touches one class plus its binding. Shipping the picker as well would have meant the "location change only when no downloads exist" rule, a SAF permission-persistence path and a second set of storage tests, none of which the M7 definition of done exercises. The `File` currency is a real (small) constraint on that future implementation: it would have to materialise a path, or the interface would gain a handle type. Recorded so a reader who expects `DocumentFile` behind this interface knows why it is absent.

## 2026-07-28 — M7: the delete cascade's user-data query lives in `DownloadDao`
- **Scope:** `:core:database` (`dao/DownloadDao.deleteSyncedUserData`); `dao/UserDataDao` untouched
- **Plan said:** Delete cascade — "keep `UserDataEntity` only if `toBeSynced`"; `UserDataDao` is the DAO for that table.
- **Done instead:** `DELETE FROM user_data WHERE itemId = :itemId AND toBeSynced = 0` is declared on `DownloadDao`, not on `UserDataDao` (which has a per-*user* `deleteSynced` from M4, but nothing per-item).
- **Reason:** M7 was built on a worktree branch alongside a bugfix branch that owns `UserDataDao`, and adding a method there would have collided at merge over a one-line query. It is also defensible on its own terms: the rule belongs to *this* cascade, nothing else uses it, and a Room DAO is free to query any table. Worth folding into `UserDataDao` at the next touch of that file.

## 2026-07-28 — M7: the Wi-Fi-only toggle lives in the Downloads top bar
- **Scope:** `:feature:downloads` (`DownloadsScreen`); `:app`'s home overflow menu untouched
- **Plan said:** Settings holds "prefs, account, storage location picker…"; M6 put the *Offline mode* toggle in the home top-bar overflow menu as an interim home (DECISIONS.md 2026-07-28).
- **Done instead:** *Wi-Fi only* is a `Switch` in the Downloads screen's `TopAppBar`, not in the home overflow menu next to *Offline mode*.
- **Reason:** It is a download setting, this is the download screen, and the consequence of flipping it — the queue stopping or starting — is visible in the list directly underneath. Grouping it with *Offline mode* would have put a download control on a screen with no downloads on it. Both move to Settings at M9; this one has a natural home in the meantime, which the offline toggle did not.

## 2026-07-28 — M7: `DownloadEntity` carries denormalised item metadata
- **Scope:** `:core:database` (`entities/DownloadEntity`)
- **Plan said:** "`DownloadEntity` (pk itemId [D], status …, mediaSourceId, bytesDownloaded/Total, queuePosition)".
- **Done instead:** Also `userId`, `directoryName`, `itemName`, `seriesName`, `errorMessage`, `createdAt`, `updatedAt`.
- **Reason:** Each earns its place at a moment when the matching `ItemEntity` is not available or not cheap: the queue tab renders a row before the cache write is visible (`itemName`/`seriesName`), the delete cascade has to find the files **after** the item row is pruned (`directoryName`), the cascade needs the owner to decide which user-data row to drop (`userId`), the queue tab has to say *why* an item failed (`errorMessage`), and the *Downloaded* list orders by when the user asked (`createdAt`). Reading them off the item row instead would mean decoding a multi-kilobyte `BaseItemDto` blob on every progress emission. The plan's column list reads as the essential set rather than an exhaustive one.

## 2026-07-28 — M7: the M4 Download stub is resolved, and its test replaced
- **Scope:** `:feature:detail` (`ItemDetailViewModel.onDownloadClick`, `UserMessage`, `res/values/strings.xml`, `ItemDetailViewModelTest`)
- **Plan said:** n/a — this closes the M4 entry "Play and Download buttons raise a message instead of acting".
- **Done instead:** `UserMessage.DownloadNotAvailableYet`, the string `detail_message_download_unavailable` and the test `download is honest about the pipeline landing in M7` are deleted. The button now enqueues (or cancels / removes / retries, depending on state) through `DownloadRepository`, and four new messages replace the one stub message.
- **Reason:** The stub's subject no longer exists, so the test asserting it could not be kept. Recorded rather than done silently because the governance rule forbids deleting tests. Coverage went **up**: seven new tests in `ItemDetailViewModelTest` pin what the button does in each download state, that a failure is reported, and that badges reach the season / episode / related cards.

## 2026-07-28 — M7: one Download button with four meanings, and Retry resumes rather than deletes
- **Scope:** `:feature:detail` (`ItemDetailHeader.DownloadButton`, `ItemDetailViewModel.onDownloadClick`)
- **Plan said:** ItemDetail carries "Play/Resume, Download, Mark played, Favorite" — one Download affordance, behaviour unspecified.
- **Done instead:** The single button reads *Download* → *Cancel* (queued/downloading/paused) → *Remove* (downloaded) → *Retry* (failed), and *Retry* calls `resume`, not `delete`-then-`enqueue`.
- **Reason:** A separate delete affordance would be dead most of the time, and "tap again to undo" is what the watched and favourite buttons on the same row already mean. *Retry* resuming matters more than it looks: the partial file is still on disk, so resuming costs only the missing bytes, whereas a delete-and-re-enqueue would throw away a possibly-multi-gigabyte transfer that failed at 95 %.

## 2026-07-28 — M7: no Room migration test (still no instrumented-test infrastructure)
- **Scope:** `:core:database` (schema v4, `@AutoMigration(3, 4)`)
- **Plan said:** "unit tests accompany every repository/ViewModel/mapper"; migrations are implied to be exercised.
- **Done instead:** v4 is a purely additive `@AutoMigration` with its schema exported to `core/database/schemas/…/4.json` and validated by Room at compile time; there is no `MigrationTestHelper` test.
- **Reason:** Unchanged from the M6 entry of the same name — no module has an `androidTest` source set and `MigrationTestHelper` only runs on a device. The additive-only shape of v1→v2→v3→v4 is exactly what Room's auto-migration verifies. Still worth revisiting at M10.

## 2026-07-28 — M7: `POST_NOTIFICATIONS` is requested from `MainActivity`
- **Scope:** `:app` (`MainActivity.NotificationPermissionRequest`); closes an M5 known issue
- **Plan said:** Nothing about runtime permissions; M5's STATUS entry logged "`POST_NOTIFICATIONS` is declared but never requested at runtime… M9".
- **Done instead:** On API 33+ the app asks once, on first composition, and ignores the answer.
- **Reason:** The download notification is the *only* way to pause or cancel a transfer from outside the app, which makes it part of M7's surface rather than polish. The work itself is unaffected either way — the foreground promotion keeps it alive, not the notification being visible — so a declined permission degrades nothing. Ten lines in `:app` rather than a Settings screen that does not exist yet; M9 can move it into a proper onboarding flow.

<!-- END M7 (downloads) -->

## 2026-07-28 — M7 fix: the download worker restores the session itself
- **Scope:** `:data:downloads` (`work/DownloadSessionGate`, `engine/DownloadQueue`, `work/DownloadWorker`)
- **Plan said:** M1 owns session restore ("session restore" is an M1 deliverable, driven from the UI at launch); the download pipeline's enqueue step says nothing about who configures the API client.
- **Done instead:** `DownloadQueue.drain()` consults a `DownloadSessionGate` before it touches a row. If the SDK client has no base URL / token, the gate calls `SessionRepository.restoreSession()` — the M1 local-only path (EncryptedSharedPreferences + two Room reads, no network) — and re-checks. If there is still no session the drain returns `DrainOutcome.NO_SESSION`, the worker returns `Result.retry()`, and **no row is marked ERROR**.
- **Reason:** On the M7 device walk, `am force-stop` + relaunch had WorkManager start the worker before `MainViewModel` had restored anything, so the first URL the file plan built threw the SDK's `Required value baseUrl is null…`, the item went ERROR, and the user had to press *Retry* on a download that was never broken — the opposite of the milestone's "resumes after app kill". Waiting for the UI is not an option (the worker legitimately runs with no UI at all), and duplicating the restore would give one token two owners. `Result.retry()` rather than a permanent park because the only remaining cause is a signed-out device, where WorkManager's exponential backoff is exactly the right amount of patience — the queue then picks itself up after the next sign-in with no further code.
- **Also:** the row's `errorMessage` is now user copy (`DownloadErrorCopy`), mapped through `:data`'s `AppError` taxonomy, never `throwable.message`. The copy lives in Kotlin rather than `strings.xml` because it is written to Room at failure time and read back days later, so it could not be re-resolved against the device's current locale anyway — the trade `HomeViewModel.toMessage()` already makes. Resource-backed error copy is M9 polish.

## 2026-07-28 — M7 fix: offline library membership is decided by item type, not by `parentId`
- **Scope:** `:core:database` (`ItemDao.pagingDownloaded`, `latestDownloaded`, new `seasonsOfSeries`, `childrenOf` deleted), `:data` (`OfflineJellyfinRepository`)
- **Plan said:** the offline read path serves "downloaded items only" per library; M6 implemented that scoping as `parentId = <library id> OR seriesId IN (children of that library)`.
- **Done instead:** the two library-scoped offline queries no longer filter on any parent. `OfflineJellyfinRepository` resolves the requested library's `CollectionKind` from the cached `library_views` and narrows the *item types* instead — a movie library shows `MOVIE`, a TV library `SERIES` (grid) and `SERIES + EPISODE` (Latest); an unknown library id narrows nothing. Season lookup moved from `childrenOf(parentId = seriesId)` to `seasonsOfSeries`, which matches `seriesId OR parentId`.
- **Reason:** The predicate could not match on a real device. Downloaded rows are stored with `parentId NULL` (the enqueue-time DTO carries no usable `ParentId`), and even where a server does send one, a movie's `ParentId` is its containing *folder*, not the library-view id the grid asks about — so the offline Films grid said "Nothing to show here." next to a fully downloaded film, and the offline home had no Latest row. Type is an exact proxy for the libraries v1 supports (movies and TV only, `CollectionKind.SUPPORTED`); it is deliberately *not* a general rule — two movie libraries would share their downloads — hence this entry rather than a silent change. The alternative, an explicit `libraryId` column, needs a schema migration *and* a reliable ancestor walk at enqueue time to fill it, and would still be null for everything downloaded before that walk existed. Series → season → episode navigation is unaffected: it runs on `seriesId`/`seasonId`, which the cached DTOs do carry.

<!-- BEGIN M8 (offline playback + sync) — appended by the M8 worktree; keep as one block when merging -->

## 2026-07-29 — M8: a completed download always wins, whatever the connection is doing
- **Scope:** `:player` (`resolve/PlaybackSourceResolver`, `resolve/LocalPlaybackResolver`, `ui/PlayerViewModel`)
- **Plan said:** "**Offline:** `LocalPlaybackResolver` — `ItemEntity.dto.mediaSources[0]` + `DownloadFileEntity` URIs (media, subs, trickplay tiles), DIRECT_PLAY, zero network, positions via `toBeSynced=true`." The plan describes the offline resolver but never says *when* it is chosen; every other per-call choice in the app (`DelegatingJellyfinRepository`) is made on `ConnectionState`.
- **Done instead:** `PlaybackSourceResolver` picks the **local** source whenever the item has a completed download, **regardless of `ConnectionState`**. Only an item with no local copy is negotiated with the server, and only then does connectivity matter: offline with nothing on disk fails immediately with `AppError.Network` instead of firing a `PlaybackInfo` POST into a dead network. One exception: a request with `enableDirectPlay = false` skips the local copy (see the next entry).
- **Reason:** The product's stated differentiator is "downloaded media visible and playable in the same screens" (docs/PLAN.md, opening paragraph). A user who deliberately put a 2.9 GB film on the device does not expect it to be re-streamed — with a transcode, a bitrate cap and a data bill — because Wi-Fi happened to be up. Mirroring the repository's connection-driven rule here would also make the *offline* path the rarely-exercised one, which is exactly the path the milestone's definition of done depends on; as written, the code that runs in airplane mode is the code that runs every day. It is strictly faster besides: no `PlaybackInfo` round trip, no transcode negotiation, DIRECT_PLAY by construction.

## 2026-07-29 — M8: a forced transcode is the one request that skips the local file
- **Scope:** `:player` (`resolve/PlaybackSourceResolver`, `fallback/DecoderFallbackHandler`)
- **Plan said:** `DecoderFallbackHandler` — "a **renderer/decoder** failure means this device cannot play the file as delivered, so we forbid direct play *and* direct stream, forcing a transcode". Written for M5, when every source was a server stream.
- **Done instead:** `PlaybackSourceResolver` treats `enableDirectPlay = false` as "not the bytes on disk either" and goes straight to the server. `DecoderFallbackHandler.onPlayerError` now takes the widened `PlaybackMediaSource`; a local source can never be transcoding, so a source error on one falls through to the same force-transcode branch a direct play does.
- **Reason:** Without it, a downloaded file whose codec this device cannot decode would re-resolve to the same file forever — the fallback ladder's attempt counter would end the loop, but only after two visible reloads, and the transcode that *would* fix it would never be requested. Offline the request then fails with the network error rather than retrying bytes that have already failed, which is the honest answer: nothing on this device can play that file.

## 2026-07-29 — M8: the quality picker is hidden while playing a download
- **Scope:** `:player` (`ui/PlayerUiState.isLocalPlayback`, `ui/PlayerControls`, `PlayerViewModel.selectQuality`)
- **Plan said:** "Same `PlaybackMediaSource` sealed type → player UI byte-identical online/offline."
- **Done instead:** One control differs: the quality sheet button is not drawn for a local source, and `selectQuality` ignores a call that arrives anyway. Track and subtitle pickers, the play-method badge, the seek bar and every transport control are identical.
- **Reason:** `maxStreamingBitrate` is a cap on what the **server** encodes; there is no server in a local session, so the picker could only either do nothing or force a needless re-resolve back onto the same file. A visible control that does nothing is a worse departure from "identical" than an absent one. Recorded because it is a literal exception to a sentence in the plan.

## 2026-07-29 — M8: offline trickplay ships as data now; the scrubber lands with M9
- **Scope:** `:data:downloads` (`offline/DownloadedTrickplay`), `:player` (`model/LocalTrickplay`, `LocalPlaybackResolver`)
- **Plan said:** M8 delivers "offline trickplay"; M9 delivers the "trickplay scrubber".
- **Done instead:** M8 makes the downloaded tile sheets reachable and unit-tested — `LocalPlaybackMediaSource.trickplay` carries the tiles' `file://` URIs in tile order plus the server's geometry (thumbnail size, tiles per sheet, thumbnail count, interval), and `LocalTrickplay.tileFor(positionMs)` resolves a position to a sheet, column and row. Nothing draws them yet.
- **Reason:** The two milestone lines only make sense read together: the tiles are downloaded at M7, made reachable at M8, and rendered at M9, where the *online* scrubber is built at the same time. Building a scrubber here would either duplicate M9's work or pre-empt it with an offline-only widget. The arithmetic that is easy to get wrong (which sheet, which cell) is the part that is testable without a screen, so it is done and pinned now; M9 adds a `@Composable` over an API that already exists.

## 2026-07-29 — M8: the Room access behind `LocalPlaybackResolver` lives in `:data:downloads`
- **Scope:** `:data:downloads` (`offline/DownloadedMediaProvider`, `offline/DownloadedMedia`), `:core:database` (`DownloadDao.getWithFiles`), `:player` (`resolve/LocalPlaybackResolver`)
- **Plan said:** The module table puts `LocalPlaybackResolver` in `:player`; `:player`'s dependencies are `:core:common`, `:core:ui`, `:core:network`, `:data`, `:data:downloads`.
- **Done instead:** `LocalPlaybackResolver` is in `:player` as specified, but it holds no DAO. A new `DownloadedMediaProvider` in `:data:downloads` answers "what is on disk for this item" as a plain `DownloadedMedia` value (the media `file://` URI, the SDK `MediaSourceInfo`, the subtitle sidecars, the trickplay tiles); the resolver turns that into a `LocalPlaybackMediaSource`. `DownloadDao` gained one `@Transaction` read, `getWithFiles(itemId)`.
- **Reason:** `:player` does not — and should not — depend on `:core:database`; `:data` and `:data:downloads` both take it as `implementation`, so the DAOs are deliberately invisible from there. Putting the query in `:data:downloads`, which already owns the download schema, the storage layout and `ItemEntityMapper`, keeps the module boundary the plan drew and puts the on-disk existence check next to the code that wrote the files. The split also gives each half a natural test: the provider against real temp files, the resolver against a mocked provider, and neither needs the other's fixtures.

## 2026-07-29 — M8: `PlaybackReporter` sends nothing while offline, not even for a server source
- **Scope:** `:player` (`report/PlaybackReporter`)
- **Plan said:** "**Reporting** (5s ticker + state edges): `reportPlaybackStart/Progress/Stopped`, `markPlayedItem` on ENDED, `stopEncodingProcess(deviceId, playSessionId)` when transcoding… **Always also** `userDataRepository.setPosition(...)` locally."
- **Done instead:** The server half of every report is skipped when the source is local **or** when `ConnectionStateProvider` says the app is offline. The local half is unchanged and unconditional: `setPosition` on every tick and on a mid-item stop, `setPlayed` on ENDED. `stopEncodingProcess` is skipped under the same condition.
- **Reason:** A local source has no `playSessionId`, so the triad is not merely useless but unbuildable, and there is no encoder to kill. The offline-*remote* case (a stream that lost the network mid-film) is the real departure: without the guard each five-second tick spends a full connect timeout before failing, and each writes a warning — for reports that cannot arrive. Nothing is lost, because the position still lands in Room with `toBeSynced = true` and `UserDataSyncWorker` delivers it on reconnect, which is a strictly better channel than a report the server never received. The plan's "always also write locally" is what makes the skip safe, and it is the half that is kept.

## 2026-07-29 — M8: the sync worker asserts the whole row, through three endpoints, and abandons a 404
- **Scope:** `:data` (`userdata/UserDataSyncer`, `userdata/UserDataSyncWorker`)
- **Plan said:** "Sync conflict: **most-recent-wins** — worker fetches server userData, compares `lastPlayedDate` vs local `updatedAt`, pushes only if local is newer, otherwise adopts the server value and clears the flag."
- **Done instead:** The rule is implemented exactly as written, with three points the plan leaves open resolved as follows.
  1. **A push sends the whole row** through the same endpoints `UserDataRepositoryImpl` uses for the equivalent single operation — `markPlayedItem`/`markUnplayedItem`, then `markFavoriteItem`/`unmarkFavoriteItem`, then `updateItemUserData` with the full desired state. Three calls, in that order. The worker cannot know which operation produced a pending row (an offline session batches several into one), so it asserts the row rather than guessing; and `markPlayedItem` clears the server's resume position, so the position has to be asserted *after* it or every watched item would come back at position 0.
  2. **A tie goes to the server.** `!row.updatedAt.isAfter(serverInstant)` — an identical instant means the server already holds this state, so adopting is idempotent and pushing is a wasted round trip. A server row with no `lastPlayedDate` is never newer, so the local change wins.
  3. **A 404 abandons the row** (`toBeSynced` cleared, logged) instead of retrying. The item is gone from the server, or no longer visible to this user, so the change has nowhere to go; retrying forever would keep the worker permanently dirty and hold back nothing useful. Every other failure keeps the flag and returns `Result.retry()`.
- **Reason:** Each is a case the plan's sentence does not decide and the device walk would hit. The comparison itself is deliberately `updatedAt` against the server's `lastPlayedDate` rather than the two `lastPlayedDate`s: a favourite toggle never touches `lastPlayedDate`, so comparing those would make every offline favourite lose to a film watched last week. Both sides go through `SdkDateTime`'s helpers, since the SDK's `LocalDateTime` is zone-aware in both directions.

## 2026-07-29 — M8: the sync drain is also triggered from app start and from reconnection
- **Scope:** `:data` (`userdata/UserDataSyncTrigger`), `:app` (`JellyfinNativeApplication`)
- **Plan said:** "`UserDataRepositoryImpl` — … else/on failure enqueue `UserDataSyncWorker` (unique work, NetworkType.CONNECTED, backoff)." The failed local push is the only enqueue the plan names.
- **Done instead:** A `UserDataSyncTrigger` singleton collects `ConnectionStateProvider.state`, and on every transition into `ONLINE` — including the first emission, which is the app-start check — enqueues the worker **if** `countPendingSync() > 0`. `JellyfinNativeApplication.onCreate` starts it.
- **Reason:** The plan's single trigger cannot deliver the milestone's own definition of done. The DoD is "airplane-mode playback to 50% → **reconnect** → server shows 50% resume", and on that path there is no failed push to enqueue anything: the app was offline, the positions were written locally without ever attempting the network, and by the time connectivity returns the process may have been killed. `NetworkType.CONNECTED` only re-runs work that was enqueued in the first place. The count query keeps a normal launch — nothing pending — at one indexed `COUNT(*)` and no scheduled work. It lives in `Application.onCreate` rather than in a ViewModel because a device coming back online with the app backgrounded is precisely the case that matters.

<!-- END M8 (offline playback + sync) -->
