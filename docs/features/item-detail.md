# Feature: Item detail — M4

One screen serves the three shapes the plan lists: **Movie**, **Series** and **Season**
(docs/PLAN.md, "Screens" → ItemDetail). Which rows appear follows from the loaded item's type, so
there is no separate mode flag — a movie simply has no seasons, a season no similar items.

## What loads, per type

| Type | Calls after `getItem` |
|---|---|
| Movie | `getSimilarItems` |
| Series | `getSeasons`, `getNextUpForSeries`, `getSimilarItems` |
| Season | `getEpisodes(seriesId, seasonId)` |
| Episode | `getSimilarItems` |

The item loads first, then every related row is fetched **concurrently** in a `coroutineScope`, so
a series page is bound by its slowest request rather than by the sum of three.

**Failure policy** matches the home screen: only the item itself failing produces an error screen.
A related row that fails is simply absent — the page still renders.

## Detail is a full re-fetch

The screen never reuses the lean item a list handed it. `getItem` calls
`userLibraryApi.getItem(itemId)`, the one endpoint that always serialises the complete field set —
overview, taglines, genres, people, media sources, streams, chapters, trickplay. That is the
"lists are lean, detail is full" half of the Swiftfin pattern the plan adopts; it also pre-loads
everything M5's playback resolver will need.

`getEpisodes` takes both `seriesId` and `seasonId`: the server's episode endpoint is rooted at the
series (`/Shows/{seriesId}/Episodes`) and treats the season as a filter. A season item always
carries its `seriesId`. It is the one list request that pays for `OVERVIEW`, because each episode
row draws a synopsis.

## Layout

- **Backdrop hero** (`BackdropHeader`) with the item's backdrop → thumb → primary artwork.
- **Header block**: poster, title, subtitle (`S1:E4 · Title` for an episode, series name for a
  season), metadata line (`2016 · 116 min · 552.4 MB · PG-13 · 8.4 · 4 seasons · 32 min left`),
  resume progress bar, action buttons, tagline, overview, credit line, genre chips. The size fact
  only appears for items with a media source of their own (movies, episodes) — series and seasons
  omit it. Once a local copy is what the user actually has (`downloadState is Downloaded`), the
  size fact switches to the on-device footprint — `"620 MB on device"`, from
  `DownloadRepository.observeBytesOnDisk` (a `SUM(bytesDownloaded)` projection over the item's
  `download_files` rows, the same figure the Downloads tab shows) — since a transcoded download
  can weigh half the server file. A fully-downloaded container aggregates to `Downloaded` but has
  no download row of its own, so its SUM is `null` and the server figure stays.
- **Rows**: *Next up* → *Seasons* → *Episodes* → *More like this*.

On a viewport wider than 720.dp **and at least 480.dp tall** the poster moves beside the text
instead of above it — the same rearrangement jellyfin-web makes on a desktop, and the layout the
project's tablet test device gets in landscape. The height guard exists because a phone in
landscape (~800×360dp) clears the width test while being far too short for a side-by-side header:
it gets the stacked layout, and its banner is drawn at half the viewport height instead of the
fixed 220/320dp (which filled ~90% of the screen — 2026-07-31 phone-size sweep, DECISIONS entry).
Long-form text stops growing at 680.dp; a full-width paragraph on a tablet is unreadable.

Below 480.dp of viewport width, episode rows shrink their thumb from 160dp to 128dp so the
title/overview column keeps enough room to read (`compact` is measured once at the screen level
and threaded down — no per-row subcomposition).

Episode rows reuse `ThumbCard` for their artwork so the watched tick, resume bar and download
badge are byte-identical to the ones the home rows show.

## Actions

| Button | Behaviour |
|---|---|
| Play / Resume | Live from M5 — navigates to `Routes.Player`; see docs/features/playback.md. **In a SyncPlay group it plays for the group instead**: `ItemDetailViewModel.onPlay` sends `SetNewQueue` and does not navigate, and the button reads "Play/Resume for &lt;group&gt;" (docs/features/syncplay.md, DECISIONS.md 2026-07-31). Every play entry point on the page — the header button and each episode row's play button — goes through it |
| Download | Snackbar: "Downloads arrive in M7." |
| Mark watched | Live — `UserDataRepository.setPlayed` |
| Favourite | Live — `UserDataRepository.setFavorite` |

Both live toggles are local-first and reflect **optimistically via `UserDataEventBus`**, which the
ViewModel collects: the button flips from the local Room write, not from a server round-trip, and
the same event patches the home rows behind the screen. See `docs/features/user-data.md`.

The two not-yet-built buttons are drawn and enabled and say what is actually true rather than
being disabled (which reads as "broken") or silently doing nothing — logged in `DECISIONS.md`.

### Batch selection over the episode list

Long-press an episode row to select it, then *Mark watched* / *Mark unwatched* / *Download* the
whole set — see [`docs/features/batch-selection.md`](batch-selection.md). Detail-screen specifics:

- selection is scoped to the **episode list** and to nothing else on the page; the seasons row,
  *Next up* and *More like this* are navigation surfaces, not lists of peers;
- this screen has no top bar, so the overlaid Back + Home pair *is* its bar and the contextual bar
  takes that place while the mode is on (Home deliberately absent for the duration; `BackHandler`
  intercepts Back only while selecting);
- **Select all** is offered here — unlike the library grid — because an episode list is fetched
  whole, so "all" is a set the user can see and count;
- a background refresh (a connectivity edge) **keeps** the selection, minus any episode the server
  no longer returns;
- a selected row shows a `secondaryContainer` wash and its Play button becomes a checkbox.

## Navigation

Route: `Routes.ItemDetail(itemId: String)` in `:core:common` (already declared at M0).

```kotlin
ItemDetailScreen(
    viewModel = hiltViewModel(),          // ItemDetailViewModel, @HiltViewModel
    onItemClick = { navController.navigate(Routes.ItemDetail(it.id)) },
    onBack = navController::popBackStack,
    onHome = navController::navigateHome,
)
```

`ItemDetailViewModel` reads its argument from `SavedStateHandle` under the key
`ItemDetailViewModel.ARG_ITEM_ID` (`"itemId"`) — the property name Navigation stores a type-safe
route's arguments under.

Tapping a season, an episode or a related item pushes another `ItemDetail` for it, so the same
screen handles the whole series → season → episode drill-down.

### Getting out of the chain

Because every hop pushes another `ItemDetail` and a pushed destination shows no app bar, that
drill-down is the one place in the app that can get ten entries deep with Back as the only exit.
The overlaid controls at the top-start corner are therefore a **Row of two**: Back (pops one) and
Home (leaves the whole chain). Both sit inside a single `windowInsetsPadding(WindowInsets.statusBars)`
so the backdrop still draws edge-to-edge underneath them.

Home calls `AppScaffold.navigateHome` — `navigate(Routes.Home, homeNavOptions())`. A
`popBackStack(Routes.Home, inclusive = false)` would read more directly but fails silently (returns
`false`, moves nothing) if Home is ever absent from the stack; a `navigate` still lands the user on
Home. `:feature:library`'s grid and `:feature:settings` carry the same pair in their `TopAppBar`'s
`navigationIcon` slot; the player is excluded, since leaving playback belongs to its own chrome.

`homeNavOptions()` is **not** `topLevelNavOptions()`, which is what the button shipped with and what
made it look dead. Those options carry `saveState`/`restoreState`, and Navigation maps the state
saved by a non-inclusive `popUpTo(X) { saveState = true }` to *X's own* destination id — here Home,
the destination being navigated to. `NavController.navigate` reads that map after running the
`popUpTo`, so one tap saved the chain under `Home` and instantly restored it: the user stayed on the
detail screen. The Home *tab* is unaffected because popping to Home from a top-level screen pops
nothing, storing a null sentinel that makes the restore a no-op. `homeNavOptions()` therefore keeps
`popUpTo<Home>` + `launchSingleTop` and drops both state flags — Home is never itself popped, so it
never has a stack worth restoring. The cost is that unwinding through another tab (Libraries → grid
→ Home) drops that tab's chain, exactly as pressing Back twice would.

## Key classes

| Class | Module | Responsibility |
|---|---|---|
| `ItemDetailScreen` / `ItemDetailContent` | `:feature:detail` | Stateful wrapper + stateless rendering |
| `DetailHeader` / `EpisodeRow` | `:feature:detail` | Header block and episode list rows |
| `ItemDetailViewModel` / `ItemDetailUiState` | `:feature:detail` | Loads the item and its rows, owns the toggles |
| `JellyfinRepository` (M4 section) | `:data` | `getItem`, `getSeasons`, `getEpisodes`, `getNextUpForSeries`, `getSimilarItems` |
| `ItemMapper` | `:data` | Adds taglines, child count, premiere date, studios, people and media file size |
| `UserDataRepository` | `:data` | The watched / favourite writes |

## Offline behaviour

None yet. M4 is still online-only: `getItem` and friends are pure network reads. `getItem` also
serving cached rows (and returning `available = false` instead of throwing) arrives with
`OfflineJellyfinRepository` in M6. The user-data half of this screen, by contrast, already works
with no network at all.

## Verification

M4 DoD walked on the test tablet (2026-07-28): movie and episode detail from grid/home
cards; series → season → episodes chain firing each expected request exactly once
(`/Items/{id}`, `/Shows/{id}/Seasons`, `/Shows/{seriesId}/Episodes?seasonId=`,
`/Shows/NextUp?seriesId=`, `/Items/{id}/Similar`); landscape and portrait passes.
