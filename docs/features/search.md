# Feature: Search — M3

A debounced text field over one server query, rendered as one row per item type.

## Key classes

| Class | Module | Responsibility |
|---|---|---|
| `SearchScreen` / `SearchContent` | `:feature:search` | Stateful wrapper + stateless rendering |
| `SearchViewModel` / `SearchUiState` | `:feature:search` | Debounce, request, section split |
| `JellyfinRepository.getItems` | `:data` | One unpaged page of items |
| `ItemQuery.toGetItemsRequest` | `:data` (`mapper/QueryMapper.kt`) | Domain query → SDK request |
| `MediaRow`, `PosterCard`, `ThumbCard`, state views | `:core:ui` | Rows, cards and states |

## Endpoint

`itemsApi.getItems` with `searchTerm`, `includeItemTypes = [MOVIE, SERIES, EPISODE]`,
`recursive = true`, `limit = 50`, plus the same lean card field set the home rows use
(`fields=[PRIMARY_IMAGE_ASPECT_RATIO]`, `enableImageTypes=[PRIMARY, BACKDROP, THUMB]`,
`imageTypeLimit=1`, `enableUserData=true`, `enableTotalRecordCount=false`).

Search is deliberately **not** paged: one capped request split into sections, which is what
jellyfin-web's search does, and it keeps the debounced typing path to a single in-flight request.

## Debounce

500 ms (docs/PLAN.md, "Screens" → Search), on the trimmed term:

- typing a whole word costs **one** request, not one per keystroke;
- `collectLatest` cancels a search still in flight when the term changes, so a slow response can
  never overwrite a newer term's results;
- `distinctUntilChanged` drops a term that ends up unchanged (e.g. typing then deleting a space);
- clearing the field **bypasses** the debounce — an empty screen appears immediately;
- whitespace-only input never reaches the server.

`SearchViewModelTest` exercises all of this on virtual time.

## Sections

One `MediaRow` per type, in jellyfin-web's order — Movies and Shows as `PosterCard`s, Episodes as
`ThumbCard`s. An empty section is skipped entirely rather than emitted as a zero-height item, which
would still consume the column's `spacedBy` gap.

## States

| State | Rendering |
|---|---|
| field empty | prompt (`EmptyState`, search icon) |
| request in flight, nothing yet | `LoadingState` |
| search ran, nothing matched | `EmptyState` quoting the submitted term |
| failure | `ErrorState` with retry (re-runs the current term, bypassing the debounce) |

`SearchUiState.submittedQuery` lags `query` by the debounce, so the "nothing matched X" message
always quotes the term the results actually belong to.

## Navigation

Reuses the existing top-level `Routes.Search` (`:core:common`) — the bottom-nav destination.
`SearchScreen(viewModel, onItemClick, modifier)`; the ViewModel is passed in so `:app` owns the
`hiltViewModel()` call, as it does for home.

## Offline behaviour

Online only until M6. At M8 the offline implementation answers the same `getItems` call from
`ItemDao.searchDownloaded`, and the screen does not change.
