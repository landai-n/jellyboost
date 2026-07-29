# Settings (M9)

One screen for everything the user can decide, plus the account they decided it for. It replaces
the two temporary controls that lived in the home top bar's overflow menu since M1 and M6 — the
overflow keeps *Offline mode* as a quick toggle and now opens this screen instead of signing out.

Module: `:feature:settings`. It reads and writes `:core:datastore` directly, ends the session
through `:core:network`, and asks `:data:downloads` for the storage figures and the deletes.

---

## What it does

| Section | Rows |
|---|---|
| Playback | *Skip intro* and *Skip outro* — three-way pickers over `SegmentSkipMode` (`OFF` / `SHOW_BUTTON` / `AUTO_SKIP`); *Picture-in-picture* — enter a floating window when leaving the app mid-playback. |
| Downloads | *Wi-Fi only* — pause transfers on metered networks; *Download quality*; a storage line reporting used/free bytes and where the files live; *Storage location* — one option per mounted volume (internal storage, SD card), shown only on a device that has more than one. |
| Connectivity | *Offline mode* — the same force-offline preference the home overflow toggles and the offline banner reports. |
| Account | The signed-in user name and the server name, and *Sign out* behind a confirm dialog with an optional *Also delete downloads*. |

Every row writes a DataStore key and reads the same key back. Nothing is cached in the screen, so a
preference changed somewhere else — the Downloads tab's own Wi-Fi switch, the home overflow's
offline toggle — is already correct here without any cross-screen wiring.

---

## Structure

```
JellyfinNavHost
  composable<Routes.Settings> → SettingsScreen(viewModel = hiltViewModel(),
                                               onBack = popBackStack, onHome = navigateHome)
                                     │
                                     ▼
                            SettingsViewModel  ── AppPreferences        (6 keys, read + write)
                                     │         ── SessionRepository     (sessionState, signOut)
                                     │         └─ DownloadRepository    (observeStorage, observeStorageLocations,
                                     │                                   setStorageLocation, observeDownloads, delete)
                                     ▼
                            StateFlow<SettingsUiState>
                                     │
                                     ▼
                            SettingsContent  (stateless; previewable)
                               ├── PlaybackSection
                               ├── DownloadsSection
                               ├── ConnectivitySection
                               └── AccountSection → SignOutDialog
```

| File | Responsibility |
|---|---|
| `SettingsViewModel.kt` | The projection and the five setters; owns the sign-out ordering. |
| `SettingsUiState.kt` | `SettingsUiState` and `AccountInfo`. |
| `SettingsScreen.kt` | `Scaffold` + `TopAppBar`, the four sections, the sign-out dialog, the preview. |
| `SettingsRows.kt` | The row vocabulary — section, switch row, choice group/row, info row — plus `formatBytes`. |

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

`Routes.Settings` is a pushed destination, not a tab, so the screen owns a `TopAppBar` with a back
action the way `LibraryGridScreen` and `ItemDetailScreen` do, and the app bar hides while it is up.

Sign-out moving here made `:app`'s whole `onSignOut` chain dead — `MainActivity` →
`JellyfinNativeApp` → `AppScaffold` → `JellyfinNavHost` → `HomeRoute`, plus `MainViewModel.signOut()`
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

---

## Tests

| Suite | Covers |
|---|---|
| `SettingsViewModelTest` | each of the six preferences read back from the store and written through by its setter; a preference changed upstream reaching an open screen; the storage volumes reaching the state and a card removed while the screen is open; a switch passing the user's agreement through, and a refused switch leaving the state alone; `LoggedIn` vs `LoggedOut` account info; delete-then-sign-out ordering (`coVerifyOrder`); no deletes when the box is unchecked; a failed delete still signing out |

`SettingsContent` is stateless and takes a `SettingsUiState` plus a `SettingsActions` bundle, so the
`@Preview` renders the full screen — every section, a live storage figure, a signed-in account —
without a ViewModel or a Hilt graph.
