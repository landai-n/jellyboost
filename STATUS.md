# STATUS

## Current milestone: M7 — Downloads (pipeline built on a worktree branch, awaiting merge + device DoD) + user-data stale-row bugfix (parallel worktree)

**DoD (M7):** 2GB movie resumes from byte offset after app kill; Wi-Fi-only honored;
delete frees bytes.

### Next
- M7 agent: full download pipeline per docs/PLAN.md; merge, review, gate, device DoD,
  tag m7.
- Done meanwhile: user-data read-refresh fix merged and device-verified (see Known
  issues — struck through).

### M7 device-walk bugfixes (worktree branch, awaiting re-walk)
Four findings from the DoD walk, all fixed with unit coverage; **the kill-resume walk
must be re-run on the tablet** (this branch had no device):
- **A — cold start raced session restore.** WorkManager started the worker before the UI
  restored the session, so the first URL threw the SDK's `Required value baseUrl is null`
  and the item went ERROR. `DownloadSessionGate` now restores it inside the drain; no
  session at all → `Result.retry()` with rows left "Waiting", never ERROR. Row error copy
  is now mapped (`DownloadErrorCopy`) so SDK internals cannot reach the screen.
- **B — retry re-planned the media filename.** A retry whose DTO had no `path` renamed the
  1.38 GB partial and restarted from zero. The queue now reuses the persisted
  `download_files` rows (names + identity) and rebuilds only URLs; re-planning happens
  only when no rows exist.
- **C — "queue-cancel leaks files" not reproduced.** The row was `DOWNLOADED` at cancel
  time (the transfer had just finished), so the files were legitimate. All three cancel
  paths already share the delete cascade; that is now pinned by tests. The queue also
  aborts an item whose row disappears mid-transfer, so a cancel landing between two files
  cannot re-create the directory.
- **D — downloads invisible in offline grids / Latest.** Offline library scoping moved off
  `parentId` (stored NULL, and a folder id even when present) onto the library's item
  kinds (DECISIONS.md). Season lookup moved to `seasonsOfSeries` (`seriesId OR parentId`).

## Previous milestone: M5 — Playback (online) (DONE, tagged m5)

**DoD walk on test tablet (2026-07-28), all pass** (server evidence via `/Sessions`,
which is what Dashboard renders):
- **Direct play:** "28 Ans plus tard" (h264) → `PlayMethod=DirectPlay`, no
  TranscodingInfo; player badge "Direct play".
- **Forced transcode:** Quality → *Lowest — 720 kbps* on the direct-playing item →
  method flips to `Transcode` at the same position (server transcoding at 592 kbps);
  badge flips to "Transcoding". Also organic transcode: Citizen Vigilante (HEVC) →
  `Transcode`, reason `VideoProfileNotSupported` (see Known issues).
- **Track switching:** subtitle dialog (Off/English/French) → French SRT side-loaded and
  **visually rendered** ("Merci." on screen), session `SubtitleStreamIndex=3`; audio
  dialog (3 tracks) → English Atmos switched **instantly in-stream** (no re-resolve,
  still DirectPlay, session `AudioStreamIndex=3`).
- **Resume:** exit at ~35 min → detail button becomes *Resume* with "80 min left"
  (event-bus patch, no refetch) → resume starts at 35.0 min server-side.
- **No orphaned ffmpeg:** every stop/re-resolve fires `DELETE /Videos/ActiveEncodings`
  with the right playSessionId (logcat) — after exit, `/Sessions` shows no NowPlaying
  and no TranscodingInfo (checked after both transcode sessions).
- **Reporting triad:** `Sessions/Playing` start/progress (5 s)/stopped all observed,
  plus the local-first `POST /UserItems/{id}/UserData` position writes alongside.
- **Timezone fix verified on a real write:** `LastPlayedDate` stored ≈30 s before "now"
  (UTC-correct) — the M6 fix works end-to-end.
- Seek-bar drag, ±10/30 s skips, pause/play, immersive landscape all fine.

## Previous milestone: M6 — Offline read path (DONE, tagged m6)

**DoD walk on test tablet (2026-07-28), all pass:**
- Force-offline: overflow-menu toggle on/off and the banner's *Go online* action all
  fire and persist (log-verified handler + DataStore write; survives force-stop).
  Repeated `input tap` drops made this look broken at first — it is the documented the OEM ROM
  injection flakiness, worse in same-coordinate bursts; log-verified single taps work.
- Forced-offline browsing: Libraries serves the cached view list, grid/search show
  graceful empty states ("Nothing to show here." / "Nothing matched"), **zero** network
  requests fired (logcat), no crashes.
- Airplane mode: banner ("No network — showing downloaded media") already present on
  the first UI dump after enabling (~1s swap after callback; 2.8s wall-clock including
  dump overhead); navigation while offline crash-free; recovery ~5s after disabling
  (Wi-Fi reassociation + probe).
- Server-down, Wi-Fi up (simulated with a blackhole HTTP proxy at a non-routable
  address + cold start so no pooled connections): session restore 21:50:30.1 → probe
  verdict 21:50:33.2 = **3.06 s** to the "Can't reach the server" banner with cached
  My Media rendered — no 30 s hang; *Retry* recovers once the proxy is cleared.
- Landscape: banner renders correctly above the nav bar (screenshot-verified).
- Room v2→v3 auto-migration ran in place on the existing device install (no crash, data
  intact).
- Note: a warm OkHttp connection pool ignores a newly-set system proxy — the first
  simulation attempt failed because of connection reuse; cold start fixed it (test
  methodology, not an app bug).

### Done (M6, worktree branch `worktree-agent-a25cf3584ae0036b2`, merged to main)
- `:core:database` schema **v3** (`@AutoMigration(2, 3)`, additive, schema exported):
  `ItemEntity` (single table, structured columns + full `BaseItemDto` JSON blob +
  `source: BROWSE_CACHE|DOWNLOAD` + `cachedAt`), `LibraryViewEntity`, `ItemDao`,
  `LibraryViewDao`, enum/list converters, `UserDataDao.getUserDataFor`.
- `:core:datastore`: `AppPreferences`/`DataStoreAppPreferences` (`forceOffline`) + the
  singleton preferences `DataStore`.
- `:core:network` `connectivity/`: `ConnectivityMonitor` (default-network callback),
  `ServerReachabilityProbe` (3 s `getPublicSystemInfo`, rotates `ServerAddressEntity`
  candidates and re-points the client), `ConnectionStateProvider` (conflated probe queue
  with a 2 s debounce). Plus `@ApplicationScope` and `ApiClientProvider.useAddress`.
- `:data`: write-through caching on every `OnlineJellyfinRepository` read
  (`BrowseCacheWriter`, never downgrades a `DOWNLOAD` row and never bumps its `cachedAt`),
  `OfflineJellyfinRepository` (Room-only; downloaded-items-only lists, `getItem` also
  serves cached parents, `available=false` instead of throwing),
  `DelegatingJellyfinRepository` **now bound as `JellyfinRepository`**.
- `:app`: `ConnectionViewModel`, app-wide `OfflineBanner` in `AppScaffold` (distinct copy
  per reason + Retry / Go online), force-offline toggle in the home overflow menu, probe
  refresh on app resume.
- Bug fix: `datePlayed`/`lastPlayedDate` timezone (see "Known issues" below).
- 86 new unit tests (317 total, 0 failures); full gate green in one run
  (`ktlintCheck detekt testDebugUnitTest assembleDebug`).
- Docs: `docs/features/offline-read.md`, `docs/ARCHITECTURE.md`; 8 DECISIONS entries.

### Next
- M6 device DoD by the orchestrator: airplane-mode swap ~1 s, server-down degradation,
  tablet/landscape check on the banner + overflow menu; then tag m6.
  (Offline lists are empty in practice until M7 writes `source = DOWNLOAD` rows;
  correctness is pinned by unit tests that seed Room.)
- Merge the M5 worktree branch when the agent reports, orchestrator-review + full gate,
  device DoD walk, tag m5.

## Previous milestones: M3 — Library grid + Search, and M4 — Item detail + user data (DONE, tagged m3/m4)

**DoD walk completed on test tablet, 2026-07-28 (second half; first half recorded below):**
- M3 sort round-trip: selecting *Date added* re-queried at `startIndex=0` with
  `sortBy=DateCreated&sortOrder=Descending` (auto-flips direction for date sorts, like
  web) and the grid re-rendered accordingly (logcat + UI verified).
- M3 filter sheet: facets fetched via `/Items/Filters` — Watched (Any/Watched/Unwatched),
  real server genres, real library years; applying *Watched* re-queried with
  `isPlayed=true` and the grid showed watched-only titles; *Clear all* restored the
  unfiltered query while keeping the sort selection.
- M3 search: typing "house" produced **exactly one** debounced request
  (`searchTerm=house&limit=50`, types Movie/Series/Episode) with results sectioned
  Movies / Shows.
- M3 landscape: search + grid render correctly (8 adaptive poster columns).
- M3 >500-item scale: no such library exists on test-server — verified at 184 items on
  device + 520 items in `OnlineJellyfinRepositoryPagingTest` (see DECISIONS.md
  2026-07-28 entry).
- M4 series walk: series → *Saison 3* → 4 episodes → episode detail, each screen firing
  the expected requests once (`/Items/{id}`, `/Shows/{id}/Seasons`,
  `/Shows/{seriesId}/Episodes?seasonId=`, `/Shows/NextUp?seriesId=`, `/Similar`).
- M4 favorite toggle: `POST /UserFavoriteItems/{id}` → server `IsFavorite=True` →
  button flips; revert sent `DELETE` → server `False` (user data left as found).
- M4 landscape: series/season/episode detail rendered correctly (walk performed in
  landscape; portrait re-verified after rotating back).
- UI polish note (corrected during the M6 walk): the grid's sort/filter icons DO have
  content descriptions ("Sort"/"Filter") — they sit on the inner icon nodes, which the
  first uiautomator pass missed. No accessibility gap.

### Done (this session, 2026-07-28)
- M3 and M4 built in parallel opus-subagent worktrees, merged to main (conflicts in the
  shared append-only sections of `JellyfinRepository`/`OnlineJellyfinRepository`/
  `DECISIONS.md` resolved by keeping both sections). Orchestrator-verified full gate:
  231 unit tests, 0 failures, `ktlintCheck detekt testDebugUnitTest assembleDebug` green.
- Integration pass (sonnet subagent): bottom nav (Home/Libraries/Search; Downloads
  deferred to M7 per DECISIONS), `LibrariesScreen` + tests, NavHost wiring for
  LibraryGrid/Search/ItemDetail, home click-through, auth screens restyled onto
  `:core:ui`. WorkManager/Hilt in `:app` was already wired at M0.
- Device DoD walked so far (test tablet, signed in as Alex):
  - M3 paging: Films grid (184 items) scrolls to the bottom cleanly with exactly one
    request per page — offsets 0/50/100/150, each requested once (logcat-verified).
    Note: no library on test-server exceeds 500 top-level items (Films 184, Séries 28);
    the >500 scale is pinned by `OnlineJellyfinRepositoryPagingTest` (520 items → exactly
    11 requests). Sort menu renders (Name/Date added/Release date/Community rating/
    Runtime/Random + Ascending).
  - M4: card → detail navigation works (`/Items/{id}` + `/Similar` fire once); "Mark
    watched" on Citizen Vigilante flipped the button label, sent `POST /UserPlayedItems`,
    server showed `Played=true` (jellyfin-web reads this same user data); back on Home
    the card's watched badge appeared via the event bus with **zero** network requests
    (logcat-verified) — then the toggle was reverted to leave user data as found.

### Remaining before tagging m3/m4
- M3: sort/filter round-trip on device (menu opens; selection re-query not yet
  verified), filter sheet contents, Search screen walk, tablet/landscape pass,
  DECISIONS note for the >500-item verification adaptation.
- M4: series → seasons → episodes detail walk, favorite toggle, optional visual check
  in jellyfin-web UI, landscape pass.
- Then `/milestone finish M3` and `finish M4` (tags m3, m4).

### Known issues (new)
- ~~`datePlayed`/`lastPlayedDate` sent to the server carry UTC wall-clock time with the
  device's local offset appended~~ — **fixed on the M6 branch** (`fix(data): send user-data
  timestamps as the instant the server expects`). The SDK's `DateTimeSerializer` is
  zone-aware in *both* directions, so `ItemMapper`'s read path was corrected too; see
  DECISIONS.md 2026-07-28 "M6: the `datePlayed` timezone fix also corrects the read path".
- ~~**Stale local user-data rows corrupt server state on playback**~~ — **FIXED**
  (`fix(data): refresh local user_data from server reads unless pending`, merged
  2026-07-28): `BrowseCacheWriter` now adopts the server's `userData` into `user_data`
  rows that are absent or `toBeSynced=false`; pending rows untouched (M8 reconciles).
  Device-verified: after a `getItem` read of the previously-stale Citizen Vigilante
  row, 15 s of playback left the server at `Played=False` (previously re-marked within
  5 s), and exit reset position server-side. +11 tests incl. an end-to-end regression
  pair (401 total).
- HEVC files transcode with `TranscodeReasons=[VideoProfileNotSupported]` even though
  the Helio G100 decodes HEVC Main/Main 10 — the device profile's HEVC CodecProfile
  conditions likely don't match what the decoder probe reports on this device.
  Playback still works (graceful transcode); investigate the built profile vs
  `MediaCodecList` output before M10.
- Backgrounding the app pauses playback: `POST_NOTIFICATIONS` is declared but never
  requested (M9), so the media notification can't show; background-continue +
  notification permission flow are M9 scope (background playback is not in the M5 DoD).
- Screens loaded while offline keep their offline data after connectivity returns until
  the user re-enters them (e.g. Home shows only cached My Media after a reconnect; a
  killed/relaunched app is fine). The delegating repository is per-call, but ViewModels
  don't re-fetch on connection regain — wire a refresh-on-reconnect (or pull-to-refresh)
  by M9.
- the OEM ROM `uiautomator dump` can fail silently and leave a stale dump file; UI-driving
  scripts must delete the file first and re-verify the screen before every tap (a stale
  dump caused stray taps this session — see incident note).
- Incident (resolved): stray automation taps marked "Sans un bruit : Jour 1" played,
  clearing its real resume position. Restored from a pre-incident screenshot
  measurement: `played=false`, position 47531078400 ticks (~78% of runtime,
  bar-verified on device after relaunch). Citizen Vigilante's test toggle likewise
  reverted (`played=false`, pos 0). The app's local `user_data` rows for these two items
  retain the test writes (`toBeSynced=false`, so they will never push); server state is
  authoritative for reads today.

## Previous milestone: M2 — Design system + Home (online) (DONE, tagged m2)

**DoD walk on test tablet (2026-07-28), side-by-side vs jellyfin-web as the same user
('Alex'), all rows compared item-by-item to the end via UI-dump row walks — pass:**
- My Media: Films, Séries (web also shows Musique — excluded by v1 scope, pre-approved).
- Continue Watching: 12/12 items identical, same order (Sans un bruit : Jour 1 → Wonder Man).
- Next Up: 9/9 identical, same order (House of the Dragon S3:E1 → Zero Day S1:E5).
- Latest Films: 16/16 identical, same order (Backrooms → Big World).
- Latest Séries: 16/16 identical, same order (House of the Dragon → Wonder Man).
- Landscape sanity check on the tablet: rows/cards render correctly.
- Found and fixed during the walk (DECISIONS.md 2026-07-28 "Home row limits and filters"):
  the app's raw `getResumeItems`/`getNextUp` calls did not match jellyfin-web's requests —
  web sends `mediaTypes=Video`, `enableResumable=false`, a 365-day next-up cutoff, and
  limits 12/24 (not the plan's 20/20). Next Up wrongly showed in-progress episodes
  (Malcolm S1:E2, Emily in Paris S5:E1) and stale series (Key & Peele, Squid Game), and
  Continue Watching showed 8 extra items until aligned.
- Verification note: comparing as the same user matters — the app had been left signed in
  as 'admin' from M1 testing and its home legitimately differed from web-as-Alex;
  re-login via Quick Connect (code approved by an authenticated web session) fixed that.

## Previous milestone: M1 — Auth & session (DONE, tagged m1)

**DoD walk on test tablet (2026-07-28), all pass:**
- UDP discovery: "Servers on this network" lists test-server (screenshot-verified).
- Manual/candidate resolution: `Resolved http://192.168.1.10:8096 (score GREAT,
  version 10.11.11)`.
- Password login (fresh install) and Quick Connect login (code approved in web UI,
  signed in as approving user) both land on Home.
- Token hygiene via `run-as`: DB schema/WAL contain no token column and no token-shaped
  strings; `secure_credentials.xml` fully encrypted (Tink AES-SIV keys / AES-GCM values).
- Session restore: force-stop → relaunch → straight to signed-in Home
  (`Restored session for 'Alex' on 'test-server'`), no network.
- Sign-out: credential entries wiped (only keyset metadata remains), app returns to
  ServerSetup; server/user Room rows kept per DECISIONS.md.
- Dashboard→Devices: user confirmed "jellyfin-native 0.1.0" session in web UI.
- Server version 10.11.11 (upgraded from 10.10.7 during M1); download policy
  `enableContentDownloading=true` — risk #4 cleared, download pipeline (M7) unblocked.

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
- `:core:network`: ApiClientProvider (createJellyfin + single mutable ApiClient),
  ServerDiscoveryRepository (UDP discovery Flow + address-candidate scoring),
  AuthRepository (password + Quick Connect w/ 5s-poll/5-min-cap Flow; download policy
  logged per risk #4), SessionRepository (local-only restore, best-effort sign-out),
  SessionStateHolder, JellyfinApiFacade (testability seam), AppError.ServerResolution.
  29 unit tests (token-hygiene, poll timing on virtual clock, restore/sign-out paths).
  2 DECISIONS entries (no getCurrentUser round-trip; sign-out keeps Room rows).
- `:feature:auth`: ServerSetupScreen/ViewModel (live UDP list + manual URL, jellyfin-android
  error copy) and LoginScreen/ViewModel (public users, disclaimer, password, Quick Connect
  dialog); resolved server handed over via a feature-internal `PendingServerStore`.
  20 unit tests. Strings in `feature/auth/res/values/strings.xml`; plain Material 3 only —
  `:core:ui` untouched (design system is on the parallel M2 branch).
- `:app`: MainViewModel (restore once, splash held while `SessionState.Unknown`),
  JellyfinNavHost (ServerSetup → Login → Home, logout redirect driven by session state),
  temporary HomePlaceholderScreen with sign-out. 3 unit tests.
- Runtime fixes found by running on the tablet: SLF4J binding for the SDK (UDP discovery
  crashed without it) and a network-security-config permitting cleartext + user CAs
  (targetSdk 36 blocked plain-HTTP LAN servers). Both in DECISIONS.md.

### Next
- M2 (parallel branch): design system in `:core:ui`, Home screen, restyle auth screens
  onto the design system at integration.

### Known issues
- adb `input tap` injection is flaky on the test tablet (the OEM ROM): roughly one tap in two is
  silently dropped — retry loops with logcat confirmation needed when driving the UI.

---

### M2 (built on parallel worktree branch, now merged)

Built in parallel with M1 on `worktree-agent-ae7ad42c50e2b31bd`, merged after M1 completed.
Quality gate verified green on the branch by the orchestrator (full `--rerun-tasks` run):
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
- Integration (orchestrator): `:core:network` provides `org.jellyfin.sdk.api.client.ApiClient` to
  the Hilt graph (`di/NetworkModule.kt`, `ApiClientModule`); `HomeViewModel` is now `@HiltViewModel`;
  `Routes.Home` in the `:app` NavHost renders a new `HomeRoute` (`Scaffold` + `TopAppBar` with a
  sign-out action) hosting `HomeScreen(viewModel = hiltViewModel(), …)`, replacing the M1
  `HomePlaceholderScreen` (deleted). Bottom nav + `OfflineBanner` (`AppScaffold`) are not part of
  this pass — they arrive with the milestones that need them.

- On-device check (test tablet, 2026-07-28): home renders real test-server data — My Media
  (Films/Séries), Continue Watching with progress bars, Next Up, Latest Films/Séries — in
  portrait and landscape, no errors logged. Found and fixed en route: `OnlineJellyfinRepository`
  ran SDK calls on the caller's dispatcher, so loads from `viewModelScope` died with
  `NetworkOnMainThreadException` (invisible to JVM unit tests — no StrictMode); it now hops to
  the injected `@IoDispatcher` like the M1 repositories, and `ApiErrorMapper` logs any exception
  that falls into the `Unknown` bucket.

**Next**
- M3 (parallel worktree): `:feature:library` grid (Paging 3, sort/filter) + `:feature:search`.
- M4 (parallel worktree): `:feature:detail` + user-data repository (local-first + EventBus).

**Known issues (M2)**
- Write-through Room caching (`source=BROWSE_CACHE`) is intentionally absent; it is M6 scope.
- `DownloadBadge` always renders `NotDownloaded` until the M7 download pipeline supplies real
  states.

---

<!-- BEGIN M5 (playback) — appended by the M5 worktree; keep as one block when merging -->

### M5 — Playback (online) (built on a parallel worktree branch, awaiting device DoD)

**DoD (M5):** direct-play + forced transcode (server dashboard shows the method), track
switching, resume, no orphaned ffmpeg after exit.

**Done**
- `:player` built out: `DeviceProfileBuilder` (+ `MediaCodecProbe` seam, `CodecHelpers`),
  `PlaybackInfoResolver` (dash-less media-source-id quirk, play-method decision),
  `ExoMediaSourceFactory`, `PlaybackReporter` (5 s ticker, start/progress/stop,
  `stopEncodingProcess`, always-local `setPosition`), `DecoderFallbackHandler`,
  `PlayerHandle`/`ExoPlayerHandle`, `TrackSelectionController`,
  `PlaybackService : MediaSessionService`, `JellyfinAuthInterceptor`, `PlayerViewModel` +
  Compose player UI (play/pause, seek bar, audio/subtitle pickers, quality picker,
  immersive landscape).
- `:player/src/main/AndroidManifest.xml` declares the service and the
  foreground-service-media-playback permissions, so `:app`'s manifest is untouched.
- Wiring: `Routes.Player(itemId, mediaSourceId?, startPositionTicks)`, NavHost entry in
  `:app`, `:feature:detail` Play/Resume and per-episode play buttons navigate for real
  (the M4 snackbar stub is gone).
- 90 new unit tests in `:player`, 5 new in `:feature:detail`.
- 6 DECISIONS entries (MediaController divergence, markPlayed via `UserDataRepository`,
  profile toggles as parameters, `PlaybackMediaItemSpec`, Play-on-a-container semantics,
  the resolved M4 stub).

**Next**
- Device DoD walk by the orchestrator after merge: direct play, forced transcode via the
  quality picker at *Lowest*, audio/subtitle switching, resume, and `ps | grep ffmpeg`
  on the server after leaving the player.

**Known issues (M5)**
- No persisted preference for default quality or the ASS/SSA toggle — M9 settings.
- `POST_NOTIFICATIONS` is declared but never requested at runtime; on API 33+ with the
  permission denied playback continues but the media notification is invisible. M9.

<!-- END M5 (playback) -->

<!-- BEGIN M7 (downloads) — appended by the M7 worktree; keep as one block when merging -->

### M7 — Downloads (built on a parallel worktree branch, awaiting device DoD)

**DoD (M7):** a 2 GB movie resumes from its byte offset after an app kill; Wi-Fi-only is
honoured; delete frees bytes.

**Done**
- `:data:downloads` built out: `DownloadRepository`/`Impl`, `DownloadEnqueuer`,
  `DownloadDeleter`, `DownloadApi`, `plan/` (`DownloadFilePlanner`, `DownloadUrlFactory`,
  `DownloadPaths`), `engine/` (`FileDownloader` with HTTP Range resume, `DownloadQueue`,
  `ProgressThrottle`), `storage/` (`DownloadStorage` + `FileDownloadStorage`), `work/`
  (`DownloadWorker`, `DownloadScheduler`, `DownloadNotifier`, `DownloadActionReceiver`),
  plus its own `AndroidManifest.xml` and `strings.xml` so `:app`'s manifest is untouched.
- Room **v4**: `DownloadEntity` (`downloads`) + `DownloadFileEntity` (`download_files`,
  FK cascade) + `DownloadDao`, via a purely additive `@AutoMigration(3, 4)`; schema
  exported to `core/database/schemas/…/4.json`. `DownloadStatus` / `DownloadFileType`
  moved to `:core:common`.
- `AppPreferences.downloadOverWifiOnly` (defaults **on**) → WorkManager `UNMETERED` +
  `storageNotLow` constraints.
- `:feature:downloads`: *Downloaded* (grouped, sizes, delete) and *Queue* (progress,
  speed, pause/resume/cancel/reorder) tabs, storage header, Wi-Fi-only toggle.
- Fourth bottom-nav tab + `Routes.Downloads` NavHost entry (closes the M3/M4 entry
  "Downloads tab deferred to M7"); `POST_NOTIFICATIONS` requested once at startup
  (closes an M5 known issue).
- Badges wired app-wide: one `observeStates()` subscription each in home, library, search
  and detail, stamped onto `JellyfinItem.downloadState` — `:core:ui`'s cards unchanged.
- `:feature:detail`'s Download button is live (enqueue / cancel / remove / retry), closing
  the M4 stub.
- **+145 unit tests** (106 in `:data:downloads`, 22 in `:feature:downloads`, +6 detail,
  +4 datastore, +3 home, +2 search, +2 library); project total **551**.
- 10 DECISIONS entries; `docs/features/downloads.md` + a delimited ARCHITECTURE section.

**Next**
- Device DoD walk by the orchestrator after merge — see the merge report for the exact
  adb/Room/logcat commands (watch `bytesDownloaded`, kill mid-transfer, relaunch, confirm
  the `Range` header; toggle Wi-Fi-only on cellular; measure a delete).

**Known issues (M7)**
- SAF / secondary-volume (SD card) storage is not implemented and there is no storage
  location picker; `DownloadStorage` is the seam it goes behind (DECISIONS.md).
- Downloaded items still play through the **online** path — `LocalPlaybackResolver` is M8.
- Trickplay tiles are downloaded but nothing renders them yet (M9's scrubber).
- The queue runs one item at a time by design; there is no concurrency setting.

<!-- END M7 (downloads) -->
