# STATUS

## Current milestone: M1 — Auth & session

**Definition of done:** server discovery (UDP broadcast + manual URL with address-candidate
scoring), password + Quick Connect login, access token stored ONLY in
EncryptedSharedPreferences (never Room), session restore on app start, sign-out.
Verify on device: `run-as` inspection shows no token in the DB; session appears in server
Dashboard→Devices. Also confirm server version (10.11.x expected) and the user's download
policy (risk #4).

### Done
- Repo initialized, governance files (PLAN/DECISIONS/STATUS/CLAUDE) in place.
- Version resolution complete and recorded in DECISIONS.md: Media3 1.9.0 + ffmpeg-decoder
  1.9.0+1, Hilt 2.60.1, androidx.hilt 1.4.0, Room 2.8.4, Compose BOM 2026.06.01,
  lifecycle 2.11.0, KSP 2.3.10, Kotlin 2.4.10, AGP 9.3.1 (built-in Kotlin), Gradle 9.6.1,
  compileSdk 37 / targetSdk 36 / minSdk 26, SDK 1.8.12.
- Gradle skeleton: build-logic (7 convention plugins), version catalog, all 16 modules
  compiling with real stub sources; `:app` = Hilt Application + dark-themed MainActivity.
- Quality gate green (verified independently by orchestrator, not just the build agent):
  `assembleDebug detekt ktlintCheck testDebugUnitTest`. Debug APK 34.8 MB,
  `dev.jellyfinnative.app.debug`.
- Hooks (.claude/hooks: session-start, post-edit, pre-commit-gate, stop-gate) and skills
  (/verify /checkpoint /diverge /milestone /document-feature) created and smoke-tested,
  incl. deny paths and stop_hook_active loop guard.
- Test device documented in CLAUDE.md: test tablet ([redacted]), Android 16 / API 36, via adb.
- On-device DoD check passed: `installDebug` OK, app launches with dark #101010 screen
  (screenshot-verified). An earlier `INSTALL_FAILED_USER_RESTRICTED` was transient —
  "Install via USB" is enabled and working on this device.

### Done (M1 so far)
- `:core:database`: session schema — ServerEntity, ServerAddressEntity (multi-URL, CASCADE),
  UserEntity (NO token column), upsert DAOs, JellyfinDatabase v1 (schema exported),
  UuidConverter + unit tests, Hilt module.
- `:core:datastore`: SecureCredentialStore interface + EncryptedSharedPreferences impl
  (AES256_GCM/SIV, IO-dispatched, corrupt-keyset recreate-once recovery), StoredSession
  (serverId+userId+token as one atomic record), Hilt binding.

### Next
- `:core:network`: ApiClientProvider (SDK wiring), ServerDiscoveryRepository (UDP 7359 +
  manual URL candidates, reference jellyfin-android ConnectionHelper.kt), AuthRepository
  (password + Quick Connect initiate/poll/authenticate), SessionRepository (restore, sign-out).
- `:feature:auth`: ServerSetup + Login screens/ViewModels; wire NavHost in `:app`
  (auth flow vs. placeholder home based on session state).
- Unit tests for repositories/ViewModels (JUnit5 + MockK + Turbine).
- On-device DoD walk: run-as DB inspection, Dashboard→Devices session, server version +
  download policy confirmation.

### Known issues
- (none)

---

### M2 (worktree branch)

Built in parallel with M1 on `worktree-agent-ae7ad42c50e2b31bd`; not yet merged or wired into
`:app`. Quality gate green on that branch:
`./gradlew ktlintCheck detekt testDebugUnitTest assembleDebug`.

**Done**
- `:core:common` — domain models: `JellyfinItem`, `UserData`, `ItemType`, `DownloadState`,
  `LibraryView`/`CollectionKind`, `ItemQuery`/`SortBy`/`SortOrder`, `FilterOptions`.
  Plus a `testDebugUnitTest` alias so this pure-JVM module joins the gate (see DECISIONS.md).
- `:core:ui` — design system on the existing `#101010`/`#202020`/`#00A4DC` theme:
  `JellyfinGradients` (`#AA5CC3 → #00A4DC` accent, backdrop scrim, image placeholder), `Dimens`,
  `JellyfinAsyncImage` (Coil 3), `PosterCard` (2:3), `ThumbCard` (16:9), `LibraryCard`, `MediaRow`,
  `BackdropHeader`, `DownloadBadge`, `OfflineBanner`, `LoadingState`/`ErrorState`/`EmptyState`.
  Compose previews on every component.
- `:data` — `JellyfinRepository` (home-scope surface) + `OnlineJellyfinRepository` on
  jellyfin-sdk 1.8.12, `ItemMapper` (`BaseItemDto` → domain, with the jellyfin-web artwork fallback
  chain), `ImageUrlFactory`/`SdkImageUrlFactory`, `ApiErrorMapper` (SDK exceptions → `AppError`),
  Hilt `@Binds` module.
- `:feature:home` — `HomeScreen`/`HomeContent` + `HomeViewModel` + `HomeUiState`, rows in
  jellyfin-web order (My Media → Continue Watching → Next Up → Latest &lt;library&gt;), with
  loading/error/empty states.
- Unit tests: `JellyfinItemTest` (13), `ItemMapperTest` (13), `OnlineJellyfinRepositoryTest` (9),
  `HomeViewModelTest` (9) — 44 new tests, all green.

**Next (integration, orchestrator)**
- Provide `org.jellyfin.sdk.api.client.ApiClient` from `:core:network` (M1), then add
  `@HiltViewModel` to `HomeViewModel` (see DECISIONS.md 2026-07-28).
- Wire `Routes.Home` → `HomeScreen(viewModel = hiltViewModel(), …)` into the `:app` NavHost,
  together with `AppScaffold` (bottom nav + `OfflineBanner`).
- Then run the M2 DoD: side-by-side vs jellyfin-web home on the test tablet — same rows, items and
  order.

**Known issues (M2)**
- Write-through Room caching (`source=BROWSE_CACHE`) is intentionally absent; it is M6 scope.
- `DownloadBadge` always renders `NotDownloaded` until the M7 download pipeline supplies real
  states.
