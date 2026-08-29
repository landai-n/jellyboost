# Settings (M9; restructured into a hub M14)

Everything the user can decide, plus the account they decided it for. It replaces the two temporary
controls that lived in the home top bar's overflow menu since M1 and M6 — the overflow keeps
*Offline mode* as a quick toggle and now opens this screen instead of signing out.

Module: `:feature:settings`. It reads and writes `:core:datastore` directly, ends the session
through `:core:network`, and asks `:data:downloads` for the storage figures and the deletes.

Since M14 it is a **category hub plus pushed pages**, not one flat scroll — see
[The hub](#the-hub) below and the DECISIONS entry of 2026-08-29.

---

## What it does

| Category | Rows |
|---|---|
| Account | The signed-in user name and the server name, and *Sign out* behind a confirm dialog with an optional *Also delete downloads*. Reached from the hub's identity row. |
| Playback | *Skip intro* and *Skip outro* — three-way pickers over `SegmentSkipMode` (`OFF` / `SHOW_BUTTON` / `AUTO_SKIP`); *Picture-in-picture* — enter a floating window when leaving the app mid-playback; *Styled ASS subtitles* — libass rendering for ASS/SSA, experimental and default-off, read once per player build. See [`playback.md`](playback.md). |
| Downloads | A storage line reporting used/free bytes and where the files live; *Storage location* — one option per mounted volume (internal storage, SD card), shown only on a device that has more than one; *Wi-Fi only* — pause transfers on metered networks; *Download quality*. |
| Appearance | *Theme* — a three-way picker over `ThemeMode` (`SYSTEM` / `LIGHT` / `DARK`); *Use wallpaper colours* — Material You, **absent below API 31** rather than disabled, because there is no wallpaper palette to derive from there. See [`theme.md`](theme.md). |
| Network | *Offline mode* — the same force-offline preference the home overflow toggles and the offline banner reports. |
| About | The app version; *Source code*, which opens the repository in a browser; *Licence*, which pushes the app's own GPL-3.0 text; *Third-party licences*, which pushes the generated list of bundled dependencies. |

Every row writes a DataStore key and reads the same key back. Nothing is cached in the screen, so a
preference changed somewhere else — the Downloads tab's own Wi-Fi switch, the home overflow's
offline toggle — is already correct here without any cross-screen wiring.

---

## The hub

The hub is an identity row plus one row per category. **Each category row's summary is that
category's current state, not a list of its contents** — "Skip automatically · Picture-in-picture
on", "Wi-Fi only on · 12.3 GB on this device", "Follow the device · Use wallpaper colours on",
"Offline mode off", "Jellyboost 0.1.0 · GPL-3.0". A table of contents would tell the reader nothing
the category name did not; the state is what pays for the extra tap.

Derivation is pure and lives in `SettingsSummaries.kt`. It returns a `List<SummaryPart>` of resource
**ids**, never text — a summary derived into a `String` carries whatever language was current when
the state was built, and `MissingTranslation` cannot see a Kotlin literal (the `UiText` lesson, audit
H8). `summaryText()` resolves the parts at draw time and joins them with " · ".

Two details worth keeping:

- **A switch part always carries its state**, both ways — "Wi-Fi only **on**", "Offline mode
  **off**". A bare label reads as "this is on" whichever way the switch is set, which is the one
  thing a state summary must not do. The frames are format strings (`%1$s on` / `%1$s off`) rather
  than concatenation so a language can put the state word first.
- **The Appearance summary hides the wallpaper-colour part below API 31**, on the *same*
  `dynamicColorAvailable` fact the page tests before drawing the row. Threaded through
  `hubSummaries(…)` rather than read in two places, so the summary cannot name a preference the page
  refuses to show.

**Growth rule** (from the canvas): a category splits at roughly seven rows; Subtitles leaves Playback
first.

---

## Structure

```
JellyfinNavHost
  composable<Routes.Settings>          → SettingsScreen(onBack, onOpenCategory, onOpenAccount, …)
  composable<Routes.SettingsAccount>   → SettingsAccountScreen
  composable<Routes.SettingsPlayback>  ┐
  composable<Routes.SettingsDownloads> │
  composable<Routes.SettingsAppearance>├→ SettingsCategoryScreen(category = …)
  composable<Routes.SettingsNetwork>   │
  composable<Routes.SettingsAbout>     ┘
                                     │
                                     ▼
                            SettingsViewModel  ── AppPreferences        (9 keys, read + write)
                                     │         ── SessionRepository     (sessionState, signOut)
                                     │         └─ DownloadRepository    (observeStorage, observeStorageLocations,
                                     │                                   setStorageLocation, observeDownloads, delete)
                                     ▼
                            StateFlow<SettingsUiState>
                                     │
                                     ▼
                            SettingsContent  (stateless; previewable)
                               │
                    <840dp ────┴──── ≥840dp
                       │                │
              SettingsHubPanels    SettingsHubRail  │  SettingsPaneBody
                (pushes routes)    (sets openPane)  │  → SettingsCategoryBody(pane)
                                                    │
              SettingsPageChrome ──→ SettingsCategoryBody(pane)   ← the *same* body, one copy
                                       ├── AccountPage → SignOutDialog
                                       ├── PlaybackPage
                                       ├── DownloadsPage → SwitchStorageDialog
                                       ├── AppearancePage
                                       ├── NetworkPage
                                       └── AboutPage ──→ Routes.Licence            → LicenceScreen
                                                     └─ Routes.ThirdPartyLicences → ThirdPartyLicencesScreen
```

`SettingsCategoryBody` is the only copy of every page. The compact path wraps it in
`SettingsPageChrome` (a Back-only header and a scroll); the wide path draws it in a pane. That is
what stops the two shapes drifting — the shell is the only thing they disagree about, which is the
audit's "fix the sibling too" made structural rather than remembered.

| File | Responsibility |
|---|---|
| `SettingsViewModel.kt` | The projection and the nine preference setters (plus `setStorageLocation`, which writes through `DownloadRepository`); owns the sign-out ordering. Unchanged by the restructure. |
| `SettingsUiState.kt` | `SettingsUiState` and `AccountInfo`. |
| `SettingsScreen.kt` | The Settings destination: the compact hub, the ≥840dp two-pane shell, `SettingsActions`, the previews. |
| `SettingsHub.kt` | The hub's two shapes — `SettingsHubPanels` (compact, panelled, nothing selected) and `SettingsHubRail` (wide, loose rows, the open one has a surface under it). |
| `SettingsCategory.kt` | The category enum and its icons, `SettingsPane` (the categories plus Account), the 840dp predicate, `dynamicColorAvailable`. |
| `SettingsSummaries.kt` | `SummaryPart`, the pure per-category derivations, `hubSummaries`, and `summaryText()`. |
| `SettingsCategoryScreens.kt` | Every category page body, the pushed-screen chrome, the sign-out and storage dialogs, the source-code intent. |
| `SettingsRows.kt` | The row vocabulary — eyebrow section, panel, separator, switch row, choice group/row, info row, action row, category row, identity row. |
| `LicenceViewModel.kt` | Reads `res/raw/gpl_3_0.txt` off the IO dispatcher and reflows it into paragraphs (`licenceBlocks`). |
| `LicenceScreen.kt` | The app's own licence: a translated sentence, then the untranslated document. |
| `ThirdPartyLicencesScreen.kt` | AboutLibraries' `LibrariesContainer` over the raw resource `:app` hands it. |

The state combines nine sources. `combine` tops out at five typed flows, so the six preference
keys fold into one private `Preferences` value first and the outer `combine` takes that plus the
storage usage, the storage locations and the session. The alternative — the `Array<Any?>` overload — would mean casting
each element back by index, which is exactly the kind of thing that survives a refactor and fails at
runtime.

`stateIn(WhileSubscribed(5 s))` rather than an eagerly-collected `MutableStateFlow`: rotating the
tablet should not re-read DataStore and re-`statfs` the download directory for values that have not
changed.

---

## Rows and touch targets

There is no shared settings-row component in `:core:ui` — Settings is the only screen with rows of
this shape, and a component generalised from one example is a guess. The rows live in
`:feature:settings` and move to `:core:ui` if a second screen ever needs them.

There are **six row types and nothing else** (`design/components/settings-rows.html`): category,
switch, choice group, info, action, danger. A new setting picks one of them; if it fits none, that is
the signal to talk about it rather than to invent a seventh. The category row is hub-only — inside a
category, rows carry **no leading icon**, because an icon there only narrows the label column.

Two naming and heading rules ride with them:

- **An eyebrow only earns its place on a page carrying more than one section.** On a single-section
  page the screen title already does that work, and an eyebrow reading "THEME" over a group
  captioned "Theme" says it twice. Playback (2 sections) and Downloads (3) draw eyebrows; Appearance,
  Network, Account and About are one panel each and draw none. A choice group's own caption is not
  an eyebrow and always stays — it is the group's a11y anchor, repeated into every row's
  `contentDescription`.
- **A category is called the same thing on its hub row and on its own page.** No long-form alias
  waiting inside. `SettingsPane.titleRes()` agrees with `SettingsCategory.titleRes` for every
  category, and `aCategoryHasOneNameNotTwo` keeps it that way.

### The storage meter

The storage info row draws a 6dp meter under its figures, and it carries **no**
`progressBarRangeInfo` — it is a picture of the sentence the merged node already speaks, not a datum
of its own. `MediaCardArtwork.InsetProgressBar` is the same call.

This is deliberately the opposite of `:feature:downloads`' `UsageBar`, which *does* declare an
explicit range: that one is drawn loose, three to a wide layout, with no text beside it naming the
volume it measures, so the range is the only thing that makes it speak at all. Height, radius and
track ink are identical between the two — two meters of the same quantity in one app should not be
two different objects — and each KDoc names the other so the pair is not "unified" later.

`storageUsedFraction` puts the whole volume in the denominator (used + free), so the bar answers
"how much room is left on this device". A volume that reports nothing draws an **empty** track: an
unknown size is not a size of zero, and a full bar would read as a device out of room.

The invariant all of them uphold: **the whole row is the touch target and carries the semantics**,
and the control inside it is inert.

- Switch rows: `Modifier.toggleable(value, onValueChange, role = Role.Switch)` on the `Row`, and
  `Switch(checked, onCheckedChange = null)` as the trailing control.
- Choice rows: `Modifier.selectable(selected, onClick, role = Role.RadioButton)` on the `Row`,
  `RadioButton(selected, onClick = null)` trailing, and `Modifier.selectableGroup()` on the
  container so TalkBack announces "2 of 3" rather than three unrelated radio buttons.
- Minimum height 48 dp on every row, text needing it or not.

A 48 dp strip that only responds on the last 52 dp of its width is the most common accessibility
defect in settings UIs, and putting the click on the container is what prevents it.

The home overflow's *Offline mode* entry got the same treatment, with one wrinkle: `DropdownMenuItem`
already dispatches its `onClick` across the whole row, so adding a second handler would toggle
twice. Its switch role and on/off state are declared on the trailing `Switch` node instead of on the
item's `modifier` — `clickable` merges the semantics of its *descendants*, so an ancestor
`Modifier.semantics {}` would sit on a node TalkBack never focuses.

### Width

`SettingsContentMaxWidth = 640.dp`, applied with `Modifier.widthIn(max = …)` to the centred column —
the same device concern behind `:feature:auth`'s `AuthContentMaxWidth = 460.dp`, and a bit wider
because a settings list is a list, not a form. Unconstrained on the 2560 × 1600 test tablet, a label
sits at one edge and its switch at the other: unreadable, and unreachable one-handed.

---

## Two panes at ≥840dp

`isTwoPaneSettings(maxWidth)` — the same `840.dp` cutoff `ServerSetupScreen`'s `AuthTwoPaneMinWidth`
splits at, so the app has *one* width at which a screen becomes two panes. The hub becomes a 340dp
rail and the category opens beside it, so nothing on a tablet is a level deeper than it is on a
phone. Below 840dp the phone push flow is used verbatim.

**Width only**, unlike `isWideHome`, which also demands 560dp of height: Home's wide hero needs the
height for a 104dp copy inset, whereas a rail beside a scrolling pane is legible in any 840dp window.
A landscape condition would drop an 840dp *portrait* tablet back to the push flow for no reason a
user could see. `SettingsSizingTest` pins the cutoff and both test-tablet orientations — it splits in
landscape (1138dp) and pushes in portrait (711dp).

**The open category is saveable state on the Settings destination, not a route.** At ≥840dp the
per-category routes are never navigated to; the compact path is the only thing that pushes them. The
state is hoisted **above** the width branch: parked inside the two-pane arm it would be remembered by
a host that rotating into portrait destroys, and the tablet would come back to Playback every time it
was turned.

**The rail carries the only Back on the screen and the pane carries none.** A second Back beside the
pane title would look like a way out of the pane, which is not a place you can be. The rail's
selected row gets a surface under it and nothing else: it stays a `Role.Button`, because activating
it is still what opens the category, and a `selectable` here would promise a radio group the compact
shape does not have — the two shapes must not read differently to a screen reader.

---

## Navigation chrome

Settings is the **first surface to adopt the rule** in
`design/foundations/navigation-chrome.html`: Back is the only leading control, and Home never appears
in a header. Two adjacent glass circles look like the same affordance and do different things, and
Home is already reachable from the nav bar.

`ScreenHeader.onHome` is therefore `(() -> Unit)? = null` and the Home button is drawn only when a
caller passes one. Every existing caller — `LicenceScreen`, `ThirdPartyLicencesScreen`,
`LibraryGridScreen`, `SyncPlayGroupsScreen` — passes one and is unchanged. Rolling the rule out to
Library, Detail and Player is deliberately a separate wave: Detail can be entered by deep link with
no back stack and carries Home at the *trailing* edge, which is its own argument to have.

---

## Sign out

The dialog offers *Also delete downloads*, unchecked by default. Confirming calls
`SettingsViewModel.signOut(deleteDownloads)`, which:

1. if asked to delete, snapshots `downloads.observeDownloads().first()` and calls
   `downloads.delete(itemId)` for each item, logging and stepping over individual failures;
2. calls `sessionRepository.signOut()`.

**The order is the point.** Signing out clears `SecureCredentialStore`, and files deleted after that
would leave rows nobody can re-download without logging back in. `SettingsViewModelTest` pins it
with `coVerifyOrder`.

Deletion is best-effort in the other direction too: a file the OS will not let go of is not a reason
to keep somebody signed in on a shared tablet, so a failed delete is logged and the sign-out
proceeds. There is no bulk-delete API on `DownloadRepository` and this does not add one — the
per-item call already runs the full delete cascade (files, rows, orphaned parents, user-data prune).

A snapshot rather than a subscription, because collecting a live `Flow` while deleting the rows it
reports is a loop waiting to happen.

### A sign-out the screen cannot lose

Both steps run in the **application** scope, not `viewModelScope`. `SessionRepository.signOut()`
tells the server the session ended, and against an *unreachable* server that request sat on OkHttp's
timeouts for 6–30 s with nothing on screen to say so. A user who backed out of Settings during that
silent wait cleared this ViewModel, which cancelled the coroutine somewhere between the deletes and
the credential wipe — and left them quietly signed in against a dead session. The state a sign-out
leaves behind is not this screen's to abandon, so the screen only *starts* the work.

`SessionRepository.signOut()` is immune to its caller for the same reason: it runs its body as a job
in the application scope and merely joins it, so a caller that goes away abandons the join while the
teardown runs to completion. The goodbye itself — every `SignOutHook` plus `reportSessionEnded` —
shares one `SERVER_GOODBYE_TIMEOUT` (5 s) budget, after which the local teardown proceeds regardless
and the expiry is logged. A cut-short SyncPlay group leave is caught by
`SyncPlayController.watchSignOut`, which acts on the `LoggedOut` transition (audit NET-03/SP-10).

While the sign-out is in flight `SettingsUiState.signingOut` is true: the button holds an inline
spinner and stops taking taps, and the confirm dialog will not reopen. It is never lowered — the
session flipping to `LoggedOut` navigates the user off this screen, so the only thing lowering it
could do is flash an enabled button on the way out.

Nothing here navigates. `signOut()` flips `sessionState` to `LoggedOut` and `:app`'s
`LogoutRedirectEffect` sends the user to `Routes.ServerSetup` with the back stack cleared — which is
what makes this button and a future 401-driven logout land in the same place.

---

## Entry point

App bar → ⋮ overflow → **Settings** (`Icons.Filled.Settings`), which navigates to `Routes.Settings`.
The plan asks for Settings "behind top-bar avatar"; there is no user-avatar image or asset pipeline
anywhere in this app, so the established overflow menu carries it instead — logged in DECISIONS.md,
2026-07-29, *"M9: Settings is opened from the home overflow menu, not a top-bar avatar"*. That menu
moved from the home screen's own top bar into the combined `AppTopBar` later the same day, so it is
now reachable from all four top-level destinations rather than from Home alone.

`Routes.Settings` is a pushed destination, not a tab, so the screen owns a `ScreenHeader` with a back
action the way `LibraryGridScreen` and `ItemDetailScreen` do, and the app bar hides while it is up.
The six category destinations (`Routes.SettingsAccount`, `SettingsPlayback`, `SettingsDownloads`,
`SettingsAppearance`, `SettingsNetwork`, `SettingsAbout`) are pushed the same way. They are separate
argument-less `data object`s rather than one route carrying the category as an argument: each is
independently deep-linkable, and a route with no arguments cannot be navigated to with a category
that does not exist.

Sign-out moving here made `:app`'s whole `onSignOut` chain dead — `MainActivity` →
`JellyboostApp` → `AppScaffold` → `JellyfinNavHost` → `HomeRoute`, plus `MainViewModel.signOut()`
— and it was removed with the feature rather than left threaded through five composables that
nothing calls. `MainViewModel` still injects `SessionRepository` for `restoreSession()` and
`sessionState`.

---

## Storage location

The picker the plan asks for, added in M9 polish (docs/POLISH.md, "New run"). It lists the volumes
`getExternalFilesDirs(null)` reports — internal storage, plus the SD card when one is in — and hides
itself on a device with only one. Each row is named by the platform (`StorageVolume.getDescription`,
already localised) with *Internal storage* / *SD card* as our fallback, and carries the volume's
free space as supporting text.

Two behaviours are worth knowing:

- **Switching deletes.** Nothing moves downloaded files between volumes yet (`MoveStorageWorker` is
  deferred), so picking a different volume while downloads exist opens a confirmation — *Delete and
  switch* — and picking one with an empty device is immediate. The rule is enforced in
  `DownloadRepository.setStorageLocation`, not in the dialog: the screen asking nicely is a
  courtesy, the repository refusing is the guarantee.
- **A removed card is announced, not hidden.** If the chosen volume is not mounted the pipeline
  falls back to internal storage, and the section says so in `colorScheme.error` above the group.

Everything behind it — the token stored in DataStore, the fallback rule, why the pipeline still runs
on `java.io.File` — is in docs/features/downloads.md, "Storage". Choosing an *arbitrary* folder still
waits on SAF (DECISIONS.md, 2026-07-29).

---

## About, and what distributing the binary requires

The app is GPL-3.0. Shipping the compiled thing means conveying the licence with it (§4) and
offering the corresponding source (§6), and the bundle also carries a few hundred Apache-2.0 AndroidX
artifacts, each of which wants its notice. Three rows under *App version* carry all of it.

**Source code.** An `Intent.ACTION_VIEW` on `https://github.com/landai-n/jellyboost`, with the URL
itself as the row's supporting line — the URL is the offer, so it stays on screen rather than hiding
behind a label. A device with no browser at all throws `ActivityNotFoundException`; that is a
permanent failure, not a transient one, so it is reported once as a toast instead of retried.

**Licence.** The repository's `LICENSE` is bundled verbatim as `res/raw/gpl_3_0.txt` — a raw
resource, not a string: it is the document being conveyed, and putting it in `strings.xml` would
oblige `validate_i18n.py` to demand 69 translations of a legal text nobody should translate. Above it
sits one translated sentence saying what the licence grants, plus the licence's own name, which is
`translatable="false"` for the same reason.

The file is hard-wrapped at ~70 columns, which on a phone breaks every line twice, so `licenceBlocks`
rejoins each paragraph and leaves headings — lines indented eight columns or more in the source —
standing alone. `LicenceBlocksTest` pins that the reflow loses no word, and `LicenceViewModelTest`
pins that the bundled copy is byte-identical to `LICENSE`.

**Third-party licences.** `com.mikepenz:aboutlibraries`. Its Gradle plugin is applied to `:app`,
because `:app`'s resolved dependency graph is the one that ships, and emits `R.raw.aboutlibraries` —
223 artifacts with the full text of six licences, read from the real graph at build time, so the list
cannot drift from the bundle. `:feature:settings` draws it with `LibrariesContainer` but never names
`:app`'s `R`: the resource id arrives as a parameter, the same seam `appVersion` already uses.

Its colours are passed explicitly rather than defaulted. `LibraryDefaults.libraryColors()` derives
the dialog background, the version chip and the content colour from `libraryBackgroundColor`, so the
`Color.Transparent` that lets the app's own background show through would otherwise make the dialog
and the chip invisible.

---

## Not here

**An arbitrary storage folder.** The picker above chooses between app-specific volume directories,
which need no permission and no persisted URI grant. Pointing downloads at any folder the user
browses to needs SAF and a `DocumentFile` backend behind the `DownloadStorage` seam, which is still
deferred.

**The server address.** The Account section shows the user name and the server *name* only.
`SessionState.LoggedIn` carries no address and `:core:network` exposes no accessor for one; adding
API surface there for a line of text was out of scope for this screen. If the address is wanted
later, it is a `SessionRepository` change first.

**Playback speed and default quality.** Speed is deliberately session-scoped and never persisted
(DECISIONS.md, 2026-07-29); no key exists for it, so no row does either.

**Switch account and Manage servers.** `design/screens/settings-account.html` draws them on the
Account page, but they belong to M14 track 1, which is hard-gated on the server-scoping backend
workstream in `docs/notes/m14-multiserver-design-brief.md`. The Account page holds exactly what the
old `AccountSection` held.

**Player gestures.** `design/screens/settings-playback.html` draws a dimmed *Gestures* group to show
where M14 track 5 lands. It is placement, not a spec, and no row exists for it.

---

## Tests

| Suite | Covers |
|---|---|
| `SettingsViewModelTest` | each of the nine preferences read back from the store and written through by its setter; a preference changed upstream reaching an open screen; the storage volumes reaching the state and a card removed while the screen is open; a switch passing the user's agreement through, and a refused switch leaving the state alone; `LoggedIn` vs `LoggedOut` account info; delete-then-sign-out ordering (`coVerifyOrder`); no deletes when the box is unchecked; a failed delete still signing out; a screen popped mid-sign-out (`viewModelScope` cancelled) still deleting and signing out; the busy flag going up on the first ask and staying up; the theme mode and the dynamic-colour flag reaching the state, writing through, and a mode changed upstream reaching an open screen; the styled-ASS switch writing through and projecting default-off |
| `SessionRepositoryTest` | (`:core:network`) a caller cancelled mid-goodbye still clearing the credentials and reaching `LoggedOut`; a server that never answers costing the sign-out `SERVER_GOODBYE_TIMEOUT` rather than the session; a hook that hangs being cut short the same way |
| `LicenceViewModelTest` | the bundled text reaching the screen as paragraphs; an unreadable raw resource leaving the screen empty rather than taking the process down; the packaged copy being byte-identical to the repository's `LICENSE` |
| `LicenceBlocksTest` | headings standing alone, hard-wrapped prose joining, blank lines separating, and the whole document surviving the reflow word for word |
| `SettingsSummariesTest` | every hub summary's exact parts; both positions of every switch; the API-31 case where the wallpaper row is absent, so the summary must not name it either; that every category and every pane has a title of its own and that a category's hub row and page agree on it; that the panes are the categories plus Account; the storage meter's arithmetic — empty, full, 12.3 of 53.3 GB, an unknown volume, and clamping |
| `SettingsSizingTest` | the 840dp two-pane cutoff, the values either side of it, both test-tablet orientations, and that the rail still leaves the pane the larger share of the window |

Instrumented (`connectedDebugAndroidTest`, milestone DoD — not part of `/verify`):

| Suite | Covers |
|---|---|
| `SettingsHubA11yTest` | ATF sweep of the hub at the default scale and at fontScale 2.0; every category row being one `Role.Button` node that speaks its *summary* as well as its title; the identity row naming the user and the server in one stop; 48dp targets; exactly one Back and no Home |
| `SettingsCategoryPagesA11yTest` | ATF sweep of every page at both scales; the switch row carrying `Role.Switch` and the toggle state with an inert `Switch` inside it; both skip groups repeating their own name so their identical options can be told apart; the choice row being selectable at row level; the storage info row being one stop and not clickable; **the storage meter adding no progress node**; the volume picker repeating its group on every row; **zero eyebrows on a single-section page and one per section on a multi-section one**; an eyebrow spoken sentence-case; the theme group keeping its caption with no eyebrow above it; Back-and-no-Home on every page header |
| `SettingsTwoPaneA11yTest` | ATF sweep of the ≥840dp shape at both scales; exactly one Back across rail *and* pane, and no Home; the pane opening on Playback and the rail swapping it without the rail going away; the identity row opening the Account pane rather than pushing a screen |

`:feature:settings` left `scripts/a11y-scaffolding-allowlist.json` with these three suites — the
instrumented coverage it had owed since the 2026-08-05 accessibility audit.

`SettingsContent` is stateless and takes a `SettingsUiState` plus a `SettingsActions` bundle, so the
`@Preview`s render both shapes — the hub and the 1138 × 711 two-pane — without a ViewModel or a Hilt
graph.
