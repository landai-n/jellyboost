# Feature: Batch selection

Long-press a card or a row to enter selection mode, tap to add and remove, then act on the whole
set at once: **Mark watched**, **Mark unwatched**, **Download**.

Every action is composed from the *existing* single-item path — `UserDataRepository.setPlayed` and
`DownloadRepository.enqueue`. No new server call, no new repository method, no new download
semantics. What is new is one interaction mode and one summary snackbar.

## v1 scope

| Surface | Selection | Why |
|---|---|---|
| **Library grid** (`:feature:library`) | ✅ | The screen where a user faces a hundred titles at once |
| **Episode list** on a season page (`:feature:detail`) | ✅ | The other list of comparable things, and the one a "download this half of the season" request is really about |
| Home shelves (`:feature:home`) | ❌ | Deliberately out. The shelves are *curated* rows — Continue watching, Next up — whose membership changes as you mark things watched; a selection would rearrange itself under the finger |
| Search results (`:feature:search`) | ❌ | Deliberately out. Results are transient and re-queried on every keystroke, which is the same problem as the grid's query change but continuous |
| Seasons row, *Next up*, *More like this* | ❌ | Navigation surfaces that lead somewhere else, not lists of peers |

Home and search can be added later without touching anything below: the model and the bar are
shared, and a surface joins by exposing a `StateFlow<ItemSelection>` and one
`(SelectionIntent) -> Unit`.

## Key classes

| Class | Module | Responsibility |
|---|---|---|
| `ItemSelection` | `:core:common` | The selection: an **id-keyed**, immutable `Set<String>` with `toggled` / `selecting` / `cleared` / `retaining` |
| `SelectionIntent`, `SelectionAction` | `:core:common` | What the bar can ask for (`Toggle`, `SelectAll`, `Clear`, `Run(action)`) |
| `BatchOutcome`, `BatchReport`, `runBatch` | `:core:common` | Sequential batch runner and its `done` / `failed` / `skipped` counts |
| `SelectionAppBar` | `:core:ui` | The contextual M3 top app bar, in `secondaryContainer` colours |
| `batchOutcomeText` | `:core:ui` | The one snackbar line, resolved from resources in Compose |
| `MediaCardArtwork` / `PosterCard` / `ThumbCard` | `:core:ui` | Selection scrim, border and check indicator; `selectableCardClick` (tap + haptic long press) |
| `LibraryViewModel.selection` / `onSelection` | `:feature:library` | The grid's selection and batch |
| `ItemDetailViewModel.selection` / `onSelection` | `:feature:detail` | The episode list's selection and batch |
| `EpisodeRow` | `:feature:detail` | Row-shaped selection: container wash + trailing checkbox |

## Interaction

- **Enter**: long-press any card or row. The press buzzes (`LocalHapticFeedback`, `LongPress`) —
  without it the press reads as having done nothing until the eye finds the bar at the other end of
  the screen.
- **Add / remove**: tap while the mode is on. Outside the mode, tap opens the item as always.
- **Leave**: the close (X) in the bar, or system Back (`BackHandler`, enabled *only* while the mode
  is on, so Back keeps popping the destination at every other moment).
- **Mode is derived from emptiness** — `ItemSelection.isActive == ids.isNotEmpty()`. There is no
  second flag, so the bar can never read "0 selected", and deselecting the last item leaves the mode
  on its own.
- Running an action **ends the mode immediately**, before the work starts: a batch is a series of
  single-item calls that can take a while, and a bar left up over a live list invites a second tap on
  the same selection. The snackbar says when it finished.

### The contextual bar, per surface

| Surface | What it replaces | Select all |
|---|---|---|
| Library grid | its own `TopAppBar` (Back + Home in `navigationIcon`, Sort + Filter in `actions`) | **no** |
| Season page | the overlaid Back + Home row — this screen has no top bar, so that pair *is* its bar | **yes** |

Both put the close affordance where Back was, so the top-left corner keeps meaning "get out of
here". On the grid, replacing the bar also takes Sort and Filter away for the duration, which is
exactly right: they re-query the grid, and that is what must not happen with a selection open.

### Why the grid has no *Select all*

On a Paging 3 grid, "all" would mean one of two things and neither is honest:

- **the pages loaded so far** — a different set after every scroll, with nothing on screen saying
  so. "Select all" then "Mark watched" would mark 50 items or 500 depending on how far the user had
  scrolled a minute earlier;
- **everything matching the query** — which no client-side call can enumerate; it needs a walk of
  the whole library, page by page, i.e. new server traffic for a button.

The episode list has neither problem: it is fetched whole in one call, so "all" is a set the user
can see and count. It gets the action.

### Selection vs. the list changing underneath

| Event | Selection |
|---|---|
| Grid: sort or filter applied / cleared | **dropped** (`LibraryViewModel.dropSelection`) |
| Grid: filter sheet opened, draft filters edited | kept — nothing is re-queried |
| Grid: a new page appends | kept |
| Grid: a download badge or watched tick changes | kept |
| Season page: background refresh (connectivity edge) | kept, **intersected** with the episodes that came back |

Changing the query swaps the `Pager` underneath, so a kept selection would be ids the user can no
longer see, and the next batch would act on items that are not on screen. The season page's reload
is not a user action at all — it fires on a connectivity change — so dropping a selection there
would look like the app losing the user's work; instead `ItemSelection.retaining` keeps whatever
survived the reload.

## The actions

### Mark watched / Mark unwatched

`UserDataRepository.setPlayed(id, played)` per id — the same call the detail page's watched toggle
makes. Local-first by construction (docs/PLAN.md, "Data layer"): Room is written and the change is
published on `UserDataEventBus` *before* the server is contacted, so:

- **it works offline**, and the result is durable and syncs later via `UserDataSyncWorker`;
- the ticks appear from the local write. The library grid now collects the bus and patches its
  loaded pages in place (`LibraryViewModel.userDataPatches`, combined downstream of `cachedIn`), so
  marking twenty titles watched costs **zero** extra requests and re-fetches no page. The home
  screen's own membership refresh reacts to the same bus and does its thing.

A **series or season** card publishes the container's id, which is what the single-item path already
does — the server marks the episodes under it, and the next sync brings that back.

### Download

`DownloadRepository.enqueue(id)` per id — the same call the detail page's Download button makes,
including its container expansion, its default-quality stamping and its queue ordering.

**Items already spoken for are skipped, and counted.** The rule is `DownloadState.isDownloadable`:
anything `NotDownloaded` or `Failed` is enqueued, anything downloaded, queued, downloading or paused
is not. This is not a duplicated guard — the enqueuer's own skip (`DownloadEnqueuer.isRetryable`)
only runs when it *expands a container*; a **single** movie or episode handed to it is re-fetched
and written back as `QUEUED`, which on a finished download would reset the row and transfer the file
again. So the batch answers the question the enqueuer does not.

Two consequences worth knowing:

- a **series** in the grid never has a download row of its own — the pipeline expands it — so it
  always looks downloadable here and always reaches the enqueuer, which then skips the episodes
  already on the device itself (DECISIONS.md, 2026-07-29). Selecting a half-downloaded show and
  tapping Download queues exactly the missing episodes.
- a **failed** item is deliberately *not* skipped: re-enqueueing is how a failure is retried, and it
  keeps its queue position and the bytes already on disk.

**Offline**, an enqueue fails exactly as a single tap on the same card does today — the item's full
re-fetch cannot reach the server — and the summary reports the failures. Nothing here invents an
offline behaviour the single-item path does not have.

### Remove download — not in v1

Left out on purpose. It is destructive and needs a confirmation dialog of its own, the Downloads
screen already has a confirmed per-row delete and a *Cancel all*, and a fourth icon on a bar that
must stay readable on a phone is a real cost. The bar has room to grow if it is asked for.

## Batch execution and the summary

`runBatch` (`:core:common`) runs the calls **sequentially** and counts both outcomes — `map` then
count, never `any`/`all`, so a failure never short-circuits the rest (the same shape
`DownloadsViewModel.pauseAll` uses). Sequential rather than bounded-concurrent because the watched
path is a local Room write and the download path ends in a queue drained one item at a time anyway:
concurrency would buy no wall-clock time while making the failure counts depend on scheduling order.

The order is the order things were selected — `ItemSelection.ids` is always a `LinkedHashSet` — and
after a *Select all*, list order.

One snackbar per batch, from `batchOutcomeText`, in four shapes:

| Case | Copy |
|---|---|
| nothing succeeded | "Couldn't mark 3 items watched" |
| mixed | "Marked 4 watched, 1 failed" |
| everything already downloaded | "2 items are already on this device" |
| clean, some skipped | "Added 3 to downloads — 2 already on this device" |
| clean | "Marked 5 watched" |

The `ViewModel`s carry `BatchReport(action, outcome)` — resource-free — and Compose picks the words,
the same arrangement `:feature:detail`'s `UserMessage` and `:feature:downloads`' `DownloadsMessage`
already use.

## Performance

The grid was recently tuned (`contentType`, no per-cell `BoxWithConstraints`, artwork requested at
display size), and selection is built not to spend that back:

- the selection lives in **its own `StateFlow`**, not in `LibraryUiState`. A cell that read it out of
  the ui state would also be subscribed to the sort key, the filters, the facets and the snackbar
  message — opening the sort menu would recompose every visible cell;
- the screen passes the selection **as a `State`**, not as a value, and each cell derives its own
  flag inside a `remember(selection, id) { derivedStateOf { … } }`. Toggling one card invalidates
  one cell: `derivedStateOf` re-evaluates when the set changes but only notifies its readers when
  *that item's* `Boolean` actually flipped;
- the cards take a plain `Boolean?` (`null` = not in selection mode), never the set, so
  `PosterCard` / `ThumbCard` skip normally;
- a card with no `onLongClick` keeps its existing plain `clickable` — `combinedClickable` is
  installed only on the two surfaces that need it, so the app's other few hundred cards are
  untouched.

The same `derivedStateOf` idiom is used for the episode rows, where a season can hold forty.

## Visual language

- **Cards** (poster, thumb): a `primary` scrim at 35 %, a 2 dp `primary` border, and a filled
  `CheckCircle` / hollow `RadioButtonUnchecked` at the top-start corner over a dark pill so it reads
  on bright artwork. Both states are drawn — an unselected card has to say that it *could* be
  selected, otherwise the mode looks like it applies to one card only.
  The **watched tick is hidden while the mode is on**: it lives in the same corner and is the same
  glyph, and two checks on one card is a puzzle rather than two facts. It comes straight back when
  the mode ends.
- **Episode rows**: a `secondaryContainer` wash across the whole row, and the trailing Play button is
  replaced by a `Checkbox`. A row's identity is its text, so dimming only its thumbnail would not
  read at a glance down a column; and play is a one-item action, which is the wrong thing to offer
  in a many-item mode.

## Tests

| Class | Covers |
|---|---|
| `ItemSelectionTest` (`:core:common`) | toggle in/out of mode, insertion order, `selecting`, `retaining` (including identity when nothing changed), and `runBatch` never short-circuiting |
| `LibraryViewModelTest` | enter/leave, close, *Select all* ignored on a paged grid, selection surviving pages and badge changes, cleared on sort and on applied filters but not on opening the sheet, one repository call per id for each action, mixed-failure counts, download skipping what is already on the device, mode ending as the batch starts, one-shot snackbar, and a user-data change patching the loaded pages with no re-query |
| `ItemDetailSelectionTest` | the same, plus *Select all* over the loaded episodes and a background refresh keeping the selection minus dropped episodes |
