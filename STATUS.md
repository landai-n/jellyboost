# STATUS

## Current milestone: M10 — Release hardening (next up)

**DoD (M10, docs/PLAN.md):** R8 rules (SDK serializers/Room/Hilt/Media3), baseline
profile, CI (GitHub Actions: assemble+detekt+test), signing; re-run M5/M8
verification on the minified build.

### Next
- Launch the M10 agent. Also on the M10 list from Known issues: the HEVC
  `VideoProfileNotSupported` investigation (device profile CodecProfile conditions
  vs `MediaCodecList` on the Helio G100).
- Note: the repo has no GitHub remote — CI can be authored but not exercised.

## Previous milestone: M9 — Polish (DONE, tagged m9)

Built in two sequential worktree passes (player polish, then settings + app-wide),
orchestrator-merged and gated: **748 tests, 0 failures** (forced rerun; 645 → 714 →
748 across the two merges).

**DoD walk on test tablet (2026-07-29), all drivable checks pass:**
- **Speed:** sheet 0.5×–2×; 1.5× measured for real — 45 s of media in 30 s of wall
  clock; indicator in the top bar; resets on exit by design.
- **Gestures:** double-tap thirds seek exactly +30 s / −10 s (verified via
  `dumpsys media_session` position deltas); vertical swipes drive volume (overlay,
  stream 15/15) and brightness (overlay, window attribute) on the correct halves.
- **PiP:** Home during playback floats the video at the film's aspect ratio, no
  controls, still playing; Home from a non-player screen floats nothing; exiting
  the player releases the session.
- **Background playback (M5 known issue closed):** root cause was that no
  `MediaController` ever connects (UI drives ExoPlayer directly), so the session was
  never *added* to `PlaybackService` and Media3 never promoted it. `addSession()` in
  `onCreate` fixed it: `isForeground=true` (mediaPlayback type), media3 transport
  notification, session `active=true` with a launch intent.
- **Server-source regression:** `PlaybackInfo` + full reporting triad unchanged;
  `MediaSegments` (Intro/Outro) fetched once → "Loaded 0 media segment(s)", no button.
- **Settings screen:** all four sections render (portrait + landscape, content capped
  ~640 dp); every pref row is whole-row tappable (verified by tapping labels, not
  controls: segment-skip radios + PiP switch); storage line + fixed location shown;
  Account shows Alex / test-server; sign-out dialog opens with the
  "Also delete downloads" checkbox — **cancelled, not confirmed** (signing out would
  strand the session; the flow below the dialog is pinned by `coVerifyOrder` tests).
- **Hit-target fixes (M7 note closed):** Downloads Wi-Fi-only row toggles from a tap
  on its *label*, first attempt, both directions.
- **Offline push gate (M8 note closed):** ~30 s of offline local playback produced
  7 debug "stays pending (offline, not pushing)" lines and **zero** doomed HTTP
  POSTs / warning stacks (was one per 5 s tick).
- **Refresh-on-reconnect (M6 known issue closed):** on the airplane-off edge, with no
  input, live screens re-fetched themselves (logcat: `UserViews`, the open item +
  `/Similar`) and the pending user-data row drained in ~1 s; the detail screen
  visibly gained its full online content without re-entry. One refresh per edge, no
  storm.

**Not device-verifiable on this setup** (recorded, pinned by unit tests instead):
- Trickplay scrubber *positive* path: test-server has trickplay generated for zero
  items, and Alex's token is not admin (403 on `/ScheduledTasks`), so tiles
  cannot be generated. Absence path verified live (plain bar, no crash);
  tile-selection math pinned by `TrickplayResolverTest`/`TrickplayTiles` tests.
- Segment-skip *positive* path (button/auto-skip): no intro-detection plugin on the
  server. Graceful absence verified live; controller pinned by
  `SegmentSkipControllerTest` (incl. the seek-back anti-loop rule).
- Headphone-pull pause (becoming-noisy): no wired headphones on the test bench.

Server user data restored (Ouistreham + 28 Ans plus tard: pos 0, unplayed, count 0);
the four downloads remain on the tablet.

## Previous milestone: M8 — Offline playback + sync (DONE, tagged m8)

**DoD walk on test tablet (2026-07-29), all pass** (test film: Ouistreham, 0.6 GB,
runtime 106.4 min):
- **Offline local playback:** airplane-mode cold start → offline home (badged rows) →
  detail → Play. Logcat: `Playing <id> from local storage`; **zero** server requests —
  no `PlaybackInfo` POST, no `Sessions/*` triad (each 5 s tick logs
  `nothing to report to the server`). Badge *Direct play*; no quality button. Player
  landscape verified during the same session.
- **Seek + local position:** instant seek to 53:53 (≈51 %), 20 s of playback, exit →
  `user_data` row at 32,728,010,000 ticks, `toBeSynced=1`; offline detail immediately
  shows "51 min left · Resume".
- **Reconnect push (the DoD headline):** airplane off → within ~1 s
  `UserDataSyncTrigger` → worker → `Pushed the local user data (it was newer)` →
  server `PlaybackPositionTicks` exactly 32,728,010,000 (51.2 %), flag cleared.
- **Reverse (adoption):** offline mark-watched, then a newer contradicting server
  write → cold start → `Adopted the server's user data (it was newer)`; local change
  correctly discarded. Most-recent-wins verified in both directions.
- **Bug found & fixed during the walk:** the app-start drain raced the session
  restore — first attempt died on `MissingBaseUrlException` and burned a 30 s
  WorkManager backoff before the retry succeeded. Fixed by hoisting M7's
  `DownloadSessionGate` to `:core:network` as a shared `SessionGate` used by both
  `DownloadQueue` and `UserDataSyncWorker` (DECISIONS.md 2026-07-29). Re-walked:
  `SessionGate` restores the session inside the worker and the **first** attempt
  pushes in ~1.1 s.
- Server user data restored as found (position 0, unplayed, play count 0); the four
  downloads left on the tablet.
- Note for M9 (new): while offline, `UserDataRepositoryImpl` still attempts one doomed
  position-push per 5 s tick (fails fast, row stays pending — harmless but noisy).

## Previous milestone: M7 — Downloads (DONE, tagged m7)

**DoD walk on test tablet (2026-07-28/29), all pass:**
- **Byte-offset resume after app kill:** Backrooms (2.94 GB) killed via `force-stop` at
  exactly 861,145,720 bytes → relaunch → after WorkManager's retry backoff + the OEM ROM
  scheduling (~75 s) the worker Range-resumed **the same file** from that offset —
  monotonic growth to completion, no truncation, no second file. (First walk caught
  bugs A/B below; this is the post-fix result.)
- **Wi-Fi-only honored:** the WorkManager job requires the `NOT_METERED` capability
  (JobScheduler dump) with the toggle on (its default); the Downloads-top-bar switch
  writes `download_over_wifi_only` to DataStore and `restart()`s the unique work
  (REPLACE) so the new constraint applies immediately. No SIM in the tablet, so
  cellular end-to-end wasn't drivable — constraint-level verification.
- **Delete frees bytes:** queue-tab delete freed 4,220,780 KB in one cascade (incl. an
  orphaned partial from bug B), detail *Remove* freed exactly the 2,870,691 KB media
  file; directories removed, headers/live state update; delete also works fully
  offline.
- **Parent prune:** two Bref episodes downloaded → E1 deleted offline → series and
  season pages still open offline showing only E2.
- **Offline integration:** cold-start in airplane mode shows Next Up (Bref E1, badge),
  Latest Films row, Films grid (3 movies, all badged), Séries grid (Bref), full
  series→season→episode offline navigation, offline search with badges.
- File plan on disk matches the plan (`Movie (Year)/` and
  `Series - S01E02 - Title/` dirs; primary → media (server filename) → backdrop /
  series-primary; images webp).
- `POST_NOTIFICATIONS` requested at first launch (granted); foreground download
  notification observed.
- UX note for M9: the Wi-Fi-only *label* is not tappable (only the Switch), and the
  overflow *Offline mode* row is the same pattern in reverse — unify hit targets.

### M7 device-walk bugfixes (merged `172afd3`, re-walk done)
Four findings from the first DoD walk, all fixed with unit coverage (+25 tests) and
re-verified on device where applicable:
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
- ~~Screens loaded while offline keep their offline data after connectivity returns until
  the user re-enters them (e.g. Home shows only cached My Media after a reconnect; a
  killed/relaunched app is fine). The delegating repository is per-call, but ViewModels
  don't re-fetch on connection regain — wire a refresh-on-reconnect (or pull-to-refresh)
  by M9.~~ — **fixed on the M9 branch**: `ReconnectRefresher` (`:data`) publishes a
  `false → true` connectivity edge that home, libraries, search, item detail and the
  library grid's filter facets re-load themselves on (the grid's items already swap via
  `getItemsPaged`). See `docs/features/offline-read.md`, "Coming back online".
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

<!-- BEGIN M8 (offline playback + sync) — appended by the M8 worktree; keep as one block when merging -->

### M8 — Offline playback + sync (built on a parallel worktree branch, awaiting device DoD)

**DoD (M8):** airplane-mode playback to 50% → reconnect → server shows 50% resume.

**Done**
- `:player` offline playback: `LocalPlaybackMediaSource` (second variant of the M5 sealed
  type, `DIRECT_PLAY` by construction) + `LocalTrickplay`; `LocalPlaybackResolver`;
  `PlaybackSourceResolver` — **a completed download always wins, whatever the connection
  is doing**; no local copy + offline → immediate `AppError.Network`, never a hang.
  `ExoMediaSourceFactory`, `PlaybackReporter` and `DecoderFallbackHandler` widened to the
  sealed type; local `file://` URIs (media + subtitle sidecars) bypass `StreamUrlFactory`.
- `:data:downloads` `offline/DownloadedMediaProvider` — the playable/not-playable gate:
  row `DOWNLOADED`, media file row `DOWNLOADED`, **and** the bytes still on disk;
  optional files filtered one by one. Keeps `:player` free of DAOs.
- `:core:database` `DownloadDao.getWithFiles(itemId)`. **No schema change — still v4.**
- Offline reporting guard: the server triad and `stopEncodingProcess` are skipped for a
  local source *and* whenever `ConnectionState` is offline; `setPosition` / `setPlayed`
  still run on every tick and on stop, so an airplane-mode session leaves exactly the
  `toBeSynced = true` rows the worker drains.
- `:data` `UserDataSyncer` — real most-recent-wins (server `lastPlayedDate` vs local
  `updatedAt`, both via `SdkDateTime`): local newer → push the whole row through
  markPlayed/markFavorite/`updateItemUserData` in that order; server newer or tied →
  adopt + emit on the event bus; `null`/absent server data → push; transport failure →
  keep the flag + `Result.retry()`; 404 → abandon the row. `UserDataSyncWorker` is no
  longer a stub.
- `:data` `UserDataSyncTrigger` + `JellyfinNativeApplication.onCreate` — enqueues the
  drain at app start and on every return to `ONLINE`, guarded on `countPendingSync()`.
  Without it the DoD path has nothing to enqueue the worker.
- Offline trickplay tile URIs + geometry reachable on the local source
  (`LocalTrickplay.tileFor(positionMs)` → sheet/column/row); the scrubber itself is M9.
- Player UI is identical online/offline except one control: the quality picker is hidden
  for a local source (nothing to cap). Track/subtitle pickers unchanged.
- **+74 unit tests** (13 `:data:downloads`, 22 new + 14 extended `:player`, 24 `:data`,
  +1 `:feature:detail`); project total **661**, 0 failures. Full gate green in one run
  (`ktlintCheck detekt testDebugUnitTest assembleDebug`).
- 7 DECISIONS entries; `docs/features/offline-playback.md`, `user-data.md` sync section
  rewritten, delimited ARCHITECTURE section.

**Next — device DoD walk (orchestrator)**
1. `./gradlew installDebug`, launch, confirm the four downloads are still `DOWNLOADED`
   (`adb shell run-as dev.jellyfinnative.app.debug sqlite3 databases/jellyfin.db 'SELECT itemName,status FROM downloads;'`).
2. Note the server's current position for the test film
   (`/Users/{userId}/Items/{itemId}` → `UserData.PlaybackPositionTicks`).
3. `adb shell cmd connectivity airplane-mode enable`; confirm the offline banner.
4. Open the film's detail page → **Play**. Expect in logcat:
   `Playing <itemId> from local storage` and **no** `POST /Items/{id}/PlaybackInfo`.
   The player badge reads *Direct play*; there is **no** quality button.
5. Seek to ~50 %, leave it playing ≥ 15 s, then back out of the player. Expect
   `Playing <itemId> locally; nothing to report to the server` at debug level and **zero**
   `Sessions/Playing` requests.
6. Confirm the pending row:
   `adb shell run-as … sqlite3 databases/jellyfin.db 'SELECT itemId,playbackPositionTicks,toBeSynced FROM user_data WHERE toBeSynced=1;'`
   — one row, position ≈ 50 % of runtime in ticks.
7. `adb shell cmd connectivity airplane-mode disable`. Watch for
   `… user-data row(s) pending and the server is reachable; scheduling a sync` then
   `Reconciling N pending user-data row(s)` and `Pushed the local user data for <itemId> (it was newer)`.
   (the OEM ROM can delay WorkManager; `adb shell cmd jobscheduler run -f dev.jellyfinnative.app.debug <id>`
   forces it.)
8. Re-read the server item — `PlaybackPositionTicks` should now match step 6, and the
   detail screen in jellyfin-web should show the ~50 % progress bar. Re-query `user_data`:
   `toBeSynced` back to 0.
9. Reverse check while online: mark the film watched in jellyfin-web, then toggle
   *Mark watched* off in the app while offline with an **older** local timestamp, reconnect,
   and confirm the app adopts the server value (`Adopted the server's user data for <itemId>`).
10. Tablet/landscape sanity check on the player while playing locally.

**Known issues (M8)**
- Trickplay tiles are reachable but nothing renders them — M9's scrubber.
- The quality picker is absent during local playback by design (DECISIONS.md); there is no
  "play the server copy instead" affordance for a downloaded item.
- A local file this device cannot decode falls back to a server transcode, so offline it
  simply fails — there is no local transcode and never will be.
- Carried over from M6: screens loaded while offline keep their offline data until
  re-entered; a refresh-on-reconnect is still M9.

<!-- END M8 (offline playback + sync) -->

<!-- BEGIN M9 (player polish) — appended by the M9 worktree; keep as one block when merging -->

## M9 — player polish (worktree branch `worktree-agent-acf3fac666db7d869`)

The **player half** of M9 only. The settings screen and the app-wide polish pass are a parallel
branch; this branch adds the data layer and the defaults its author will surface, and touches
nothing in `:feature:settings`.

**Done**
- **Trickplay scrubber.** `model/TrickplayTiles` (geometry + `tileFor(positionMs)` → sheet, column,
  row — now the single implementation, with `LocalTrickplay.tileFor` delegating to it),
  `trickplay/TrickplayResolver` (offline: the sheets M7 downloaded; online: the item's `trickplay`
  map, closest width to 320 px, one tile URL per derived sheet), `ui/TrickplayPreview` (draws the
  whole sprite sheet offset inside a clipping window, so neighbouring thumbnails are Coil cache
  hits). The preview follows the thumb, is clamped to the seek bar, and is simply absent when the
  item has no thumbnails.
- **Media segments.** `segments/MediaSegmentLoader` (`getItemSegments(INTRO, OUTRO)`, server-only,
  every failure ends at "no segments"), `segments/SegmentSkipController` (`OFF` / `SHOW_BUTTON` /
  `AUTO_SKIP` per type; auto-skip fires **once per segment** so a user who seeks back is not put in
  a loop), and a "Skip intro"/"Skip outro" button that is deliberately independent of the controls'
  visibility.
- **Picture-in-picture.** `pip/PipController` (`@Singleton` seam: the player publishes "route up +
  playing + preference on", `MainActivity` arms `setAutoEnterEnabled` on API 31+ and falls back to
  `onUserLeaveHint` on API 26–30). Aspect ratio from the decoded video size, clamped to Android's
  1:2.39 … 2.39:1. In PiP the screen draws bare video; the media notification carries transport.
- **Gestures.** `gesture/PlayerGestureController` (zones, 0.66-screen full sweep, 48 dp/64 dp
  exclusion margins — jellyfin-android's numbers) plus `ui/PlayerGestureLayer` (`AudioManager`,
  window brightness, transient indicator). Left-half swipe = brightness, right-half = volume,
  double-tap outer thirds = −10 s/+30 s, middle third dead, single tap toggles the controls
  (which now auto-hide after 4 s while playing).
- **Playback speed.** `model/PlaybackSpeed` 0.5×–2×, a fourth picker in the existing dialog host,
  shown on the control when it is not 1×. Session-scoped and re-applied after every re-resolve.
- **Background playback — root cause found and fixed.** Media3 only manages a session (notification,
  foreground promotion) once it has been **added** to the service; that normally happens when a
  `MediaController` connects, and this app deliberately has none, so nothing ever added it and the
  service was never promoted. `PlaybackService.onCreate` now calls `addSession` itself, sets a
  session activity `PendingIntent`, and handles `onForegroundServiceStartNotAllowedException`.
  `ExoPlayerHandle` adds `setHandleAudioBecomingNoisy(true)` and `setWakeMode(WAKE_MODE_NETWORK)`.
  The `POST_NOTIFICATIONS` explanation carried in the M5 known issues was wrong — the permission
  only ever decided whether the notification was *visible*.
- **Tablet/landscape.** The controls bar is width-capped (1000 dp) and centred; the trickplay
  preview is clamped inside the bar; the immersive-landscape effect stands down in PiP.
- **New preferences** (data layer + defaults only, for the settings branch): `segment_skip_intro`
  and `segment_skip_outro` (`SegmentSkipMode` = `OFF`/`SHOW_BUTTON`/`AUTO_SKIP`, default
  `SHOW_BUTTON`), `pip_on_leave` (`Boolean`, default `true`). `:player` gained
  `implementation(projects.core.datastore)` to read them.
- **+53 unit tests** (`TrickplayTilesTest` 7, `TrickplayResolverTest` 9, `MediaSegmentLoaderTest` 8,
  `SegmentSkipControllerTest` 10, `PlayerGestureControllerTest` 8, `PipControllerTest` 8, plus 13 new
  `PlayerViewModelTest` cases and 7 new `DataStoreAppPreferencesTest` cases); project total **714**,
  0 failures. Full gate green in one run (`ktlintCheck detekt testDebugUnitTest assembleDebug`).
- 7 DECISIONS entries; `docs/features/playback.md` M9 section, delimited ARCHITECTURE section.

**Next — device verification (orchestrator)**
1. `./gradlew installDebug`, open a **server** movie that has trickplay generated.
2. **Trickplay:** drag the seek bar. A thumbnail with a time label should appear above it, follow the
   thumb, and stay inside the bar at both ends. Logcat has nothing to say when it works; when the
   item has none, expect `No trickplay available for <itemId>` at debug level and a plain bar.
   Repeat in **airplane mode** on a downloaded item — same preview, no network.
3. **Segments:** open an episode of a series with an intro-detection plugin. Expect a *Skip intro*
   button while inside the intro; tapping it jumps to its end. Turn the intro preference to
   `AUTO_SKIP` (until the settings screen lands:
   `adb shell run-as dev.jellyfinnative.app.debug` … or simply verify `SHOW_BUTTON`) and watch for
   `Auto-skipping INTRO to <ms> ms`, then seek back into the intro and confirm it is **not** skipped
   again — a button appears instead. On a server without the plugin expect
   `No media segments available for <itemId>` and no button at all.
4. **Background playback (the M5 known issue):** start playback, press Home. Audio must continue and
   a media notification with play/pause must appear. `adb shell dumpsys media_session | grep -A3
   jellyfinnative` shows the session; `adb shell dumpsys activity services PlaybackService` should
   show `isForeground=true`. Tap the notification → back in the player at the live position.
   Pull the headphones/disconnect Bluetooth → playback pauses.
5. **PiP:** while playing, press Home (or swipe up). The video should shrink into a floating window
   with no controls, at the film's aspect ratio. Returning to the app restores the full UI.
   Repeat from the **library grid** (not playing) and confirm nothing floats.
6. **Gestures:** swipe up/down on the left half → brightness overlay; right half → volume overlay.
   Double-tap the left third → −10 s, right third → +30 s, middle → nothing. Single tap toggles the
   controls; they fade after ~4 s while playing and stay while paused. Swipe from the extreme edges
   and confirm the system's back gesture still works.
7. **Speed:** the *Speed* control opens the 0.5×–2× picker; the chosen rate shows in the bar and the
   top bar. Change quality and confirm the rate survives the reload. Leave and re-enter the player —
   it is back to 1× by design.
8. **Tablet/landscape:** repeat 2, 3 and 6 at 2560×1600 landscape and in portrait.

**Known issues (M9 player)**
- Auto-skipping an outro that runs to the end of the file ends the item and closes the player; there
  is no queue to advance to the next episode (out of scope until a queue exists).
- The trickplay tile URL carries the access token as an `ApiKey` query parameter so Coil can fetch
  it (DECISIONS.md 2026-07-29); it lives only in Coil's in-memory cache key.
- Brightness is a window override and is not remembered between sessions — jellyfin-android has a
  `rememberBrightness` preference, this branch does not.
- The double-tap seek has no ripple/animation feedback yet; the position simply moves.
- Carried over: screens loaded while offline keep their offline data until re-entered (the
  refresh-on-reconnect belongs to the app-wide polish half of M9).

<!-- END M9 (player polish) -->

<!-- BEGIN M9 (settings + app polish) — appended by the M9 worktree; keep as one block when merging -->

## M9 — settings + app polish (worktree branch `worktree-agent-a041cc39c512aaa0f`)

The **settings + app-wide polish half** of M9 only. `:player` (trickplay, segments, PiP, gestures,
speed, background playback) is the parallel branch documented in the block above; this branch does
not touch `:player` and reads the preferences that branch defined (`introSkipMode`, `outroSkipMode`,
`pipOnLeave`).

**Done**
- **`:feature:settings` — the full settings screen.** `SettingsViewModel` folds five
  `AppPreferences` flows, `DownloadRepository.observeStorage()` and `SessionRepository.sessionState`
  into one `StateFlow<SettingsUiState>`. `SettingsScreen` (`Scaffold` + back-button `TopAppBar`,
  content capped at 640 dp and centred so a 2560×1600 tablet doesn't strand a label at one edge and
  its control at the other) renders four sections: **Playback** (Skip intro / Skip outro — three-way
  `SegmentSkipMode` choice each — and Picture-in-picture on leave), **Downloads** (Wi-Fi-only switch
  plus an informational used/free/root-path storage line — no location picker, see Known issues),
  **Connectivity** (Offline mode switch), **Account** (user name, server name, Sign out). Every row
  is a single `Modifier.toggleable`/`.selectable` container (`Role.Switch`/`Role.RadioButton`,
  `heightIn(min = 48.dp)`) so the whole row is the touch target, not just the trailing control. Sign
  out opens a confirm `AlertDialog` with an unchecked-by-default "Also delete downloads" checkbox;
  when checked, `SettingsViewModel.signOut` snapshots the current download list, best-effort-deletes
  every item, and only then calls `SessionRepository.signOut()` — verified in order with
  `coVerifyOrder`. Reached from the home top-bar overflow menu's new *Settings* entry, which replaces
  the temporary M8 *Sign out* entry there (DECISIONS.md, two entries: storage picker deferred to ship
  with SAF support; Settings behind the existing overflow icon, not a new avatar).
- **Dead sign-out plumbing removed.** `onSignOut` no longer threads through `MainActivity` →
  `JellyfinNativeApp` → `AppScaffold` → `JellyfinNavHost` → `HomeRoute`; `MainViewModel.signOut()` is
  gone along with its test. `MainViewModel` still restores/exposes the session.
- **Hit-target fix.** `feature/downloads/DownloadsScreen.kt`'s Wi-Fi-only top-bar row now toggles on
  the whole row (`Modifier.toggleable(role = Role.Switch)`, `Switch.onCheckedChange = null`), closing
  the STATUS M7 note. The home overflow's Offline-mode row already dispatched on the whole
  `DropdownMenuItem`; it gained explicit `Role.Switch` + on/off `stateDescription` for TalkBack.
- **Refresh-on-reconnect**, closing the M6 known issue. `Flow<ConnectionState>.reconnectEdges()`
  (`:core:network`) emits once per `false → true` edge, dropping the flow's initial value (so a
  normal launch — which already fetches once in every `init` — does not double-fetch; this is
  deliberately narrower than `UserDataSyncTrigger`'s convention, DECISIONS.md). `ReconnectRefresher`
  (`:data`) wraps it as a bare `Flow<Unit>` so feature modules never need `core:network` on their
  classpath. Wired into `HomeViewModel`/`LibrariesViewModel`/`ItemDetailViewModel` (call their
  existing `refresh()`), `SearchViewModel` (`retry()`, only if the query is non-blank), and
  `LibraryViewModel` (facets only — the grid already rebuilds its `Pager` per connection change
  inside `DelegatingJellyfinRepository.getItemsPaged`, so it needed no new wiring).
- **Offline user-data push silenced**, closing the M8 known issue. `UserDataRepositoryImpl.
  pushToServer` now returns immediately (one `Timber.d` line, no HTTP attempt, no
  `syncScheduler.enqueue()`) when `ConnectionStateProvider.state.value.isOnline` is false — the
  local Room write (`toBeSynced = true`) and the `UserDataEventBus` emission both still happen
  unconditionally beforehand. `UserDataSyncTrigger` already drains every pending row on the next
  `OFFLINE → ONLINE` edge, so nothing is lost; a five-minute offline playback session now logs one
  debug line total instead of ~60 warning stacks. Online behaviour is byte-for-byte unchanged.
- **+34 unit tests** (12 `SettingsViewModelTest`, 15 across `ReconnectEdgesTest`/
  `ReconnectRefresherTest`/the five reconnect-wired ViewModel test files, 5 `UserDataRepositoryImplTest`,
  2 `MainViewModelTest` net); project total **748**, 0 failures, 0 skipped (independently counted
  from the JUnit XML, not the gradle summary line). Full gate green in one run
  (`ktlintCheck detekt testDebugUnitTest assembleDebug`).
- 4 DECISIONS entries (storage picker deferral; Settings via overflow not avatar; offline write does
  not enqueue the sync worker; reconnect signal drops its initial value); `docs/features/settings.md`
  (new), `docs/features/offline-read.md` and `docs/features/user-data.md` updated.

**Next — device verification (orchestrator)**
1. `./gradlew installDebug`, launch signed in.
2. **Settings screen:** tap the home top-bar overflow (⋮) → *Settings* (no more *Sign out* there).
   Confirm all four sections render; toggle each switch and each three-way skip choice, relaunch the
   app, confirm every choice persisted (`adb shell run-as dev.jellyfinnative.app.debug` +
   `DataStore` prefs, or just observe the UI survives the relaunch). Rotate to landscape on the
   tablet — content should stay capped and centred, not stretch edge-to-edge.
3. **Sign out:** tap *Sign out* → confirm dialog appears with the "Also delete downloads" checkbox
   unchecked. Cancel, confirm nothing happened. Download one small item first, then sign out with the
   checkbox checked — confirm the download is gone (`adb shell run-as … sqlite3 databases/jellyfin.db
   'SELECT COUNT(*) FROM downloads;'` → 0) and the app lands on server setup. Sign back in.
4. **Hit targets:** in Downloads, tap anywhere on the Wi-Fi-only row (not just the switch) — it
   toggles. In the home overflow, tap anywhere on the Offline-mode row — it toggles (this already
   worked; confirm no regression).
5. **Refresh-on-reconnect:** airplane mode on, browse Home/Libraries/Search/an item detail page so
   each loads its offline data, airplane mode off. Within a couple seconds each screen should
   silently refresh — watch logcat for each screen's normal load call re-firing exactly once (no
   storm), and check Home/Search in particular show live data again without leaving the screen.
6. **Offline push silence:** airplane mode on, start local playback of a download, let it run
   ~30 s (six 5-second ticks). Logcat should show six `User data for … stays pending (offline, not
   pushing)` debug lines and **zero** `stays pending: …` warning-with-stack-trace lines. Reconnect —
   the existing M8 drain path (`UserDataSyncTrigger` → `Pushed the local user data…`) still fires.

**Known issues (M9 settings + app polish)**
- No storage-location picker; the Downloads section shows the fixed location as text only. Ships
  with SAF/SD-card support (DECISIONS.md).
- The Account section has no server *address* field (only server name) — `SessionState.LoggedIn`
  doesn't carry it and no accessor exists on `SessionRepository` for feature code; would need new
  surface on `:core:network` to add.
- Settings is reached via the existing overflow menu icon, not a dedicated avatar (DECISIONS.md) —
  there is no user-avatar image/asset pipeline anywhere in the app yet.
- Not verified on device by this worktree (rule: no adb/device access from the settings-branch
  agent) — see the walk above for the orchestrator to run after merge.

<!-- END M9 (settings + app polish) -->

<!-- BEGIN M9 (downloads polish) — appended by the downloads-polish worktree; keep as one block when merging -->

## M9 — downloads polish (worktree branch `worktree-agent-a4271a149498eba88`)

Five findings from the M9 device walk (docs/POLISH.md), all in the downloads domain. No
`:player`, no `:feature:detail`, no schema change (new DAO query only — still Room v4).

**Done**
- **Download speed was 20× too high** (100–180 MB/s shown for a 2–8 MB/s transfer).
  `DownloadDao.observeAll` is a `@Transaction` over `downloads` *and* `download_files` and
  `DownloadQueue` writes the file's byte counter then the item's back to back, so one
  throttled update emits two or three times milliseconds apart — and `DownloadSpeedTracker`
  divided a whole window's bytes by that gap. The tracker now folds a measurement only once
  ≥ 1 s has passed since the last one; nearer samples accumulate against the same anchor.
  `DownloadRepositoryImpl.observeDownloads()` also gained the `distinctUntilChanged` its
  sibling `observeStates()` already had.
- **Pause did not stick.** *Pause* writes `PAUSED` and then cancels the work to interrupt the
  transfer; `DownloadQueue`'s cancellation handler unconditionally wrote the row back to
  `QUEUED`, and `nextRunnable` picked it straight back up. The handler now uses a new
  `DownloadDao.requeueIfDownloading(itemId, updatedAt)` whose `WHERE` clause carries the
  status test, so it cannot overwrite a status someone else has since written. (Table-wide
  `requeueInterrupted` was already `WHERE status = 'DOWNLOADING'` and needed no change.)
- **Films were drawn under a heading of their own title** ("Dune" over "Dune"). Only series
  get a `GroupHeader` now (`DownloadGroup.isSeries`); a film is a group of one drawn without
  one, and two films sharing a title no longer merge. Series and films interleave
  alphabetically. `DownloadItem.groupKey` → `seriesKey` (`null` for a film).
- **Wi-Fi-only toggle** in the Downloads top bar: label and switch were touching; the row now
  uses `Arrangement.spacedBy(Dimens.SpaceSmall)`. Placement unchanged (DECISIONS.md
  2026-07-28/29).
- **Deleting a finished download now asks first** — an M3 `AlertDialog` ("Delete &lt;title&gt;?",
  Cancel/Delete) modelled on the settings sign-out dialog. Queue-tab *Cancel* stays immediate
  by design (it only costs the bytes not yet spent).
- **+5 unit tests** in the touched files (`DownloadSpeedTrackerTest` 8→9,
  `DownloadsViewModelTest` 14→16, `DownloadQueueTest` 23→24, `DownloadRepositoryImplTest`
  18→19), 0 failures. Full gate green in one run
  (`ktlintCheck detekt testDebugUnitTest assembleDebug`).
- 2 DECISIONS entries (series-only headings; the one-second speed window, which replaces one
  test's expectation).

**Next — device verification (orchestrator)**
1. Queue a large item and watch the Queue tab: the speed line should read single-digit MB/s
   and update about once a second, not 100+ MB/s.
2. Press *Pause* on the item that is transferring: the row must say **Paused** and stay that
   way (`… sqlite3 databases/jellyfin.db 'SELECT itemName,status FROM downloads;'` → `PAUSED`),
   with no bytes growing. Anything else queued behind it must keep downloading. *Resume*
   restarts it from its byte offset. Repeat from the notification's *Pause* action.
3. Downloaded tab: a film shows one row and no heading above it; a series still shows its name
   over its episodes; both sorted together alphabetically.
4. Downloads top bar: a visible gap between "Wi-Fi only" and the switch; the whole row still
   toggles (the M9 hit-target fix).
5. Delete a downloaded film: the confirm dialog names it; *Cancel* leaves it alone, *Delete*
   removes it and frees the bytes. Queue-tab *Cancel* still deletes immediately.

**Known issues (M9 downloads polish)**
- The pause guard now lives in SQL (`requeueIfDownloading`), which the JVM unit tests cannot
  execute — there is no Room/Robolectric test setup in this project. The tests pin that the
  queue calls the conditional statement and never the unconditional `setStatus(QUEUED)`;
  the statement itself is verified on device (step 2 above).
- The speed reading can still overshoot briefly when a fold lands just after an item-level
  write and the previous one landed just before one; the EMA damps it and it is bounded by the
  window, nowhere near the 20× that was reported.
- The delete confirmation is `remember`ed, not `rememberSaveable`d: rotating the tablet while
  the dialog is open dismisses it (nothing is deleted).

<!-- END M9 (downloads polish) -->

