# Quality audit — 2026-08-06 (code quality, duplication, complexity, architecture)

Third full audit. Unlike the two diff audits (`audit-2026-07.md`, `audit-2026-08.md`),
this one is a *whole-tree structural* pass over production source (334 files, ~58k lines,
`*/src/main/*`, worktrees/build excluded): architecture conformance vs `docs/PLAN.md`,
semantic duplication, complexity/spaghetti, and code hygiene.

Method: 4 read-only auditors (Fable on architecture, Opus on duplication / complexity /
hygiene), each primed with `DECISIONS.md` + `STATUS.md` so logged decisions and the known
backlog (the `DownloadedMetadataRefresher`/`DownloadedMediaProvider` seam,
`removeDoomedContainerRow`'s catch) are screened out, not re-reported. The orchestrator
independently grep-verified the three headline claims (HYG-1, HYG-2, CPX-3) — all three
confirmed. Finding IDs are per-auditor (ARCH/DUP/CPX/HYG) for traceability.

## 1. Executive summary

**44 unique findings** (50 raw; 6 merged as cross-auditor duplicates — the strongest
signal in the audit, since independent auditors converged on them):
**0 Critical · 8 High · 19 Medium · 17 Low.**

The tree is in genuinely good structural shape. The module graph matches PLAN.md exactly
(verified from all 16 build files: `core ← data ← {feature,player} ← app`, zero
feature→feature edges, zero SDK types reaching UI), the offline seam is clean, DI is
disciplined, and hygiene basics are excellent (zero `!!`, zero `println`, zero TODOs,
consistent cancellation-rethrow discipline *except* one module — see HYG-4). The problems
cluster in four places:

| Cluster | Findings | Severity peak |
|---|---|---|
| Dead retention paths (cache/user-data never evicted) | HYG-1, HYG-2, HYG-3 | High |
| `SyncPlayController` internals | CPX-1/ARCH-5, CPX-2, CPX-4, CPX-6, CPX-15, HYG-4 | High |
| Quality-gate blind spots (detekt globals, unenforced invariants) | CPX-3+CPX-14, ARCH-4, CPX-10 | High |
| Drifted copy-paste (error copy, badges, auth headers, snackbars) | DUP-1..5, DUP-7, CPX-13 | High |

The rest is API-surface tightening (ARCH-1..3, ARCH-9/10), Compose decomposition
(CPX-7..9, CPX-11/DUP-9), and small-constant consolidation (DUP-6, DUP-10..15).

## 2. High findings

### H1. Browse cache is write-only — eviction was never wired (HYG-1) — ORCHESTRATOR-VERIFIED
`ItemDao.evictBrowseCacheOlderThan` (`core/database/.../ItemDao.kt:286`) has **zero
callers** in production or test code. Every server read writes through as `BROWSE_CACHE`
rows carrying full `BaseItemDto` JSON blobs; nothing ever deletes one. PLAN.md:57 states
the policy ("DOWNLOAD rows never evicted" + `cachedAt`) — the `cachedAt` half was never
wired. The `items` table grows monotonically for the life of the install, and
server-deleted items keep resolving offline forever.
**Fix:** call it with a TTL from an app-scope entry point (`JellyboostApplication.onCreate`
beside `downloadedMetadataRefresher.start()`, or the user-data sync worker) + unit test.

### H2. Sign-out leaves every Room table intact; `deleteSynced` is dead code (HYG-2) — ORCHESTRATOR-VERIFIED
`UserDataDao.deleteSynced(userId)` (`UserDataDao.kt:117`) has zero callers (the
similarly-named `DownloadDao.deleteSyncedUserData` is a different, used method).
`SessionRepository.runSignOut()` (`SessionRepository.kt:131-139`) clears credentials,
home layout, and the API client only. On a shared tablet, user A's cached items (not
user-scoped) still serve user B's offline read path and search, and A's `user_data` rows
persist indefinitely.
**Fix:** invoke `deleteSynced(userId)` + a browse-cache wipe from `runSignOut`, before
`sessionStateHolder.update(LoggedOut)`.

### H3. `BrowseCacheWriter` item merge races `DownloadEnqueuer` — can downgrade a DOWNLOAD row (HYG-3)
`BrowseCacheWriter.kt:148→186`: `getCacheKeys` snapshot → 30 lines of merge → `upsert`,
no transaction, fire-and-forget on `@ApplicationScope`. Races the enqueuer's
`itemDao.upsert(... ItemSource.DOWNLOAD ...)` (`DownloadEnqueuer.kt:247`). Window: open a
season page (lean list write in flight) and tap Download — the stale snapshot's
else-branch upserts `source = BROWSE_CACHE` with the lean blob over the rich DOWNLOAD
row. This is exactly the POLISH.md bug the class exists to prevent, and once H1 is fixed
the downgraded row becomes *evictable while its files remain on disk*.
**Fix:** put the read-merge-write behind a `@Transaction` DAO method / `db.withTransaction`,
keeping the pure merge separately testable. **Sequence with H1** (H3 first or together).

### H4. Fourteen `runCatching` sites in `:player/syncplay` swallow `CancellationException` (HYG-4)
`SyncPlayController.kt:1962` (the shared wrapper behind every user transport action) plus
13 sibling `api.*` sites (460, 501, 533, 593, 671, 780, 1131, 1418, 1474, 1528, 1816,
1833, 1867). The project identified this exact hazard and fixed it three times in
`:data:downloads` (`runCatchingUnlessCancelled` in `SubtitleSidecarTopUp.kt:208`, the
`DownloadQueue.kt:471` comment, the `DownloadWorker.kt:97` decision record) — SyncPlay
was never swept. Concrete: `cancelRejoin()` mid-`getGroups()` logs a failure and burns a
retry attempt on a user-initiated abort; backing out of `startSession` surfaces "Could
not join a SyncPlay group".
**Fix:** promote `runCatchingUnlessCancelled` to `:core:common`; replace all 14 sites.

### H5. `SyncPlayController` is a genuine god class with named seams (CPX-1 + ARCH-5)
2123 lines, 96 methods, 18 mutable fields, 12 collaborators, ≥9 distinct responsibilities
(membership lifecycle; rejoin state machine l.1017-1258; 3-signal connection-loss
detection; socket dispatch; buffering/ready handshake l.1542-1909; queue reconciliation;
timer safety nets; direct player driving; transport pass-throughs). The `@Suppress`
rationale ("splitting means publishing state") is false for responsibilities that own
*disjoint* field sets. Deliberate decision per DECISIONS precedent, flagged anyway
because harm is measurable: B1–B3 device-DoD bugs and the SP race family all landed here.
**Direction (when the next SyncPlay wave lands, not before):** extract
`SyncPlayRejoinPolicy` (owns `rejoinTarget`/`rejoinJob`/`lostMembership`/`troubledAt`)
and `SyncPlayRecoveryNets` (owns `selfSyncJob`/`pauseNetJob`/`groupPlayingAnchor`/
`NetStage`) — the scheduler/drift-monitor/pinger extractions already prove the pattern.

### H6. `teardown` vs `standDown`: 12 byte-identical reset statements, 3-statement semantic delta (CPX-2)
`SyncPlayController.kt:711` / `:1150`. Every new session-scoped field (already 18) must
be hand-added to both lists; missing one leaves a rejoin carrying stale state into a
fresh group — the exact bug shape of B2/B3, invisible in review. Neither variant
`.cancel()`s `connectivityGraceJob` (both rely on `closeSession()` scope cancellation).
**Fix:** box the 13 shared fields into `private var session: GroupSessionState?` so reset
is one assignment with two named constructors — highest-leverage single fix in the module.

### H7. Detekt gate is structurally blind to the UI layer — four unlogged global raises (CPX-3 + CPX-14) — ORCHESTRATOR-VERIFIED
`config/detekt/detekt.yml:14-45`: `TooManyFunctions` 11→20 (classes *and* interfaces),
`LongParameterList` ignoreAnnotated `[Composable, Inject]`, `LongMethod` ignoreAnnotated
`[Composable]`, `ReturnCount` 2→6. All date from the M0 bootstrap, none logged in
DECISIONS.md — against the house rule (targeted `@Suppress`, logged; see the 2026-08-03
PlayerViewModel precedent). Measured blindness: `JellyfinTextField`'s 19 params, the
12-collaborator SyncPlay constructor, six composables >100 lines. The `ReturnCount` raise
buys nothing: the motivating function (`remuxBytes`, 9 returns) still needs its targeted
`@Suppress`. Secondary evidence the ceiling *distorts design*: `ItemDetailViewModel` has
four functions exiled to file-level scope with comments admitting it's to duck the count
(CPX-10).
**Fix:** revert `ReturnCount` to default (keep the 2 existing suppressions); drop the
blanket Composable exemptions in favor of per-offender `@Suppress`; either revert the
`TooManyFunctions` raise or log it in DECISIONS.md. Expect a one-time suppression-adding
sweep as the gate regains sight.

### H8. `AppError`→message mapping ×5, three copies hardcoded English (DUP-1 = CPX-13)
Both auditors independently: `HomeViewModel.kt:391`, `ItemDetailViewModel.kt:648`,
`PlayerViewModel.kt:1538` return English literals; `LibraryErrorMessage.kt:10`,
`SearchErrorMessage.kt:9` do it right via `stringResource`. In a 69-locale app with
`MissingTranslation` as a build error, home/detail/player show untranslated error copy —
the gate can't see Kotlin string literals. DECISIONS.md:374 deferred this as "M9 polish";
it's overdue debt, not an approved state. Copies have also drifted (`Server` and
`NotFound` copy differ between screens).
**Fix:** one `AppError.toMessageRes()` in `:core:ui` with per-screen overrides only where
copy genuinely differs; delete the five mappers.

## 3. Medium findings

- **CPX-4** — SyncPlay recovery encoded in 5 self-nulling `Job` fields + a `NetStage`
  param smuggled through a recursion (`armSelfSync(Elicit)`→`armSelfSync(Fallback)`);
  self-null vs `cancel()` race unguarded in 4 places — same shape as the scheduler bug
  SP-01 fixed by identity-guard. Direction: one sealed `RecoveryState` under a single
  supervising coroutine. *(High-adjacent; folded under the H5 extraction.)*
- **CPX-5** — `PlayerViewModel`: 9 of 16 mutable fields are pure temporal coupling
  (`pendingAudioIndex`, `recoverySource`, `stopReported`, `forcedRemote`…). Direction:
  collapse into one `ActiveSession?` value reassigned atomically in `publish()`.
- **CPX-6** — SyncPlay concurrency context chosen per-call-site 34 times (13 `scope.launch`,
  10 `launchInSession`, 11 `withContext(main)`), each load-bearing, none enforced.
  Direction: session-scope receiver type only `enterGroup` can mint.
- **CPX-7** — `PlayerControls.BottomBar`: 7 inline visibility predicates over 6 state
  fields; the "five pickers max" width invariant is a comment. Direction: pure
  `List<SheetChipSpec>` derivation, testable, assertable against the width threshold.
- **CPX-8** — `JellyfinTextField`: 19 params, 4 correlated pairs the caller must sync by
  hand (`label`/`labelText`, `isError`/`errorMessage`, `password`/`visualTransformation`,
  `enabled`/`readOnly`); a11y guarantees rest on call-site discipline. Direction: value
  types (`FieldLabel`, `FieldState`, `FieldContent`).
- **CPX-9** — `PlayerScreen`: 3 independent sheet booleans (8 states, 4 nonsense) and a
  `return@Box` at l.210 that silently drops any sibling appended after l.285 in PiP.
  Direction: one `openPanel: PlayerPanel?`; invert the PiP guard into a positive `if`.
- **CPX-10** — `ItemDetailViewModel`: metric-driven design distortion (4 exiled top-level
  functions re-taking collaborators as params). Direction: split a
  `DetailDownloadsDelegate`; repatriate the functions. *(Pairs with H7.)*
- **CPX-11 + DUP-9** — `PosterCard`/`ThumbCard` ~90% identical, 4-value overlay cluster
  forwarded verbatim through 4 signature levels (10→12→8 params). Direction:
  `CardOverlayFacts` value + one private `MediaCard(shape, …)` both delegate to.
- **CPX-12** — `DownloadQueue.downloadOne`: audio-sidecar cleanup rule stated in 3 catch
  arms; 5 concerns in one 14-line progress lambda; 6 params forwarded unchanged.
  Direction: `withFetchFile {}` helper + `ProgressPublisher` value.
- **DUP-2** — download-badge subscription ×4 ViewModels (the STAB-10 fix applied four
  times, linked by comment cross-reference); `withDownloadStates` byte-identical ×2 +
  drifted ×1. Direction: shared error-guarded flow + extension in `:data:downloads`.
- **DUP-3 + HYG-8** — one-shot snackbar idiom ×5 screens with five *different* bottom
  insets (two screens have none), plus the Detail copy keys `LaunchedEffect` on the
  resolved string, so two distinct messages sharing copy wedge `userMessage` non-null.
  Direction: `core/ui` `rememberOneShotSnackbar` + `JellyboostSnackbarHost` owning the
  chrome inset, keyed on message identity — fixes the wedge everywhere at once.
- **DUP-4** — `SelectionAction` dispatch ×2 (detail top-level fn / library inline) incl.
  the subtle isDownloadable series/season carve-out, twice. Direction: hoist next to
  `runBatch` in `core/common/selection`.
- **DUP-5** — `Authorization` header assembly ×3 across 2 modules; only
  `JellyfinAuthInterceptor` carries the NET-04 same-origin guard, undocumented at the
  other two. Direction: `jellyfinAuthorizationHeader(apiClient)` + predicate in
  `:core:network`.
- **DUP-6** — ticks constants ×6 under 3 names/3 scales; the only named converters are
  stranded in `:player`. Direction: `core/common` `Ticks` object.
- **DUP-7** — episode label spelled `"S1:E4"` (non-localizable, `JellyfinItem.episodeLabel`)
  on cards/a11y descriptions but `"S1 · E4"` (resource-backed) on the detail header —
  TalkBack speaks one spelling while the screen draws the other. `subtitleLine()` also
  drops the movie-year fallback `displaySubtitle` has. Direction: resource-backed forms
  become the single implementation as `core/ui` extensions.
- **HYG-5** — 4 broad catches in `DownloadRepositoryImpl` (`:258/:366/:403/:429`) missed
  by the 2026-07-30 cancellation sweep; a torn-down scope during bulk pause logs E-level
  and returns `Failure(Storage)` for an ordinary cancel.
- **HYG-9** — `SyncPlayQueueViewModel.hydrate` memoizes successes only: an unresolvable
  queued item re-fires `getItem` on *every* queue emission for the sheet's lifetime.
- **ARCH-1** — `@DefaultDispatcher`/`@IoDispatcher`/`@ApplicationScope` live in
  `:core:network`, forcing false dependency edges (feature/downloads' build file admits
  it). Direction: move qualifiers to `:core:common`.
- **ARCH-2** — `:player` exposes 111 public types; `:app` (sole consumer) imports 10.
  Direction: `internal` sweep keeping the 10 consumed entry points.
- **ARCH-3** — downloads engine ring (`FileDownloader`, `MatroskaSeekIndexRepair`,
  planner, storage, notifier…) public with zero external consumers; compiles `internal`
  today. Fold into the already-logged seam work.
- **ARCH-4** — the "UI never sees `org.jellyfin.*`" invariant — the plan's core mechanism
  — holds by discipline only. Direction: forbidden-import gate (Konsist test or lint rule
  over `feature/*` + `core:ui`). Cheap; converts the codebase's best invariant into a
  compile-time fact.

## 4. Low findings (compressed)

- **ARCH-6** `UserDataRepositoryImpl` public while every sibling impl is internal.
- **ARCH-7** SDK time-conversion facade spans `:data`+`:player`; move to `core/common`.
- **ARCH-8** back-stack policy (the device-debugged save/restore asymmetry) lives in
  `AppScaffold.kt:508-615`, not beside the graph; move to `NavPolicy.kt`.
- **ARCH-9** `:core:ui` exports Coil as `api` without justification (Haze is justified).
- **ARCH-10** `:player` exports Media3 as `api`; `:app` imports no Media3 type.
- **CPX-15** `reconcile`: `loadedPlaylistItemId` touched 6× with 4 meanings; direction:
  pure `ReconcileAction` sealed result.
- **DUP-8** a11y comma-join rule ×3 with drift (HomeHero skips blank-trim → spoken
  "Rated , …" on empty rating).
- **DUP-10** HTTP status constants ×8 files; use `HttpURLConnection.HTTP_*` (as
  `SyncPlayController` already does).
- **DUP-11** library item-type list duplicated across `:data`/`:feature:library` with a
  prose comment as the only sync mechanism; hoist to `core/common` or pin with a test.
- **DUP-12** interpunct `" · "` ×10 sites (2 named, 8 inline); `ItemDetailHeader` KDoc
  claims hair-spaces the code doesn't have.
- **DUP-13** `"und"` protocol constant ×3 (two in the same package); player and download
  sidecar naming must agree — hoist to `core/common`.
- **DUP-14** device-profile bitrate ceilings ×2 with a "same ceiling" comment; shared
  `DeviceProfileDefaults` (profiles themselves correctly separate).
- **DUP-15** lazy-list `contentType` strings ×8 across 4 files; export from `:core:ui`.
- **HYG-6** `LoginViewModel.kt:225` unchecked `as AppResult.Success` — a third sealed
  variant becomes a sign-in-path crash; use exhaustive `when`.
- **HYG-7** *(folded into HYG-9 above — medium)*.
- **HYG-10** server host/IP logged at INFO on discovery/reachability paths — debug-only
  builds, but these are the lines that reach a pasted logcat; demote to `d`, log host
  only (consistent with SEC-05/06 and the history scrub).
- **HYG-11** `AudioSidecarExtractor.kt:69` hardcodes `Dispatchers.Main` where an injected
  `@MainDispatcher` exists — the reason the transmux path has no JVM test.

## 5. Non-findings worth recording

Independently confirmed clean by the auditors (calibrates the findings above):
- Module graph = PLAN.md exactly; zero feature→feature, zero SDK-in-UI (grep-verified).
- `DelegatingJellyfinRepository` offline seam clean, matches plan verbatim, has the
  reflection-walking anti-forgetting test.
- `AppScaffold` (615 lines): ~60% load-bearing KDoc around a ~140-line composable.
- `:player` internal decomposition already done; `syncplay/` is the best-layered package
  in the repo (the *controller class* is the exception, not the package).
- `DownloadsScreen.kt` (1433 lines) is **a model for the UI layer** — pure body-selection
  function, shared `LazyListScope` extensions so the two layouts can't drift, breakpoints
  as JVM-testable pure functions. Likewise `ItemDetailHeader`, `LibraryGridScreen`, the
  MKV parsers (local parse cursors, nil class state).
- Online vs Offline repository = polymorphism, zero copied bodies. Auth screens already
  share a scaffold. Device profiles deliberately separate (constants excepted, DUP-14).
- Hygiene floor: zero `!!`, zero `println`, zero TODO/FIXME, all DAOs suspend/Flow, eight
  sequential auto-migrations with schema export, every hand-written broad catch outside
  the flagged sites rethrows cancellation (all 27 sites individually checked), lint gate
  annotations all justified, no baseline files.
- 66 `viewModelScope.launch` fire-and-forget sites sampled: mutations route failures into
  user-visible `UserMessage`.

## 6. Proposed remediation tiers

**Tier 1 — correctness, ship soon (~1 session):**
H1 eviction wiring + H3 transaction (sequence together) · H2 sign-out cleanup ·
H4 cancellation sweep (`runCatchingUnlessCancelled` → `core/common`, 14 sites) ·
HYG-5 (4 catches) · HYG-8/DUP-3 snackbar consolidation (fixes the wedge) · HYG-9 memo.

**Tier 2 — gates & governance (~half session, mostly config + one DECISIONS entry):**
H7 detekt un-blinding (+ DECISIONS entry or revert) · ARCH-4 forbidden-import gate ·
H8 error-copy consolidation (pays the deferred M9 debt; restores the i18n gate's truth) ·
DUP-11 pin test.

**Tier 3 — structural, schedule with the next touch of each area:**
H6 `GroupSessionState` boxing (do *before* any other SyncPlay work) · H5/CPX-4/CPX-6
extractions (with the next SyncPlay defect wave) · CPX-5 `ActiveSession` boxing ·
CPX-10 detail split · DUP-2 badge flow · DUP-5 auth header · CPX-11/DUP-9 card merge ·
ARCH-1/2/3 visibility+qualifier sweeps · CPX-8 field API.

**Tier 4 — opportunistic:** everything in §4 not named above.
