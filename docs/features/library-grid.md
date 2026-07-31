# Feature: Library grid — M3

Every title in one library, paged with Paging 3, sortable and filterable. The M3 definition of
done is a >500-item library that scrolls cleanly with **one network request per page**.

## Key classes

| Class | Module | Responsibility |
|---|---|---|
| `LibraryGridScreen` | `:feature:library` | Scaffold, top bar (name, sort menu, filter action), grid |
| `LibraryGridContent` | `:feature:library` | Stateless grid + Paging load states |
| `LibrarySortMenu` | `:feature:library` | Sort key + direction dropdown |
| `LibraryFilterSheet` | `:feature:library` | Genres / years / watched bottom sheet |
| `LibraryViewModel` / `LibraryUiState` | `:feature:library` | Query building, sort, filters, facets |
| `JellyfinRepository.getItemsPaged` | `:data` | `Flow<PagingData<JellyfinItem>>` contract |
| `ItemPagingSource` | `:data` | Offset/limit `PagingSource` over `getItems` |
| `ItemQuery.toGetItemsRequest` | `:data` (`mapper/QueryMapper.kt`) | Domain query → SDK request |
| `FilterFacets` / `FilterOptions` / `ItemQuery` | `:core:common` | Domain models |
| `PosterCard`, `LoadingState`/`ErrorState`/`EmptyState` | `:core:ui` | Cards and states |

## Endpoints

| Call | Parameters |
|---|---|
| `itemsApi.getItems` | `parentId`, `includeItemTypes=[MOVIE, SERIES]`, `recursive=true`, `sortBy`, `sortOrder`, `genres`, `years`, `officialRatings`, `isPlayed`, `isFavorite`, `startIndex`, `limit=50`, `fields=[PRIMARY_IMAGE_ASPECT_RATIO]`, `enableImageTypes=[PRIMARY, BACKDROP, THUMB]`, `imageTypeLimit=1`, `enableUserData=true`, `enableTotalRecordCount=false` |
| `filterApi.getQueryFiltersLegacy` | `parentId`, `includeItemTypes` → genres, years, official ratings (see DECISIONS.md 2026-07-28) |

## Paging

`PagingConfig(pageSize = 50, initialLoadSize = 50, prefetchDistance = 10, enablePlaceholders = false)`.

- **Keys are item offsets, not page numbers.** Jellyfin pages by `startIndex`/`limit`, so the key
  *is* the `startIndex`; the arithmetic stays exact even if Paging asks for a different load size.
- `initialLoadSize` is pinned to the page size so the first load is one `limit=50` request rather
  than Paging's default 3×.
- `prefetchDistance = 10` (Paging's default is the page size, which would queue page 2 the moment
  page 1 renders).
- The end of the list is detected by a **short page** (`items.size < loadSize`), which is why the
  request can leave `enableTotalRecordCount` off and save the server a COUNT per page.
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

The sheet edits a **draft** copy of `FilterOptions`; nothing is re-queried until the user applies
it — a chip tap must not re-query a 500-item library. Facets are fetched once per screen, the first
time the sheet opens (including when the server answers with nothing).

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

`LazyVerticalGrid(GridCells.Adaptive(120.dp))` — two columns of ~160dp on a 360dp phone
(device-verified in the 2026-07-31 phone-size sweep), five or more on the tablet, with no separate
layout. Cells pass `Dp.Unspecified` to `PosterCard` so cards fill their column without per-cell
subcomposition (the earlier per-cell `BoxWithConstraints` was removed in the cleanup wave).

The *Libraries* tab (`LibrariesScreen`) is the screen with a width branch: its adaptive floor is
`Dimens.ThumbWidth` (210dp) at 600dp+ but 150dp below that, because a 210dp floor folds to a
single full-width column on a phone (see `librariesMinCellWidth` and DECISIONS 2026-07-31).

## Navigation

`Routes.LibraryGrid(libraryId, libraryName)` (`:core:common`). The name travels in the route so the
top bar renders before the first page arrives. `LibraryViewModel` reads both from `SavedStateHandle`
under the property names `libraryId` / `libraryName`.

`LibraryGridScreen(viewModel, onItemClick, onBack, onHome, modifier)` — the ViewModel is passed in
so `:app` owns the `hiltViewModel()` call, as it does for home.

The top bar's `navigationIcon` slot holds **both** navigation affordances — Back and Home — rather
than just Back, because a pushed destination hides the app bar's tabs and this grid is the entry to
detail chains that can get many entries deep. `onHome` is `AppScaffold.navigateHome`; see
docs/features/item-detail.md, "Getting out of the chain", for why it navigates rather than pops.
`actions` stays reserved for sort and filter.

## Batch selection

Long-press a poster to select it, then act on the whole set — see
[`docs/features/batch-selection.md`](batch-selection.md) for the full behaviour. Grid-specific
points:

- the contextual bar **replaces** this screen's `TopAppBar`, so Sort and Filter are gone while a
  selection is open — which is the point: they re-query the grid;
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
