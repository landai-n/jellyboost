# STATUS

## Current milestones: M3 — Library grid + Search, and M4 — Item detail + user data (built, DoD walk in progress)

**DoD (M3):** Paging 3 library grid with sort/filter + debounced search;
>500-item library scrolls clean, one request per page.
**DoD (M4):** item detail (movie/series/season) with local-first user-data writes +
EventBus (sync worker stubbed); mark played → appears in jellyfin-web; home row patches
without refetch.

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
- `datePlayed`/`lastPlayedDate` sent to the server carry UTC wall-clock time with the
  device's local offset appended (observed `17:22:57+02:00` for a 17:22 UTC event → the
  server stores it 2h early). Harmless for played/favorite state, but must be fixed
  before M8's most-recent-wins sync compares timestamps.
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
