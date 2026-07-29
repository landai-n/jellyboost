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
  season), metadata line (`2016 · 116 min · PG-13 · 8.4 · 4 seasons · 32 min left`), resume
  progress bar, action buttons, tagline, overview, credit line, genre chips.
- **Rows**: *Next up* → *Seasons* → *Episodes* → *More like this*.

On a viewport wider than 720.dp the poster moves beside the text instead of above it — the same
rearrangement jellyfin-web makes on a desktop, and the layout the project's tablet test device
gets in landscape. Long-form text stops growing at 680.dp; a full-width paragraph on a tablet is
unreadable.

Episode rows reuse `ThumbCard` for their artwork so the watched tick, resume bar and download
badge are byte-identical to the ones the home rows show.

## Actions

| Button | Behaviour |
|---|---|
| Play / Resume | Live from M5 — navigates to `Routes.Player`; see docs/features/playback.md |
| Download | Snackbar: "Downloads arrive in M7." |
| Mark watched | Live — `UserDataRepository.setPlayed` |
| Favourite | Live — `UserDataRepository.setFavorite` |

Both live toggles are local-first and reflect **optimistically via `UserDataEventBus`**, which the
ViewModel collects: the button flips from the local Room write, not from a server round-trip, and
the same event patches the home rows behind the screen. See `docs/features/user-data.md`.

The two not-yet-built buttons are drawn and enabled and say what is actually true rather than
being disabled (which reads as "broken") or silently doing nothing — logged in `DECISIONS.md`.

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

Home calls `AppScaffold.navigateHome` — `navigate(Routes.Home, topLevelNavOptions())`, byte-for-byte
what tapping the Home tab does. A `popBackStack(Routes.Home, inclusive = false)` would read more
directly but fails silently (returns `false`, moves nothing) if Home is ever absent from the stack;
a `navigate` still lands the user on Home. `:feature:library`'s grid and `:feature:settings` carry
the same pair in their `TopAppBar`'s `navigationIcon` slot; the player is excluded, since leaving
playback belongs to its own chrome.

## Key classes

| Class | Module | Responsibility |
|---|---|---|
| `ItemDetailScreen` / `ItemDetailContent` | `:feature:detail` | Stateful wrapper + stateless rendering |
| `DetailHeader` / `EpisodeRow` | `:feature:detail` | Header block and episode list rows |
| `ItemDetailViewModel` / `ItemDetailUiState` | `:feature:detail` | Loads the item and its rows, owns the toggles |
| `JellyfinRepository` (M4 section) | `:data` | `getItem`, `getSeasons`, `getEpisodes`, `getNextUpForSeries`, `getSimilarItems` |
| `ItemMapper` | `:data` | Adds taglines, child count, premiere date, studios and people |
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
