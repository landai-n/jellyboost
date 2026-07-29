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

## 2026-07-29 — M8 fix: session gate hoisted to `:core:network` for the sync worker
- **Scope:** `:core:network` (new `session/SessionGate`, `session/SessionGateTest`), `:data:downloads` (`engine/DownloadQueue`, `engine/DownloadQueueTest`; `work/DownloadSessionGate` and its test deleted), `:data` (`userdata/UserDataSyncWorker`)
- **Plan said:** the M7 decision above this one, "the download worker restores the session itself" — a `:data:downloads`-local `DownloadSessionGate` consulted once per drain.
- **Done instead:** The gate moved to `:core:network` as `SessionGate` (same `ensureSession()`/`isUsable()` logic, generic log copy) so it can be shared. `DownloadQueue.drain()` now consults the shared `SessionGate` instead of the deleted `DownloadSessionGate`, unchanged otherwise. `UserDataSyncWorker.doWork()` calls the same `SessionGate.ensureSession()` before `UserDataSyncer.sync()` and returns `Result.retry()` on `false`, mirroring `DownloadWorker`'s `NO_SESSION` handling. (One correction to how this was scoped going in: the gate's call site on the download side is `DownloadQueue.drain()`, not `DownloadWorker.doWork()` itself — `DownloadWorker` only maps `DrainOutcome.NO_SESSION` to `Result.retry()` and never touched the gate directly, then or now.)
- **Reason:** The M8 device walk hit the same cold-start race in the sync drain that M7 fixed for downloads: `UserDataSyncTrigger` enqueues `UserDataSyncWorker` at app start when rows are pending, the worker raced `MainViewModel.restoreSession()` on a cold start and lost, `UserDataSyncer.fetchServerUserData` threw `MissingBaseUrlException` ("Required value baseUrl is null"), and the drain burned one attempt plus a 30 s WorkManager backoff before the retry adopted correctly. Rather than copy `DownloadSessionGate` a second time into `:data`, the fix shares one implementation from `:core:network` — a module every worker-owning module already depends on — so any future worker gets the same protection for free.

<!-- END M8 (offline playback + sync) -->

<!-- BEGIN M9 (player polish) -->

## 2026-07-29 — M9: `:player` takes a dependency on `:core:datastore`
- **Scope:** `player/build.gradle.kts`, `:core:common` (`model/SegmentSkipMode`), `:core:datastore` (`AppPreferences`, `DataStoreAppPreferences`, `PreferenceKeys`), `:player` (`ui/PlayerViewModel`)
- **Plan said:** the module table gives `:player` the dependencies `:core:common`, `:core:ui`, `:core:network`, `:data`, `:data:downloads`. `:core:datastore` is not among them.
- **Done instead:** `:player` now also takes `implementation(projects.core.datastore)`, and `PlayerViewModel` reads three preferences directly: `introSkipMode`, `outroSkipMode`, `pipOnLeave`. The enum they carry, `SegmentSkipMode`, lives in `:core:common` so that the preference store, the player and the (M9, other branch) settings screen can all name it without seeing each other.
- **Reason:** the plan puts two of M9's behaviours behind a *per-type preference* ("Media segments (M9): … per-type pref; server-only") and a third behind an on/off setting, and the player is the only thing that can act on them. The alternatives were worse: routing them through `:data` would put user-interface settings in the repository layer, and passing them down from `:app` would make the player's behaviour depend on who constructed the screen. `:data:downloads` already takes the same dependency for the Wi-Fi-only constraint, so this is the established shape rather than a new one.

## 2026-07-29 — M9: segment skip is two per-type enum preferences, not one map
- **Scope:** `:core:common` (`model/SegmentSkipMode`, `model/MediaSegmentKind`), `:core:datastore`
- **Plan said:** "Media segments (M9): `getItemSegments(INTRO/OUTRO)` → skip button; **per-type pref**; server-only." How "per-type" is stored is left open.
- **Done instead:** two `String` preference keys, `segment_skip_intro` and `segment_skip_outro`, each holding a `SegmentSkipMode` (`OFF` / `SHOW_BUTTON` / `AUTO_SKIP`) by `name`, both defaulting to `SHOW_BUTTON`. An unparseable stored value degrades to the default rather than throwing. Types the app has no behaviour for (`COMMERCIAL`, `PREVIEW`, `RECAP`) are neither requested nor stored.
- **Reason:** a serialised map would buy generality this client cannot use — the plan scopes M9 to intro and outro, and every additional type needs UI copy and a decision about what "skip" means for it before a preference for it is worth anything. Two flat keys are also two independent `Flow`s, so the settings screen renders two ordinary pickers and the ViewModel reads them without parsing. `SHOW_BUTTON` is the default because the segment data comes from a server-side detection plugin whose guesses are sometimes wrong: a wrong guess that offers a button is a button nobody presses, while a wrong guess that seeks is a film that jumps.

## 2026-07-29 — M9: the media session is added to the service explicitly (the background-playback fix)
- **Scope:** `:player` (`session/PlaybackService`, `session/ExoPlayerHandle`)
- **Plan said:** "hosted in `PlaybackService : MediaSessionService` (background/PiP/notification), Compose `PlayerScreen` via MediaController" — amended on 2026-07-28 to "the player UI drives the shared ExoPlayer" (no `MediaController`).
- **Done instead:** `PlaybackService.onCreate` now calls `addSession(session)` itself, registers a `MediaSessionService.Listener` for `onForegroundServiceStartNotAllowedException`, and gives the session a launch `PendingIntent` so the notification returns to the app. `ExoPlayerHandle` additionally sets `setHandleAudioBecomingNoisy(true)` and `setWakeMode(C.WAKE_MODE_NETWORK)`.
- **Reason:** this is the root cause of the "backgrounding the app pauses playback" issue carried in STATUS since M5, and it is a direct consequence of the 2026-07-28 divergence rather than of the notification permission the issue blamed. Media3 only manages a session — posts the media notification, promotes the service to the foreground — once the session has been *added* to the service. In the canonical sample that happens implicitly: the UI connects a `MediaController`, which triggers `onGetSession` and the add. This app deliberately drives the shared `ExoPlayer` directly, so no controller ever connects, so nothing ever added the session; the service stayed an ordinary background service and the platform stopped it (aggressively so on the the OEM ROM test device) the first time the app left the foreground. `POST_NOTIFICATIONS` only ever decided whether the notification was *visible*. Keeping the 2026-07-28 architecture and adding the session by hand is one line; reverting to a `MediaController` would re-introduce the async connection the earlier decision removed.

## 2026-07-29 — M9: playback speed is session-scoped and not a stored preference
- **Scope:** `:player` (`model/PlaybackSpeed`, `session/PlayerHandle`, `ui/PlayerViewModel`, `ui/PlayerSheets`)
- **Plan said:** "M9 Polish: … speed/quality, full settings" — which could be read as a persisted setting alongside the others.
- **Done instead:** `PlaybackSpeed` lives in `PlayerUiState` only. It is re-applied after every re-resolve (a new media item starts at 1×) and forgotten when the player screen closes. Nothing is written to DataStore, and the settings screen gets no key for it.
- **Reason:** jellyfin-web behaves this way, and the reason is sound: a rate set for one talking-head documentary following the user into the next film is a setting they did not knowingly make and would struggle to find again. The two things the plan *does* want persisted — default quality and the ASS/SSA toggle — are still open for the settings branch; speed is deliberately not one of them.

## 2026-07-29 — M9: trickplay tiles carry the access token in the URL
- **Scope:** `:player` (`api/StreamUrlFactory`, `api/SdkStreamUrlFactory`, `trickplay/TrickplayResolver`)
- **Plan said:** nothing specific; the established pattern in `:player` is that media requests are authenticated by `JellyfinAuthInterceptor`, which adds the Jellyfin `Authorization` header to the player's own OkHttp client.
- **Done instead:** `StreamUrlFactory.trickplayTileUrl` returns the SDK's tile URL with the access token appended as the `ApiKey` query parameter (`ApiClient.QUERY_ACCESS_TOKEN`).
- **Reason:** trickplay sheets are fetched by **Coil**, not by ExoPlayer, and Coil's image loader is configured in `:core:ui` with no knowledge of the Jellyfin session — the interceptor never sees the request. The alternatives were to thread an `Authorization` header through the composable (jellyfin-android's approach, which puts session details in the UI layer and defeats the `StreamUrlFactory` seam the scrubber is unit-tested through) or to configure a second app-wide image loader. Jellyfin accepts the token as a query parameter for exactly this case, so the seam stays a plain `String` any loader can take. The token is not written to a log or to disk by this path; the URL is a cache key inside Coil's in-memory cache.

## 2026-07-29 — M9: the player controls now hide themselves, and gestures own the bare surface
- **Scope:** `:player` (`ui/PlayerScreen`, `ui/PlayerGestureLayer`, `gesture/PlayerGestureController`)
- **Plan said:** "M9 Polish: … gestures". The M5 player drew its controls permanently; the plan does not say they should hide.
- **Done instead:** a single tap toggles the controls, and they fade out on their own four seconds after appearing **while something is playing** (a paused player keeps them, because a paused film with no controls reads as a frozen app). The skip-segment button is deliberately *not* tied to that visibility — an intro arrives while the controls are hidden, which is exactly when the offer is worth something.
- **Reason:** the plan's gesture list includes "single tap toggles controls", which presupposes controls that can be off; permanently-drawn controls also make the double-tap seek zones unreachable over half the screen. The gesture surface is a sibling *below* the controls in the same `Box`, so a tap that lands on a button is consumed by the button and everything else falls through — which is what keeps the icons free of any knowledge of gestures.

## 2026-07-29 — M9: `PlayerViewModel.onTick` and `setScreenPresent` are `internal` for the tests
- **Scope:** `:player` (`ui/PlayerViewModel`, `ui/PlayerViewModelTest`)
- **Plan said:** "Unit tests accompany every repository/ViewModel/mapper" (CLAUDE.md); nothing about visibility.
- **Done instead:** the 500 ms position poll was split so that its *body* — one reading of the player, which is what applies the segment rules and publishes picture-in-picture readiness — is an `internal fun onTick(snapshot)`, and the screen's presence is an `internal fun setScreenPresent(present)`. `setScreenVisible` is unchanged from the outside and still owns the timer.
- **Reason:** the same reason `releaseSession` was made `internal` at M5. The timer is not the interesting part; what happens at one position is. It is also a `while (true) { … delay(…) }` on `viewModelScope`, and `runTest` cannot drain a coroutine that never finishes — a test that started the poll spun forever inside `runTest`'s cleanup instead of failing, which is a far worse failure mode than a slightly wider visibility. Driving `onTick` directly makes each segment test one call and one assertion.

<!-- END M9 (player polish) -->

<!-- BEGIN M9 (settings + app polish) -->

## 2026-07-29 — M9: the storage location picker does not ship with the settings screen
- **Scope:** `:feature:settings`
- **Plan said:** docs/PLAN.md line 77 — "Settings: prefs, account, storage location picker, sign out (clears SecureCredentialStore, optional delete downloads)."
- **Done instead:** the Downloads section of the settings screen shows the current fixed download location as informational text only; there is no picker UI and no way to change it.
- **Reason:** SAF/SD-card support was deliberately deferred at M7 behind the `DownloadStorage` seam (DECISIONS.md, M7) specifically so a storage picker could be added later without reworking the download pipeline. That SAF work does not exist yet, so a picker here would have nothing real to pick between. The picker ships together with SAF support, not at M9.

## 2026-07-29 — M9: Settings is opened from the home overflow menu, not a top-bar avatar
- **Scope:** `:feature:settings`, `:app` (`HomeRoute.kt`)
- **Plan said:** docs/PLAN.md line 13 — "Navigation: bottom nav bar Home / Libraries / Search / Downloads; Settings behind top-bar avatar."
- **Done instead:** the home top bar's existing overflow (kebab/⋮) menu — the same menu that already carried the M6 offline-mode toggle and a temporary M8 sign-out entry (`HomeRoute.kt`, both call sites' KDoc already flagged this as temporary pending M9) — has its "Sign out" entry replaced with a "Settings" entry that navigates to `Routes.Settings`. Sign-out itself moves into the new Settings screen's Account section (with the confirm dialog and optional delete-downloads checkbox). The overflow's "Offline mode" quick toggle is unchanged.
- **Reason:** there is no user-avatar image or avatar asset pipeline anywhere in the app (Jellyfin user profile images are never fetched today). Building one — fetching/caching the profile image, giving it a dedicated top-bar slot — is out of scope for M9's settings work and not otherwise needed. The overflow menu is already the established, tested entry point behind an icon in the home top bar; reusing it is mechanical and low-risk, and still satisfies the plan's functional intent (settings reachable from an icon in the top bar, not from the bottom nav) even though it is not literally an avatar.

## 2026-07-29 — M9: an offline user-data write does not enqueue the sync worker
- **Scope:** `:data` (`UserDataRepositoryImpl.pushToServer`)
- **Plan said:** docs/PLAN.md line 63 — "`UserDataRepositoryImpl` — **local-first always**: upsert Room (`toBeSynced=true`) → emit on `UserDataEventBus` → **if online** push `itemsApi.updateItemUserData(...)`, clear flag on success; **else/on failure enqueue `UserDataSyncWorker`**".
- **Done instead:** the "if online push" half is now implemented literally — `pushToServer` returns early on `!connectionStateProvider.state.value.isOnline`, logging one `Timber.d` line. The plan's "else" half is **not** implemented: an offline write schedules nothing. `syncScheduler.enqueue()` still runs on the on-failure half (a push that was attempted online and failed), which is unchanged.
- **Reason:** STATUS.md's M8 known issue — `PlaybackReporter` calls `setPosition` every five seconds, so an offline session fired one doomed request and one `Timber.w` stack per tick. Gating the push is what the plan asks for; the enqueue is what is left over. It is redundant because `UserDataSyncTrigger` (M8, which post-dates this plan line) already drains **all** pending rows on every `OFFLINE → ONLINE` edge and at app start, so the per-write enqueue only re-schedules that same unique work hundreds of times during one film. The plan's guarantee — a pending row eventually reaches the server — is delivered by the trigger, and the row still carries `toBeSynced = true`. Online behaviour is unchanged, and `UserDataRepositoryImplTest` pins both branches.

## 2026-07-29 — M9: the reconnect refresh signal drops its initial value (unlike `UserDataSyncTrigger`)
- **Scope:** `:core:network` (`connectivity/ReconnectEdges.kt`), `:data` (`ReconnectRefresher`), `:feature:home`, `:feature:library`, `:feature:search`, `:feature:detail`
- **Plan said:** nothing about how a screen learns that connectivity returned. The established idiom in the codebase is `UserDataSyncTrigger`, which collects `ConnectionStateProvider.state`, maps to `isOnline`, `distinctUntilChanged`s and acts on **every** `true` — including the flow's initial value, so a normal online launch fires it once.
- **Done instead:** `Flow<ConnectionState>.reconnectEdges()` deliberately `drop(1)`s, so it emits on a `false → true` edge and never for the state a collector starts with. The two conventions now differ, on purpose.
- **Reason:** the two signals answer different questions. `UserDataSyncTrigger`'s consumer is idempotent and guarded by a `COUNT(*)` that costs nothing when there is nothing pending, so firing at app start is free and covers "the process was killed with rows still pending". Its consumers here are ViewModels that already fetch in `init` — an initial emission would make **every** screen fetch everything twice on an ordinary launch, on the home screen alone one `getUserViews` plus a `getLatestMedia` per library. There is no cheap guard available to make that harmless, and the plan's performance targets ("home first paint", "no redundant round trips") are the thing it would spend. Logged so a future reader does not "fix" the inconsistency by aligning the two.

<!-- END -->

<!-- BEGIN M9 (downloads polish) -->

## 2026-07-29 — M9: only series get a heading in the *Downloaded* tab
- **Scope:** `:feature:downloads` (`DownloadsUiState`, `DownloadsScreen`), `:data:downloads` (`model/DownloadItem`)
- **Plan said:** docs/PLAN.md line 76 — "Downloads | Room-only: *Downloaded* tab (**grouped**, sizes, delete) …".
- **Done instead:** episodes are still grouped under their series name; a film is a group of one drawn with **no** heading (`DownloadGroup.isSeries`), and `DownloadItem.groupKey` — which fell back to the item's own title — became `DownloadItem.seriesKey`, which is `null` for a film. Series and films are ordered together alphabetically rather than in two blocks.
- **Reason:** that fallback made every film render a `GroupHeader` reading its own title directly above a row reading the same title — "Dune" over "Dune", on every film on the screen, found on the M9 device walk. It also merged two different films that happen to share a title into one heading with two identical-looking rows. Nothing is lost by dropping the heading: the size it showed on the right is already on the row underneath it.

## 2026-07-29 — M9: the download speed is measured over a one-second window, and one test's expectation changed with it
- **Scope:** `:feature:downloads` (`DownloadSpeedTracker`, `DownloadSpeedTrackerTest`)
- **Plan said:** docs/PLAN.md line 76 asks the Queue tab for a speed; CLAUDE.md governance rule 4 — "never weaken or delete a test to make it pass; if a test is genuinely wrong, log first".
- **Done instead:** the tracker no longer divides by the gap between the last two emissions. A sample is folded into the rate only once at least 1 s has passed since the previous fold; nearer samples accumulate against the same anchor. The test `a half-second window is extrapolated to a second` pinned the old rule and could not survive it, so it is replaced by `samples inside the window are accumulated, not extrapolated` (same fixture, stricter assertion — the held bytes must still be counted in the next full window) plus a new `a burst of emissions milliseconds apart cannot inflate the speed`. No assertion was dropped without a stronger one taking its place, and the file's test count goes up by one.
- **Reason:** `DownloadDao.observeAll` is a `@Transaction` over `downloads` *and* `download_files`, and `DownloadQueue` writes the file's byte counter and then the item's back to back, so one throttled progress update produces two or three emissions milliseconds apart. Half a throttle window's bytes over 1 ms of wall clock is ~500 MB/s; the Queue tab was showing 100–180 MB/s for transfers actually running at 2–8 MB/s. Extrapolating from *any* sub-second gap is that bug in its general form, which is why the old expectation had to go rather than be worked around.

<!-- END -->


<!-- BEGIN M9 (detail polish) -->

## 2026-07-29 — M9: deleting a download from the detail screen now asks first, and the test pinning immediate deletion changed with it
- **Scope:** `:feature:detail` (`ItemDetailViewModel`, `ItemDetailScreen`, `ItemDetailViewModelTest`)
- **Plan said:** nothing about confirmation on delete; CLAUDE.md governance rule 4 — "never weaken or delete a test to make it pass; if a test is genuinely wrong, log first". docs/POLISH.md (user request, 2026-07-29): "Deleting a downloaded file is not showing a confirmation dialog, it should ask for confirmation before deleting."
- **Done instead:** `onDownloadClick()` on a `Downloaded` item no longer deletes; it raises `showDeleteConfirmation`, and only `confirmDeleteDownload()` deletes. The test `download deletes an item that is already on the device` pinned the old immediate-delete behavior and was replaced by three tests: the click requests confirmation instead of deleting, confirming deletes and clears the flag, dismissing leaves the download untouched. Retry-on-failed and cancel-in-flight still act immediately (nothing finished is lost by them).
- **Reason:** the old assertion described exactly the behavior the user reported as a bug — a tap silently destroying a completed download. The replacement suite is strictly stronger: it still pins that deletion happens (now behind the confirm) and adds the two paths the old test could not see.

<!-- END -->

<!-- BEGIN M9 (app chrome + performance polish) -->

## 2026-07-29 — M9: the top bar and the bottom navigation bar are one combined bar
- **Scope:** `:app` (`AppScaffold`, new `AppTopBar`, `JellyfinNavHost`, `HomeRoute` deleted), `:feature:library` (`LibrariesScreen`), `:feature:downloads` (`DownloadsScreen`), `:feature:search` (`SearchScreen`)
- **Plan said:** docs/PLAN.md line 13 — "Navigation: bottom nav bar Home / Libraries / Search / Downloads; Settings behind top-bar avatar" — and line 43, `:app` owns an "AppScaffold (bottom nav + OfflineBanner)". Each top-level screen additionally grew its own `TopAppBar`.
- **Done instead:** the bottom `NavigationBar` is gone, and so are the per-screen `TopAppBar`s on the four top-level destinations. One `AppTopBar` in `:app` now carries the four destinations as selectable tabs (icon + label on windows ≥ 560dp, icon-only below), the offline status icon, and the app overflow menu (*Offline mode* + *Settings*). Pushed destinations — `LibraryGrid`, `ItemDetail`, `Settings`, the auth flow, the player — are untouched and keep their own bars and back handling. Two knock-on moves: the overflow menu that used to hang off the *home* top bar is now reachable from every top-level destination (amending "M9: Settings is opened from the home overflow menu"), and the Downloads Wi-Fi-only toggle moved out of that screen's now-deleted top bar into its storage header (amending "M7: the Wi-Fi-only toggle lives in the Downloads top bar" — same screen, same reasoning, one row lower).
- **Reason:** user request, recorded in docs/POLISH.md: the two bars together cost roughly 140dp of vertical space on every top-level screen and largely repeated each other, since the top bar's title only ever named the tab that was already highlighted at the bottom. On the test tablet in landscape that is a fifth of the window spent on chrome. It also fixes a real bug for free — the Search screen was a bare `Column` with no bar and no inset padding, so under the edge-to-edge window its text field drew *under* the status-bar icons; with the combined bar above it, the screen is padded like every other. The inset contract from the M3/M4 pass is preserved and extended: the outer `Scaffold` still consumes nothing, the app bar pads itself out of the status bar, and the nav host gets `navigationBarsPadding()` on top-level destinations — the space the bottom bar used to reserve.

## 2026-07-29 — M9: the offline banner becomes a status icon in the app bar
- **Scope:** `:app` (`AppScaffold`, `AppTopBar`, `ConnectionStatusTest`), `:core:ui` (`OfflineBanner` deleted)
- **Plan said:** docs/PLAN.md line 65 — connectivity "drives repository delegation + the single app-wide `OfflineBanner` in `AppScaffold`" — and line 45 lists `OfflineBanner` among `:core:ui`'s components.
- **Done instead:** `OfflineBanner` is deleted. The same three states are reported by one icon in the combined app bar: `WifiOff` (no network), `CloudOff` (server unreachable) and `AirplanemodeActive` (offline mode on), the first two tinted `error` and the last `onSurfaceVariant`, each with its own content description. Tapping it shows the message that used to be the banner's text in a snackbar, carrying the same action the banner offered — *Retry* when the server is unreachable, *Go online* when offline mode is on, and nothing when there is no network to retry. The `ConnectionState` plumbing (`ConnectionStateProvider`, `ConnectionViewModel`, `refresh()`, `setForceOffline()`) is unchanged; `ConnectionState → ConnectionStatus` is now a unit-tested mapping.
- **Reason:** POLISH.md — a permanent full-width strip is a lot of screen for a fact the user usually already knows, and offline is not a transient state in this app: a user on a plane or off the LAN sees it for the whole session. An icon states it continuously in space the bar already occupies, and a tap still gets the full sentence and the fix. Deleting the component rather than leaving it unused keeps `:core:ui` a set of things the app actually draws.

## 2026-07-29 — M9: lazy media lists declare `contentType`, and grid cells size themselves
- **Scope:** `:core:ui` (`MediaRow`, `MediaCardArtwork.cardWidth`, `PosterCard`, `ThumbCard`, `LibraryCard`), `:feature:home`, `:feature:search`, `:feature:library`, `:app` (`JellyfinNativeApplication`)
- **Plan said:** docs/PLAN.md's performance targets ("home first paint < 1s warm", "scrolling stays smooth") and M3's "a >500-item library scrolls clean"; nothing about how.
- **Done instead:** three mechanical changes. (1) Every lazy list that draws media now declares a `contentType` — the home and search `LazyColumn`s per row, `MediaRow`'s `LazyRow` per card (new `contentType` parameter, defaulted so no caller is forced to change), and both grids per cell — so a lazy layout can reuse a scrolled-off node instead of composing a new one. (2) `PosterCard`/`ThumbCard`/`LibraryCard` accept `width = Dp.Unspecified` to fill the space they are given, and the two grids use it; this deletes the `BoxWithConstraints` that wrapped **every** cell, i.e. one subcomposition per visible poster on every scroll. (3) The app now configures the Coil `ImageLoader` it had been leaving on Coil 3's bare defaults: 25 % of the heap as memory cache, a 256 MB disk cache under `cacheDir/image_cache`, and a 150 ms crossfade. Coil 3 gives a hand-built loader no disk cache at all unless asked, so artwork that fell out of memory was being re-fetched from the server on every scroll back.
- **Reason:** POLISH.md, "media list scrolling is not smooth". These are the standard, low-risk wins and none of them touch a ViewModel, a data flow or a fetch policy. Recorded here because the plan's `:core:ui` component contracts (a card takes a fixed `Dp` width) and its silence on image-loader configuration both changed.

<!-- END -->

<!-- BEGIN M9 (offline refresh + download quality) -->

## 2026-07-29 — the refresh signal fires on **both** connectivity edges, and two tests changed with it
- **Scope:** `:core:network` (`connectivity/ReconnectEdges.kt` → `ConnectivityEdges.kt`, `ReconnectEdgesTest` → `ConnectivityEdgesTest`), `:data` (`ReconnectRefresher` → `ConnectivityRefresher`), `:feature:home`, `:feature:library`, `:feature:search`, `:feature:detail`
- **Plan said:** docs/PLAN.md line 66 — `ConnectionStateProvider` "drives repository delegation + the single app-wide `OfflineBanner`". Nothing about how a screen that already fetched learns the connection changed. The M9 entry above ("the reconnect refresh signal drops its initial value") introduced `reconnectEdges()`, which emits **only** on a `false → true` edge.
- **Done instead:** the primitive is now `Flow<ConnectionState>.onlineStateChanges(): Flow<Boolean>` — the new online-ness on **every** change after the initial value, in both directions — and `:data` exposes it as `ConnectivityRefresher.connectivityChanged: Flow<Unit>`. All five ViewModels that collected `reconnected` collect `connectivityChanged` and run the same reload they already ran. `ReconnectEdgesTest`'s `losing the connection emits nothing` and `ReconnectRefresherTest`'s `does not fire when the connection is lost` pinned the one-way behaviour and could not survive the fix; each is replaced by its opposite (`losing the connection emits false` / `fires when the connection is lost`) in the renamed test classes, and every `drop(1)` / flapping property they shared a file with is kept. Net test count goes up, not down.
- **Reason:** the one-way signal *was* the bug. Switching to offline mode (`OFFLINE_FORCED`, from the home overflow toggle) or losing the network left every already-loaded screen showing server rows the app can no longer play — the home screen kept its online *Continue watching* and *Latest* rows next to the offline banner, and only a manual pull-to-refresh corrected it (`DelegatingJellyfinRepository` picks its source per call, so the refetch itself was always right). The `drop(1)` the earlier decision exists to protect is untouched: an ordinary launch still fetches exactly once. Going offline now costs one Room-backed reload per visible screen, which is cheaper than the network reload the online edge already triggered.

## 2026-07-29 — the home screen hides a library card with nothing behind it
- **Scope:** `:feature:home` (`HomeViewModel.fetchRows`)
- **Plan said:** docs/PLAN.md line 72 — Home shows `getUserViews` (MOVIES/TVSHOWS only); offline "rows from Room (resume=downloads w/ position>0; next-up=next downloaded episode per series; latest=recent downloads)".
- **Done instead:** a library is dropped from `HomeUiState.libraries` (the *My Media* cards) when its `getLatestMedia` call **succeeded and returned nothing**. A library whose call *failed* is kept.
- **Reason:** `OfflineJellyfinRepository.getUserViews` returns every cached `library_views` row, downloaded content or not, so offline the *My Media* row offered cards opening onto empty grids. The *Latest* sections below were already filtered exactly this way (`items.isNotEmpty()`), so this only makes the cards agree with the rows. Distinguishing failure from emptiness is what stops one flaky `getLatestMedia` online from making a library vanish from the home screen.

## 2026-07-29 — transcoded downloads ship after all, as a **download quality** setting
- **Scope:** `:core:common` (`model/DownloadQuality`), `:core:datastore` (`AppPreferences.downloadQuality`, `PreferenceKeys.DOWNLOAD_QUALITY`), `:core:database` (`DownloadEntity.quality`, schema v5), `:data:downloads` (`DownloadUrlFactory`, `DownloadFilePlanner`, `DownloadPaths`, `DownloadEnqueuer`, `DownloadQueue`), `:feature:settings`
- **Plan said:** docs/PLAN.md line 7 — "**Not v1:** … transcoded downloads". The download pipeline's file plan (line 79) fetches `/Items/{id}/Download`, "the original file untouched".
- **Done instead:** a `downloadQuality` preference (`ORIGINAL` — the default and the plan's behaviour — plus `HIGH`/`MEDIUM`/`LOW`) chooses between the original file and a server-side transcode requested as `/Videos/{id}/stream.mkv?static=false` with explicit H.264/AAC/Matroska parameters. `ORIGINAL` leaves every existing byte of the pipeline alone.
- **Amended 2026-07-29:** this entry originally said `mp4`, and shipped that way. It was wrong — see *"the transcode container is Matroska, because the mp4 the server muxes is unplayable"* below. Only the container changed; everything else in this entry stands.
- **Reason:** explicit user request, recorded in `docs/POLISH.md` under *Next steps* (2026-07-29). The tablet has 64 GB of usable storage and the library is full of 25–40 Mbps remuxes; downloading a season means choosing between the whole season at a watchable bitrate and two episodes at the source's. The plan's exclusion was a scoping decision about effort, not a judgement that the feature is wrong, and the escape hatch it leaves — "don't preclude" — is honoured: `ORIGINAL` is still the default, still the only path the plan describes, and the transcode is one branch inside `DownloadFilePlanner.media()`.

## 2026-07-29 — the chosen quality is stored **on the download row**, not read from the preference at run time
- **Scope:** `:core:database` (`DownloadEntity.quality`, `DatabaseConstants.DATABASE_VERSION` 4 → 5, `AutoMigration(4, 5)`, `DownloadQualityConverter`), `:data:downloads` (`DownloadEnqueuer`, `DownloadQueue.reconcile`)
- **Plan said:** docs/PLAN.md, "Download pipeline" → Enqueue lists the row's columns; quality is not among them, because transcoded downloads were out of scope.
- **Done instead:** `DownloadEnqueuer` reads `AppPreferences.downloadQuality` **once**, when the user taps *Download*, and stamps it onto the `downloads` row. `DownloadQueue.reconcile` builds every later file plan from `download.quality`, never from the live preference. Schema v5 adds one column with `defaultValue = "ORIGINAL"`, so the bump stays a Room `@AutoMigration` and an existing install keeps its queue.
- **Reason:** correctness, not tidiness. `reconcile` deliberately rebuilds the URL on every run (the server's base address rotates between LAN and remote), and the partial file on disk *is* the resume bookmark. A user who changes the setting while a transcoded download is half-finished would otherwise have its next run resume `/Items/{id}/Download` with `Range: bytes=N-`; that endpoint honours ranges, so original bytes would be appended to transcoded ones and the item would be marked `DOWNLOADED` as a silently corrupt file. Stamping the row makes the quality as immutable as the file name already is, for the same reason and with the same rule: Room holds the file plan, Room wins.

## 2026-07-29 — a transcoded download is not resumable, and its size is an estimate
- **Scope:** `:data:downloads` (`DownloadEnqueuer.toDownloadRow`, `DownloadQueue.ItemProgress`), `docs/features/download-quality.md`
- **Plan said:** docs/PLAN.md, "Download pipeline" → Progress/Resume: byte-level progress from `Content-Length`, and a `Range` request that "picks up exactly where a killed process left off" — the M7 definition of done measures it.
- **Done instead:** for a non-`ORIGINAL` download the server transcodes on the fly, so the response is chunked (no `Content-Length`) and `Range` is ignored. The row's `bytesTotal` is seeded from `runTimeTicks × (videoBitRate + audioBitRate)` at enqueue time and used as a **floor** by `ItemProgress` for as long as any file's real size is unknown, so the queue tab and the notification show an approximate percentage instead of an indeterminate bar. An interrupted transcode restarts from zero: `FileDownloader` already truncates and rewrites when a server answers a `Range` request with `200`, so the failure mode is a repeated transfer, never a corrupt file.
- **Reason:** the server cannot know the length of a file it has not finished encoding, and it cannot seek into one either. The alternatives were an HLS download (many files, a manifest to rewrite, and a plan-level departure far larger than this one) or no feature at all. `ORIGINAL` — the default — keeps the exact `Content-Length` and byte-exact resume the milestone's DoD measures, so the guarantee is not lost, it is a property of a quality the user opts out of.

## 2026-07-29 — the transcode container is **Matroska**, because the mp4 the server muxes is unplayable
- **Scope:** `:core:common` (`model/DownloadQuality.CONTAINER`), `:data:downloads` (`DownloadUrlFactory.transcodedVideoUrl`, `DownloadFilePlanner.media`, `DownloadPaths.mediaFileName`), `docs/features/download-quality.md`, `DownloadPathsTest`, `DownloadFilePlannerTest`, `DownloadQueueTest`
- **Plan said:** nothing — the plan excludes transcoded downloads entirely. The two entries above are what this amends: they specify `mp4`, and that is what shipped.
- **Done instead:** `DownloadQuality.CONTAINER` is `mkv`. The transcode is requested as `/Videos/{id}/stream.mkv?static=false` with the identical codec parameters (h264 / aac / bitrate / maxHeight / 2 channels / `EncodingContext.STATIC`), and a transcoded file lands as `<directory> (<quality>).mkv`. `ORIGINAL` is untouched, byte for byte: it still fetches `/Items/{id}/Download` and still keeps the server's own filename and extension.
- **Reason:** the mp4 was not a worse choice, it was a broken one. Verified on the test tablet: a `LOW` download produced `ftyp → free → mdat(size 0, "extends to end of file")` with the `moov` appended at the tail — the shape ffmpeg emits when it must mux mp4 without being able to seek back and patch the header, which is the only shape available to a server producing the file as it sends it. Media3's `Mp4Extractor` resolves a zero-sized `mdat` as running to EOF, which swallows the trailing `moov`, so it never finds the index and fails the load outright with `ParserException: Loading finished before preparation is complete, contentIsMalformed=true`. Offline playback of a transcoded download failed; online it silently fell through to server streaming, which is why it survived the first pass. A file that cannot be played is worthless for the one thing a download is for.
  Matroska has no such constraint — every element carries its own size as it is written, which is why it is the basis of WebM and of every live-streamed mkv — so the bytes are valid at every prefix and complete when the transfer ends. Media3 ships a full `MatroskaExtractor`, `mkv` is already in this app's own `DeviceProfileBuilder.SUPPORTED_CONTAINER_FORMATS` with h264 among its codecs, and jellyfin-android's device profile likewise offers `mkv` as a transcoding container — so this is a container both the server and the device were always going to agree on. MPEG-TS (`.ts`) would also have been progressively valid; it was not chosen because it carries no duration metadata, costs ~4 % in packetisation overhead, and is a worse file for the user who plugs the tablet into a computer.
- **Tests:** `DownloadPathsTest`'s *"a transcoded download is named for the mp4 it will actually receive"* becomes *"…for the container it will actually receive"* and now expects `.mkv`; `DownloadFilePlannerTest`'s transcoded-file-name test expects `.mkv` too. Neither is a weakened test — the assertion they made was the bug, and both are joined by a new `a transcoded download is never named mp4`, which pins the whole ladder against a regression and records the `contentIsMalformed` failure as its reason. `DownloadQueueTest`'s stub URLs follow for readability only.

## 2026-07-29 — Download on a season or a series downloads its **episodes**
- **Scope:** `:data:downloads` (`DownloadEnqueuer`, `DownloadApi`/`SdkDownloadApi`, new `FolderItems.kt`, `DownloadFilePlanner` + new `NotDownloadableException`, `DownloadErrorCopy`, `DownloadRepository` docs), `:feature:detail` (`ItemDetailUiState` — new `aggregateDownloadState`, `isDownloadContainer`, `downloadTargets`; `ItemDetailViewModel`), `docs/features/downloads.md`
- **Plan said:** docs/PLAN.md line 74 puts a *Download* button on the "ItemDetail (Movie/Series/Season)" screen **without saying what it downloads**, while lines 83–84 define enqueue as one `DownloadEntity` per item whose file plan fetches `libraryApi.getDownloadUrl(itemId)`. Read together they say "a season enqueues the season", which is what shipped.
- **Done instead:** the ambiguity is resolved as **containers expand**. `DownloadEnqueuer.enqueue` now returns `AppResult<List<DownloadEntity>>` and, when the item it re-fetched is a folder, replaces it with the episodes underneath: a `SEASON` becomes every episode of that season, a `SERIES` every episode of the show (one `tvShowsApi.getEpisodes(seriesId, seasonId?)` request for the ids, then the ordinary full re-fetch), each row stamped exactly as a direct tap on that episode would have stamped it — same fields, same paths, one download-quality read for the whole expansion. Episodes that already have a row are skipped unless it is `ERROR`, which is re-enqueued keeping its queue position and the bytes on disk. The container's own row, if one exists, is removed through the ordinary delete cascade. A folder that cannot be expanded (box set, library) is refused rather than queued. `DownloadFilePlanner` additionally throws `NotDownloadableException` for any folder (`isFolderItem` = the server's own `isFolder`, with a kind list as the fallback) *before* it builds a URL, and `DownloadErrorCopy` renders it as *"This is a show or a season, not a single video. Remove it and download the episodes."* On the detail page a season's button state is the aggregate of its episodes' states (`aggregateDownloadState`), and remove/cancel act on the episode rows rather than on the season id.
- **Reason:** user-reported bug (docs/POLISH.md): *"downloading a season fails with 'The server couldn't send this download (error 400)'"*. `/Items/{seasonId}/Download` hands a folder id to a file endpoint; the server answers `400`, `DownloadQueue` only retries `403`, so the row landed in `ERROR` for good — and, being keyed on the season, no retry could ever have worked. Expanding inside the downloads domain rather than in `ItemDetailViewModel` is deliberate: the ViewModel is one of several possible callers, a type check there would have to be repeated by each future one, and `enqueue(itemId)` is the only door into the pipeline. The other reading of the plan — a Download button that refuses containers outright — was rejected because "download this season" plainly means its episodes, and a season with no file of its own has nothing else it could mean.
- **Left out:** the **series** page's button has no aggregate state. That page loads seasons and next-up, not episodes, so there is nothing on it to aggregate, and giving it one would mean an extra episode fetch per series page. Its tap always enqueues, which is idempotent — expansion skips every episode already on the device — but it never reads *Downloaded*, and season cards on a series page carry no badge (they never did: a season has no row of its own). Deleting a whole series from its own page is likewise not offered; the season pages and the Downloads tab both do it.
- **Tests:** no test was weakened. `DownloadEnqueuerTest` keeps every existing case (its `row` accessor now reads the single captured row of a list) and gains twelve for expansion — order, dedupe, `ERROR` retry, the container-row cleanup, one quality for the whole season, the cache write, the empty and unexpandable containers, and a failing listing writing nothing. `DownloadFilePlannerTest` gains the folder guard (and a case pinning that "no media source" is *not* the same question), `DownloadErrorCopyTest` the honest copy, `DownloadRepositoryImplTest` a multi-row enqueue, `ItemDetailViewModelTest` seven container cases, and `aggregateDownloadState` gets its own test class.

<!-- END -->

<!-- BEGIN downloaded-metadata sync -->

## 2026-07-29 — downloaded items' cached metadata is kept current by a standing sync, not only at enqueue time
- **Scope:** `:data:downloads` (new `DownloadedMetadataRefresher` + `DownloadedMetadataRefresherTest`), `:app` (`JellyfinNativeApplication`), `docs/features/offline-read.md`, `docs/features/downloads.md`
- **Plan said:** nothing about a download's metadata after it is downloaded. docs/PLAN.md line 57 defines `ItemEntity` as structured columns plus the full `BaseItemDto` blob with `source: BROWSE_CACHE|DOWNLOAD` ("DOWNLOAD rows never evicted"); the download pipeline (lines 83–86) writes that row **once**, at enqueue, and the only lifecycle event it then specifies is the delete cascade. The plan's one refreshing write is the browse-cache path, which the M6 offline-read rule explicitly forbids from touching a download's row. Read literally, a download's metadata is written once and is correct forever.
- **Done instead:** a new `@Singleton` `DownloadedMetadataRefresher` in `:data:downloads`, started from `JellyfinNativeApplication.onCreate` beside `UserDataSyncTrigger`. Once per stretch of connectivity it reads `DownloadDao.allItemIds()`, fetches the full `DOWNLOAD_FIELDS` DTOs via `DownloadApi.getFullItems` (chunked at 50), fetches the series/season parents of what came back, and upserts the lot straight to `ItemDao` with `source = DOWNLOAD` — the same write `DownloadEnqueuer` performs for a fresh download, and deliberately **not** through `BrowseCacheWriter`. It borrows `UserDataSyncTrigger`'s trigger shape exactly (collect `ConnectionStateProvider.state`, map to online-ness, `distinctUntilChanged`, act on every `true` **including** the initial value), so one code path serves both "started online" and "came back online". Every failure is swallowed and logged; the next offline → online edge retries.
- **Reason:** "written once and correct forever" is false the moment anyone edits the library. A retitled film, an identify/refresh pass that replaces the artwork tags, a corrected overview, a renumbered episode, a renamed show — none of it ever reaches a downloaded item, so the offline library drifts permanently away from the library it is a copy of. The plan simply does not cover the case; this fills the gap rather than contradicting it, and it costs one batched request per online stretch. The immediate trigger was the lean-write bug (an older build let a browse-list DTO replace the rich blob, leaving downloaded films with a blank offline detail page): `OnlineJellyfinRepository.getItem` with `full = true` already repairs such a row, but only the one the user happens to open, so a device that upgraded across the bug would need a manual visit per download. The first pass of this sync heals all of them at once — which is a **side effect**, not the purpose, and the KDoc and both feature docs say so in as many words so that a future reader who knows the bug is fixed does not delete the class as spent migration code.
- **Two deliberate departures from `DownloadEnqueuer`, whose write this otherwise mirrors:**
  - `cachedAt` is **preserved** for a row that already exists, and stamped `now` only for a row this pass creates (a parent never cached). `cachedAt` is the offline "recently downloaded" ordering key, so re-stamping the whole table would reshuffle the offline home into refresh order on every sync. The enqueuer writes `now` because for a fresh download `now` genuinely is the download time.
  - The fetch is chunked at 50 ids. The enqueuer never needs it (it fetches one item, or one season's episodes); here the ids travel in one `getItems` query string, and a few hundred downloaded episodes would build a URL a reverse proxy may reject.
- **Tests:** 20 new cases in `DownloadedMetadataRefresherTest`, no existing test touched or weakened — when it fires (app start online, offline → online, never while offline, no fire between two offline reasons, once per stretch however often asked, re-armed by losing the connection, no API call and no session restore with an empty downloads table); what it writes (`source = DOWNLOAD` never `BROWSE_CACHE`, an episode's series and season alongside it, a parent that is itself downloaded not fetched twice, `cachedAt` preserved for an existing row and stamped for a new one, three batches for 120 ids); what it survives (a failing fetch, one failing batch of several, an item the server no longer knows, a failing parent fetch, an unrestorable session, an unreadable downloads table, a failing write).

<!-- END -->

<!-- BEGIN scroll performance, round 2 -->

## 2026-07-29 — the grid "lag" is the **debug build**, measured: 4.2 % janky vs 0.5 % in release
- **Scope:** measurement only — no production behaviour changed by this entry. `app/build.gradle.kts` (release `signingConfig`).
- **Plan said:** docs/PLAN.md's performance targets ("scrolling stays smooth", M3's ">500-item library scrolls clean") and M10 "release hardening", which owns R8, baseline profiles and real signing.
- **Done instead:** the release build type now sets `signingConfig = signingConfigs.getByName("debug")`, so a release-mode APK can be installed on the test tablet and profiled. This is a **local measurement aid**, not release signing — M10 still owns that — and `isMinifyEnabled` is still `false`, so what was measured is the debuggable-vs-not difference alone, with R8 out of the picture.
- **Reason:** round 1 (`contentType`, no per-cell `BoxWithConstraints`, a configured Coil `ImageLoader`) landed and the gallery was still reported laggy — on a debug build, which is the only build that had ever been installed on the tablet. A debuggable app runs with ART's optimisations restricted, and Compose is exactly the allocation- and megamorphic-call-heavy code that pays for that. The hypothesis had to be confirmed or ruled out before spending more effort on the image pipeline. It was confirmed, decisively.
- **Method:** test tablet, portrait, 1600×2560 @ 2.25x, **90 Hz** (11.1 ms budget). Films library, ~500 posters, `GridCells.Adaptive(110.dp)` → 5 columns of 126dp (285 px). Per run: warm the disk cache with a full pass, force-stop (so the memory cache is cold — the case the user feels), reopen the grid, `dumpsys gfxinfo <pkg> reset`, 16 one-way fling-downs 1.4 s apart, `dumpsys gfxinfo <pkg>`. Arrival on the grid is asserted with `uiautomator dump` before measuring. Two traps worth recording: `monkey` injects a rotation event and **unlocks auto-rotate**, which silently rotated the tablet to landscape and put the swipe coordinates off-screen (use `am start`); and janky-**percentage** is not comparable across runs whose total frame count differs, so absolute *Frame deadline missed* counts are quoted next to it.
- **Numbers** (16 flings; janky % / 50th / 90th / 95th / 99th / deadline misses / Slow UI thread):

  | build | janky | 50th | 90th | 95th | 99th | missed | slow UI |
  |---|---|---|---|---|---|---|---|
  | debug, round 1 (run A) | 4.09 % | 13 ms | 32 ms | 93 ms | 109 ms | 33 | 30 |
  | debug, round 1 (run B) | 4.25 % | 12 ms | 30 ms | 89 ms | 121 ms | 40 | 36 |
  | **release**, round 1 | **0.49 %** | **7 ms** | **9 ms** | **10 ms** | **14 ms** | **9** | **0** |
  | debug, round 2 (3 runs) | 3.4–4.7 % | 10–13 ms | 23–36 ms | 77–89 ms | 121–150 ms | 35–38 | 30–38 |
  | **release**, round 2 | **0.49 %** | **7 ms** | **9 ms** | **10 ms** | **14 ms** | **9** | **0** |

  GPU time is 5–9 ms in every run and *Slow bitmap uploads* is 0 in every run: the frame cost is on the **UI thread**, and it is ~8× larger when the app is debuggable. The release figures are reproducible to the frame (three runs: 0.49 %, 0.48 %, 0.49 %; nine missed deadlines each). Release already meets the plan's target on the same content, device and server — **before** R8 and before baseline profiles, both still M10's to add. The practical consequence: perceived scroll quality must be judged on a release-mode build, and a debug install is not evidence of a performance bug.

## 2026-07-29 — artwork is requested at the size it is drawn, from one dp knob per surface
- **Scope:** `:data` (new `mapper/ArtworkRequestWidths.kt` + `ArtworkRequestWidthsTest`, `mapper/ImageUrlFactory` — the three `*_MAX_WIDTH` constants are gone, `mapper/ItemMapper`, `cache/ItemEntityMapper`, `di/DataModule`)
- **Plan said:** nothing about image request widths. `ImageUrlFactory` carried three hard-coded pixel constants — `POSTER_MAX_WIDTH = 400`, `THUMB_MAX_WIDTH = 640`, `BACKDROP_MAX_WIDTH = 1280` — a one-size-fits-all guess.
- **Done instead:** each surface declares the widest width **in dp** it ever draws that artwork at (`POSTER_DP = 128`, `THUMB_DP = 224`, `BACKDROP_DP = 512`); `ArtworkRequestWidths.forDensity()` multiplies by the display density and snaps up to a bucket. Hilt resolves it once from the real display (`DataModule.provideArtworkRequestWidths`); the two mappers take it as a constructor parameter defaulted to a 2.0x baseline, so unit tests still build a mapper without a display. On the test tablet posters now come back **320×480 instead of 400×600** (verified by reading Coil's disk cache off the device) — 36 % fewer pixels on the wire, in the disk cache, and through the decoder on every memory-cache miss. On a 3.0x phone the same knob asks for 480, which is *more* than the old constant: 400 was under-serving dense phones while over-serving this tablet, which is what a fixed pixel constant does.
- **Reason:** a user question, and the right one — Coil's disk cache holds the bytes the *server* sent, so every memory-cache miss re-decodes and rescales them. Requesting the display size makes the cached bytes *be* the display-resolution thumbnail, with nothing downscaled on the way to the screen. Bucketing rather than an exact pixel width keeps the URL stable across densities and app versions, which is what stops the server's resized-image cache and Coil's disk cache from being fragmented or needlessly invalidated.
- **Honest result:** this did **not** measurably improve scroll jank — round 2's debug runs sit inside round 1's run-to-run spread (see the table above). Decode runs on `Dispatchers.IO`, and the frame data says the UI thread, not the decoder, is what misses deadlines. It is kept because it is strictly less work — fewer bytes fetched, cached and decoded — and because it replaces a guess with a derivation, not because it bought frames.
- **Not done, and why:** (1) **A decoded-bitmap cache on disk.** Raw bitmaps are ~485 KB per poster against ~40 KB of JPEG, and reading one back is not obviously cheaper than decoding; right-sizing the JPEG reaches the same end without a second cache to keep coherent. (2) **Explicit `SizeResolver`/`.size()` on requests.** Verified unnecessary: every `JellyfinAsyncImage` call site passes bounded, tight constraints (`.size()`, `.fillMaxWidth().aspectRatio()`, or `.fillMaxWidth().height()`), so Coil's `ConstraintsSizeResolver` already resolves an exact pixel size and nothing falls back to source size. (3) **Suppressing crossfade on cache hits.** Already Coil 3.4's behaviour — `CrossfadeTransition.Factory.create` returns `NONE` when `dataSource == MEMORY_CACHE`, and the Compose path goes through the same factory. Measured with crossfade removed entirely anyway: absolute deadline misses got *worse* (49 vs 35), and only the percentage looked better because there were half as many frames to divide by. Crossfade stays at 150 ms. (4) **A wider grid prefetch window.** `DefaultLazyGridPrefetchStrategy` already composes the next line ahead, which starts its image requests a row early; the opt-in `LazyGridCacheWindowPrefetchStrategy` is experimental and would compose *more* cells per frame on the UI thread — the one resource the frame data shows saturated. No evidence asked for it. (5) **Baseline profiles and R8** — M10's, per the plan, and the release numbers say they are not needed to hit the target.
- **Left alone:** `:data:downloads` keeps its own `PRIMARY_IMAGE_WIDTH` / `BACKDROP_IMAGE_WIDTH` / `SERIES_IMAGE_WIDTH`. Artwork saved beside a download is written once and has to outlive the screen it was downloaded on, so it stays sized generously and independently of the display currently attached.

<!-- END -->

<!-- BEGIN reachability re-probe on session change -->

## 2026-07-29 — the reachability probe re-runs on every session change, not only on network changes
- **Scope:** `:core:network` (`connectivity/ConnectionStateProvider` + `ConnectionStateProviderTest`)
- **Plan said:** docs/PLAN.md line 65 enumerates the probe's triggers: `ServerReachabilityProbe` (3s `getPublicSystemInfo` on **network change / app resume / reported failure**; rotates `ServerAddressEntity` candidates)". Session changes are not on that list.
- **Done instead:** `ConnectionStateProvider` now also collects `SessionStateHolder.state`, drops the launch-time `Unknown`, maps each session to its identity (`serverId` + `userId`, `null` once signed out), applies `distinctUntilChanged`, and calls the existing `refresh()` on every distinct identity. No new type, no new trigger mechanism — it drops a token in the same conflated probe channel as the other three triggers, so the `PROBE_DEBOUNCE_MS` spacing and the single-consumer guarantee are untouched.
- **Reason:** a bug reproduced on the tablet: **on a fresh install the first sign-in left the app claiming it could not reach the server until the process was restarted.** The plan's three triggers are all *external* events, but the probe's **address source is the session** — `ServerReachabilityProbe.candidateAddresses()` reads `SessionStateHolder.state.value` and returns an empty list when nobody is signed in, which is a deliberate `false` ("no server to be reachable"). At launch the network-available probe therefore correctly logged `No server address to probe` and set `OFFLINE_SERVER_UNREACHABLE`; the user then signed in successfully, and because signing in is neither a network change nor an app resume nor a failed request, nothing re-asked. The stale verdict routed `DelegatingJellyfinRepository` offline for the whole app run. Restarting fixed it because `SessionRepository.restoreSession()` runs before the launch probe, which is why every device walk with pre-existing credentials missed it. The plan's trigger list is an omission rather than a constraint: an input to the probe changed, so the probe has to re-run.
- **Why here rather than in the sign-in path:** `SessionStateHolder` is the one cell both `AuthRepository` (sign-in) and `SessionRepository` (restore, sign-out) write to and it already lives in `:core:network`, so observing it covers all three transitions at one site with no new dependency and no way for a future session writer to forget the call. Wiring `refresh()` into `AuthRepository.completeAuthentication` instead would have fixed only sign-in and left restore and sign-out to be remembered separately.
- **Deliberate consequences:** (1) signing **out** re-probes too, so the state says "no server" instead of the last session's verdict; (2) a normal launch with a stored session now costs a second probe (one for the network, one for the restored session) — the debounce spaces them, and it is exactly the probe that makes a restored session's address get tried; (3) the offline→online edge the fix produces is *wanted*: `ConnectivityRefresher`, `UserDataSyncTrigger` and `DownloadedMetadataRefresher` all watch `ConnectionStateProvider.state`, so the screens fetch as soon as sign-in lands. There is no loop — a probe never writes session state, and identical sessions are collapsed before they reach `refresh()`.
- **Tests:** 5 new cases in `ConnectionStateProviderTest` (12 → 17), none of the existing 12 touched or weakened — the fresh-install sequence end to end (unreachable with no session → session appears → ONLINE, with the network never moving), no probe for the launch `Unknown`, a re-probe on sign-out, no probe when the same session is republished with only a new server version, and one probe for a burst of eight same-identity emissions.

<!-- END -->

<!-- BEGIN transcode size estimate uses the source bitrate -->

## 2026-07-29 — the transcode size estimate uses the source bitrate when it is under the cap
- **Scope:** `:data:downloads` (`DownloadEnqueuer.expectedBytes`, `DownloadEnqueuerTest`, `DownloadFixtures`)
- **Plan said:** nothing about size estimates — the formula being amended is this log's own, from *"a transcoded download is not resumable, and its size is an estimate"* above: `bytesTotal` seeded from `runTimeTicks × (videoBitRate + audioBitRate)`, i.e. runtime × the quality tier's **cap**.
- **Done instead:** the bitrate in that product is now the *effective* one: `min(cap, mediaSources[0].bitrate)` when the source bitrate is known and positive, the cap alone when it is missing or zero. `ORIGINAL` still returns the server's exact file size, and the no-runtime fallback is unchanged.
- **Reason:** the cap is a ceiling the server is told not to exceed, not a prediction — most sources (HEVC especially) sit well under it, and a transcode can't need more bits per second than the source carries for the whole runtime. Measured on the final polish walk (docs/POLISH.md): a LOW episode estimated **552 MB** and landed at **232 MB**. `min(cap, source)` keeps the estimate a deterministic upper bound — no empirical fudge factor — while collapsing the error whenever the source rate is the binding constraint. The estimate can still overshoot when the encoder undershoots the cap on easy content; that residual is inherent to estimating a file that does not exist yet.
- **Tests:** the pre-existing *"a transcoded download is sized from its runtime and bitrate instead"* now pins its fixture's source bitrate **above** the cap, so it still guards the cap-wins branch rather than passing vacuously; joined by *"a transcoded download of a source under the cap is sized from the source bitrate"* and *"a transcoded download with no source bitrate falls back to the quality cap"*. `DownloadFixtures.mediaSource`/`movie` gained a `bitrate`/`sourceBitRate` parameter defaulting to `null`, so every existing fixture call is untouched.

<!-- END -->

<!-- BEGIN cancel keeps finished episodes -->

## 2026-07-29 — Cancel on a season keeps the episodes that already finished
- **Scope:** `:feature:detail` (`ItemDetailViewModel`, `ItemDetailUiState.UserMessage`, `ItemDetailScreen`, `strings.xml`, `ItemDetailViewModelTest`)
- **Plan said:** docs/PLAN.md line 76 gives the queue tab "progress %, speed, pause/resume/**cancel**, reorder" — cancel exists, but the plan predates container downloads (*"Download on a season or a series downloads its episodes"*, above) and says nothing about what cancelling the container does to episodes that already completed.
- **Done instead:** the detail screen's Cancel on an in-flight container now partitions the season's rows and deletes only the ones that are queued, transferring, paused or failed — `DownloadState.Downloaded` rows are kept — and a snackbar says so ("Download cancelled — N finished episode(s) kept", a plural resource). The confirmed **Remove** path is untouched: it still deletes every row, completed included. Single-item cancel is unaffected.
- **Reason:** observed on the final polish walk (docs/POLISH.md): cancelling a season three episodes in silently destroyed those three finished, playable files. The punch list asked whether cancel should *confirm*; keeping the finished episodes is strictly better than warning about losing them — Cancel stays immediate (consistent with the queue tab's deliberately unconfirmed cancel) and stops being destructive at all. The only thing a dialog could have protected is now simply not at risk.
- **Deliberate consequence:** a partly-kept season aggregates back to *NotDownloaded* — that is the pre-existing, test-pinned behaviour (*"a season with only some episodes downloaded still offers to download the rest"*) — so the detail button then offers **Download** for the missing episodes rather than Remove. Removing the kept episodes goes through the Downloads screen's confirmed delete. The aggregate was left alone precisely because changing it would have weakened that test.
- **Also:** `UserMessage` went from `enum class` to `sealed interface` because the new message carries the kept count for the plural (precedent: `:feature:auth`'s `AuthErrorMessage`); all other messages became `data object`s and no call site changed shape. `:feature:downloads`' `DownloadsMessage` stays an enum — it has no count-carrying message.
- **Tests:** *"cancelling a queued season cancels only the episodes that have rows"* gained an assertion (message is plain `DownloadDeleted` when nothing was kept) and lost none; new cases cover the partial cancel keeping the finished episode and emitting the counted message, the post-cancel season offering to download the rest, and a confirmed delete still removing what a cancel would have kept.

<!-- END -->

<!-- BEGIN portrait banner height is viewport-proportional -->

## 2026-07-29 — the portrait detail banner is a share of the viewport height
- **Scope:** `:feature:detail` (`ItemDetailScreen`: `backdropHeight()`, `PORTRAIT_BACKDROP_FRACTION`, `MAX_BACKDROP_HEIGHT`)
- **Plan said:** docs/PLAN.md line 45 lists `BackdropHeader` as a `:core:ui` component with no sizing rule; the sizes were implementation constants (220dp, 320dp above the 720dp width breakpoint).
- **Done instead:** in portrait (`maxHeight > maxWidth`) the banner is `0.40 × maxHeight`, coerced between the old width-derived value (as the floor) and 560dp (so the facts and Play button stay on the first screenful). Landscape keeps the width-based constants byte for byte. `BackdropHeader` itself is untouched — the screen just passes a computed height.
- **Reason:** the width-only breakpoint misfired on the test tablet: at ~753dp wide in portrait it took the "wide" 320dp branch on a ~1200dp-tall screen, stranding the artwork at the top with dead space below (docs/POLISH.md). A height share scales with the actual viewport: ~480dp on the tablet in portrait, ~320dp instead of 220dp on a ~360×800dp phone (the same fix, proportionally), and unchanged in landscape where vertical space is scarce.

<!-- END -->

<!-- BEGIN device id is app-generated, not the SDK's ANDROID_ID default -->

## 2026-07-29 — the device id is an app-generated UUID, not the SDK's ANDROID_ID default
- **Scope:** `:core:datastore` (new `DeviceIdStore` / `SharedPreferencesDeviceIdStore` / `DeviceIdProvider` + `DeviceIdProviderTest`, `PreferenceKeys`, `DatastoreModule`), `:core:network` (`ApiClientProvider`), `docs/features/auth.md`
- **Plan said:** nothing about device-id derivation — M1 (docs/PLAN.md line 100) covers discovery, login, token storage and session restore only. The SDK default was used implicitly: `createJellyfin { context = … }` falls back to `androidDevice(context)`, which is literally `Settings.Secure.ANDROID_ID` (confirmed by decompiling `jellyfin-core-android` 1.8.12).
- **Done instead:** the client is built with an explicit `DeviceInfo(id = DeviceIdProvider.deviceId, name = androidDevice(context).name)`. The id is a random UUID generated once per installation and persisted in a plain `SharedPreferences` file of its own (`device_identity`; written with `commit()` — losing the once-per-install write to a crash would present a new device on the next launch). It is deliberately not encrypted (it travels in every request's `Authorization` header and is shown verbatim in Dashboard → Devices) and deliberately not cleared on sign-out (it is identity, not a credential; keeping it re-uses the same Devices row).
- **Reason:** the recurring "Your session expired" bug. A Jellyfin server keeps **one access token per (user, device id)** — signing in with an already-registered device id revokes that id's previous token. Since Android 8 the SSAID is scoped per *signing key*, not per package, and the release profiling variant (debug-signed by design, DECISIONS.md 2026-07-29) is signed with the same key as the debug app — `dumpsys package` shows identical `signatures=[4daaf536]` for both installs. So both presented the same device id, and the release variant's first sign-in (installed 11:32 on the 29th) revoked the debug app's token (last written 17:59 on the 28th): every authenticated call 401'd until re-login, and each re-login on either install would have killed the other again.
- **Deliberately not done:** seeding the new store with the ANDROID_ID-derived id when a session already exists (the "keep existing installs signed in" migration). Both installs on the tablet have a stored session, so both would seed the *same* id and the collision would survive the fix permanently — the heuristic cannot tell which install "owns" the old id. It would also have needed a synchronous `EncryptedSecureCredentialStore` read inside a singleton constructor that `SessionRepository` itself depends on, and the debug session it would preserve is already dead. Cost accepted: **one re-sign-in per install** after this upgrade (the id changes, so the server sees a new device).
- **Follow-up (report only, not built):** there is still no 401-driven logout — a dead token leaves the app `LoggedIn` with per-screen error copy, and the only escape is Settings → Sign out. A later change should debounce N consecutive `Unauthorized` results on authenticated calls into `SessionRepository.signOut()` (never on the login endpoints, never while offline), letting the existing nav redirect to ServerSetup take over.

<!-- END -->

<!-- BEGIN home softens "patch, never refetch" to a debounced membership refresh -->

## 2026-07-29 — Home softens "patch, never refetch" to a debounced membership refresh
- **Scope:** `:feature:home` (`HomeViewModel`, `HomeUiState`), `:data` (`ConnectivityRefresher.isOnline`)
- **Plan said:** "`UserDataRepositoryImpl` — **local-first always**: upsert Room (`toBeSynced=true`) → emit on `UserDataEventBus` (SharedFlow; every list ViewModel patches in-memory items instantly, Swiftfin pattern)" (docs/PLAN.md, "Data layer"), with M4's DoD "home row patches without refetch".
- **Done instead:** The instant patch stays and is now the only thing the user waits for, but home adds two things on top. (a) `withUserData` treats *Continue watching* / *Next up* as rows of unfinished items: a change that says `played` evicts the item rather than patching it. (b) A change whose `played` flipped — or one for an item no row shows — queues a debounced (1.5 s), silent re-fetch of `getResumeItems()` + `getNextUp()` only: online only, no spinner, no `isRefreshing`, no error state, *Latest* untouched. Local values published this session are re-applied on top of the fetched rows and cleared by any full load. Position-only writes deliberately do not trigger it. `ConnectivityRefresher` gained `isOnline` so a feature module can ask without depending on `:core:network`.
- **Reason:** A patch can only rewrite a card already on screen under that exact id, so three user-visible cases were unfixable by it: a watched movie stayed in *Continue watching*, *Next up* never advanced to the next episode, and *Mark watched* on a series/season publishes the container's id, which matches no episode card (docs/POLISH.md, "New run"). Row *membership* is a server-side question; the debounce keeps "mark a season watched" at one pair of requests, and re-applying the local values keeps the read from overtaking its own write (`StaleUserDataRegressionTest`'s rule). Position is excluded because `PlaybackReporter` writes every five seconds, which would turn the debounce into a poll.
- **Tests:** one existing test re-pointed, not weakened — `patches a loaded row when user data changes elsewhere, without refetching` now uses a favourite change (identical assertions plus `getNextUp` exactly once), because a played change now legitimately evicts and refreshes; the played behaviour is pinned by four stricter new tests (same-frame eviction with zero requests, *Next up* advancing without re-fetching views/latest, container-id refresh, 5-toggle burst → one refresh, offline evicts without fetching, stale server rows not resurrecting local state, pull-to-refresh taking the server's answer, silent-refresh failure leaving rows untouched, position reports never refetching).

<!-- END -->

<!-- BEGIN the storage location picker ships now, backed by secondary volumes -->

## 2026-07-29 — M9 polish: the storage location picker ships now, backed by secondary volumes
- **Scope:** `:data:downloads` (`storage/StorageLocationManager`, `storage/StorageVolumeProvider`, `FileDownloadStorage`, `DownloadRepository`), `:core:datastore` (`AppPreferences`, `PreferenceKeys`), `:feature:settings`
- **Plan said:** docs/PLAN.md line 87 — "**Storage:** default `getExternalFilesDir(null)/downloads` [D]; optional SAF tree or secondary `getExternalFilesDirs` volume (SD). `DownloadStorage` interface hides File vs DocumentFile. v1: location change only when no downloads exist (or \"delete all and switch\") [D]; `MoveStorageWorker` deferred." Line 77 lists a "storage location picker" in Settings. This supersedes the reasoning of DECISIONS.md 2026-07-29, *"M9: the storage location picker does not ship with the settings screen"*, which deferred the picker until SAF existed.
- **Done instead:** the picker ships, backed by the plan's **secondary-volume** route only. `StorageLocationManager` (the class docs/PLAN.md:50 names) resolves the downloads root from `context.getExternalFilesDirs(null)`; the chosen volume is persisted as a stable token (volume UUID, or `"primary"`) under the renamed key `download_storage_volume` (was the unread `download_storage_uri`), and an unmounted choice falls back to the primary volume with the fallback surfaced in Settings. `DownloadStorage`'s interface is unchanged and every path stays `java.io.File`. The plan's v1 policy is enforced in `DownloadRepository.setStorageLocation`: unknown volume → failure; downloads exist and the caller did not agree to lose them → failure; agreement → stop the queue, run the delete cascade for every row, then move the root. SAF/arbitrary-tree picking and `MoveStorageWorker` remain deferred.
- **Reason:** the user asked for a configurable path (SD card) directly (docs/POLISH.md, "New run"). The earlier entry assumed a picker needs SAF, which is not true of the plan's other route: app-specific directories on a secondary volume need no runtime permission, no persisted URI grant and no `DocumentFile`, so the whole pipeline — planner, queue, downloader, delete cascade, offline playback — keeps working unchanged, and the app-private wipe-on-uninstall property holds on the card too. Deferring it further would have blocked a real user need on work (SAF) that buys nothing for the SD-card case. The delete-all guard is not caution: `DownloadFileEntity.path` is absolute and is only re-resolved on (re-)enqueue, so files left on the old volume would keep playing until the card came out and then silently fall back to streaming.

<!-- END -->

<!-- BEGIN home renders the server-configured section layout -->

## 2026-07-29 — Home renders the section layout configured in jellyfin-web, not a hardcoded one
- **Scope:** `:core:common` (new `HomeSectionType`), `:data` (new `homelayout/` — `HomeLayoutRepository`, `resolveHomeSections`, `DEFAULT_HOME_SECTIONS`), `:core:datastore` (new `HomeLayoutStore` / `SharedPreferencesHomeLayoutStore` / `HomeLayoutStoreModule`), `:feature:home` (`HomeViewModel`, `HomeUiState.sections`, `HomeScreen`), `docs/features/home.md`, `docs/notes/home-sections-feasibility.md`
- **Plan said:** "Home | `getUserViews` (MOVIES/TVSHOWS only), `getResumeItems(limit=20)`, `getNextUp(limit=20)`, `getLatestMedia(parentId, 16)` per library" (docs/PLAN.md, "Screens") — a fixed row set in a fixed order, with no mention of DisplayPreferences.
- **Done instead:** the row order and visibility come from the user's own jellyfin-web configuration. `HomeLayoutRepository` reads the `usersettings` DisplayPreferences record with the legacy `client = "emby"` partition key (both strings load-bearing: any other id is MD5-hashed into an unrelated record, any other client reads a private, always-empty one), resolves the ten `homesectionN` slots — each missing, empty or unrecognised value falling back to *that slot's* jellyfin-web default, `folders` accepted as the legacy alias for the libraries row — then drops `none` and de-duplicates. The resolved list is persisted (`home_layout`, a plain prefs file of its own) and is the offline answer; with nothing persisted the answer is jellyfin-web's `DEFAULT_SECTIONS`, which is exactly the plan's fixed order, so an unconfigured account sees no change. The call never throws and never yields an unrenderable layout. `HomeViewModel` fetches **only the visible rows** (a hidden *Next Up* costs no `getNextUp`; a layout with neither the libraries row nor *Latest* skips `getUserViews` entirely) and the debounced membership refresh skips hidden rows too; `HomeScreen` iterates `HomeUiState.sections`. Section types v1 has no row for (`resumeaudio`, `resumebook`, `livetv`, `activerecordings`) are carried through resolution and skipped at render, so hiding one still moves the rows around it correctly. Resolution happens on every full load (initial, pull-to-refresh, connectivity edge) — no polling.
- **Reason:** docs/POLISH.md's "New run" asked whether the server-side home configuration was readable; `docs/notes/home-sections-feasibility.md` verified it is, with one SDK call we already ship, and that jellyfin-androidtv honors it the same way. The plan's fixed order was a reasonable M2 default, not a decision to ignore the user's configuration — a user who reorders or hides rows on the web expects the app to agree. The per-slot default fallback is the load-bearing part: a fresh account has *no* `homesectionN` keys at all, so "missing" has to mean "client defaults", not "empty home screen".
- **Deliberately not done:** the per-library exclusions in `User.Configuration` (`LatestItemsExcludes`, `MyMediaExcludes`, `OrderedViews`, `HidePlayedInLatest`), which need a second `GET /Users/{id}` for full web parity; rendering `librarybuttons` as large buttons (both spellings draw the existing tile row, once, since two lazy items under one key would crash); and any way to edit the layout from the app. Consequence accepted: the *My Media* cards are filtered to libraries with something behind them using the *Latest* answers, so a layout that hides *Latest* lists every visible library — offline including ones with no downloads.
- **Structure:** deliberately **not** a `JellyfinRepository` method. That interface is the browse contract, split online/offline and delegated per call; this is one piece of configuration whose offline answer is a cache of the last layout rather than a Room query, and which must never surface a failure — so both browse implementations were left untouched. The cache lives in `:core:datastore` but outside `AppPreferences`/`PreferenceKeys`: it is a disposable server-derived value, not a setting the user chose here.
- **Tests:** 28 new — the decode (every value, case/whitespace, `folders`, missing, unknown), the resolver (defaults from an empty map, full layout in order, `none` dropped without disturbing later slots, garbage → slot default, duplicate deduped first-wins, everything hidden, out-of-range keys ignored), the repository (persists a successful fetch, asks for the exact `usersettings`/`emby` record, empty record → defaults, fetch failure → persisted then defaults, offline → cache with no request, offline fresh install → defaults) and the ViewModel (order reaches the state, hidden row neither fetched nor shown, libraries row without *Latest*, no library-backed row → no `getUserViews`, layout re-resolved per refresh, membership refresh skipping hidden rows and skipping entirely when both are hidden). No existing test weakened: the 28 existing `HomeViewModelTest` cases changed only by the new constructor argument and stay green on the default layout.

<!-- END -->

<!-- BEGIN a transcoded download's size stops being a ceiling and becomes a measurement -->

## 2026-07-29 — a transcoded download's size stops being a ceiling and becomes a measurement
- **Scope:** `:core:database` (`DownloadEntity.projectedBytes` + `.sizeIsExact`, `DATABASE_VERSION` 5 → 6,
  `AutoMigration(5, 6)`, `DownloadDao.updateProgress`/`completedSiblings`, new `SchemaMigrationTest`),
  `:data:downloads` (new `engine/MkvClusterScanner` + `engine/TranscodeSizeProjector`, `FileDownloader`'s new
  `MediaChunkSink` tap, `DownloadQueue.projectorFor`/`ItemProgress`, `DownloadEnqueuer.sizeEstimate`/`remuxBytes`/
  `siblingSeed`, `DownloadUrlFactory.transcodedVideoUrl`, `DownloadItem.sizeCertainty`/`displayTotalBytes`,
  `DownloadRepositoryImpl`), `:feature:downloads` (new `DownloadProgressRatchet`, `DownloadRows`, `DownloadsUiState`,
  `DownloadsViewModel`, `DownloadsScreen`, `strings.xml`), `docs/features/download-quality.md`,
  `docs/notes/download-size-estimation.md`
- **Plan said:** docs/PLAN.md excludes transcoded downloads entirely ("**Not v1:** … transcoded downloads"), so it says
  nothing about sizing one. What this amends is this log's own reasoning, from *"the transcode size estimate uses the
  source bitrate when it is under the cap"* (2026-07-29): "`min(cap, source)` keeps the estimate a deterministic upper
  bound — **no empirical fudge factor** … The estimate can still overshoot when the encoder undershoots the cap on easy
  content; that residual is inherent to estimating a file that does not exist yet."
- **Done instead:** the residual is no longer accepted as inherent. Three mechanisms may now lower the figure below the
  ceiling, and a fourth stops the ceiling being needed at all. (1) `MkvClusterScanner` reads Matroska cluster timestamps
  out of the bytes `FileDownloader` is already copying, and `TranscodeSizeProjector` turns them into
  `bytesReceived × runtime / mediaTimeReceived` on the existing 500 ms/1 % throttle cadence, clamped into
  `[bytesReceived, ceiling]`. (2) `DownloadEnqueuer.siblingSeed` seeds an episode from the median bytes-per-millisecond
  of up to eight finished episodes of the **same series at the same quality**. (3) `DownloadEnqueuer.remuxBytes`
  recognises the requests the server answers with a video stream copy and computes the size arithmetically. (4) The
  Downloads screen words the figure three ways — `"X"` exact, `"~X"` projected, `"up to X"` ceiling — and
  `DownloadProgressRatchet` keeps the displayed percent monotone per item for the session, holding at 99 % until
  `DOWNLOADED`. Schema v6 adds two additive columns; `bytesTotal` is never overwritten, and the completion path still
  snaps totals to written bytes.
- **Reason:** the earlier entry's "no fudge factor" rule was about *what kind of number is allowed*, not about how good
  the number may get, and this change keeps that rule while retiring the resignation attached to it. A LOW episode
  estimated 552 MB and landed at 232 MB; the ceiling was correct and useless. Every figure introduced here stays
  principled by the same test the fudge factor failed — it is **measured** (the projection is this transfer's own output
  bitrate; the seed is what this show's finished episodes actually weigh on disk), **conditioned** (per item, or per
  series *and* quality — never a global constant applied to everything), and **explainable** (the projection is
  arithmetic over bytes the user is watching arrive; the seed comes from rows visible on the Downloads screen). The
  global observed-ratio store stays rejected for exactly the reasons it always was, and `docs/notes/download-size-
  estimation.md` records both verdicts side by side. Everything is clamped by the old ceiling, so no path here can be
  worse than what shipped this morning.
- **Verified, not assumed:** the remux rule was checked against `EncodingHelper.CanStreamCopyVideo` in jellyfin
  `release-10.11.z` rather than inferred. That method runs ~a dozen gates; for the exact URL this client sends, all but
  four are inert because we send none of `profile`, `level`, `maxRefFrames`, `maxVideoBitDepth`, `videoRangeType`,
  `framerate`, `maxWidth`, `deInterlace`, `requireNonAnamorphic` or a subtitle stream index. So the four conditions
  checked (h264 exact case-insensitive codec match; `height` present and ≤ `maxHeight`; `bitRate` present, positive and
  ≤ `videoBitRate`; input container not `avi`) are sufficient rather than merely necessary. Two asymmetries decided the
  design: a **null** stream height fails the gate, and a **null** stream bitrate fails it too — the server's only escape
  hatch there is a `LiveStreamId`, which a download never has. That is why the rule requires the per-stream bitrate to be
  present instead of deriving video bytes from the source's total size: the server would not have copied the stream, and
  claiming an exact remux it never granted is worse than the estimate it replaces.
- **Deliberate consequences:** a row the enqueue step marks exact is never handed a projector — an arithmetic answer
  outranks a measured one, and re-measuring would flip a plain figure to a hedged one for nothing. The scanner is wired
  only when `appendFrom == 0`, since a resumed body starts mid-container; a transcode always lands there anyway, because
  the server ignores `Range` and answers `200`. The ratchet means an interrupted transcode (which restarts from zero)
  holds its bar rather than visibly retreating — a stalled bar beats a reversing one, and the byte figure beside it stays
  honest. `DownloadProgress`, the four-column projection behind the app-wide `DownloadBadge`, deliberately does **not**
  carry `projectedBytes`: badges keep dividing by the ceiling, because there is no per-item ratchet on that path and a
  retreating badge would be the very failure this entry adds a ratchet to prevent.
- **Tests:** no test weakened. `DownloadRowsTest`'s two `isSizeCapped` cases became `sizeCertainty` cases making the same
  claims — an original download's size is not a ceiling, a LOW download's is — plus the two states that boolean could not
  express (remux-exact, projection-approximate) and five `displayTotalBytes` clamp cases; the class went 5 → 13. New:
  `MkvClusterScannerTest` (21, all against synthetic EBML bytes — split ids and timestamp elements, every chunk boundary,
  one-byte feeds, the id occurring inside payload data, invalid size varints, a wrong first child, implausible sizes,
  non-monotonic and out-of-range timestamps, `TimestampScale` handling), `TranscodeSizeProjectorTest` (10),
  `DownloadEnqueuerSizeTest` (20 — split out of `DownloadEnqueuerTest` to keep it under detekt's `LargeClass` limit),
  `DownloadProgressRatchetTest` (10), `SchemaMigrationTest` (6, diffing the exported schema JSONs since the project has
  no androidTest source set). `DownloadQueueTest` +7, `DownloadRepositoryImplTest` +3, `DownloadsViewModelTest` +2. The
  `downloader.download` stubs and the `updateProgress` verification changed argument count only.

<!-- END -->

<!-- BEGIN sibling size seeding happens three times, not once -->

## 2026-07-29 — sibling size seeding happens three times, not once
- **Scope:** `data/downloads/.../SiblingSeeder.kt` (new), `DownloadEnqueuer.kt`, `engine/DownloadQueue.kt`,
  `core/database/.../dao/DownloadDao.kt`, `docs/features/download-quality.md`
- **Plan said:** docs/PLAN.md leaves download size reporting to the pipeline's own design; the shipped feature
  (commit b7b4e42, "live size projection for transcoded downloads") computed the sibling seed at **enqueue time
  only**, and `DownloadEnqueuer` documented that a season enqueued in one go is seeded "from whatever finished
  before the tap".
- **Done instead:** the seed is asked for at three moments, and the median arithmetic moved out of
  `DownloadEnqueuer`'s private methods into a reusable `SiblingSeeder`: (1) at enqueue, as before; (2) when
  `DownloadQueue` picks a row up with no projection; (3) when an item reaches DOWNLOADED, over every
  `QUEUED`/`PAUSED` row of the same series and quality that still has no projection and no exact size. Two new
  DAO statements carry it — `unseededSiblings` and `setProjectedBytesIfAbsent`, whose `projectedBytes IS NULL`
  clause means a seed can never overwrite a live `TranscodeSizeProjector` measurement or an earlier seed.
  `bytesTotal` is never written by any of this. No schema change (v6 stands). A row already mid-transfer when a
  sibling lands keeps its in-memory figure: it was seeded at pick-up, and its own scanner is about to produce
  something better.
- **Reason:** user report — "sibling seeding doesn't seem to work with currently running download".
  Enqueue-time-only seeding cannot serve the case the feature exists for: a season queued in one tap has no
  finished sibling at the instant its rows are written, so every episode after the first kept the "up to X"
  ceiling wording for the whole season however many siblings landed. The enqueue-time data path was verified
  sound (series name is persisted for expanded episodes, the Room quality converter round-trips by name against
  a TEXT column, episode runtimes are cached, and a bitrate-less source still gets a cap-derived ceiling) — the
  gap was purely one of *when* the question is asked.
- **Tests:** `SiblingSeederTest` (18, new) and `SeasonSeedingScenarioTest` (5, new — the whole scenario over a
  map standing in for Room: enqueue a season, finish episode one, assert the rest acquire a seed within their
  own ceilings, with other series, other qualities, already-projected rows and finished/failed rows all
  untouched); `DownloadQueueTest` 35 → 41 for the two new triggers, including a re-seed failure not failing the
  download that triggered it. Every existing test unchanged and green; full gate green.

<!-- END -->

<!-- BEGIN a transcoded download's seek index is written by the client -->

## 2026-07-29 — a transcoded download's seek index is written by the client, into the muxer's own reserved space
- **Scope:** `:data:downloads` (`engine/MatroskaSeekIndexRepair`, `offline/DownloadedMediaProvider`,
  `model/DownloadItem`), `:feature:downloads` (`DownloadRows`)
- **Plan said:** docs/PLAN.md's download pipeline copies "the original file untouched" and the player opens what is
  on disk; nothing anywhere contemplates the client *modifying* a downloaded file, and transcoded downloads are
  outside the plan to begin with (see the 2026-07-29 entry that shipped them).
- **Done instead:** Two things. (1) A downloaded transcode's header is patched in place, once, before its first
  local playback: a 26-byte `SeekHead` naming the file's own `Cues`, and an 11-byte `Duration`, written into the
  Void elements ffmpeg reserved for exactly those and never came back to fill. (2) A transcoded queue row no longer
  offers *Pause*.
- **Reason:** Reported as "streaming of transcoded downloads doesn't allow selecting the reading position". Reading
  a real `(low).mkv` off the tablet showed why: Jellyfin's ffmpeg writes its output to a transcoding temp file and
  patches the header at the end, but by then those header bytes have already been streamed to the device — so the
  download keeps a 152-byte reserved Void where the `SeekHead` belongs, no `Duration`, and a complete 698-point
  `Cues` element at the end of the file that nothing points at. Media3's `MatroskaExtractor` finds `Cues` only
  through a `SeekHead`, publishes `SeekMap.Unseekable` without one, and `ProgressiveMediaPeriod.seekToUs` turns
  every seek into a seek to zero. The index is already in the file; only the 26 bytes naming it are missing, and
  writing them is strictly cheaper and strictly more accurate than the alternative considered — recording our own
  index during the download (schema v7, a bespoke `SeekMap`, a custom `Extractor`, and no help at all for the
  downloads already on the device). Every byte written lands inside a Void, so nothing that means anything is
  overwritten and the file's length never changes; the patched regions are read back and the header re-walked
  afterwards, and a disagreement restores the original bytes. It runs from `DownloadedMediaProvider` rather than
  the download pipeline because that is the only path that also reaches downloads made before the fix — the ones
  the fault was reported against. Pause goes for the matching reason: `/Videos/{id}/stream.mkv?static=false`
  ignores `Range`, so a paused transcode restarts from zero; a button that silently discards several hundred
  megabytes is not a pause, and *Cancel* already says what it does.
- **Tests:** `MatroskaSeekIndexRepairTest` (17, incl. the real ffmpeg header at
  `src/test/resources/ffmpeg-transcode-header.bin`), `DownloadedMediaProviderTest` +2, `DownloadRowsTest` +2.
  Verified end to end on a real 220 MB `(low)` episode: 33 bytes changed, all inside the reserved Voids, length
  unchanged, `ffprobe` duration `N/A` → `1380.000000`, and ffmpeg seeks and decodes at 600 s. Full gate green.

<!-- END -->

<!-- BEGIN the offline Latest shelf groups episodes into their series -->

## 2026-07-29 — the offline Latest shelf groups episodes into their series
- **Scope:** `:core:database` (`ItemDao.latestDownloaded` → `latestDownloadedKeys`, new `LatestDownloadKey`
  projection), `:data` (`OfflineJellyfinRepository.getLatestMedia`, `ItemEntityMapper.toSeriesCardOrNull`),
  `docs/features/offline-read.md`, `docs/features/home.md`
- **Plan said:** docs/PLAN.md defines the offline *Latest* row as "recent downloads" and says nothing about
  grouping; `OfflineJellyfinRepository` listed the downloaded rows raw, newest first.
- **Done instead:** downloaded episodes collapse into their series before the row limit applies, the way the
  server's `getLatestMedia` does online (`GroupItems`). A two-column projection reads every downloaded row of the
  library's kinds newest-first with the id of the card it belongs to; the first row of each group wins; the card
  is the series' own cached row, and — when the pipeline's best-effort parent fetch had failed — a card
  synthesised from the episode's `seriesId`/`seriesName`/`seriesPrimaryImageTag`. Movies group onto themselves
  and are unchanged.
- **Reason:** user report — "latest series in offline mode is showing individual episodes instead of the season
  itself". A downloaded season filled the whole shelf with its own episodes where the online row showed one
  poster, so going offline visibly reshaped the screen the plan wants to be seamless. The synthesised card is the
  one thing that goes beyond the plan's "the same mapper produces both": it is a `JellyfinItem` no cached row
  backs. It exists only for the degraded case, it is built from the episode's blob (not from the query columns),
  and the alternative — falling back to bare episodes for that show — is the very bug being fixed.
- **Tests:** `OfflineJellyfinRepositoryTest` 27 → 34, `ItemEntityMapperTest` 12 → 17. No test weakened; full gate
  green.

<!-- END -->

<!-- BEGIN library grid minimum cell width raised to Dimens.PosterWidth -->

## 2026-07-29 — library grid minimum cell width raised to Dimens.PosterWidth (120dp)
- **Scope:** `feature/library/.../LibraryGridScreen.kt`
- **Plan said:** docs/PLAN.md, "Screens" → LibraryGrid: `LazyVerticalGrid(Adaptive(110.dp))`.
- **Done instead:** `Adaptive(Dimens.PosterWidth)` (120dp), the same token Home's poster row uses.
- **Reason:** user report — library tab items rendered smaller than Home's. On the test tablet in landscape,
  `Adaptive(110.dp)` settles at 9 columns of ~112dp, narrower than Home's 120dp poster cards — a visible size
  inconsistency between the two screens for the same poster shape (portrait was already fine at 5 × ~126dp).
  Anchoring the floor to `Dimens.PosterWidth` guarantees cell width ≥ Home's card width in any orientation
  (`Adaptive` always grows cells to ≥ minSize), while leaving portrait and other grids untouched. Artwork request
  sizing unaffected: the new landscape cell (~127.7dp) stays under the fixed 128dp Coil bucket.
- **Tests:** view-layer sizing with no Compose-UI harness in the repo — code-only change; full gate green.

<!-- END -->

<!-- BEGIN Libraries tab (category tiles) minimum cell width raised to Dimens.ThumbWidth -->

## 2026-07-29 — Libraries tab category tiles minimum cell width raised to Dimens.ThumbWidth (210dp)
- **Scope:** `feature/library/.../libraries/LibrariesScreen.kt`
- **Plan said:** docs/PLAN.md, "Screens" has no entry for this screen at all — only `LibraryGrid` (the paged
  item grid *inside* a library) specifies a grid, and that one was already fixed above. The Libraries tab
  (the "Films"/"Séries" category picker, `LibrariesScreen.kt`'s own `MIN_CELL_WIDTH = 160.dp`) was a
  screen-local constant with no plan basis either way.
- **Done instead:** `Adaptive(Dimens.ThumbWidth)` (210dp), the same token Home's *My Media* row uses for the
  same 16:9 library-tile shape (`LibraryCard`'s default `width` parameter).
- **Reason:** round 2 of the same user report ("library tab items are smaller than Home") — round 1 (the
  entry above) fixed the poster grid *inside* a library, but the user meant the Libraries tab itself, whose
  category tiles are a different screen with their own `MIN_CELL_WIDTH`. On the test tablet, `Adaptive(160.dp)`
  settles at 4 portrait columns of ~161dp and 6 landscape columns of ~174dp — both below Home's fixed 210dp
  card (portrait was sitting barely off the 160dp floor). Anchoring the floor to `Dimens.ThumbWidth` gives 3
  portrait columns of ~218dp and 5 landscape columns of ~212dp — both now at or above Home's card width
  (`Adaptive` always grows cells to ≥ minSize), reading as the same product in both orientations. Artwork
  request sizing unaffected: the widest new cell (~218dp) stays under `ArtworkRequestWidths.THUMB_DP`'s 224dp
  bucket, which was already sized with this headroom in mind.
- **Tests:** view-layer sizing with no Compose-UI harness in the repo — code-only change; full gate green.

<!-- END -->

<!-- BEGIN the Downloaded tab gathers films under a shared Movies heading -->

## 2026-07-29 — the Downloaded tab gathers films under a shared Movies heading, after every series group
- **Scope:** `:feature:downloads` (`DownloadsUiState.DownloadGroup`/`toGroups`, `DownloadsScreen.GroupHeader`/
  `DownloadedTab`, `strings.xml`), `docs/features/downloads.md`
- **Plan said:** docs/PLAN.md line 76 — "Downloads | Room-only: *Downloaded* tab (**grouped**, sizes, delete) …".
  The 2026-07-29 entry above it ("only series get a heading in the *Downloaded* tab") additionally states
  "Series and films are ordered together alphabetically rather than in two blocks."
- **Done instead:** that ordering changes. Once at least one series group exists on the tab, every film is now
  gathered under one shared "Movies" heading (`DownloadGroup.isMoviesSection`, title left blank and resolved to
  a string resource in Compose — the same reasoning `DownloadsMessage` uses to keep the ViewModel free of
  resources), placed after every series group, which are themselves still ordered alphabetically among
  themselves, and the films inside the Movies group alphabetically among themselves. When no series exists,
  films are unchanged: their own headerless rows, ordered alphabetically. `DownloadGroup.isSeries` keeps its
  narrower meaning (this group's title is a series name); a new `isMoviesSection` flag drives the shared header
  instead of overloading it.
- **Reason:** user report — on the *Downloaded* tab, a film immediately following a series' last episode, at the
  same indentation and with nothing marking where the series group ended, read as though it belonged to that
  series. The alphabetical-interleave ordering from the prior entry is exactly what let a film land in that
  position. A per-movie heading was deliberately removed earlier (docs/POLISH.md, "Downloads page duplicate
  movie header") because it repeated the film's own row title — that heading is not reinstated; one shared
  heading over the whole films block does not have that problem, and marks the boundary the bug needed marked
  without adding a header per film.
- **Tests:** `DownloadsViewModelTest` grouping tests updated/added (series-only groups gain an explicit
  `none { it.isMoviesSection }` assertion; a new test pins the mixed case — series groups first alphabetically,
  then one Movies group holding every film alphabetically, after every series group). No assertion weakened —
  the changed tests assert the new, intentionally different ordering; full gate green.

<!-- END -->

<!-- BEGIN the queue tab gains Pause all / Resume all / Cancel all -->

## 2026-07-29 — the queue tab gains Pause all / Resume all / Cancel all

- **Scope:** `:feature:downloads` (`DownloadsUiState`, `DownloadsViewModel`, `DownloadsScreen`, `DownloadRows`,
  `strings.xml`, `DownloadsViewModelTest`, `DownloadRowsTest`), `docs/features/downloads.md`
- **Plan said:** docs/PLAN.md line 76 — "Downloads | Room-only: … *Queue* tab (progress %, speed,
  pause/resume/cancel, reorder)". Every action the plan names is **per row**; nothing queue-wide is specified.
  The 2026-07-29 entry "Cancel on a season keeps the episodes that already finished" additionally states
  "`:feature:downloads`' `DownloadsMessage` stays an enum — it has no count-carrying message."
- **Done instead:** the queue tab draws a bulk action bar above the list (only while the queue is non-empty)
  with three actions, each composed from the existing per-item repository calls — no new `DownloadRepository`
  method, and therefore no second delete cascade to keep correct:
  - **Pause all** pauses every row the *row's own* Pause button would offer, i.e. `QUEUED`/`DOWNLOADING` and
    `isPausable`. Transcodes are skipped, not paused: the server ignores `Range` on a file it is still
    producing, so pausing one discards the transfer (DECISIONS.md 2026-07-29, the pause/seek-index entries).
    The button is **disabled** when the queue holds nothing pausable, and when it did skip transcodes a
    snackbar reports both numbers ("Paused 2 — 1 transcode keeps downloading").
  - **Resume all** re-queues every `PAUSED` or `ERROR` row, transcoded or not (a transcode's resume is
    legitimate, it just costs the transfer again — the same rule the per-row button follows). Disabled with
    no such row; silent on success, since the rows visibly change to *Waiting*.
  - **Cancel all** deletes every row on the tab behind a **confirmation dialog** naming the count. Finished
    downloads are untouched by construction rather than by a filter: the queue list is `toQueue()`, which
    excludes `DOWNLOADED` — the season-cancel rule applied to the whole queue.
  The per-row buttons now branch on the same two predicates the bulk actions use (`DownloadItem.isPauseTarget`
  / `isResumeTarget`, in `DownloadsUiState.kt`), so row and bar cannot drift apart. `DownloadsMessage` becomes
  a **sealed interface** — reversing the note quoted above — because `PausedKeepingTranscodes` carries two
  counts for a plural (the same move `:feature:detail`'s `UserMessage` made, for the same reason).
  `showCancelAllConfirmation` lives in `DownloadsUiState`, unlike the *Downloaded* tab's `remember`ed
  `pendingDelete`: the question is about the ViewModel's whole queue and has to survive rotation and the
  recompositions a live queue causes twice a second (precedent: `ItemDetailUiState.showDeleteConfirmation`).
- **Reason:** user request — emptying or pausing a queue of twenty episodes meant twenty round trips through
  per-row icon buttons. The plan predates container downloads, which is what makes a long queue ordinary: one
  tap on a season enqueues every episode of it, so the screen needs one tap that undoes that. The bulk actions
  are additive to the plan's per-row list rather than a replacement — every row action is unchanged — but the
  transcode-skipping semantics, the confirmation asymmetry against the per-row *Cancel*, and the reversal of
  the `DownloadsMessage`-stays-an-enum note are each worth recording.
- **Tests:** `DownloadsViewModelTest` +12 (pause-all pauses only pausable rows and never a transcode, paused
  or failed row; the counted transcode message; silence when nothing was skipped; a failed pause outranking
  that message; resume-all covering `PAUSED`/`ERROR` including transcoded rows and nothing else; the
  disabled-with-no-targets cases doing nothing at all; cancel-all asking first, dismissing cleanly, deleting
  every queue row while never touching either `DOWNLOADED` row, and reporting a failed delete; an empty queue
  offering no bulk action and refusing to open the dialog). `DownloadRowsTest` +4 pinning the shared
  pause/resume predicates. No existing test changed or weakened; full gate green.

<!-- END -->
