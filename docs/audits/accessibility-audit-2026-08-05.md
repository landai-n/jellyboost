# Accessibility audit — 2026-08-05

Full-app accessibility audit (TalkBack/semantics, touch targets, text scaling, contrast,
keyboard, motion, tooling). Five parallel audit passes: design system (`core:ui`), player,
browse surfaces (home/library/detail/search), forms & shell (auth/settings/downloads/app),
and cross-cutting (contrast math, manifest, keyboard, localization). Read-only — no code
was changed. Contrast ratios were computed from the actual hex/alpha tokens (WCAG 2.x
relative luminance with alpha compositing).

**Verdict:** the static foundations are unusually good (all text in `sp`, zero shipped
hardcoded content descriptions across 90 locales, a documented 48dp touch-target pattern,
clean RTL and manifest hygiene, a real M3 `Slider` under the custom seek bar). The gaps are
almost all *dynamic*: nothing in the app ever announces (zero `liveRegion`, zero
`progressBarRangeInfo`, one `stateDescription` repo-wide), cards/fields don't compose their
semantics into coherent nodes, the player is unreachable under TalkBack once controls hide,
there is no keyboard support at all, and several alpha-derived color tokens fail contrast
over bright content. There is no a11y test or lint coverage to hold any fix in place.

---

## Critical findings (8)

| ID | Where | Problem | Fix sketch |
|----|-------|---------|------------|
| CR-1 | `player/ui/PlayerScreen.kt:143-148,570` + `PlayerGestureLayer.kt:151-158` | Controls auto-hide after 4s; the tap-to-reveal layer is a bare `pointerInput` with **no semantics node**, so a TalkBack user can never bring controls back (touch exploration consumes taps). Controls also vanish mid-traversal. | Add `semantics { onClick(label) { onToggleControls(); true } }` to the gesture box; suppress/extend auto-hide when `AccessibilityManager.isTouchExplorationEnabled` (use `calculateRecommendedTimeoutMillis`); re-show on any key event. |
| CR-2 | `core/ui/component/JellyfinTextField.kt:126-190` | The hand-rolled field never associates label, error, or supporting text with the `BasicTextField` node. Every field in the app (server address, username, password, search) announces only its value + "edit box". `isError` is color-only; no call site even passes it. | Thread a `labelText: String`; `Modifier.semantics { contentDescription = labelText; if (isError) error(msg) }` on the field; pass `isError` from auth call sites. |
| CR-3 | repo-wide | **Zero announcements**: no `liveRegion` anywhere; `stateDescription` ×1; `progressBarRangeInfo` ×0. Auth failures, loading states, search results, buffering, SyncPlay waiting, skip-intro offers, snackbar-adjacent banners, selection-count changes — all silent. | Polite `liveRegion` on loading/status/result-count nodes; Assertive on `ErrorBanner`/auth errors/player error; see per-area lists below. |
| CR-4 | repo-wide (`FocusRequester` ×1, `onKeyEvent` ×0, `focusProperties` ×0) | **No keyboard/d-pad support** in a tablet-targeting app. No player shortcuts (space/arrows/Esc), no traversal grouping, no visible focus indicator (the only focus affordance, `FieldActiveBorder` @0.22 white, is 1.97:1). Media keys work only via `MediaSession`. | `onPreviewKeyEvent` on the player root; `focusGroup()` around chrome/content; a shared ≥3:1 focus ring indication. |
| CR-5 | `player/ui/PlayerControls.kt:803,115` | Controls scrim is `Black@0.35` → over a bright frame the title is **2.44:1**, subtitle 1.92:1, clock 1.77:1, seek track 1.23:1. The same file's `VIDEO_GLASS_FILL` (Black@0.6) passes at 5.74:1. | Raise `SCRIM` to ≥0.62 or use top/bottom gradient scrims reaching 0.62 under the control bands. |
| CR-6 | `core/ui` cards: `PosterCard.kt:58-102`, `ThumbCard.kt:51-96`, `LibraryCard.kt:93-103`, `MediaCardArtwork.kt:69-85` | Cards don't merge descendants and the artwork `contentDescription` duplicates the title → double announcement, 3–6 swipes per card, badges floating free of their item, no `Role`. Universal (28+ call sites). Nested-clickable variant makes episode rows two stops, the first useless (`EpisodeRow.kt:104-165`). | One authored merged description per card (type + full title + subtitle + progress/state), `role = Role.Button`; null the artwork description when a title renders; de-clickable the nested ThumbCard. Must carry the *untruncated* title (visible text is `maxLines=1`). |
| CR-7 | no `androidTest` source set in any of 17 modules | **Zero instrumented/a11y tests**; `ui-test`/espresso/ATF deps not even in the version catalog; `connectedDebugAndroidTest` has nothing to run. Lint a11y checks are non-fatal (`abortOnError=false`) and lint isn't in the `/verify` gate. | Add `ui-test-junit4` + `espresso-accessibility`; `AccessibilityChecks.enable()` smoke test per destination; `lint.xml` making `ContentDescription`/`TouchTargetSizeCheck` errors; add `lintDebug` to `/verify`. |
| CR-8 | `PlayerGestureLayer.kt:104-142` | Brightness is **gesture-only** (precise vertical drag), no button/menu/custom-action alternative. (Double-tap seek has button fallbacks — but only while controls are visible, see CR-1.) | Brightness/volume rows in a playback sheet, or `customActions` on the gesture layer node. |

---

## Major findings

### Announcements & state (instances of CR-3)
- **AUTH**: errors never announced — `ServerSetupScreen.kt:624-634`, `LoginScreen.kt:368`, `ErrorBanner.kt:68-102` (both call sites are auth). Progress silent: `ServerSetupScreen.kt:425-436`, `LoginScreen.kt:228-235,561-577`; busy pill announces "disabled" (`JellyfinButtons.kt:305-311`). Disabling fields mid-request drops focus with no anchor (`ServerSetupScreen.kt:397`, `LoginScreen.kt:320,330`).
- **SEARCH**: results/empty/error never announced; no result count exists in `SearchUiState` (`SearchScreen.kt:174-227`).
- **PLAYER**: buffering is computed but has **no UI consumer at all** — mid-playback rebuffer shows a frozen frame with no spinner and no announcement (`PlayerUiState.kt:46`, set `PlayerViewModel.kt:1510`). SyncPlay waiting overlay, skip-segment offer (time-boxed!), and error state all appear silently (`PlayerScreen.kt:186-224,309-372`).
- **DOWNLOADS/CARDS**: progress conveyed by ring/bar fill only — `DownloadBadge.kt:104-113` (in-flight state is the only unlabeled one), `DownloadRows.kt:151-296` (bare "45 percent" node untied to its item), `DownloadsScreen.kt:1104-1126` (`UsageBar`, no semantics), `MediaCardArtwork.kt:392-416` (`InsetProgressBar`, resume progress invisible to TalkBack — the point of Continue Watching). Detail's `LinearProgressIndicator` (`ItemDetailHeader.kt:422-428`) is the one correct case.
- **SELECTION**: count changes silent + fixed 60dp height + ellipsizes to "4 sel…" on narrow widths (`SelectionAppBar.kt:79-104`). Card selection is a description fragment, not `selected` semantics; long-press entry unlabeled (`MediaCardArtwork.kt:69-85,447-484`); `Checkbox(onCheckedChange = null)` in `EpisodeRow.kt:279` contributes no semantics at all.

### Semantics & labeling
- **Quick Connect code** reads as N separate bare glyphs — no grouping, no "your code is…" (`LoginScreen.kt:593-620`). Merge with spaced-character description.
- **User picker**: selection is color-only ring; avatar-less users announce as their initial letter ("C" not "claude") because the name is outside the clickable's merge (`LoginScreen.kt:460-525`). Use `selectable(role = RadioButton)` over the whole column + `selectableGroup()`.
- **PillChip**: no `selected` semantics/role (filters invisible to TalkBack — library rail, filter sheet, 11 sites); `enabled=false` genre chips announce "disabled" for inert labels; 36dp target with plain `clickable` (`PillChip.kt:80-104`; `ItemDetailHeader.kt:481`).
- **Player picker chips** announce no current value ("Audio" — which track?) (`PlayerControls.kt:727-776,408-469`).
- **"Retry" that isn't**: player error's Retry performs `onBack` — mislabeled action, WCAG 2.5.3 (`PlayerScreen.kt:186-191`).
- **Downloaded row's** primary action (play) is unnamed/role-less (`DownloadRows.kt:100`).
- **Traversal order risk**: glass chrome (top nav, action cluster, bottom pill) are later siblings fully overlapping the NavHost; no `isTraversalGroup`/`traversalIndex` anywhere → chrome likely read after the whole page (`AppScaffold.kt:152-247`). Needs on-device TalkBack verification.
- **No `heading()` anywhere** — TalkBack heading-jump navigation is dead on every screen (`MediaRow.kt:84-91`, `ItemDetailScreen.kt:553-565`, etc.).
- **"See all" ×N identical labels** on home (`MediaRow.kt:95-104`); sort menu's active tick unlabeled (`LibrarySortMenu.kt:63-67`); expandable overview has no state/click label (`ItemDetailHeader.kt:606-628`); detail meta row reads as 4–5 disconnected fragments ("8.6", "2016", "TV-MA") (`ItemDetailHeader.kt:358-378`, also `HomeHero.kt:304-321`, `CastRail.kt:76-105`); clock and title in player are split stops (`PlayerControls.kt:206-230,529-551`); SyncPlay queue rows expose identical Move up/down/Remove labels (`SyncPlayQueueSheet.kt:289-306`); shuffle switch unnamed (`SyncPlayGroupSheet.kt:131-141`); settings info rows two stops (`SettingsRows.kt:167-191`); choice-group headings unassociated (`SettingsRows.kt:143-163`); storage-missing recovery affordance invisible non-visually (`SettingsScreen.kt:303-336`).
- **`JellyfinAsyncImage` drops the caller's `contentDescription` on the placeholder path** — items without artwork are unlabeled where no text follows (`JellyfinAsyncImage.kt:61-69`).
- **Password field**: no `semantics { password() }`, no autofill `ContentType` anywhere in the repo (`LoginScreen.kt:325-355`).
- 10 role-less `clickable` sites (list in cross-cutting A11Y-ROLE-01).

### Contrast (computed)
- `BACKDROP_SCRIM` Black@0.45 → 3.35:1; `DIM_ALPHA` 0.7 on Black@0.6 → 3.76:1 (`PlayerScreen.kt:335,551-561`).
- `ChromeFill` bg@0.45 → 3.05:1 for top-nav labels over bright artwork; `BottomNavFill` was deliberately set to 0.72 (7.70:1) with the math in a KDoc — top chrome never got the same fix (`GlassDefaults.kt:63-76`).
- `Fill` White@0.06 glass → **1.00:1** over bright artwork; no contrast floor of its own (`GlassDefaults.kt:31`).
- Disabled content at 0.35 alpha → 3.19–3.20:1 (`JellyfinButtons.kt:81-88`, `DownloadsScreen.kt:765,1275`); the text field's 0.5 (5.33:1) shows the right value.
- **All 15 border/hairline tokens fail 1.4.11 (3:1)**; the two that matter: `GhostBorder` @0.12 (1.38:1 — a ghost button's only edge) and `FieldActiveBorder` @0.22 (1.97:1 — the focus indicator). White needs α ≥ 0.375 on `#101010` for 3:1. `Outline #3C3C3C` (the M3 outline role) is 1.72:1 → raise to ≥ `#6E6E6E`.
- Passing (no action): base palette is strong — onSurface 16.3:1, onSurfaceVariant 8.6:1, primary 6.65:1, error 5.28:1, etc.

### Text scaling
- `PillFrame` uses `requiredHeight` — hard cap on every pill button; labels clip at 1.5–2.0× (`JellyfinButtons.kt:275-276`). **Documented as deliberate → needs `/diverge`.** Change to `defaultMinSize(minHeight=)`.
- Fixed `.height()` on text-bearing rows (all <4dp slack at 2.0×; GlassTopNav's 36dp capsule clips with font padding): `GlassTopNav.kt:94,193`, `PlayerControls.kt:761`, `HomeScreen.kt:357`, `DownloadsScreen.kt:1190`, `SelectionAppBar.kt:85`, `LoginScreen.kt:610-616` (Quick Connect boxes). `GlassBottomNav.kt:74` already does `heightIn(min=)` with the reasoning in a comment — propagate it.
- Home hero clips its copy and **actions** at large scale: fixed height + `clipToBounds` "backstop", and the shed/keep threshold is dp-only, ignoring `fontScale` (`HomeHero.kt:85,211-219,407`, `HomeScreen.kt:529-541`). Player's `showSheetButtonLabels` threshold likewise ignores `fontScale` (`PlayerControls.kt:491,900`); trickplay preview offset hardcodes an 18dp label height (`:614,903`).
- 40 × `maxLines = 1` with no scale-aware relaxation; card titles ellipsize after ~4-6 chars at 2.0× (mitigation today is the artwork description — which CR-6's fix removes, so the merged description must carry the full title).
- Artwork overlay badges (`topStartBadge`, `TimeChip`, rating) have `maxLines=1` with **no ellipsis** inside a clipped box — hard mid-glyph cuts at ≥1.5× (`MediaCardArtwork.kt:324-384`).
- Snackbar visually truncates at 2 lines (semantics keep the full string) (`PillSnackbar.kt:100-108`).

### Policy / system integration
- **Player force-locks landscape** unconditionally (`PlayerScreen.kt:500`) — WCAG 1.3.4; blocks fixed-mount users. Mechanics (save/restore, PiP suspension) are careful; the policy is the issue. **Needs `/diverge`** (make it a preference or `SCREEN_ORIENTATION_USER`).
- **No reduced-motion support**: 54 animation sites (22 in `AppScaffold.kt`), zero reads of `ANIMATOR_DURATION_SCALE`/`MotionDurationScale`. One `CompositionLocalProvider` in `JellyfinTheme` covers all of it. (No marquee/infinite animations exist — good.)
- Seek granularity: `steps=0` over `0f..1f` → one TalkBack/arrow adjust ≈ 6 min on a film; add −10s/+30s `customActions` or model in seconds (`PlayerControls.kt:627-641`). Slider also lacks name and time-based `stateDescription` (announces "34 percent").
- `PlayerView` AndroidView not semantics-cleared — Media3 internals may surface stray nodes (`PlayerScreen.kt:452-464`).
- Uppercased *strings* (not textTransform) reach TTS: "USERNAME"/"PASSWORD"/"TRANSCODING 1080P" (`LoginScreen.kt:321-421`, `PlayerControls.kt:280`).
- Cast button description is static "Cast to a device" even when casting (`CastRouteButton.kt:125`).

---

## What's already strong (keep and propagate)

- **Localization of a11y strings is exemplary**: 0 shipped hardcoded content descriptions, 40 `stringResource()` uses, ~90 locale folders, `generateLocaleConfig = true`. RTL fully clean (zero absolute alignments).
- **Text sizing**: all 73 `fontSize` declarations in `sp`; no fontScale/density overrides; `configChanges` correctly omits `fontScale`.
- **48dp touch-target architecture**: `Dimens.MinTouchTarget` + `PillFrame`/`GlassIconButton` invisible frames, documented; `GlassIconButton` makes `contentDescription` a required param (41/41 call sites pass real strings).
- **The seek bar kept a real M3 `Slider`** under custom track/thumb — TalkBack `setProgress` commits seeks, arrow keys work, 48dp touch height. The most commonly botched player element, done right.
- **Settings rows are textbook**: `toggleable`/`selectable` on the whole row, inert inner control, one merged node, `heightIn(min=48.dp)` — the exact pattern cards/chips should copy. Same for nav tabs (`Role.Tab` + selected, icon↔label description swap).
- **Captions inherit system `CaptioningManager` settings** via Media3 `PlayerView` (caveat: burned-in transcode subs bypass it).
- **`MediaSession`** gives an accessible out-of-band transport (media keys, headset, Switch Access) even with on-screen controls hidden.
- Manifest hygiene: no static orientation lock, `resizeableActivity`, PiP, `supportsRtl`.
- Contrast *was* reasoned in three places (`BottomNavFill`, `TAG_TEXT`, `ErrorBanner` error color) — the instinct exists; it wasn't propagated to `SCRIM`/`ChromeFill`/borders.
- Status conveyed textually, not color-only, throughout downloads; no swipe-only destructive actions; hidden player controls are truly composed out (no phantom focus).

---

## Suggested remediation order

**Wave 1 — one-line/token fixes (hours):**
`SCRIM` 0.35→0.62 · `ChromeFill` 0.45→0.72 · `BACKDROP_SCRIM`/`DIM_ALPHA` · disabled alphas 0.35→0.48 · `GhostBorder`→0.40, `FieldActiveBorder`→0.42, `Outline`→`#6E6E6E`, progress tracks→0.40 · six `.height(` → `.heightIn(min=` · `MotionDurationScale` provider in `JellyfinTheme` · fix "Retry"→"Close player".

**Wave 2 — design-system semantics (the big win, ~3 components fix 30+ findings):**
`JellyfinTextField` label/error semantics (CR-2) · card merge + authored descriptions (CR-6, incl. progress/type/selection state) · `PillChip` selectable + 48dp + non-interactive path · `LoadingState`/`ErrorBanner`/`PillSnackbar`-adjacent live regions · `JellyfinAsyncImage` placeholder description.

**Wave 3 — player (CR-1, CR-5 done in wave 1, CR-8):**
touch-exploration-aware auto-hide + semantic tap-to-reveal · brightness alternative · seek bar name/time stateDescription/customActions · buffering UI + announcement · keyboard handling on player root (start of CR-4).

**Wave 4 — screens & structure:**
auth (errors, Quick Connect, user picker, password semantics/autofill) · search announcements · downloads progress semantics · selection mode semantics · headings + traversal groups for chrome · hero fontScale-aware thresholds.

**Wave 5 — tooling (locks it all in):**
androidTest + `AccessibilityChecks` smoke tests · lint.xml with a11y errors + `lintDebug` in `/verify` · a Compose semantics test per fixed component.

**Governance:** two fixes contradict documented deliberate decisions and need `/diverge` first: `PillFrame.requiredHeight` (KDoc says design-fixed height) and the unconditional landscape lock in `ImmersiveLandscapeEffect`.

**Device verification (test tablet):** TalkBack walk of home → detail → player (controls reveal, seek, track pick) and the chrome traversal order (F9/CR-1 need on-device confirmation); contrast spot-check of player controls over a bright frame; font scale 2.0 pass over hero, top nav, pills, Quick Connect.
