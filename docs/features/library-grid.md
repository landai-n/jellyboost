# Feature: Library grid — M3

Every title in one library, paged with Paging 3, sortable and filterable. The M3 definition of
done is a >500-item library that scrolls cleanly with **one network request per page**.

## Key classes

| Class | Module | Responsibility |
|---|---|---|
| `LibraryGridScreen` | `:feature:library` | Glass header (name, item count, back/home/sort), inline filter chips, grid |
| `LibraryGridContent` | `:feature:library` | Stateless grid + Paging load states |
| `LibrarySortMenu` | `:feature:library` | Sort key + direction dropdown |
| `LibraryFilterSheet` | `:feature:library` | Genres / years / watched bottom sheet |
| `LibraryViewModel` / `LibraryUiState` | `:feature:library` | Query building, sort, filters, facets, item count |
| `LibraryFilterChip` | `:feature:library` | The inline chips' mapping onto `FilterOptions` |
| `JellyfinRepository.getItemsPaged` | `:data` | `Flow<PagingData<JellyfinItem>>` contract |
| `ItemPagingSource` / `ItemPage` | `:data` | Offset/limit `PagingSource` over `getItems`, plus the first page's total |
| `ItemQuery.toGetItemsRequest` | `:data` (`mapper/QueryMapper.kt`) | Domain query → SDK request |
| `FilterFacets` / `FilterOptions` / `ItemQuery` | `:core:common` | Domain models |
| `PosterCard`, `LoadingState`/`ErrorState`/`EmptyState` | `:core:ui` | Cards and states |

## Endpoints

| Call | Parameters |
|---|---|
| `itemsApi.getItems` | `parentId`, `includeItemTypes=[MOVIE, SERIES]`, `recursive=true`, `sortBy`, `sortOrder`, `genres`, `years`, `officialRatings`, `isPlayed`, `isFavorite`, `startIndex`, `limit=50`, `fields=[PRIMARY_IMAGE_ASPECT_RATIO]`, `enableImageTypes=[PRIMARY, BACKDROP, THUMB]`, `imageTypeLimit=1`, `enableUserData=true`, `enableTotalRecordCount` = **`true` on the grid's first page only**, `false` everywhere else |
| `filterApi.getQueryFiltersLegacy` | `parentId`, `includeItemTypes` → genres, years, official ratings (see DECISIONS.md 2026-07-28) |

## Paging

`PagingConfig(pageSize = 50, initialLoadSize = 50, prefetchDistance = 10, enablePlaceholders = false)`.

- **Keys are item offsets, not page numbers.** Jellyfin pages by `startIndex`/`limit`, so the key
  *is* the `startIndex`; the arithmetic stays exact even if Paging asks for a different load size.
- `initialLoadSize` is pinned to the page size so the first load is one `limit=50` request rather
  than Paging's default 3×.
- `prefetchDistance = 10` (Paging's default is the page size, which would queue page 2 the moment
  page 1 renders).
- The end of the list is detected by a **short page** (`items.size < loadSize`), so *appending*
  never asks the server to count anything.
- The **first** load of a source (`params is LoadParams.Refresh`) does ask, once, and reports the
  answer through `getItemsPaged(query, onTotalCount)` → `LibraryUiState.totalCount` → the header's
  "N items" (`ItemQuery.includeTotalCount`, DECISIONS.md 2026-08-01). A full scroll of a 520-item
  library still costs exactly one COUNT. The offline grid reports none: Room holds the downloaded
  items, not the library, so the header simply omits the line.
- Placeholders are off, so the grid never draws empty cells.
- `cachedIn(viewModelScope)` keeps loaded pages across configuration changes.

`ItemPagingSourceTest` pins first page, middle page, full walk of a 520-item library (11 requests,
every item once), short last page, empty library and error propagation.
`OnlineJellyfinRepositoryPagingTest` drives the real `Pager` through `asSnapshot` and asserts the
request count and offsets for a deep scroll.

## Sort

Keys: name (`SORT_NAME`), date added, release date, community rating, runtime, random. Picking the
active key flips the direction; a new key starts in its natural direction (names ascend, dates and
ratings descend), matching jellyfin-web.

`SORT_NAME` rather than `NAME`: the server's sort name strips leading articles, so "The Expanse"
sorts under E.

**Known limitation:** `RANDOM` re-randomises per request, so paging a randomly-sorted library can
repeat or skip items across page boundaries. jellyfin-web has the same behaviour.

## Filters

Two surfaces, one model. The **sheet** edits a *draft* copy of `FilterOptions` and re-queries only
when the user applies it; facets are fetched once per screen, the first time the sheet opens
(including when the server answers with nothing).

The **inline chip row** under the header is a set of shortcuts into that same `FilterOptions`, with
no draft stage — a chip *is* the applied state (`LibraryViewModel.toggleFilterChip`):

| Chip | Edit |
|---|---|
| *All* | clears every filter; selected when none is active |
| *Unwatched* / *Watched* | `isPlayed = false` / `true`, exclusive, tapping again clears it |
| one per applied genre / year | removes that genre or year |
| *Filters* | opens the sheet, which remains the full editor |

Facets the user has **not** applied are deliberately absent from the row: they only exist once the
sheet has been opened, and a row that grew a dozen genres after an unrelated interaction would read
as a bug. The old filter-count badge is gone — the applied filters are legible instead.

Errors are surfaced inside the sheet with a retry, never as a full-screen error over loaded items.

## Load states

| State | Rendering |
|---|---|
| `refresh = Loading`, nothing loaded | `LoadingState` |
| `refresh = Error` | `ErrorState` with retry (`LazyPagingItems.retry()`) |
| loaded, `itemCount == 0` | `EmptyState`; with active filters it offers "Clear filters" |
| `append = Loading` | full-width spinner under the last row |
| `append = Error` | full-width message + retry under the last row |

Paging failures travel as `AppErrorException` (`:core:common`) so the screen can unwrap the domain
`AppError` and reuse the same copy as its non-paged siblings.

## Layout

`LazyVerticalGrid(GridCells.Adaptive(Dimens.PosterWidth))` with 20dp side padding, a 16dp gutter and
20dp between rows — two columns on a 360dp phone, four in tablet portrait, seven in tablet
landscape, with no separate layout. Cells pass `Dp.Unspecified` to `PosterCard` so cards fill their
column without per-cell subcomposition (the earlier per-cell `BoxWithConstraints` was removed in the
cleanup wave). Each card carries the item's community rating as its bottom-left badge alongside the
watched tick, download badge and inset progress.

The header and the chip row sit above the grid; one `BoxWithConstraints` for the whole screen picks
the two things that change at 600dp+ — the title grows to `ScreenTitleLarge`, and sort moves out of
the header into the end of the chip row as a labelled control. A faint `JellyfinGradients.ScreenGlow`
is drawn behind both.

The *Libraries* tab (`LibrariesScreen`) is the screen with a width branch: its adaptive floor is
`Dimens.ThumbWidth` at 600dp+ but 150dp below that, because the tablet floor folds to a single
full-width column on a phone (see `librariesMinCellWidth` and DECISIONS 2026-07-31). Its tiles are
`LibraryCard`s subtitled with the library's `itemCount` ("412 items", shared plural
`core.ui:library_item_count`, hidden when the count is unknown — offline, or when the count request
failed), under a scrolling `ScreenTitle` header. That count is **not** the server's `ChildCount`,
which counts a collection folder's media folders rather than its titles: `getUserViews` fires one
concurrent `limit=0` count query per library over the same `[Movie, Series]` recursive selection this
grid pages, so a tile and the grid it opens always report the same total (DECISIONS 2026-08-01).

## Navigation

`Routes.LibraryGrid(libraryId, libraryName)` (`:core:common`). The name travels in the route so the
top bar renders before the first page arrives. `LibraryViewModel` reads both from `SavedStateHandle`
under the property names `libraryId` / `libraryName`.

`LibraryGridScreen(viewModel, onItemClick, onBack, onHome, modifier)` — the ViewModel is passed in
so `:app` owns the `hiltViewModel()` call, as it does for home.

The header holds **both** navigation affordances — Back and Home, as two glass circles — rather than
just Back, because a pushed destination hides the app's chrome entirely and this grid is the entry to
detail chains that can get many entries deep. `onHome` is `AppScaffold.navigateHome`; see
docs/features/item-detail.md, "Getting out of the chain", for why it navigates rather than pops.

The screen is a pushed destination, so `LocalAppChromePadding` is zero and it insets itself: the
header pads against the status bar, the grid's `contentPadding` clears the navigation bar.

## Batch selection

Long-press a poster to select it, then act on the whole set — see
[`docs/features/batch-selection.md`](batch-selection.md) for the full behaviour. Grid-specific
points:

- the floating glass `SelectionAppBar` **replaces** the header *and* the chip row, so sort and the
  filters are gone while a selection is open — which is the point: they re-query the grid;
- there is **no *Select all*** on a paged grid ("all" would mean either "the pages loaded so far" or
  a page-by-page walk of the library);
- the selection is **cleared whenever the query changes** — sort applied, filters applied or
  cleared — and kept across page appends, badge changes and rotation;
- `LibraryViewModel` now also collects `UserDataEventBus` and patches the loaded pages in place, so
  a batch *Mark watched* shows its ticks with no re-query.

## Offline behaviour

Online only until M6. `OnlineJellyfinRepository` is a pure network reader with no Room
write-through. At M6 the offline implementation pages `ItemDao.pagingDownloaded` behind the same
`Pager` and the same `getItemsPaged` signature — the screen does not change.
