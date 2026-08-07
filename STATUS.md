# STATUS

## Project identity: **Jellyboost** (renamed 2026-07-31)

The project, app label and package are `Jellyboost` / `dev.jellyboost` (was
`jellyfin-native` / `dev.jellyfinnative`); see the `DECISIONS.md` entry of the same date.
The on-disk repo directory is still `jellyfin-native` — that is deliberate, not a leftover.
Names for **Jellyfin the server** (`JellyfinRepository`, `JellyfinApiFacade`, `JellyfinItem`,
`JellyfinDatabase`, …) are unchanged. Because `applicationId` changed, an old build on a
device is a *separate* install: uninstall `dev.jellyfinnative.app(.debug)` before the next
device walk, and expect its downloads and database not to carry over. Full gate re-run after
the rename: ktlint + detekt + unit tests + `assembleDebug` + `assembleRelease` all green,
baseline profile still compiles into the release APK (`assets/dexopt/baseline.prof`).

## Current milestone: M10 — Release hardening (**COMPLETE**, 2026-07-30, tag `m10`)

**DoD walk (all five items verified on the test tablet):**
1. **R8 rules** — `assembleRelease` green; 12/12 minified checks pass on device
   with zero `ClassNotFoundException`/`NoClassDefFoundError`/`NoSuchMethodError`/
   Room `_Impl`/serialization errors. SDK serializers, Room, Hilt, Media3 (incl.
   the reflectively-loaded `FfmpegAudioRenderer`) and Coil's ServiceLoader
   fetchers all survive full-mode minification + `shrinkResources`.
2. **Baseline profile** — `569b8ac`, 21 497 rules captured on the device,
   compiled into the release APK as `assets/dexopt/baseline.prof`.
3. **CI** — `.github/workflows/ci.yml`, valid YAML, one `gate` job running
   assemble + detekt + test with report/mapping upload. Authored only: the repo
   has no GitHub remote, so it cannot be exercised here (recorded deviation).
4. **Signing** — release config reads all four `RELEASE_*` properties
   all-or-none from local.properties/env, falls back to debug-signing with a
   `-debugsigned` version suffix; no keystore or secret in the tree.
5. **M5/M8 re-verified on the minified build** — direct play, forced transcode,
   audio switch, subtitles rendering; 873 MB download, offline playback from
   disk, user-data sync, Coil posters. Plus SEC-01: a real search left zero
   traces of the term, server name, UUIDs or tokens in release logcat.

**Device-verified fixes shipped during M10:** offline sidecar subtitles
(`190dc03`), forced-remote go-home (`d3f408f` — B.3 re-walk PASSED on device
2026-07-30: picking English streamed, re-picking French returned to local
storage, Quality control disappeared, server progress posts stopped), clickable
download rows (`a60274d`).

## Quality audit — whole-tree structural pass (2026-08-06 — report committed; H8 remediated)

Third full audit (`docs/notes/audit-2026-08-06-quality.md`), and the first *structural*
one: code quality, duplication, complexity/spaghetti, architecture conformance over all
production source (334 files / 58k lines), 4 parallel auditors primed with
DECISIONS/STATUS so logged decisions weren't re-reported. **44 unique findings → 0
Critical · 8 High · 19 Medium · 17 Low.** The three headline claims were independently
grep-verified. Architecture itself is clean (module graph = PLAN.md exactly, offline seam
verbatim, no SDK types in UI); the Highs cluster in four areas:

1. **Dead retention paths**: `evictBrowseCacheOlderThan` and `UserDataDao.deleteSynced`
   have zero callers — the browse cache grows unboundedly and sign-out leaves every Room
   table intact (second user inherits the first's cache); plus an un-transactioned
   `BrowseCacheWriter` merge that can downgrade a DOWNLOAD row.
2. **SyncPlayController internals**: 14 `runCatching` sites swallow
   `CancellationException` (the hazard `:data:downloads` fixed three times);
   `teardown`/`standDown` share 12 hand-synchronized reset statements; 5 self-nulling
   Job fields form an unguarded recovery state machine.
3. **Blinded quality gate**: four unlogged *global* detekt relaxations from M0
   (`TooManyFunctions` 20, blanket Composable/Inject exemptions, `ReturnCount` 6) —
   against the targeted-@Suppress house rule; needs a DECISIONS entry or a revert.
4. **Drifted duplication**: `AppError`→message ×5 with three hardcoded-English copies
   (home/detail/player errors untranslated in all 69 locales — the deferred "M9 polish"
   debt), STAB-10 badge fix ×4, auth header ×3 (same-origin guard in only one).

Report ends with a 4-tier remediation plan (Tier 1 correctness ≈ one session; Tier 2
gates/governance; Tier 3 structural, scheduled with the next touch of each area).
**Remediation started**: H8 (the drifted `AppError` copy) is landed — see the next
entry. The rest are findings only.

## Audit H8 — one `AppError`→copy mapper, and the end of English error text on home/detail/player (2026-08-06 — landed, gate green)

`docs/notes/audit-2026-08-06-quality.md`, H8 (= DUP-1 = CPX-13). Five mappings of the
`AppError` taxonomy onto user copy; three of them (`HomeViewModel`, `ItemDetailViewModel`,
`PlayerViewModel`) returned English Kotlin literals, which `MissingTranslation` cannot see —
so those three screens showed untranslated error text on all 68 non-English locales while
the i18n gate reported green. The copies had also drifted (`Server`, `NotFound`). Pays the
debt DECISIONS.md logged as "M9 polish" on 2026-07-28; conformance, not divergence, so no
new DECISIONS entry.

Now one `AppError.toUiText(AppErrorCopy)` in `:core:ui` (`error/AppErrorCopy.kt`) returning
a `UiText` (`text/UiText.kt`) — a resource id the ViewModel decides on and the screen
resolves at draw time, with `UiText.Raw` as the single escape hatch for wording that came
from outside the app (an ExoPlayer/Cast error string). Home, detail and player `UiState`
error fields went from `String?` to `UiText?`. Overrides are only where copy genuinely
differs: `unknown` always (it names what failed to load), not-found's library wording for
home + libraries, and the player's two server branches ("could not start playback").

Strings: 7 shared ones moved out of `:feature:library`/`:feature:search` into `:core:ui`
with all 69 locales intact (no re-translation); 6 new ones — `home_error_unknown`,
`detail_error_unknown`, `player_error_{server,server_with_code,unknown,unsupported_source}`
— authored in all 69 locales. `player_message_failed` was reused for the old
`PLAYBACK_FAILED` literal rather than adding a seventh. `scripts/validate_i18n.py`: 759
files, 0 problems.

Tests: new `AppErrorCopyTest` in `:core:ui` (11 cases, asserting resource ids rather than
English sentences — an English literal in a test is exactly how the old mappers passed
while showing untranslated copy); home/detail/player ViewModel tests strengthened from
`shouldContain "server"` to the exact `UiText` value. Docs: ARCHITECTURE.md ("Error copy"),
features/localization.md. Not device-walked — no visual change in English.

## Accessibility audit + full remediation (2026-08-05 — landed, gate green; TalkBack walk owed)

Full-app a11y audit (report: `docs/audits/accessibility-audit-2026-08-05.md`, ~90 findings)
followed by a five-wave remediation, all merged and `/verify`-green:
1. **Wave 1** (`b1194be8`) — contrast tokens raised with computed WCAG ratios (player scrim
   0.35→0.62, ChromeFill 0.45→0.72, outline/borders/disabled alphas), `height→heightIn` on
   text-bearing rows, pill `requiredHeightIn` floor (DECISIONS 2026-08-05), honest
   "Close player" error label. Compose 1.11 already honours animator-duration-scale
   (verified from artifacts — documented in `JellyfinTheme`, no provider added).
2. **Wave 3** (`95040389`) — player: controls reachable under TalkBack (auto-hide suppressed
   during touch exploration, labelled tap-to-reveal), Display sheet = non-gesture
   brightness/volume, keyboard map (space/arrows/Esc/media keys), seek bar spoken-time
   semantics + ±10 s/30 s custom actions, buffering finally rendered + announced,
   SyncPlay/cast controls named, `SCREEN_ORIENTATION_USER` (DECISIONS 2026-08-05).
3. **Wave 2** (`3f97cf86`) — design system: text fields carry name/error/password/autofill,
   cards are ONE authored node (`MediaCardFacts`, tested), chips selectable in 48dp frames,
   loading/error states announce, headings on section titles.
4. **Wave 4a+4b** (`8f0b51b2` + prior merge) — flows and structure: auth announces
   (Quick Connect code spoken as one grouped string, user picker = radio group), search
   announces its count, downloads rows are coherent nodes, settings groups speak their
   caption, chrome traversal groups (top −1 / content 0 / bottom +1), home hero is
   font-scale-aware (`HomeSizingTest` +9), detail facts read as sentences.
5. **Wave 5** (`e2b046f0`..`32a1de77`) — **quality gate now includes a11y** (user directive):
   `:app:lintDebug` with `abortOnError=true` + `config/lint/lint.xml`
   (ContentDescription/ClickableViewAccessibility/KeyboardInaccessibleWidget/LabelFor as
   errors; ~2 s no-op, 8–20 s incremental); wired into `/verify`, CLAUDE.md, and the
   pre-commit/stop hooks' freshness gate. First `androidTest` source sets in the repo:
   **26 instrumented a11y tests, 26/26 green on the test tablet (~40 s)** pinning merged
   cards, traversal, live regions, gesture-layer semantics (one documented ATF suppression:
   `SpeakableTextPresentCheck` on `AndroidComposeView` only). `connectedDebugAndroidTest`
   added to the `/milestone` DoD. Plus `readOnly` fields, announcing state views,
   `ActionPillChip`.

**Owed:**
- **TalkBack device walk** (user-run): chrome traversal order on both layouts, player
  controls-reveal → seek → Display sheet, Quick Connect dialog, user picker, a live
  download row, hero + pills at font scale 2.0, player scrim over a bright frame.
- ~~**i18n sweep**~~ — DONE 2026-08-05 (`30d4561e`, `d8e93ecb`): all 45 keys translated
  into all 69 locales (3105 entries, per-locale terminology/register, CLDR-correct
  plurals), the deprecated SyncPlay queue string trio retired (210 entries), and
  `MissingTranslation` promoted back to **error** in `config/lint/lint.xml` —
  `:app:lintDebug` green with 0 findings for it.
- Deferred design decisions from the audit: glass `Fill` token contrast floor,
  shared `SectionTitle` component (headings are hand-rolled per screen).

## 2026-08 diff audit + fix wave (2026-08-03 — landed, gate green; device re-walk owed)

Second full audit (`docs/notes/audit-2026-08.md`), covering everything since the 2026-07-30
audit (~165 commits: M10 tail, multi-track sidecars, all of M11 SyncPlay, M12 cast phases
1–5, i18n, 2026 UI refresh). 10 subsystem auditors + adversarial verification: **70 findings
→ 0 Critical · 3 High · 28 Medium · 37 Low** (2 refuted). The 3 Highs: downloads
stalled-socket wedge (no read timeout, uncancelled call), cast resume-position wipe (stale
`detachedSource`), cast phantom progress (reporting without checking the receiver still
holds the item).

Fix wave: 7 subsystem branches (Fable), each independently reviewed (1 blocker found and
reworked — a TOCTOU race in the downloads pause path), merged 2026-08-03; **64 findings
fixed**, ~10 skipped with logged reasons (design-level or out-of-lane; see the report and
the fix branches). Highlights: SyncPlay controller/scheduler confined to a
single-threaded dispatcher (closes the race family), drift monitor no longer seek-loops a
locally-paused player, queue replays are staleness-guarded, downloads got read timeouts +
in-transaction stop decisions + stable storage roots, cast transfers keep position/tracks
and stop reporting after the receiver drops the item, sign-out leaves the group before
revoking the token (new `SignOutHook` seam), probe re-pointing is identity-checked,
snackbar actions render, Haze/glass perf cleanups, +~40 new unit tests. Three DECISIONS
entries logged (CAST-06 unsigned poster, NET-03 sign-out ordering, PlayerViewModel
`LargeClass` suppression). Full gate green post-merge. **Owed:** a device sanity walk of
SyncPlay + cast + downloads happy paths on the test tablet.

## Sign-out vs unreachable server (2026-08-05 — landed, gate green)

User-reported: the Settings "Disconnect" button did nothing when the server was
unreachable. Two compounding defects: the sign-out ran in `viewModelScope`, so popping
Settings during the silent 6–30 s OkHttp wait on `reportSessionEnded` cancelled it between
token revocation and the credential wipe (user stayed signed in against a dead session);
and nothing capped or surfaced that wait. Fixed: `SessionRepository.signOut()` runs as an
`@ApplicationScope` job the caller merely joins, the server goodbye (hooks + session-ended
report) is capped at `SERVER_GOODBYE_TIMEOUT` (5 s), the ViewModel launches the
delete-downloads prelude + sign-out in the same app scope, and the sign-out pill shows a
spinner and stops taking taps while in flight. +5 unit tests (caller-cancellation,
hung-server, hung-hook, popped-screen, busy-flag). Docs: features/settings.md ("A sign-out
the screen cannot lose"), features/auth.md, ARCHITECTURE.md.

## Phone-size polish pass (2026-07-31 — DONE, device-verified both ways)

User-requested, outside M9's tablet-only scope (task-level + per-fix DECISIONS entries).
A phone viewport was simulated on the test tablet (`wm size 1080x2400` + `density 480` →
360×800dp; swapped for 800×360dp landscape; reset + auto-rotate restored afterwards) and every
screen screenshot-audited. Six compact-viewport defects found and fixed — Libraries single
column, detail wide-layout/banner on short landscape, cramped episode rows, player bar with
zero slack, over-tall SyncPlay queue sheet, crushed download queue-row titles — as four
`/verify`-green commits (`08d44d9`, `43e3e07`, `2cef508`, `fcac1c0`), each with JVM sizing
tests and phone-width previews; re-swept on-device at phone sizes and tablet-native (zero
tablet regression; tablet-portrait player bar intentionally icon-only). Full record:
`docs/POLISH.md` "Phone sizes" (incl. the round 2–3 user-feedback polish); feature docs updated (library-grid, item-detail, playback,
syncplay, downloads). Screenshot-test frameworks evaluated and rejected for now (preview
compileSdk 37.1 blocks Roborazzi/Paparazzi; no disk for an emulator — see DECISIONS).
Left on the device: HotD S3:E1 stays downloaded (useful for M11's local-in-group DoD item).

## 2026 design refresh — full-app modern UI pass (2026-08-01 — IN PROGRESS)

User-directed integration of the modernized design system authored remotely on
claude.ai/design ("Jellyboost Design System", updated 2026-08-01: new
`_shared/modern.css` modern surface layer + `foundations/surfaces.html` + 12 rebuilt
screen mocks + 10 rebuilt component cards). Pure UI restyle — no feature changes except
user-approved convenience displays (home hero, card overlay metadata, library count
tiles, detail cast rail, downloads tablet stats). Approved plan:
`~/.claude/plans/integrate-the-modernized-design-hidden-bear.md`. Five DECISIONS
entries logged 2026-08-01 (nav chrome reversal of M9, Haze dependency, white primary
buttons, card-metric changes + `ItemDetailSizingTest` re-pins, and the Phase-3 compact
action cluster + `LocalAppChromePadding` contract).

**Phase 4a (home) landed** on branch `design-refresh-home`: a full-bleed *Continue
watching* hero (the promoted first resume card — it plays via the existing
`Routes.Player` navigation, `Details` opens the item), quick-access glass chips
replacing the *My Media* tile row on compact (libraries + an *Offline* chip onto the
Downloads tab), episode badges and time chips on the resume/next-up thumbs, and the
list consuming only the chrome's *bottom* padding while a hero is present so the banner
runs under the status bar. Sixth DECISIONS entry logged. Gate green (ktlint, detekt,
1966 unit tests, `assembleDebug`); not yet device-walked.

**Phases 1–3 landed** on branch `design-refresh`. Phase 3 replaced `AppTopBar` with
`GlassBottomNav` (<560dp) / `GlassTopNav` (≥560dp) + an `AppActionCluster` of floating
glass buttons on compact; `AppScaffold` is now a `Box` whose chrome floats over a
single `hazeSource` nav host and publishes `LocalAppChromePadding` (`core/ui`) for
top-level screens to consume in their `contentPadding`. The four tab screens are wired
minimally — full restyles are Phase 4. Gate green (ktlint, detekt, 1904 unit tests,
`assembleDebug`); not yet device-walked.

**Phase 4b (library) landed** on branch `design-refresh-library`: `LibraryGridScreen` lost
its `TopAppBar` for a status-bar-padded glass header (back + home glass circles, library
name in `ScreenTitle`, "N items" underneath, sort as a glass circle on compact and as a
labelled control at the end of the chip row at 600dp+) over a new
`JellyfinGradients.ScreenGlow`; the filter badge became an inline `PillChip` row (*All*,
*Unwatched*/*Watched*, one chip per applied genre/year, *Filters* → the unchanged sheet);
grid cards gained the community-rating badge. `LibrariesScreen` tiles now carry the
library's item count under a scrolling "Libraries" title, and Home's *My Media* tiles the
same. Data: `ItemQuery.includeTotalCount` + `ItemPage` let the paged grid's **first** load
ask for the server's total record count (one COUNT per scroll, not per page) and report it
through `getItemsPaged(query, onTotalCount)`; `LibraryView.childCount` comes from
`getUserViews` and stays null offline (no Room column, no migration). Sixth DECISIONS entry
of the refresh logged for that count. +14 unit tests (paging source 6, library ViewModel 7,
item mapper 1).

**Phase 4d (Downloads) landed** on branch `design-refresh-downloads`: header, m-surface
storage card / m-surface `QueueRow` and `DownloadedRow` cards, a glass segmented
Downloaded/Queue tab control, glass pill bulk-action buttons, and — on wide layouts
(`!queueRowCompact`, no new breakpoint) — a three-panel tablet stat summary replacing
the storage card, backed by a new `DownloadsUiState.queueStats` pure derivation
(`QueueStats`: item count, remaining bytes, aggregate speed, ceiling-division ETA;
DECISIONS.md 2026-08-01 "Downloads gets a wide-layout queue summary"). Every row
action (incl. queue move-down, kept despite the mock dropping it) and both dialogs are
unchanged. Gate green (ktlint, detekt, unit tests incl. new `DownloadsUiStateTest`,
`assembleDebug`); `DownloadsScreenTest`/`DownloadRowsTest` pass untouched; not yet
device-walked. Awaiting merge alongside the other Phase 4 sub-branches.

**Phase 5 (unmocked-surfaces sweep) landed**: every surface the mocks did not render
swept to the idiom Phases 1-4 already established. `Modifier.mSurface` hoisted from
`:feature:downloads`'s private copy to `core/ui/theme/GlassDefaults.kt` (Downloads
refactored onto the shared one); `JellyfinTextField` gained an additive `leadingIcon`
param. Settings and SyncPlay Groups traded their `TopAppBar` for the `LibraryGridScreen`
glass header (back + home, `ScreenTitle`; SyncPlay Groups keeps a trailing glass
*Create* circle); Settings' section headings moved to `JellyfinTypeExtras.SectionTitle`
(kept primary-coloured — the one open choice the spec left, decided for wayfinding on a
scrolling list) and its sign-out button became a `GhostPillButton`. Search's field is now
a `JellyfinTextField` with a leading search glyph. SyncPlay's group/queue rows became
m-surface panels with pill actions, its now-playing tint moved to primary@12%
(replacing `secondaryContainer`), and its repeat-mode picker uses `PillChip`. Every
`AlertDialog` reached from a swept file (Settings' two dialogs, SyncPlay's create/leave
dialogs, the player's audio/subtitle/quality/speed picker) got the Quick-Connect-dialog
panel treatment (`containerColor = surface` + hairline border on `shapes.extraLarge`);
`ModalBottomSheet`s and the library sort `DropdownMenu` gained an explicit surface
container. The library filter sheet's chips became `PillChip` and its Clear/Apply row
became pills. Three stray `SnackbarHost`s that had slipped through earlier phases
without a `PillSnackbar` builder — `LibraryGridScreen`, `ItemDetailScreen`,
`PlayerScreen` — now use it; no snackbar anywhere in the app still draws the stock M3
shape, and there is no separate "offline banner" left to restyle (Phase 3 already folded
it into the chrome status icon + snackbar). Zero navigation/state/string changes. Seventh
DECISIONS entry of the refresh logged. Gate green (ktlint, detekt, unit tests,
`assembleDebug`); not yet device-walked.

Phases: 0 governance (this entry) → 1 theme/token layer (`core/ui/theme`: Glass,
elevation, type extras, Dimens changes, Haze dep) → 2 core components (pills, chips,
filled fields, card overlays, glass selection bar) → 3 chrome (GlassBottomNav pill
<560dp / GlassTopNav ≥560dp, Haze wiring, `LocalAppChromePadding`) → 4a–f screens
(home/library/detail/downloads/player/auth) → 5 unmocked-surface sweep
(settings/search/syncplay/sheets/dialogs, landed) → 6 i18n (69 locales) → 7
design-mirror + docs sync. Deliberately ignored mock elements (no matching feature):
in-library search, notifications bell, share, snackbar Undo, 4K/HDR pills, chapter
ticks. Kept despite mock omission: queue move-down, connection indicator + offline
banner (now a chrome icon + `PillSnackbar`, not a persistent banner).

**WRAP (2026-08-01): all eight phases landed on `design-refresh`.** Theme/tokens
(Haze 1.7.2, glass, elevation, type extras, 128/232/12dp card metrics), core components,
glass chrome (bottom pill <560dp / top nav >=560dp + action cluster), all six screen
restyles, unmocked-surface sweep, i18n (31 strings x 69 locales, validator clean), and the
`design/` mirror rewritten code-faithful (22 cards + modern.css + surfaces.html + token
sync). Gate green at every merge (~1948 unit tests). **Owed: the user's device walk**
(chrome both widths, hero fallback, grid selection, detail lockups, queue reorder incl.
move-down, buffered scrub, auth + Quick Connect, offline) and the DesignSync push of the
mirror to the remote project.

**Walk-defect fix round (2026-08-01, merges `4df11f7` + `fix/pill-downloads-align`):**
the user's first walk reported four defects, all fixed. (1) *Chrome unreadable over
bright content* — root cause was twofold: M3 `Button` floors every surface to 48dp, so
all glass circles/pills drew at 48dp regardless of declared size (`JellyfinButtons`
rebuilt on `Box`/`Row`; visual at declared size, invisible 48dp touch frame kept), and
the top-nav row/logo had no backdrop at all (new `TopChromeScrim` gradient sibling in
`AppScaffold` + darker `GlassDefaults.ChromeFill` Background@45% tint on chrome-level
glass). (2) *Button overlap* — `ActionClusterHeight` was 12dp short (now derived, 56dp,
pinned in `AppChromeTest`), chrome now exits at half the nav cross-fade so the action
cluster no longer overlaps pushed screens' own buttons, top nav fits 560–740dp (capsule
shrinks, labels ellipsise), safe-drawing/cutout insets consumed, and the phantom empty
Cast ring is gone (`glassContainer = true`). (3) *Filter-sheet pill overlap* — the
sheet's `FlowRow` had no `verticalArrangement` (0dp between wrapped lines) and
`PillChip` had no min height (now 36dp). (4) *Downloads misalignment* — margins unified
on 20dp, Wi-Fi row `fillMaxWidth`, one label style, 36dp action circles (32/34
constants deleted), same artwork size on both tabs per width class, dead spacer
removed; `LibrariesScreen` title gains the same wide `ScreenTitleLarge` switch. The
watched/unwatched filter report was retracted (server + pipeline verified correct via
live probe). A screenshot re-walk on the test tablet (agent-driven, at the user's request)
confirmed the fixes and caught three leftovers, fixed in the same round: (1) the raw
`MediaRouteButton` does NOT auto-hide with no routes (that's `MediaRouteActionProvider`
behaviour), so the Cast action showed as a bare oversized glyph with no circle —
`CastRouteButton` now composes out entirely on `NoDevices` and draws the glass circle
unconditionally otherwise; (2) `TopChromeScrim` at 80/45% still let white section titles
read through the brand mark mid-scroll — raised to 94/72%; (3) the "Wi-Fi uniquement"
label overlapped its switch in the narrow portrait stat panel — both switch rows' labels
now take `weight(1f)`. Verified on device: nav/logo readable over bright artwork both
orientations, filter-sheet pills spaced, Downloads margins/circles aligned, no
title/cluster overlap. Owed: the user's own look at the reinstalled build.

**Round 3 (2026-08-01, user feedback on round 2):** (1) composing the Cast button out on
NoDevices was a chicken-and-egg — the attached `MediaRouteButton` is what registers the
discovery callback, so no button meant discovery never ran and the button never came back;
it now stays attached always and is toggled `VISIBLE`/`INVISIBLE` (never GONE, never
composed out) in sync with the glass-circle gating. (2) The three wide Downloads stat
panels had equal widths but unequal heights — the row now uses `IntrinsicSize.Max` +
`fillMaxHeight` so all three cards match the tallest. (3) Top-nav tabs: unselected tabs
are icon-only (icon carries the label as content description), only the selected tab keeps
the labelled white pill — French labels never fit four-abreast on a portrait tablet and
every tab showed ellipsised text (DECISIONS entry "Top-nav tabs: labels only on the
selected tab"). Cast-with-receiver still needs a look when a Chromecast is next on the
network.

**Round 4 (2026-08-01, library-tile item counts):** the *My Media* tiles reported nonsense
("1" for a 177-movie library, "9" for 20 series). Root cause confirmed against the dev
server: `BaseItemDto.ChildCount` on a `CollectionFolder` counts the library's **media
folders**, not its titles (`/UserViews` → 3 for *Films*, 6 for *Séries*; the real totals are
177 and 20). `LibraryView.childCount` is renamed `itemCount` and no longer comes from the
DTO at all — `OnlineJellyfinRepository.getUserViews` now fires one `limit=0`
`enableTotalRecordCount` items query per supported library (all concurrent, `[Movie, Series]`
recursive, so a tile and the grid it opens agree), and a failing count leaves `itemCount`
null so the tile drops its subtitle instead of the home screen failing. Offline path
unchanged (Room stores no count). +4 unit tests; DECISIONS entry "library tiles count their
titles with a per-library COUNT request". Owed: a look on device — the tiles should read
"177 items" / "20 items" on the user's server.

**Round 4, phone sweep (2026-08-01, same session):** first real phone pass surfaced five
more defects, all fixed: (1) spamming the chrome's offline badge queued a snackbar per tap
(M3 `showSnackbar` is a mutex queue) — a tap now cancels the pending show so the message is
replaced, not enqueued (`29d476d`). (2) The detail screen's Back/heart/Home circles drew
the white@6% `GlassDefaults.Fill` over bright backdrops — they now pass `ChromeFill` like
the rest of the floating chrome (`4cc2cb3`). (3) Quick Connect's six 46dp digit boxes
overflowed a phone dialog into a horizontal scroll — boxes now narrow to fit
(`4cc2cb3`). (4) Login/ServerSetup overflowed a phone window vertically — compact-width
(<600dp) trims pane padding, avatars 88→64dp, server-name 32→26sp; tablet two-pane
untouched (`705470f`). (5) Downloads was unusable on a phone: landscape pinned
chrome taller than the window (queue unreachable), portrait pinned half the screen with
inner-list scrolling — chrome is now pinned only when the window is wide *and* ≥480dp
tall; otherwise the whole page is one LazyColumn (DECISIONS entry, `a125cba`).
Owed to a phone walk: login fits without scrolling, QC code fits its dialog, detail nav
readable over bright art, downloads scrolls as one page in both orientations, library
tiles show real counts.

**Round 5 (2026-08-01, offline home hero):** the *Continue watching* hero showed a synopsis
**offline only**, and the extra paragraph pushed its resume button over the section below the
banner. Two causes, both fixed. (1) Data: online home rows are fetched with `CARD_FIELDS`,
which does not ask for `OVERVIEW`, while offline rows are rebuilt from a download's cached
blob — which the enqueue fetch deliberately caches in full for the offline detail page. The
offline `getResumeItems`/`getNextUp`/`getLatestMedia` now return the same card shape the
online ones do (`asHomeCards()`); `getItem` still carries the overview, so the offline detail
page is unaffected. (2) Layout: the wide hero is a fixed-height banner the rows below overlap
by 48dp, and its copy block was free-standing, so any taller copy drew over them. The block
now fills the banner, insets itself above (`wideHeroCopyTopInset` — the mock's 104dp as a
fraction, so a short-capped banner keeps its lockup) and below (the rail), clips to those
bounds, and weights the overview so it, not the buttons, gives way. +3 offline-repository
tests, +6 `HomeSizingTest` tests; DECISIONS entry "Home rows answer in the same card shape
offline as online". Owed: a look on device with the network off — hero identical to online,
resume button clear of the row beneath it.

## Auth screens redesign + real avatars (2026-08-01 — landed)

User-requested UI polish, within plan scope (PLAN.md M1/M2 already specify
`getPublicUsers` and `JellyfinAsyncImage`; no DECISIONS entry needed). Merge `30e4a0f`:
- Branded auth flow: new tight-viewport in-app logo vector
  (`core/ui/res/drawable/ic_jellyboost_logo.xml` — moved there from `feature/auth` in the
  2026 refresh's Phase 3 so the wide nav bar can draw it too; geometry from
  `logo/ic_launcher_foreground.svg`), gradient wordmark + tagline hero on ServerSetup,
  `JellyfinGradients.BrandGlow` accent halo behind both screens' headers, discovered-server
  cards (gradient Dns badge + chevron), manual-address entry grouped into a panel,
  session-lost copy as an icon'd error panel.
- Login now renders the server's real public-user profile pictures:
  `publicUserAvatarUrl()` in `LoginViewModel.kt` builds `/Users/{id}/Images/Primary`
  URLs (trailing-slash tolerant, `maxWidth=168`), rendered via `JellyfinAsyncImage` in a
  gradient-ringed circle; users without a `primaryImageTag` keep the initial-letter
  fallback. 4 new LoginViewModel tests (17 total in the class), suite green.
- Localization follow-up landed (`4aaadc2`): `server_setup_tagline` translated into all
  69 locales, `auth_logo_description` marked `translatable="false"`, validator 0 problems.
- Device-checked 2026-08-01 (screenshots, both orientations, French locale): hero,
  tagline translation, server card, and real avatars against the dev server all good.
  One defect found and fixed on the spot (`941fe2f`): the `BrandGlow` radial faded with a
  width-driven radius, leaving a hard seam where the 420dp glow box ended (worst in
  landscape); radius now ends exactly at the box's bottom edge, re-verified on device.
- Debug + release builds (with the fix) installed on the tablet.
- Follow-ups from user feedback, same day, each device-verified + `/verify`-green:
  profile picker proportions (`b5b9b49`), two-pane landscape auth layout
  (`e8405c2`), centred pane pair (`6fed51d`).
- Design system mirrored to claude.ai/design ("Jellyboost Design System" project):
  `design/` in the repo holds the preview-card bundle (tokens, logo, auth components,
  screen mocks) and is the sync source — push updates via DesignSync, incrementally.
- 2026-08-01: bundle extended beyond auth to the full `core/ui` browse/media design
  system and synced (7 new cards, 2 updated): media cards (poster/thumb/library +
  resume/watched/placeholder overlays), download badge (5 states), batch selection
  (SelectionAppBar, card indicators, outcome snackbar), media row, backdrop header,
  state views, spacing & card metrics; gradients card gained BackdropScrim +
  ImagePlaceholder and tokens.css the matching tokens. claude.ai/design now covers
  every `core/ui` component.
- 2026-08-01, follow-up: Screens group extended past the auth mocks with five
  code-faithful screen mocks (home, library grid, item detail, downloads queue,
  player OSD), each mirrored from the real composables (row order, compact-width
  branches, tab bars) and synced. Settings/Search/SyncPlay-groups deliberately
  left unmocked (utility surfaces). Landscape-tablet variants added for
  home/library/detail/downloads from the real wide-layout branches (labelled
  tabs, 7-col grid, isWide poster+facts, single-tier queue rows). NOTE: raw
  DesignSync uploads don't refresh the remote `_ds_manifest.json` card index —
  new @dsCard files must be merged into it in the same sync pass or they stay
  invisible in the pane.

## Localization (2026-07-31 — landed, not in plan, DECISIONS entry logged)

Full app translation into the 69 locales the official jellyfin-android client ships
(its `values-*` set minus the invalid `chn`/`lzh` qualifiers). What landed:
- Last hardcoded strings externalized (`core/ui` badges/labels, `feature/home` section
  titles + empty state — new res dir with a proper `Latest %1$s` format string);
  `app_name` marked `translatable="false"`.
- `generateLocaleConfig = true` + `res/resources.properties` (`unqualifiedResLocale=en-US`)
  → Android 13+ offers Jellyboost in the OS per-app language setting. No in-app picker
  (see DECISIONS). Below API 33 the system locale list applies as usual.
- 759 translated `values-<locale>/strings.xml` files (11 modules × 69 locales), machine-
  generated (Claude) with Jellyfin terminology conventions; validated by
  `scripts/validate_i18n.py` (name parity, `%1$s` placeholder parity, CLDR plural
  quantities, escaping) — 0 problems. NOT native-speaker reviewed; per-agent uncertain-term
  notes live in the i18n commit message trailer / docs/features/localization.md.
- 2026-08-05: the CI i18n gate caught `player_message_change_reverted` (added in
  `2b1703b1`) missing from every locale — translated into all 69 player locales,
  validator back to 0 problems. Reminder: any new user-visible string needs the same
  69-locale sweep in the same change, or CI on `main` goes red. The re-run then
  surfaced a second, unrelated red: `FileDownloaderTest`'s "cancelling leaves the
  partial file on disk" was racy (cancel via a `var job` read from OkHttp's dispatcher
  thread; a fast runner streamed the whole body before the var was assigned) — made
  deterministic with the same deferred + `awaitCancellation` + `cancelAndJoin` pattern
  as the DL-01 test, assertions unchanged.
- Known follow-ups: `feature/auth`'s `auth_app_name` could also be `translatable="false"`
  (currently carries a literal "Jellyboost" entry in every locale); dv (Divehi) and fo
  (Faroese) are the lowest-confidence locales.

## Settings: About section with app version (2026-08-01 — landed)

User-requested, DECISIONS entry logged (plan scoped Settings to prefs/account/sign-out).
An About section closes the settings list with the installed version as an info row —
`BuildConfig.VERSION_NAME` passed from `:app` at the `SettingsScreen` call site, so the
`-debug`/`-debugsigned` suffixes show as-is. Deliberately not in `SettingsUiState`
(build-time constant, not state). The new strings (`settings_section_about`,
`settings_version`) were subsequently localized into all 69 locales (`1c76f72a`).

## Current milestone: M11 — SyncPlay (IN PROGRESS: all 6 phases landed 2026-07-30, device DoD owed)

Full plan: `docs/notes/syncplay-m11-plan.md`; feature doc `docs/features/syncplay.md`.
All implementation phases committed same-day, each `/verify`-green
(suite 1231 → **1598** unit tests; every phase's divergences in DECISIONS.md):
1. `671a758` protocol plumbing — API/socket facades over SDK 1.8.12 (surface
   verified from real artifacts, 7 deltas logged), domain models, NTP-style
   time sync; all SDK `LocalDateTime` conversion confined to
   `SyncPlayDtoMapping.kt`.
2. `ea54549` coordinator — `SyncPlayController` state machine, one-slot
   command scheduler, 2 s drift monitor, pinger (3×1 s then 5 s); socket
   collected *before* the join REST (stash/replay); **confirmed connection
   loss → pause + teardown + "Left SyncPlay — connection lost", manual solo
   resume** (amended decision 10).
3. `062e46c` player integration — `PlayerSyncPlayBridge`, in-group transport
   routes to API only (zero local calls, tests pin both halves), speed hidden
   + auto-skip → manual button in-group, WAITING overlay, group sheet.
4. `42ac3f0` group queue — queue sheet (up/down reorder), reconciliation by
   `playingItemIndex` with one-skip-per-slot guard, `SyncPlaySession`
   contract in `:core:common`, detail-screen group actions (movies/episodes).
5. `3e99e5a` dedicated section — groups screen (10 s poll, 403 → disabled
   state), `Routes.SyncPlay`, NavHost launch-request effect (dup-nav
   guarded), top-bar Groups icon + active badge.
6. `5797af2` local-file in-group reporting — `ServerReportTarget` in the
   reporter (local+online+inGroup reports w/ minted-or-null playSessionId;
   `stopTranscoding` stays remote-only; existing reporter tests
   byte-untouched), `mintPlaySessionId` (no device profile, no stream URL),
   leave-mid-playback = one final stop then silence; R8 release build
   verified (50 SyncPlay SDK classes + serializers kept, zero new keep
   rules; REST polling exercised on-device minified, clean logcat).

## Planned milestone: M12 — Chromecast (all 5 phases landed 2026-07-31; device DoD + tag owed, gated on M11)

User-approved scope extension (DECISIONS 2026-07-31): phone-orchestrated Google
Cast via media3-cast `CastPlayer` + Google's default receiver (NOT the Jellyfin
web receiver's undocumented protocol). Full plan: `docs/notes/chromecast-m12-plan.md`;
M12 summary in `docs/PLAN.md`. Key shape: `CastPlayerHandle` + `RoutingPlayerHandle`
behind the existing `PlayerHandle` binding, static conservative `CastDeviceProfile`
(1080p H.264/AAC, HLS-ts), `castTarget` joins `forceRemote`, `api_key` on every
receiver-fetched URL, `CastSessionCoordinator` keeps reporting alive off-screen.
First GMS dependency, taken directly (no flavors), confined to `cast/`.
Phase 0 (governance docs) landed.

**Phase 1 landed (Cast plumbing + route button, no playback yet).** `media3-cast`
1.9.0 with `play-services-cast-framework` **22.1.0** (the version media3-cast's own
POM declares), `mediarouter` 1.8.1, `appcompat` 1.7.1 — all `:player`-only.
`JellyboostCastOptionsProvider` (default receiver, notification targeting the launch
activity, expanded controller off) is wired through the manifest meta-data the merger
carries to `:app`; `CastAvailability` owns the `CastContext` behind a
`GoogleApiAvailability` guard and publishes the GMS-free `CastDeviceState`;
`CastRouteButton` renders a themed `MediaRouteButton` in the app top bar and nothing
at all when the state is `Unavailable`. Two `:app` changes were forced by MediaRouter
and are logged in DECISIONS.md 2026-07-31: `MainActivity` is now a `FragmentActivity`
(the chooser is a `DialogFragment`; `MediaRouteButton` *throws* without one) and
`Theme.Jellyboost` derives from `Theme.AppCompat.NoActionBar` (the dialogs are themed
from the activity's theme). Merged manifest gains only Cast's own components
(`MediaIntentReceiver`, `ReconnectionService`, `GoogleApiActivity`, the
`policy_cast_dynamite` `<queries>` entry) — **no new permissions**.
**Owed:** DoD walk 1 on a real Chromecast (icon appears, chooser opens,
connect/disconnect; GMS-less device shows no icon and does not crash).

**Phase 2 landed (cast playback core — profile, routing handle, coordinator).**
New in `:player`: `CastDeviceProfile` (static, no probe — H.264 High L4.2 ≤1080p +
AAC/MP3 in mp4, VP8/VP9 in webm, HLS-`ts` H.264+AAC transcode, **WebVTT-only**
external subtitles so image subs burn in), `CastMediaSpec`/`CastSpecMapper` (pure:
`api_key` on the media URL and every subtitle URL, `external:<index>` → numeric Cast
track ids, content type per play method), `CastMediaItemConverter` (media3-cast's
`DefaultMediaItemConverter` drops `subtitleConfigurations`; the spec travels as the
`MediaItem`'s tag), `CastPlayerHandle` (over media3-cast 1.9.0 `CastPlayer`;
`player` is permanently `null`, audio select always renegotiates, subtitle select goes
through `RemoteMediaClient.setActiveMediaTracks` because `RemoteCastPlayer.
setTrackSelectionParameters` is an empty method at 1.9.0), `RoutingPlayerHandle` (the
new `PlayerHandle` binding; a pure pass-through with no cast session, `events` via
`flatMapLatest`), `CastStatusHolder`, `CastSessionMonitor` (the GMS seam) and
`CastSessionCoordinator` (session lifecycle → routing flip, detached progress ticker
and final stop + `stopTranscoding` **only while no host is attached**).
Modified: `PlaybackResolveRequest.castTarget` joins `forceRemote` in skipping the disk
copy and selects the cast profile; `StreamUrlFactory.withApiKey` (idempotent, default
= identity); `PlayerHandle` gained a `prepare(source, spec, …)` overload whose default
drops the source; `PlayerViewModel` carries `castTarget` into every re-negotiation and
skips the decoder-fallback ladder while casting. `JellyboostApplication` starts the
coordinator. **Regression gate held: no existing test file was touched**, `:player`
574 → 621 unit tests (whole suite 1892, 0 failures); full gate green in one run.
Six DECISIONS entries (2026-07-31) cover the WebVTT-only profile and the live-server
probe, the `prepare` overload and the spec carry, the application-started coordinator,
the reused `PlaybackFailed` message, the defaulted `withApiKey`, and the media-session
coexistence.
**Owed:** DoD walk 2 on a real Chromecast — direct-play mp4 and forced-transcode mkv
both play on the TV, dashboard shows the session, quality change kills the old ffmpeg,
disconnect leaves no stray encoder, resume position correct in jellyfin-web.

**Phase 3 landed (transfers + control parity).** New in `:player`: `CastPlaybackHost`
(now public, with `onCastStarted`/`onCastEnded` carrying the outgoing player's snapshot),
`CastPlaybackCoordinator`/`NoCastPlaybackCoordinator` (the defaultable attach/detach seam
`CastSessionCoordinator` implements) and `PlayerCastBridge` — `PlayerSyncPlayBridge`-shaped,
and the host itself, since a public ViewModel cannot implement a module type it must also
be constructible without. Behaviour: connecting mid-playback **transfers** the film (one
snapshot → one stop report at that position → one negotiation against the cast profile,
resuming and playing on), disconnecting brings it back **paused** at the receiver's last
position, a cast session that connects in a SyncPlay group leaves the group with a message
(decision 6), and the screen's teardown while casting neither reports nor stops anything —
the coordinator owns both from the moment the host detaches. `RoutingPlayerHandle.stopInactive`
now stops the local player when a receiver takes over (decision 1 had nobody performing it).
`PlayerUiState.cast: PlayerCastState(isCasting, deviceName)` plus `CastTransferred`,
`CastLeftSyncPlayGroup` and `CastPlaybackFailed` (which replaces Phase 2's reused
`PlaybackFailed`) and their strings; every snackbar message is now formatted with the
receiver's name, falling back to "your TV". **Regression gate held: no existing test was
modified** (two files gained new cases), `:player` 621 → 644 unit tests, 0 failures; full
gate green in one run. Five DECISIONS entries (2026-07-31) cover the pushed transfer edges,
the public host, the stop-then-open transfer and the casting teardown, `stopInactive`, and
the plain-enum `CastTransferred`.
**Owed:** DoD walk 3 — connect mid-local-playback (TV continues near the phone position),
disconnect (phone paused at the TV position, resumes locally), audio/subtitle/quality
changes on the receiver, in-group connect leaves the group, back out of the player while
casting (TV plays on, dashboard advances, one stop report when the session ends).

**Phase 4 landed (the player screen is the remote control).** While `state.cast.isCasting`
the `PlayerView` is replaced by `CastingBackdrop` — the item's artwork (`JellyfinAsyncImage`,
fitted, dimmed) with a "Casting to {device}" chip offset above centre so it clears the
transport row and the bottom bar on both viewport shapes — the vertical swipes are not
composed at all (volume and brightness belong to this device; the receiver's volume rides
the hardware keys), and picture-in-picture is disarmed, since there is no surface to float.
`PlayerControls` gained the `CastRouteButton` in its top bar, hidden in a SyncPlay group
(decision 6), and the speed picker now hides when the player in charge has no rate:
`PlayerHandle.supportsPlaybackSpeed` (defaulted `true`, overridden by `CastPlayerHandle`'s
`COMMAND_SET_SPEED_AND_PITCH` check, delegated by `RoutingPlayerHandle`) reaches the bar as
`PlayerUiState.canSetSpeed`, re-read when a receiver arrives or leaves and again at
`PlayerEvent.Ready`. New state: `artworkUrl` (fetched with the title, backdrop → thumb →
primary) and `canSetSpeed`; new string `player_casting_to`. **Regression gate held: no
existing test was modified** (`FakePlayerHandle` gained one defaulted knob), `:player`
644 → 650 unit tests, 0 failures; full gate green in one run. Three DECISIONS entries
(2026-07-31) cover the handle-answered speed capability, the artwork's fetch point and the
label's placement, and swipes-not-taps.
**Owed:** DoD walk 4 — poster + device name appear on connect, every control and sheet still
works from the poster screen, no speed picker on a receiver without a rate, leaving the app
while casting does **not** enter PiP (TV plays on), and the top-bar cast button is absent in
a SyncPlay group. Tablet *and* phone-size layout check of the casting screen.

**Phase 5 landed (cast metadata, release verification, docs) — the last code gap is closed.**
New in `:player`: `CastMetadataHolder`, the channel that finally gives the receiver a name.
Phase 4 flagged that `CastPlayerHandle` mapped every spec with a default `CastMetadata()`, so
the television and the Cast notification showed an unlabelled stream; the ViewModel's existing
item fetch (the one that already loads the title and the casting backdrop) now publishes
`CastMetadata(title, subtitle, posterUrl)` under the item's id, `CastPlayerHandle.prepare` reads
it back under `spec.mediaId`, and `CastSpecMapper` signs the poster with `withApiKey` alongside
the media and subtitle URLs. A **cast** open now joins that fetch before negotiating (a receiver
is loaded exactly once); a local open never waits. `:player` 650 → **661** unit tests, 0 failures;
full gate green in one run; **no pre-existing test file was touched** except `CastSpecMapperTest`,
whose single metadata pass-through case is replaced by four stronger ones (logged in DECISIONS).

**Release build verified, no keep rule needed.** `assembleRelease` (R8 full mode +
`shrinkResources`) is green with **zero** missing-class warnings for `com.google.android.gms.cast.*`
or `androidx.media3.cast.*`. `dev.jellyboost.player.cast.JellyboostCastOptionsProvider` survives
**unrenamed** in the release dex (`mapping.txt` maps it to itself; `apkanalyzer dex packages` shows
the class, its `<init>()` and both interface methods), kept by the framework's own consumer rule
`-keep public class * implements com.google.android.gms.cast.framework.OptionsProvider` at
`configuration.txt:480` — so `app/proguard-rules.pro` gains nothing. The merged release manifest
still carries the `OPTIONS_PROVIDER_CLASS_NAME` meta-data with the exact FQN, plus Cast's
`MediaIntentReceiver` and `ReconnectionService`. The release APK was installed on the test tablet
and cold-started: process up, `MainActivity` focused, frames drawn, **no** `FATAL`/
`ClassNotFoundException`/`NoSuchMethodError` in its logcat.

**Docs:** `docs/features/chromecast.md` (new — architecture, key classes, negotiation flow,
transfers, reporting ownership, what is deliberately not supported, the WebVTT discovery, the
GMS-less contract, test coverage), `docs/ARCHITECTURE.md` (the `cast/` package, the routing-handle
seam in the `:player` layout, and a Chromecast (M12) section). Two DECISIONS entries (2026-07-31)
cover the metadata carry and the verified-not-assumed absence of a cast keep rule.

### Interleaved fix — cast streaming failed on a real Chromecast Ultra (2026-08-01)

Device-measured during DoD walk-up, reproduced on every item with surround audio: the receiver
raised CAF `detailedErrorCode: 104` (`MEDIA_SRC_NOT_SUPPORTED`) roughly 1.5 s after `LOAD`, in HLS-ts
and progressive mp4 alike. Root cause: `CastDeviceProfile` had no audio-channel constraint anywhere,
so the server transcoded surround sources to 5.1 AAC (rejected) and would equally have direct-played
an mp4 carrying an AAC 5.1 track. AC3/EAC3 5.1 passthrough was tried too and fails outright
(`LOAD_FAILED`); HLS-fMP4 (`SegmentContainer=mp4`) was tried as a possible way out and is a dead
end on this receiver — it accepts the `LOAD` but never opens a media session at either channel
count, so it was ruled out rather than adopted. Fix: `TranscodingProfile.maxAudioChannels = "2"` on
the HLS video profile, plus a `CodecProfile` stereo cap on AAC for both direct-play shapes
(`CodecType.VIDEO_AUDIO`, `CodecType.AUDIO`). This trades surround for playback on the Default Media
Receiver; a per-device-profile revisit is already deferred to M12 phase 2. `:player` gained 2 unit
tests pinning the cap on the transcode and both direct-play codec profiles (DECISIONS.md
2026-08-01). **Device re-verified same day on the Chromecast Ultra**: the same surround film
(EAC3 5.1 mkv) now negotiates `TranscodingMaxAudioChannels=2`, the dashboard shows an
aac-2ch transcode with video copied, the receiver reaches PLAYING and advances (checked
past 80 s), pause from the phone round-trips (receiver PAUSED — the pre-fix "Invalid
Request" symptom is gone), and ending the session fires the one final stop +
`DELETE /Videos/ActiveEncodings` with zero sessions left on the dashboard.

### What remains to close M12

Nothing in code. Three things, in this order:

1. **M11 closes first** — M12 was approved as a post-M11 milestone and its DoD shares the device.
2. **The user runs DoD walks 1–9** (`docs/notes/chromecast-m12-plan.md` § Verification) on a real
   Chromecast + the test tablet + the dev server. They are the accumulated "Owed" items of phases
   1–4 plus walk 9's edges: cast icon/chooser/hardware volume; direct play; transcode + quality
   change; transport from both the controls and the Cast notification; text subtitle (no restart),
   image subtitle and audio switch (restart); transfer both ways; stop mid-film → resume position in
   jellyfin-web and no stray ffmpeg; background and kill; in-group no button, system-UI connect
   leaves the group, downloaded item casts as a stream, GMS-less device shows nothing and does not
   crash, and walks 2/3/5 repeated on the **minified** build (now installed on the tablet as
   `dev.jellyboost.app`).
3. **`git tag m12`** once the walk passes, with STATUS.md flipped to COMPLETE.

### Interleaved fix — cold start showed the offline home while online (2026-07-31)

User-reported, reproduced and root-caused on the tablet, fixed and device-re-verified
same day. Two structural holes in the M6/M9 connectivity design (DECISIONS.md
2026-07-31, "two recovery signals"): the launch probe raced session restore and
demoted the optimistic state ("No server address to probe" → `OFFLINE_SERVER_UNREACHABLE`
exactly as Home first loaded), and a screen that fell back to Room while the state
read `ONLINE` had no signal that would ever refresh it (the state never changed, so
`connectivityChanged` never fired — hence the user's "toggle offline and back"
workaround). Fixes in `ConnectionStateProvider` + `ConnectivityRefresher`: probe
requests before session restore are dropped (optimism kept; the session-change probe
re-asks immediately), a `serverReconfirmed` tick refreshes screens whose fallback's
probe found the server fine, and a wrong "unreachable" verdict now self-heals via a
15 s re-probe. Device-verified: cold start goes straight to the online home
(~1 s, no offline flash); suite now 1620 unit tests.

### Interleaved fix — app chrome popped during page transitions (2026-07-31)

User-reported: navigating between a top-level page (combined app bar) and a pushed
page (no bar) showed a layout shift — the bar and the page never appeared or
disappeared together. Cause: `isTopLevel` reads the back stack, which flips the
instant `navigate()` is called, so the `Scaffold`'s `topBar` slot and the
navigation-bar bottom padding snapped a good half-second before the NavHost's
default ~700 ms cross-fade finished. Fix (`AppScaffold.kt` + `JellyfinNavHost.kt`):
one shared 300 ms clock (`NAV_TRANSITION_MILLIS`) drives explicit NavHost fades,
an `AnimatedVisibility` (expand/shrink + fade) on the bar so `innerPadding`
animates instead of snapping, an `animateDpAsState` bottom inset, and the bar is
fed the last *top-level* destination so the selected-tab pill doesn't blink off
mid-exit. Inset contract unchanged. Verify-green; visual check on device owed
with the next M11 walk.

### Interleaved fix — preselected subtitles invisible until toggled (2026-07-31)

User-reported: starting playback with a subtitle preselected (server default or
user-chosen), nothing rendered until subtitles were switched off and on again.
Root cause: the resolved `selectedSubtitleIndex` reached `PlayerUiState` (picker
showed the tick) but nothing ever applied it to ExoPlayer — only the picker's
own path did. Two aggravators: at open time `currentTracks` is still empty
(selection must wait for `TracksChanged`, which was dropped), and the singleton
player's `trackSelectionParameters` leaked between items (one "subtitles Off"
stuck for every later playback). Fix: `TrackSelectionController.reset()` in
`prepare()` (clean slate per item), and the open's resolved audio + subtitle
choices are armed in `publish()` and applied on `TracksChanged` — best-effort,
retried until the group exists, one-shot per open, never re-resolves (burned-in
subs are already on screen), user choice always outranks it. Covers audio
preselection and reopen flows (quality/track change, SyncPlay `loadItem`) too.
+8 unit tests (suite 1710); docs/features/playback.md updated. Device check
owed with the next walk: default-subtitle item renders from first frame, and
the sticky-"Off" regression (item A off → item B default renders).

### Device DoD session #1 — 2026-07-31 (app + jellyfin-web in the tablet's Chrome)

**Core PASSES:** group create/web join + badge; play-for-group of a downloaded
item **from disk** (LocalPlaybackResolver, `PlayMethod=DirectPlay`, zero
`/Videos/*` requests, exactly one mint `PlaybackInfo` POST, both sessions on
the server); lockstep both directions (pause ~50 ms, unpause ~0.9 s, app
transport → API only, ~45 ms RTT; time-sync compensates the measured 0.55 s
clock offset correctly); WAITING overlay renders; queue add/reorder/next with
next item resolved from disk + auto-relaunch; backgrounded commands during
playback (foreground service holds network, PiP works); **Wi-Fi kill → pause
+ solo manual resume from disk (amended decision 10 verified)**; ended-in-
group keeps screen open and reloads in place; **minified release app joined,
received queue over websocket, auto-launched at group position, zero R8
serialization errors**. Protocol assumptions largely confirmed (socket-before-
join works; seek does not pause; ping accepted; rejoin re-syncs to anchor).

**Bugs found (fix batch owed):**
- HIGH B1 Ready↔Pause feedback storm: any in-group pause loops
  apply-Pause → `POST /SyncPlay/Ready` → rebroadcast, ~13 req/s until unpause.
- HIGH B2 past-due commands re-applied ~1/s indefinitely (same scheduled
  instant re-fires); likely cause of an observed one-off ~28 s forward jump.
- HIGH B3 member wedges after automatic queue advance: next item loads,
  handshake completes, group says Playing but no unpause arrives → stuck
  paused at 0:00 under the overlay; only manual play (or Pause+Unpause pair)
  recovers.
- MED B4 stale title/duration after in-place item change (queue sheet is
  right, controls header is not).
- MED B5 WAITING overlay is cosmetic — member keeps playing behind it and
  drifts ahead (web pauses; we don't).
- MED B6 groups screen shows only a participant count; names exist in
  `SyncPlayGroupSummary.participants` and the in-player sheet lists them
  (user expectation: list names).
- MED B7 two-real-client Waiting deadlock observed once — retest needed with
  exactly two real clients (a third, never-ready REST member polluted it).
- MED B8 sustained REST/socket failure while OS reports online is never
  treated as connection loss (the OEM ROM cut background network; controller stayed
  "in group" 3+ min while the server disposed the group; recovered only via
  foreground `NotInGroup`).
- TUNING B9 a 2 s Wi-Fi blip ejects the group immediately (offline signal
  fires on the transition; the "socket survives a blip" intent unmet).
- MINOR B10 create-group dialog mixes locales ("Annuler"/"Create");
  per-progress-tick `POST /UserItems/{id}/UserData` chattiness (assess —
  may predate M11).

**Fix batches landed 2026-07-31** (`fix(player)` f4af878 coordinator,
c4a2400 UI; all device-re-verified except B6-names which needs a second
client to display):
- B1 pause storm KILLED (root cause confirmed in server source: ready-report
  now sent only when the server has reset us to buffering; device: 0 Ready
  POSTs in 38 s paused, was 306/24 s) + B2 scheduler idempotency (identity =
  type+when+position+slot, `emittedAt` excluded — server re-stamps it).
- B3 wedge: root cause narrowed to a lost/undelivered SendCommand frame
  (server had broadcast it); `armSelfSync()` safety net (3 s after
  ready/Playing-state with no command → seek to inferred position + play) —
  reproduced live on device and recovered automatically.
- B5 Waiting-from-Playing now pauses this member (silently).
- B8+B9 unified `confirmLoss()`: connectivity edge starts a 5 s grace
  (player frozen, re-negotiates if it returns), 3 failed ping cycles ≈15 s
  or terminal socket failure → loss. Full Wi-Fi kill still pauses (+grace)
  with manual solo resume. **Server-side limit discovered:** jellyfin
  10.11 ends membership when the websocket drops (SessionEnded →
  LeaveGroup) — a >2 s real drop cannot be survived client-side by design;
  the client now degrades accurately instead of self-ejecting.
- B4 header refresh on in-place item change; B6 participant names on group
  rows; B10 dialog strings consistent (screenshot-verified).
- B10-chattiness assessed: per-tick `POST /UserItems/{id}/UserData` is the
  pre-M11 M5/M8 local-first user-data push (continues after group ends,
  independent of SyncPlay) — follow-up ticket material, out of M11 scope.

**User two-client repro session + fixes, 2026-07-31** (real jellyfin-web as
second member — the earlier walks' REST-member gap closed):
- **Auto-rejoin landed** (`feat(player)` 280ed98, user-requested amendment of
  decision 10): a membership the server drops (websocket blip → SessionEnded
  → LeaveGroup on 10.11) is taken back — 3 attempts 2 s apart gated on
  connectivity, `Rejoining` state, player stays paused, local file re-mints
  its session; intentional exits (leave/sign-out/access-denied/GroupGone,
  healthy-socket removal) never rejoin. Device-proven: kicked at wifi-kill,
  back in on attempt 2 in ~4 s, lockstep resumed.
- **"Browser couldn't play the media" was OURS** (`fix(player)` 128b559):
  single-episode `SetNewQueue` trips a real jellyfin-web 10.11 crash (web
  expands 1 episode → series tail locally, then indexes the 1-entry server
  playlist → TypeError, playback never starts). We now expand a lone episode
  into the series tail before queueing (new `getSeriesEpisodes` across
  online/offline repos), mirroring web. Device-proven with real web both
  directions.
- **"Tablet doesn't react to web playback" was environmental** (same-tablet
  usage: app backgrounded whenever web is used → the OEM ROM cuts app network in
  ~40 s → membership lost, solo group disposed). Fixed structurally
  (`feat(player)` fec8f45): `SyncPlayPresenceService` — `specialUse`
  foreground service held while in a group without playback (notification +
  Leave action, handover to/from PlaybackService both ways) keeps pings
  alive (3.5 min clean vs ~40 s death); plus foreground-resume net:
  10 min involuntary-loss memory survives teardown, `ProcessLifecycleOwner`
  ON_START triggers silent rejoin (or immediate ping when InGroup).
  Device-proven end-to-end: backgrounded app took the group's QueueChanged,
  launched the player, was in lockstep on foreground.
- Residual risks recorded: OEM may still kill a specialUse FGS (the
  foreground net covers it); process death loses the rejoin memory (out of
  scope); foreground-rejoin success branch unit-tested only (needs a second
  physical device to prove live).

**Still owed / blocked:** B7 two-real-client Waiting deadlock retest (the
observed instance was polluted by a never-ready REST member);
sign-out-leaves-group (needs a safe re-login path —
skipped to avoid stranding the device); 403 disabled-account copy (needs
admin to toggle `SyncPlayAccess` on a spare account); queue remove /
previous / shuffle / repeat buttons; "Left SyncPlay" snackbar text on
screen; true frame-level skew measurement; drift-monitor corrective seek
never observed in 40 min (2 s threshold untested in practice).

### Bug-report audit & fix wave — 2026-07-31 (user DoD re-walk owed)

`syncplay-bugreport.md` (two-client session, browser + app) showed pause/resume
echo misses in both directions, two-press transport, a stuck "Waiting for
group" overlay on ordinary Play, and growing desync after resume. Full audit
(inbound + outbound path maps) → six fix commits, each `/verify`-green:

1. `87312aa` scheduler forgets never-applied commands — the server's
   "client got lost" re-send lands again (applied-repeat storm guard kept).
2. `cb85ec0` group-state truth (`InGroup.groupState`) + **pause net** (3 s
   mirror of the B3 self-sync net; WAITING hold now consults the real
   player, not the phase; divergence from key decision 11 logged).
3. `07d867f` MediaSession gate (`SyncPlayAwareForwardingPlayer`) —
   notification/headset/media-button transport becomes group requests; PiP
   rides the same session (no custom actions).
4. `fb0a46f` play/pause tap + icon read `groupState`, not local `isPlaying`
   (kills the two-press bug).
5. `6232e91` hygiene: anchor uses `queue.lastUpdate`; reports carry real
   `isPlaying` (server schedules the group's unpause from it — behavior
   change to watch on device); detach keeps scheduler + phase alive for
   background playback; one clock sample before the join call.
6. (next commit) unified group play — in a group the ordinary Play/Resume
   plays for the group (SetNewQueue, series-tail expansion kept), the
   separate button is gone (user decision, DECISIONS.md); launch route
   opens paused → buffering → owed ready; adopt path reports
   buffering-then-ready instead of instant ready.

Suite: 1620 → 1663 unit tests. **Owed: user re-walk of both bug-report
scenarios** (single-press pause/resume both directions/origins, no overlay
hang, ordinary Play drives the second client, drift < 2 s over 10 min,
notification/headset pause routes through the group) with logcat capture —
if a pause still lands net-late (~3 s), the log tells whether the
SendCommand ever arrived ("SyncPlay command …" line) or was dropped at a
gate ("Ignoring …" lines); "never arrived" would point at socket delivery,
the one cause static analysis could not settle.

### Wave 3 — the transport was the culprit (2026-07-31, afternoon)

Run-2 walk still failed; the run-3 logcat (offset/lateness diagnostics from `c5ccb68`)
was decisive: clock sync healthy (~930 ms steady), every arriving command applied
within ~20 ms — but `SendCommand` frames intermittently never arrive while
`GroupStateUpdate` frames flow, and they stop entirely late in the session.
Root cause found in jellyfin-sdk-kotlin 1.8.12 (master unfixed): `SocketConnection.state`
is a **conflated StateFlow carrying messages** (`OkHttpSocketConnection.kt:39-43`); the
server sends command+state ~2 ms apart, and the first frame of the pair loses to any
collector slower than the wire. Follow-up log (11:16) also caught the 27:xx jumps:
after a track-change rebuild the server's verbatim "client got lost" re-send was eaten
by our own applied-repeat dedupe, and the blind fallback guessed off a stale queue.
Fixes (this wave): own lossless OkHttp websocket behind the `SyncPlaySocket` seam
(SDK impl kept unbound as the defect reference); two-stage nets — ask the server to
repeat itself (its `CurrentSession` re-send carries the exact position), local guess
only if unanswered; `scheduler.forgetApplied()` on every player-continuity break;
pause→playing anchors on the parked player. Earlier same-day wave: park-before-ready
(`c5ccb68` — a ready with `IsPlaying=true` from a behind member earns an
`AllExceptCurrentSession` unpause, stranding the reporter; confirmed in server source).
**Owed: user re-walk** — expect single-press transport both ways, no 23:xx/27:xx jumps,
no stranding after app-initiated seek; the logcat filter now also has `OkHttpSyncPlaySocket`
frame logs to prove delivery.

### Original DoD checklist (reference)
- **Full DoD walk** (PLAN.md M11): lockstep play/pause/seek <~1 s both
  directions; downloaded item plays from disk in-group — dashboard shows both
  sessions, zero stream traffic, web commands land; WAITING overlay on peer
  stall; queue add/reorder/remove/next/prev/shuffle from tablet visible on
  web; commands applied while backgrounded; **Wi-Fi kill mid-group → pause +
  message, manual resume solo from disk**; sign-out leaves group; minified
  build receives GroupUpdates over the websocket (only REST polling was
  exercised under R8); SyncPlay-disabled account → 403 copy.
- **Protocol assumptions to confirm live** (phase 2 record): socket-open-
  before-join delivers GroupJoined/PlayQueueUpdate (5 s Connected wait
  enough?); Seek does not imply pause; `ready` without prior `buffering`
  accepted + `ignoreWait(false)` re-arms on re-attach; null-positionTicks
  Unpause fallback; stale-playlistItemId next/prev harmless; self-leave
  `Left` event no-ops; ping-before-first-ready tolerated.
- Ended-in-group with a continuing queue keeps the screen open and reloads
  in place (phase 4 seam) — watch it on device; drift threshold (2 s) may
  need tuning; groups-screen poll lifecycle across rotation/background.

## Previous milestone: M10 — Release hardening (started 2026-07-30)

**DoD (M10, docs/PLAN.md):** R8 rules (SDK serializers/Room/Hilt/Media3), baseline
profile, CI (GitHub Actions: assemble+detekt+test), signing; re-run M5/M8
verification on the minified build.

### Done (M10 so far)
- **Tier 2 data batch landed** (`fix(data)`, d44661c): PERF-01 metadata
  memoisation, PERF-03 WhileSubscribed projection, ARCH-01 offline filters
  filter-before-paging (DECISIONS logs the PLAN naming change), SEC-03
  credential-store catch split + involuntary-logout copy.
- **Release-build DoD landed** (`build(release)`, 19e1dd9): R8 (10.3 MiB
  release), signing fallback, CI authored, :baselineprofile scaffold, SEC-04
  extraction+backup rules. Seven deviations in DECISIONS.
- **Multi-track phases 0-1 landed** (`feat(downloads)`, c5f9ffd): embedded
  text subs as sidecars + silent top-up of old rows; audioStreamIndex pinned
  and recorded (schema v8). Phase 2 awaits the /Audio endpoint check.
- **Bulk actions batched in the ViewModel + honest UNKNOWN copy**
  (`fix(downloads)`, c8b23cc).
- **Audit Tier 1 — all 11 items landed, 2026-07-30** (4 commits: `fix(player)`,
  `fix(downloads)`, `fix(stability)`, `build(logging)`; full gate green, suite
  1095 → 1110; execution record + a new Low PERF-13 appended to
  `docs/notes/audit-2026-07.md` §6).

- **Offline track-selection bug fixed, 2026-07-30** (user-reported): the offline
  picker now offers only what a transcoded file actually holds (one baked audio
  track, no embedded subs — confirmed by parsing the repro MKV's `Tracks` element
  off the device), and a track the local file cannot supply is refused with a
  message instead of the pointless `reopen()` restart loop. External-sidecar
  `external:<n>` id matching verified NOT broken (Media3 bytecode + new
  controller test). DECISIONS.md entry covers the one inverted test. Net +16
  tests. **Device walk still owed** (batched M10 session below).

- **Tier 2 player batch landed, 2026-07-30** (`refactor(player)`): ARCH-10
  decomposition (PlaybackSessionController / PlayerSessionStore /
  PlaybackPositionTracker) with the stop-transcode ordering race fixed
  structurally; STAB-05/ARCH-03 `PlayerHandle.release()` on both teardown
  paths; PERF-04 position/buffer moved to a separate fast StateFlow. :player
  203 → 229 tests, repo 1231. Device-owed: release() thread/loader check +
  lazy rebuild, PiP survives then releases on dismiss, teardown-order logcat
  watch, PERF-04 recomposition count.
- **Debug installs get an orange launcher icon** (`chore(app)`) — adaptive-icon
  overlay in `app/src/debug/res` only; release resource table untouched.
- **Tier 2 downloads-engine batch landed, 2026-07-30** (`fix(downloads)`,
  b92fe3a): STAB-01 transient retry classification (attemptCount, schema v7,
  MAX_ATTEMPTS=5 on WorkManager backoff — one blip no longer ERRORs the whole
  queue), STAB-04 awaited stop + OrphanSweeper, STAB-09 mutex drain lease +
  batched repo bulk actions. DECISIONS entry covers the two re-pointed tests
  and the lease-shape divergence. :data:downloads 344 → 383. Follow-up agent
  running: DownloadsViewModel batched wiring + UNKNOWN copy honesty fix.
  Device-owed: blip-mid-queue retry walk, cancel-orphan sweep check, bulk
  actions on a 20+ queue (after wiring), v6→v7 upgrade over a live queue.

- **Connectivity-aware track picker landed** (`feat(player)`, a38413f):
  online, a downloaded item's pickers show the full source list and a missing
  track streams via force-remote reopen (sticky across re-negotiations, falls
  back to the file if the server is gone); offline, only playable tracks are
  listed, live. Device walk owed (see device session).

- **Offline sidecar-subtitle regression fixed + installed** (`fix(player)`,
  190dc03): Media3's `MergingMediaSource` re-ids every track as
  `<child>:<id>`, so the `external:<n>` match never fired once sidecar tracks
  were (correctly) flagged external — offline, every downloaded subtitle was
  refused as "not in the downloaded file" (Les Minions 2 walk; sidecars
  verified present on-device). One-function fix + 3 merged-id tests; design
  note corrected (b5769ab). **User re-walk owed on the device.**
- **Cleanup-wave: MKV batch landed** (`fix(downloads)`, b8b7ae0): MKV-11
  fixtures (real-ffmpeg oracle, runtime 2.5 GiB sparse, unknown-size-Cluster →
  new honest `UNSUPPORTED_HEADER`), MKV-04 Duration back-fill (with a CRC-32
  refusal rider), MKV-10 transcodes never send Range / 206 restarts from zero,
  MKV-05/07/08/09 spec hygiene. +19 tests, ~65 ms.
- **Cleanup-wave: hygiene batch landed** (`fix(hygiene)`, bf63b19):
  ARCH-50/51/52 dead deps, ARCH-53/54/55/56 dead code/strings/suppressions,
  ARCH-06 all OkHttp bindings qualified, ARCH-08 seven catches narrowed,
  ARCH-12 home layout cleared on sign-out, SEC-05/06 log hygiene, SEC-09
  password-safe toString, STAB-11 null-intent restart guard.
- **Cleanup-wave: perf/UI batch landed** (`fix(perf)`, cb913e9): PERF-06/07/08/
  09/11/12/13 (one shared badge subscription, notification change guard,
  clock-per-second formatting, Compose-metrics flag, shape-keyed storage
  locations), SEC-02 token-stripped trickplay cache keys (+ both stale
  "in-memory only" doc claims corrected), SEC-07 private lockscreen
  notification, ARCH-07 first :core:ui tests, ARCH-11 one formatBytes.
- **Cleanup-wave: structural batch landed** (`refactor(arch)`, 973ae02):
  ARCH-04 api→implementation, ARCH-05 package cycles broken by moves +
  `PackageDependencyTest` (layer order + acyclicity enforced), ARCH-13
  internal repo impls with `ONLINE_CALL_TIMEOUT_MS` on the contract, ARCH-09
  reflective delegation test, drain plans with the row's
  `bakedAudioStreamIndex` (multi-track follow-up closed), last cancellation
  rethrows. `assembleRelease` (R8 full) verified green.
- **HEVC `VideoProfileNotSupported` closed as correct behavior** (e76a94d) —
  see Next §3.
- **M10 device session executed (agent-driven via adb), 2026-07-30 — 15/16 PASS,
  1 FAIL found and fixed.** Passes: offline Minions 2 subtitle repro (both subs
  apply, text renders — the 190dc03 fix confirmed on device); offline picker
  shrinks (audio control disappears at 1 track); online full-list picker +
  stream-on-pick (POST /Sessions/Playing, Quality control appears); live shrink
  on airplane; STAB-01 blip on a real 7.2 GB ORIGINAL download ("failed
  transiently (attempt 1 of 5); it stays queued", resumed from byte 1.6 G);
  STAB-02/03/06 clean (kill-restore lands 58:10 vs 57:58 noted); PiP survives
  then tears down; ORIGINAL Élémentaire offline offers all 3 audio + 6 subtitle
  entries incl. embedded SRTs; MEDIUM sidecar extraction verified real on
  Minions 2 (subtitle.1/2.fra.srt on disk); cancel sweeps the directory; bulk
  pause/resume (bulk bar — there is no long-press multi-select, by design); no
  Room/migration errors, 0 FATAL in the whole buffer. Evidence:
  scratchpad device-session2/ screenshots + logcat excerpts.
  **The FAIL (B.3):** a forced-remote session direct-playing the original never
  returned to the local file when a file-held track was re-picked (in-stream
  switch succeeded first). **Fixed** (`fix(player)`, d3f408f): `goesHome` is
  decided before the player is offered the switch, weighing both selections;
  6 new tests, mutation-checked. Installed on the tablet; **B.3 device re-walk
  owed** (30 s: stream a non-downloaded track on Minions 2, re-pick French →
  Quality control disappears as it returns to Direct play).

- **Minified-build (R8 full mode) verification DONE, 2026-07-30 — M10 DoD.**
  `app-release.apk` (10.3 MiB, debug-signed fallback) installed as
  `dev.jellyboost.app`, logged in via Quick Connect. **All 12 checks pass;
  zero `ClassNotFoundException`/`NoClassDefFoundError`/`NoSuchMethodError`/
  `Cannot find implementation`/serialization errors across the whole session.**
  M5: direct play (badge + position advancing), forced transcode via the
  quality picker, audio switch (channel count 6→2) and subtitles rendering
  (French SDH), `Loaded FfmpegAudioRenderer.` present — the reflectively-loaded
  decoder survived R8. M8: 873 MB download completed (proves HiltWorkerFactory
  injection + Room `_Impl` lookup), **offline playback from disk verified**
  (airplane ON, Direct play badge, 4:24 → 4:37), user-data sync wrote back
  (detail page "19 min left"), Coil posters on home + Films grid.
  **SEC-01 acceptance check PASS:** a real search (`zorglubwidget`) left the
  release logcat with 0 occurrences of the search term, the server name, any
  UUID, and any `ApiKey`/token across 543 buffered lines.
  UDP discovery = NOT-TESTABLE (would require signing out of the session).
  Evidence: scratchpad `minified/` + `off*.png`/`sec1*.png`.
- **Downloaded rows play on tap** (`feat(downloads)`, a60274d) — UX gap found by
  the device testing (two agents assumed it worked), fix requested by the user.
  Reuses the detail page's `Routes.Player` call and resume rule verbatim; only
  completed rows are clickable (`QueueRow` has no `onPlay` at all); the delete
  icon stays an independent target. PLAN divergence logged (PLAN.md:76 listed
  the Downloaded tab as grouped/sizes/delete only). **Device-verified**: tapping
  a Pat' Patrouille row started playback immediately, no crash.

### Offline multi-track Phase 2 — IN PROGRESS (decided 2026-07-31, user)
- **All audio tracks of a transcoded download.** Design study
  `docs/notes/offline-multitrack-design.md`; DECISIONS entry 2026-07-31
  ("Offline multi-track Phase 2") records the user decisions (always-on, new
  downloads only, sidecars not remux) and the **amended fetch**: `/Audio`
  ignores `audioStreamIndex` on 10.11 (hard-coded null server-side, verified
  in source + empirically on the dev server), so each extra track is fetched
  via `/Videos/{id}/stream.mkv?audioStreamIndex=N` with junk video
  (h264 50 kbps 4 fps, ~54× realtime measured) and stripped locally by a
  Media3 Transformer transmux into `audio.<index>.<lang>.m4a`.
- Progress: chunk 0 (endpoint verification gate) DONE — refuted the design
  note's `/Audio` assumption, produced the amended fetch above; chunk 1
  (governance) DONE; chunk 2 (plan layer: `DownloadFileType.AUDIO`, fetch
  constants, `DownloadUrlFactory.audioStreamUrl`, planner sidecar rows +
  tests) DONE (`55429dd`).
- Chunk 3 (engine: transfer semantics, `AudioSidecarExtractor` Transformer
  strip stage, `part.mkv` un-resumable fetch, the `extraAudioBytes` size-estimate
  term and its knock-on into `planQuality`) DONE (`b3da5fa`). Chunk 4 (offline
  surface: `DownloadedMedia.audio` / `DownloadedAudio`, existence-gated like
  subtitles) DONE (`9eb1c66`). Chunk 5 (player, pure half: `LocalPlaybackResolver`
  offering every on-disk sidecar, `PlaybackMediaItemSpec.audioSidecars`,
  `withoutMergePrefixes` for the doubly-merged id) DONE (`853d62c`). Chunk 6
  (player, mechanical half: `ExoPlayerHandle.prepare`'s `MergingMediaSource`
  assembly, `TrackSelectionController`'s merge-child-index mapping) DONE
  (`67d9bb3`). All five commits green on `ktlintCheck detekt testDebugUnitTest`.
  Only chunk 8's **device walk is outstanding** (user-run): a transcoded
  multi-language download offline, picking each language, seeking each one,
  confirming the offline audio picker lists them all.
- **Sidecars now drain concurrently with the media file** (DECISIONS 2026-07-31,
  "Audio sidecars fetch concurrently with the media file"): `DownloadQueue.transfer`
  splits the plan into an ordinary lane and a sequential AUDIO lane, so an item
  costs `max(media, sidecars)` rather than their sum — the first device walk was
  spending ~11 minutes on two sidecars after the film had already finished.
  New `DownloadQueueAudioLaneTest` pins the overlap, the lane's own sequencing,
  and both failure directions.

- **Downloads queue polish (2026-07-31):** ETA on downloading rows (`… · 8,4 MB/s
  · 2 min left`, `~` on a `CEILING` total, hidden when speed is unknown/stalled or
  the estimate tops 24 h; new shared `formatDurationSeconds` in `:core:common`),
  and a `Transcoded` marker on queue + Downloaded rows (`quality.isTranscoded`).
  An extra-audio-sidecar stage indicator was speced, started, then **dropped at
  the user's request** — fully reverted, nothing remains in the tree. Also landed:
  detail-page metadata line shows `"X on device"` instead of the server size when
  the item's local copy is complete (`DownloadRepository.observeBytesOnDisk`, a
  `SUM` projection over `download_files`; containers keep the server figure).
  Detail download/container tests moved to `ItemDetailDownloadTest.kt` (LargeClass
  headroom, same precedent as the group-actions/selection splits).

### Next
1. ~~**Audit backlog Tier 2 + cleanup wave**~~ — DONE. All Tier 1 (11), all Tier 2
   headliners, and the entire cleanup wave (MKV/hygiene/perf/structural batches)
   are landed; every deviation is in DECISIONS.md. Remaining code-side backlog:
   the interface seam that would let `DownloadedMetadataRefresher`/
   `DownloadedMediaProvider` go internal, and
   `DownloadEnqueuer.removeDoomedContainerRow`'s broad catch (both flagged in
   the structural DECISIONS entry); multi-track Phase 2 decision (below).
2. ~~**DoD items left**~~ — ALL DONE: baseline profile captured on the test tablet (`569b8ac`, 21 497 rules, compiled into the release APK) and the
   M5/M8 re-verification on the minified build passed 12/12 (see above).
   **M10 is ready to close** once the clickable-download-row fix lands; the
   only outstanding device item is the 30-second B.3 re-walk on the debug
   build (stream a non-downloaded track on Minions 2 → re-pick French →
   Quality control disappears as it returns to Direct play).
3. ~~**HEVC `VideoProfileNotSupported` investigation**~~ — CLOSED 2026-07-30, not a
   bug: every HEVC decoder on the device (c2.mtk.hevc.decoder, its .secure variant,
   c2.android.hevc.decoder) reports Main profile only, no Main 10 (`dumpsys
   media.player`, direct measurement). The profile is data-driven from those
   capabilities and advertises Main-only correctly; the transcode is expected.
4. ~~**M10 device session**~~ — DONE 2026-07-30 (see the Done entry above:
   15/16 PASS, the one FAIL fixed as d3f408f). Still owed to a device:
   the 30-second B.3 re-walk on the new build, and SEC-01's release-logcat
   check, which belongs to the minified-build session (needs a login on the
   release install — user present).

## Pre-M10 multi-dimension audit (2026-07-30 — DONE, report committed)

Six parallel read-only audits (architecture, dead code/tooling, performance, stability,
security, MKV-parser deep-dive) + a blind red-team verification pass over every High.
**61 unique findings: 0 Critical, 7 High (all independently verified), 15 Medium, 39 Low**,
plus a substantial verified-non-problems list (MKV parser core, token handling, Hilt scoping,
startup, module graph all came back clean). Canonical record with the full three-tier backlog:
**`docs/notes/audit-2026-07.md`**.

Headline Highs: transient server blip permanently ERRORs the whole download queue (no retry
path exists); process-death restore overwrites the real resume position on the server; offline
grid silently ignores active filters; unguarded `startService()` crash after backgrounding
during a slow Play resolve; no timeout ceiling on the Play path; download-progress ticks
re-parse every item's JSON blob and stat-walk the whole downloads tree 2–6×/s.

## Pointless transcodes fall back to the original (post-M9, 2026-07-30 — built, gate green, **device-verified**)

`DownloadEnqueuer.planQuality`: when the chosen quality is transcoded and the estimate comes to
`>= 0.9 ×` the source file's own size, the row is written as `ORIGINAL` instead — exact size,
resumable, no server CPU, no quality loss. Per row (a season's episodes decide separately), decided
before the row is built, and only ever *towards* the original. An unknown source size or an
uncomputable estimate keeps the user's preference. No downstream change: everything already reads
the row's `quality` column. Docs: `docs/features/download-quality.md` ("When a transcode is not
worth making"), `DECISIONS.md` (2026-07-30). Tests: `DownloadEnqueuerSizeTest` 20 → 27.

- **Device walk done (2026-07-30, test tablet, all-pass).** At *High*, a 2,9 GB film logged
  `Backrooms: a HIGH transcode is estimated at 4101763080 bytes against an original of 2935082241 —
  downloading the original`; its queue row read `326,8 MB of 2,9 GB` (plain, no `~`), offered
  *Pause* (which paused and re-offered *Resume*), and landed on disk as the source's own
  `Backrooms.2026.…x265-[PSA]-BATGirl.mkv`, not a `(high).mkv`. Counter-case at *Low*: an 11 Mbps
  film kept its transcode — row `263,3 MB of ~3,0 GB`, no *Pause* button, no enqueuer log line,
  file `Ballerina (2025) (low).mkv`. Both cancelled; files removed.

## Detail-page media size + downloaded badge icon (post-M9, 2026-07-30 — built, gate green, **device-verified**)

The detail header's metadata line now shows the media file size (`2016 · 116 min · 552.4 MB · …`)
for items with a media source of their own (movies, episodes; series/seasons omit it), online and
offline — `JellyfinItem.sizeBytes` mapped from `mediaSources[0].size`, third `internal formatBytes`
copy in `:feature:detail`. Episode *rows* stay lean (no MediaSources in `EPISODE_FIELDS`). The
`Downloaded` badge on item cards is now `DownloadForOffline` (circular down-arrow) instead of the
checkmark. Docs: `docs/features/item-detail.md`. Tests: `ItemMapperTest` +3, `FormatBytesTest` +5.

- **Device walk done (2026-07-30, test tablet, all-pass).** Movie `2026 · 110 min · 2,9 GB · FR-12 ·
  7.1` and episode `2019 · 53 min · 1,0 GB · 7.8`; series (`2019 · FR-16 · 8.3 · 3 seasons`) and
  season (`2026 · 8 episodes`) carry no size. Landscape keeps the line intact with the poster beside
  the text. In airplane mode a downloaded film still read `2026 · 101 min · 5,2 GB · FR-TP · 9.0`
  from cache, and the app came back online cleanly. Badges on the library grid and Home render the
  circular down-arrow for completed downloads and the progress ring while one is running.

## Batch selection (post-M9 feature, 2026-07-29 — built, gate green, **not yet device-verified**)

Long-press → selection mode on the **library grid** and the **season page's episode list**, with
*Mark watched* / *Mark unwatched* / *Download* over the set. Shared model in `:core:common`
(`ItemSelection`, `SelectionIntent`, `runBatch`), shared contextual bar and summary copy in
`:core:ui`. Every action composes an existing single-item repository call — no new server
semantics. Home shelves and search results are deliberately out of v1.
Docs: `docs/features/batch-selection.md`, `DECISIONS.md` (2026-07-29). Tests: +37
(`ItemSelectionTest` 10 new, `ItemDetailSelectionTest` 13 new, `LibraryViewModelTest` +14).

- **Known issue / next:** needs a device walk on the test tablet — long-press and haptic, the
  contextual bar in portrait *and* landscape on both screens, a mixed batch (some items already
  downloaded), and the offline behaviour of both actions.

## Post-M9 polish stream — docs/POLISH.md punch list (2026-07-29, DONE)

All 14 punch-list items plus 4 bugs found along the way, built in six parallel/serial
worktree passes, orchestrator-merged and gated. Final forced-rerun gate on the merged
tree: **858 tests, 0 failures** (748 → 858 across the stream). Two device-verification
walks on the test tablet, second walk all-pass, no regressions, device left clean.

Highlights (details in DECISIONS.md and docs/features/):
- **Downloads:** real pause; truthful speed (≥1 s windows, validated against byte
  counts); quality setting Original/High/Medium/Low — transcodes are bitrate-capped
  H.264/AAC in **MKV** (schema v5, quality stamped per row; streamed mp4 was
  unplayable); season/series taps expand into per-episode rows (folder-item guard,
  legacy stuck rows self-clean); delete confirmations everywhere.
- **Offline:** refresh fires on both connectivity edges (Home switches content the
  moment you go offline); downloaded items' rich metadata is protected from lean
  browse writes, repaired by full fetches, and kept current by a standing
  once-per-online-stretch sync (`DownloadedMetadataRefresher`).
- **Connection:** the reachability probe re-runs on every session change — fixes the
  fresh-install race where a successful login still showed "can't reach the server"
  until restart (found on the release-build install).
- **Chrome:** one combined top bar replaces bottom nav + per-screen top bars
  (~140 dp reclaimed); offline banner → status icon + timed snackbar; search inset
  bug fixed by construction; system-bar icons pinned light for the dark-only theme
  (incl. splash).
- **Performance:** contentType, no per-cell subcomposition, Coil memory+disk caches,
  artwork requested at display resolution. Measured on-device at 90 Hz on a
  ~500-poster grid: release 0.49 % janky / 7 ms median (meets target pre-R8);
  debug ~4 % — perceived scroll lag is debug-build overhead. Release build is
  debug-signed as a local measurement aid; real signing/R8/baseline profiles stay M10.

### Known issues / carried forward (also in docs/POLISH.md)
- ~~Transcode size estimates use the bitrate cap → far above real output (552 MB
  est. vs 232 MB actual on a LOW episode).~~ — **fixed 2026-07-29**:
  `DownloadEnqueuer.expectedBytes` now uses min(quality cap, source bitrate) when the
  source's bitrate is known/positive, else the cap. Unit-verified (3 tests); device
  check pending (blocked — see the second-run note below).
- ~~Portrait detail banner leaves empty space at the bottom.~~ — **fixed 2026-07-29**:
  banner is now 0.40 × viewport height in portrait, coerced between the old
  width-derived value and 560dp. Device-verified on the test tablet, both
  orientations: portrait ~449dp (was 220dp), landscape unchanged at 320dp.
- ~~Season-level Cancel silently deletes already-completed episodes (no
  confirmation).~~ — **fixed 2026-07-29**: Cancel on an in-flight season now deletes
  only queued/transferring/paused/failed rows and keeps `Downloaded` ones, with a
  snackbar naming the count kept; Remove still deletes everything. Unit-verified (4
  tests); device check pending (blocked — see the second-run note below).
- Series detail page has no aggregate download-button state (logged in DECISIONS.md).

### Second polish run (2026-07-29)
Three punch-list items above landed in code (commits 339d725, 7b90788; decisions
logged in DECISIONS.md): the transcode size estimate, the portrait banner, and
season-cancel keep-finished. Full gate on the merged tree: **884 tests, 0 failures**
(858 → 884). The banner is device-verified both orientations; the size-estimate and
season-cancel device checks are blocked — the tablet's stored server token was
revoked server-side (every authenticated call now 401s), so the user needs to sign
in again on the tablet before either check (both require enqueuing fresh downloads).

### Third polish run (2026-07-29)
The whole "New run" list landed (four worktree agents, orchestrator-merged; decisions
in DECISIONS.md): detail back arrow/snackbar system-bar insets (b0177c6); watched-state
changes now update home row *membership* — instant eviction from Continue watching /
Next up plus a debounced silent re-fetch that also covers series/season toggles
(2dc3e4e); configurable download storage location with SD-card support via secondary
volumes, delete-all-and-switch guard per plan v1 (47bf76d); and Home now renders the
section layout the user configured in jellyfin-web, read from DisplayPreferences with
per-slot default fallback and an offline cache (febd896) — the feasibility research is
in docs/notes/home-sections-feasibility.md. Also this run: the recurring "session
expired" bug root-caused and fixed — per-install UUID device id instead of the
signing-key-scoped ANDROID_ID (a64ed96); both installs need one re-sign-in. Also landed after the device walk: tab switches pop to `Routes.Home` instead of the
graph's start destination — fixes duplicated HomeViewModels/refresh pairs on
signed-out launches (649a7c8) — and capped transcode estimates now read "up to X"
(ee490d0), with the deeper estimation question answered in
docs/notes/download-size-estimation.md (mid-flight projection feasible, pre-flight
impossible). Full gate: **941 tests, 0 failures** (884 → 941). All device checks
DONE on the re-signed-in tablet (walk notes in docs/POLISH.md): insets both
orientations, watched→membership round-trip, season-cancel keeping finished
episodes, storage picker correctly hidden single-volume, single refresh pair per
toggle post-nav-fix. Size estimate confirmed working as designed (ceiling semantics;
encoder undershoot on easy content is the residual).

### Live size projection for transcoded downloads (2026-07-29)
The estimation note's full build list implemented (opus worktree agent,
orchestrator-merged; DECISIONS.md "a transcoded download's size stops being a
ceiling and becomes a measurement"): `MkvClusterScanner` reads Matroska cluster
timestamps off the bytes already being copied and `TranscodeSizeProjector` turns
them into a live projection (clamped `[bytesReceived, ceiling]`, schema v6's
`projectedBytes`/`sizeIsExact`, AutoMigration 5→6); episodes are seeded from the
median rate of finished same-series/same-quality siblings; requests the server
answers with a video stream copy are recognised (`CanStreamCopyVideo` gates
verified against 10.11 source) and shown exact; the Downloads screen words the
figure "X" / "~X" / "up to X" and a per-session ratchet keeps the percent
monotone, holding 99 % until DOWNLOADED. `playSessionId` now rides on transcode
URLs. Full gate: **1032 tests, 0 failures** (941 → 1032). User-confirmed working
on the tablet.

Follow-up (same day, user report "seeding doesn't work with a currently running
download"): seeding was enqueue-time only, so a season queued in one tap never
seeded episodes 2..N. Now a reusable `SiblingSeeder` is asked at three moments —
enqueue, queue pick-up, and sibling completion (re-seeding waiting rows via
`setProjectedBytesIfAbsent`, which can never clobber a live measurement). Data
path verified sound; the gap was purely *when*. Gate: **1061 tests, 0 failures**
(1032 → 1061).

Second follow-up (same day, user report "transcoded downloads don't allow
selecting the reading position"): root-caused from real bytes off the tablet —
ffmpeg streams the header before its end-of-encode patch, so the file lands with
a complete 698-point `Cues` index at EOF that nothing points at (152-byte
reserved Void where the `SeekHead` belongs, no `Duration`); Media3 finds Cues
only via SeekHead → `SeekMap.Unseekable` → every seek lands at 0. Fix:
`MatroskaSeekIndexRepair` writes the missing 26-byte SeekHead + 11-byte Duration
into ffmpeg's own reserved Voids at first local playback (idempotent,
verify-and-rollback, refusals leave the file byte-identical) — which also heals
every transcode already on the device; no schema change. Pause removed from
transcoded queue rows (server ignores Range → "pause" would silently restart
from zero; Resume stays). Gate: **1082 tests, 0 failures** (1061 → 1082).

Third follow-up (same day, user report): the offline *Latest* shelf listed raw
downloaded rows, so one downloaded season filled all 16 slots with its own
episodes. It now performs the server's `GroupItems` reduction client-side:
episodes collapse into one series card (grouped before the limit), the card is
the cached series row or a synthesised one from the episode's series fields,
movies unchanged. Gate: **1094 tests, 0 failures** (1082 → 1094).

### UX batch (2026-07-29, five user requests, parallel/serial worktree agents)
- Search opens with the field focused + keyboard up, guarded so it never steals
  focus over visible results (4dc7579).
- Library grid cells anchored to `Dimens.PosterWidth` — landscape was 9 cols of
  ~112dp vs Home's 120dp cards; now 8 × ~128dp, portrait unchanged (60e410c,
  DECISIONS: diverges from PLAN's literal `Adaptive(110.dp)`).
- Home button next to Back on item detail / library grid / settings; player
  excluded (793d1af). `navigateHome()` = navigate + popUpTo<Home> +
  launchSingleTop, with `saveState`/`restoreState` deliberately off — reusing
  the tab-switch options made the button a no-op on any screen pushed from Home
  (it saved the chain under Home's own key, then restored it in the same call).
- Downloaded tab: films gather under one shared "Movies" heading after all
  series groups when both kinds are present; films-only/series-only unchanged
  (dc2f521, DECISIONS: reverses logged alphabetical interleave).
- Queue tab: bulk action bar — Pause all (skips transcodes, counted snackbar),
  Resume all (PAUSED+ERROR), Cancel all (confirmation dialog surviving rotation;
  DOWNLOADED rows untouchable by construction); per-row and bulk share the same
  target predicates; DownloadsMessage enum → sealed interface (logged).
Gate after the batch: **1114 tests, 0 failures** (1094 → 1114).

Round 2 of the "library tab items smaller than Home" report: the poster-grid fix
above didn't address it — the user meant the Libraries tab itself (the "Films"/
"Séries" category tiles, `LibrariesScreen.kt`), a different screen with its own
`MIN_CELL_WIDTH = 160.dp`. Anchored to `Dimens.ThumbWidth` (210dp, same token
Home's *My Media* row uses): test tablet portrait 4×~161dp → 3×~218dp, landscape
6×~174dp → 5×~212dp — both now at/above Home's 210dp card (DECISIONS: Libraries
tab category tiles minimum cell width raised to Dimens.ThumbWidth). Code-only,
no Compose-UI harness in the repo to add a test to.

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
- **Refresh on connectivity change (M6 known issue closed):** on the airplane-off edge, with no
  input, live screens re-fetched themselves (logcat: `UserViews`, the open item +
  `/Similar`) and the pending user-data row drained in ~1 s; the detail screen
  visibly gained its full online content without re-entry. One refresh per edge, no
  storm. (This walk predates the both-edges fix below; the airplane-*on* edge — a
  live screen dropping back to its offline data — is covered by the M9 settings +
  app polish block's device-verification step 5.)

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
- ~~HEVC files transcode with `TranscodeReasons=[VideoProfileNotSupported]`~~ —
  RESOLVED as correct behavior (2026-07-30 investigation): the premise that the
  Helio G100 decodes Main 10 was wrong. Direct measurement (`dumpsys media.player`
  on the test tablet) shows every HEVC decoder path — `c2.mtk.hevc.decoder`,
  `c2.mtk.hevc.decoder.secure` (both Main/High 5.1), and the software
  `c2.android.hevc.decoder` (Main + MainStill/High 5.2) — is Main-profile-only.
  `MediaCodecProbe` → `DeviceProfileBuilder.codecProfile()` correctly advertises
  `Main` only, so the server transcodes Main 10 content as it should. 8-bit Main
  HEVC direct-plays on the hardware decoder (confirmed via `media.metrics`: a real
  1920×960 hardware decode session, no errors). No code change; matches the
  upstream jellyfin-android approach.
- Backgrounding the app pauses playback: `POST_NOTIFICATIONS` is declared but never
  requested (M9), so the media notification can't show; background-continue +
  notification permission flow are M9 scope (background playback is not in the M5 DoD).
- ~~Screens loaded while offline keep their offline data after connectivity returns until
  the user re-enters them (e.g. Home shows only cached My Media after a reconnect; a
  killed/relaunched app is fine). The delegating repository is per-call, but ViewModels
  don't re-fetch on connection regain — wire a refresh-on-reconnect (or pull-to-refresh)
  by M9.~~ — **fixed on the M9 branch**: `ConnectivityRefresher` (`:data`) publishes on
  every online-ness change — both the `false → true` edge and, since a same-day fix
  once the one-way version turned out to leave the reverse case just as stale, the
  `true → false` edge too — that home, libraries, search, item detail and the library
  grid's filter facets re-load themselves on (the grid's items already swap either way
  via `getItemsPaged`). See `docs/features/offline-read.md`, "Following the connection".
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
- Dashboard→Devices: user confirmed "Jellyboost 0.1.0" session in web UI.
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
  `dev.jellyboost.app.debug`.
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
- `:data` `UserDataSyncTrigger` + `JellyboostApplication.onCreate` — enqueues the
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
   (`adb shell run-as dev.jellyboost.app.debug sqlite3 databases/jellyfin.db 'SELECT itemName,status FROM downloads;'`).
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
   (the OEM ROM can delay WorkManager; `adb shell cmd jobscheduler run -f dev.jellyboost.app.debug <id>`
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
   `adb shell run-as dev.jellyboost.app.debug` … or simply verify `SHOW_BUTTON`) and watch for
   `Auto-skipping INTRO to <ms> ms`, then seek back into the intro and confirm it is **not** skipped
   again — a button appears instead. On a server without the plugin expect
   `No media segments available for <itemId>` and no button at all.
4. **Background playback (the M5 known issue):** start playback, press Home. Audio must continue and
   a media notification with play/pause must appear. `adb shell dumpsys media_session | grep -A3
   jellyboost` shows the session; `adb shell dumpsys activity services PlaybackService` should
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
  it (DECISIONS.md 2026-07-29). ~~it lives only in Coil's in-memory cache key~~ — corrected
  2026-07-30 (audit SEC-02): the tokened URL also reached Coil's disk cache as its default key;
  since the SEC-02 fix `TrickplayPreview` sets explicit token-stripped `diskCacheKey`/
  `memoryCacheKey`, so neither cache keys on the token and rotation no longer orphans tiles.
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
  `JellyboostApp` → `AppScaffold` → `JellyfinNavHost` → `HomeRoute`; `MainViewModel.signOut()` is
  gone along with its test. `MainViewModel` still restores/exposes the session.
- **Hit-target fix.** `feature/downloads/DownloadsScreen.kt`'s Wi-Fi-only top-bar row now toggles on
  the whole row (`Modifier.toggleable(role = Role.Switch)`, `Switch.onCheckedChange = null`), closing
  the STATUS M7 note. The home overflow's Offline-mode row already dispatched on the whole
  `DropdownMenuItem`; it gained explicit `Role.Switch` + on/off `stateDescription` for TalkBack.
- **Refresh on connectivity change**, closing the M6 known issue — and, after a same-day fix, on
  *both* edges rather than one. `Flow<ConnectionState>.onlineStateChanges()` (`:core:network`) emits
  the new online-ness on every change, dropping only the flow's initial value (so a normal launch —
  which already fetches once in every `init` — does not double-fetch; this is deliberately narrower
  than `UserDataSyncTrigger`'s convention, DECISIONS.md). It first shipped filtered down to the
  `false → true` edge only, which turned out to be the bug it was meant to fix: switching to offline
  mode or losing the network left an already-loaded screen showing online rows it could no longer
  play. Dropping that filter so it fires symmetrically closed the gap (DECISIONS.md, 2026-07-29).
  `ConnectivityRefresher` (`:data`) wraps it as a bare `Flow<Unit>` so feature modules never need
  `core:network` on their classpath. Wired into `HomeViewModel`/`LibrariesViewModel`/
  `ItemDetailViewModel` (call their existing `refresh()`), `SearchViewModel` (`retry()`, only if the
  query is non-blank), and `LibraryViewModel` (facets only — the grid already rebuilds its `Pager`
  per connection change inside `DelegatingJellyfinRepository.getItemsPaged`, so it needed no new
  wiring, in either direction). `HomeViewModel` also now drops a library card from *My Media* when
  its *Latest* call succeeded and came back empty (kept when the call failed) — offline,
  `getUserViews` answers from the full cached `library_views` table regardless of what was
  downloaded, so this keeps the cards honest on the same edge.
- **Offline user-data push silenced**, closing the M8 known issue. `UserDataRepositoryImpl.
  pushToServer` now returns immediately (one `Timber.d` line, no HTTP attempt, no
  `syncScheduler.enqueue()`) when `ConnectionStateProvider.state.value.isOnline` is false — the
  local Room write (`toBeSynced = true`) and the `UserDataEventBus` emission both still happen
  unconditionally beforehand. `UserDataSyncTrigger` already drains every pending row on the next
  `OFFLINE → ONLINE` edge, so nothing is lost; a five-minute offline playback session now logs one
  debug line total instead of ~60 warning stacks. Online behaviour is byte-for-byte unchanged.
- **+39 unit tests** (12 `SettingsViewModelTest`, 20 across `ConnectivityEdgesTest`/
  `ConnectivityRefresherTest`/the five connectivity-wired ViewModel test files, 5 `UserDataRepositoryImplTest`,
  2 `MainViewModelTest` net); 0 failures, 0 skipped. Full gate green in one run
  (`ktlintCheck detekt testDebugUnitTest assembleDebug`). The branch total is stated once, below,
  under *Download quality*, which is the later of the two passes on this branch.
- 5 DECISIONS entries (storage picker deferral; Settings via overflow not avatar; offline write does
  not enqueue the sync worker; refresh signal drops its initial value; refresh signal fires on both
  edges); `docs/features/settings.md` (new), `docs/features/offline-read.md` and
  `docs/features/user-data.md` updated.

**Done — download quality for offline downloads (M9, user-requested via docs/POLISH.md)**
- **A `downloadQuality` preference** — `ORIGINAL` (default, unchanged behaviour) / `HIGH` (20 Mbps,
  1080p) / `MEDIUM` (8 Mbps, 1080p) / `LOW` (3 Mbps, 720p), the video bitrates deliberately the same
  ladder `PlaybackQuality` uses. Enum in `:core:common`, DataStore key `download_quality` in
  `:core:datastore`, radio group in the settings Downloads section. docs/PLAN.md line 7 lists
  transcoded downloads as *not v1*, so the scope addition is logged in DECISIONS.md before the code.
- **Read once, stamped on the row.** `DownloadEnqueuer` reads the preference when the user taps
  *Download* and writes it to the new `downloads.quality` column (schema v4 → v5, a Room
  `@AutoMigration` on a `TEXT NOT NULL DEFAULT 'ORIGINAL'` column, so an existing install keeps its
  queue). `DownloadQueue.reconcile` plans every later run from the row, never from the live
  preference — `reconcile` deliberately rebuilds URLs each run, so a preference changed mid-transfer
  would otherwise resume a half-written transcode against `/Items/{id}/Download`, which honours
  `Range`, and mark a corrupt file `DOWNLOADED` (DECISIONS.md).
- **The transcode URL.** `videosApi.getVideoStreamByContainerUrl` → `/Videos/{id}/stream.mkv`, with
  the container in the *path* (one progressive file, not an HLS playlist), `static = false`,
  `videoCodec = h264`, `audioCodec = aac`, the step's `videoBitRate`/`maxHeight`, 192 kbps stereo
  AAC, `allowVideoStreamCopy = true`, and `context = EncodingContext.STATIC` — a `STREAMING`
  transcode is throttled by the server to roughly real time.
- **Two documented losses, both `ORIGINAL`-only guarantees.** No `Content-Length` (the file is not
  encoded yet): the row is seeded with `runTimeTicks × bitrate / 8` and `ItemProgress` uses it as a
  floor while any file's real size is unknown, dropping it once every file has reported, so a
  generous estimate cannot strand a finished item below 100 %. No resume: the endpoint ignores
  `Range`, and `FileDownloader` already truncates-and-rewrites on a `200` answer to a ranged
  request, so an interruption costs a repeated transfer and never a corrupt file. A transcoded media
  file is named `<directory> (<quality>).mkv`, and the `403`/`enableContentDownloading` fallback to
  the static stream is skipped for a transcoded row (it would hand back the original file).
- **+21 unit tests** (5 `DownloadFilePlannerTest`, 2 `DownloadPathsTest`, 4 `DownloadEnqueuerTest`,
  4 `DownloadQueueTest`, 4 `DataStoreAppPreferencesTest`, 2 `SettingsViewModelTest`); branch total
  **779**, 0 failures, 0 skipped (counted from the JUnit XML, not the gradle summary line). Full
  gate green (`ktlintCheck detekt testDebugUnitTest assembleDebug`).
- 3 DECISIONS entries (the scope addition itself; quality stored on the row rather than read live;
  a transcode is not resumable and its size is an estimate); `docs/features/download-quality.md`
  (new), `docs/ARCHITECTURE.md` updated.

**Done — two defects the M9 verification walk found in the work above**
- **A downloaded item's offline metadata can be refreshed and repaired again.** The blob-preservation
  fix (`BrowseCacheWriter`, "a lean browse write must not gut a download's rich DTO") was applied
  unconditionally, so it also discarded the one response strictly better than what was stored:
  `getItem` returns the *complete* field set and funnels through the same `cacheItems` path, and its
  blob was thrown away too. A row an earlier build had already gutted could therefore never be
  repaired — opening the item online refetched everything and kept the bare version, leaving a blank
  offline detail page permanently. `cacheItems`/`writeItems` now take an explicit `full: Boolean`
  (defaulting to the safe answer, *preserve*); `OnlineJellyfinRepository.getItem` is the single call
  site that passes `full = true`, every list read stays lean, and a full write skips the blob read-back
  entirely since it is about to overwrite it. The flag is a **caller** statement, deliberately not a
  heuristic sniffed out of the DTO's shape — an item that genuinely has no overview would fool any
  such sniff in the direction that loses data. `DownloadEnqueuer` is unaffected: it upserts its
  `DOWNLOAD_FIELDS` response straight to the DAO, bypassing the writer, so its blob is rich by
  construction. +5 tests (3 `BrowseCacheWriterTest` pinning both directions and the repair,
  2 `OnlineJellyfinRepositoryTest` pinning which call sites set the flag).
- **A transcoded download is now a playable file.** The M9 quality feature asked for `mp4`, and a
  server muxing mp4 as it sends it must emit `mdat` with size `0` ("runs to EOF") and append the
  `moov` behind it; Media3's `Mp4Extractor` then swallows the index inside the `mdat` and fails with
  `ParserException: … contentIsMalformed=true`. Every non-`ORIGINAL` download was unplayable offline,
  and online it silently fell back to server streaming, which is what hid it. `DownloadQuality.
  CONTAINER` is now `mkv` — every element in Matroska carries its own size as it is written, so the
  file is valid at every prefix; Media3 has a full `MatroskaExtractor` and `mkv` is already in this
  app's `SUPPORTED_CONTAINER_FORMATS`. Codecs, bitrates, `static = false` and
  `EncodingContext.STATIC` are unchanged, and `ORIGINAL` is byte-identical to before. `.ts` was the
  runner-up, rejected for having no duration metadata and ~4 % packetisation overhead (DECISIONS.md).
  +1 test (`a transcoded download is never named mp4`, pinning the whole ladder); two existing
  expectations changed from `.mp4` to `.mkv` because the assertion *was* the bug, both explicitly
  recorded in the DECISIONS entry.
- Branch total **808** unit tests, 0 failures, 0 skipped. Full gate green
  (`ktlintCheck detekt testDebugUnitTest assembleDebug`).

**Next — device verification (orchestrator)**
1. `./gradlew installDebug`, launch signed in.
2. **Settings screen:** tap the home top-bar overflow (⋮) → *Settings* (no more *Sign out* there).
   Confirm all four sections render; toggle each switch and each three-way skip choice, relaunch the
   app, confirm every choice persisted (`adb shell run-as dev.jellyboost.app.debug` +
   `DataStore` prefs, or just observe the UI survives the relaunch). Rotate to landscape on the
   tablet — content should stay capped and centred, not stretch edge-to-edge.
3. **Sign out:** tap *Sign out* → confirm dialog appears with the "Also delete downloads" checkbox
   unchecked. Cancel, confirm nothing happened. Download one small item first, then sign out with the
   checkbox checked — confirm the download is gone (`adb shell run-as … sqlite3 databases/jellyfin.db
   'SELECT COUNT(*) FROM downloads;'` → 0) and the app lands on server setup. Sign back in.
4. **Hit targets:** in Downloads, tap anywhere on the Wi-Fi-only row (not just the switch) — it
   toggles. In the home overflow, tap anywhere on the Offline-mode row — it toggles (this already
   worked; confirm no regression).
5. **Refresh on connectivity change:** first the original edge — airplane mode on, browse
   Home/Libraries/Search/an item detail page so each loads its offline data, airplane mode off.
   Within a couple seconds each screen should silently refresh — watch logcat for each screen's
   normal load call re-firing exactly once (no storm), and check Home/Search in particular show live
   data again without leaving the screen. Then the reverse edge, which is the fix landing with this
   pass: with those same screens showing live server data, turn airplane mode on (or toggle Offline
   mode from the home overflow). Each screen should silently reload from Room within a couple
   seconds — no crash, one reload each, Home's *My Media* should drop any card whose library has
   nothing downloaded rather than opening onto an empty grid.
6. **Download quality:** Settings → Downloads → *Download quality*. Leave it on *Original file* and
   download one item — the queue tab should show an exact size and the file on disk should keep the
   server's own filename and container. Set it to *Low*, download a second item — the queue row
   should show an approximate size that keeps moving (not an indeterminate bar), the file should
   land as `<directory> (low).mkv`, and it should play offline **from the local file** (watch logcat
   for the local resolver, not a stream URL — an mp4 build of this failed here with
   `contentIsMalformed=true` and silently fell back to streaming). Pause it mid-transfer and resume:
   the transfer restarts from zero (expected — the endpoint ignores `Range`) and the finished file
   is still playable. Confirm changing the setting while something is downloading does **not** touch
   the running item (`adb shell run-as … sqlite3 databases/jellyboost.db
   'SELECT itemName, quality FROM downloads;'`). Upgrade check: install over an existing build and
   confirm the queue survives (schema v4 → v5 auto-migration) with every old row reading `ORIGINAL`.
7. **Offline push silence:** airplane mode on, start local playback of a download, let it run
   ~30 s (six 5-second ticks). Logcat should show six `User data for … stays pending (offline, not
   pushing)` debug lines and **zero** `stays pending: …` warning-with-stack-trace lines. Reconnect —
   the existing M8 drain path (`UserDataSyncTrigger` → `Pushed the local user data…`) still fires.
8. **Offline metadata repair:** find a downloaded item whose offline detail page is bare (a pre-fix
   build gutted its blob — the walk found several). Online, open its detail page once, then go
   offline and open it again: the overview, genres, cast and taglines should now all be there.
   Then the other direction, which must **not** regress: with that item still downloaded, scroll the
   home and library screens past it online several times, go offline, re-open it — the description
   must still be present, and its position in the offline *Latest* row must not have moved.

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


<!-- BEGIN a11y wave 2 (design-system semantics) -->

## Accessibility remediation — wave 2: design-system semantics (2026-08-05)

Wave 2 of the audit in `docs/audits/accessibility-audit-2026-08-05.md`, after wave 1's
contrast tokens and height floors. Three components carry most of it.

**Done**
- **Text fields** (`JellyfinTextField`): a field node now has a *name* (`labelText`, sentence
  case — the visible caption is uppercased and is muted for the screen reader), an `error(…)`
  with the failure in it, `password()` and autofill content types. Both auth screens pass
  `isError` + the message they already display; search names its field.
- **Cards** (`PosterCard`, `ThumbCard`, `LibraryCard`, `MediaCardArtwork`, `DownloadBadge`,
  `JellyfinAsyncImage`): one merged node per card with an authored sentence — type, untruncated
  title, subtitle, progress, rating, download and watched state — plus real `selected`
  semantics in selection mode and a labelled long press to enter it. Everything inside a card
  is silenced so the sentence is the only thing spoken. `ThumbCard(onClick = null)` is the new
  non-clickable form, which is what makes an episode row one node instead of two.
- **Chips** (`PillChip` + new `InfoPillChip`): `selectable` with a 48dp frame; genres are inert
  rather than "disabled" (DECISIONS entry).
- **Silent states**: `LoadingState` says "Loading" politely, `ErrorBanner` announces
  assertively, a busy pill says "Busy" instead of only "disabled".
- **Structure**: section titles are headings, "See all" says *of what*, the selection bar's
  count is a polite live region, the snackbar's action is a 48dp target.
- Pure builders (`MediaCardFacts.describe`, `progressPercent`, `cardTitleMaxLines`,
  `itemTypeLabelRes`) are covered by `core/ui`'s `MediaCardFactsTest` (13 pins).

**Next (later waves, not this one)**
- Screen-level announcements: auth failures, search result counts, downloads progress
  (wave 4) — this wave gave the components live regions, not the screens.
- The player is wave 3 and was untouched here; its call sites still pass no `labelText` and
  its chips keep the old `PillChip` shape (source-compatible, so nothing broke).
- No androidTest/semantics tests exist yet — that is wave 5, and until then none of the above
  is held in place by anything but the JVM pins on the pure builders.

**Device check worth doing**: TalkBack over home → a library grid in selection mode → an
episode list: one stop per card, the Play button inside an episode row still reachable, and
the selection count announced as it changes.

<!-- END a11y wave 2 (design-system semantics) -->
