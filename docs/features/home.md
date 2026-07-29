# Feature: Home (online) — M2

The app's landing destination. Mirrors jellyfin-web's home layout so that a side-by-side
comparison shows the same sections, items and ordering (the M2 definition of done).

## Rows, in order

| # | Row | Source call | Limit |
|---|---|---|---|
| 1 | My Media | `getUserViews()`, filtered to `MOVIES` / `TVSHOWS` | – |
| 2 | Continue Watching | `getResumeItems()` | 20 |
| 3 | Next Up | `getNextUp()` | 20 |
| 4… | Latest &lt;library&gt; | `getLatestMedia(parentId)`, one row per library | 16 |

Empty rows are not rendered — jellyfin-web omits an empty shelf rather than showing a blank one,
and `MediaRow` returns early on an empty list to match.

## Key classes

| Class | Module | Responsibility |
|---|---|---|
| `HomeScreen` / `HomeContent` | `:feature:home` | Stateful wrapper + stateless rendering |
| `HomeViewModel` / `HomeUiState` | `:feature:home` | Loads and holds the rows |
| `JellyfinRepository` | `:data` | The home-scope data contract |
| `OnlineJellyfinRepository` | `:data` | SDK-backed implementation |
| `ItemMapper` | `:data` | `BaseItemDto` → `JellyfinItem` / `LibraryView` |
| `ImageUrlFactory` / `SdkImageUrlFactory` | `:data` | Builds image URLs via the SDK's `imageApi` |
| `MediaRow`, `PosterCard`, `ThumbCard`, `LibraryCard` | `:core:ui` | The row and card design system |

## Loading strategy

Libraries load first (every *Latest* row is keyed off one), then *Continue Watching*, *Next Up*
and every *Latest* row are fetched concurrently in a `coroutineScope`, so the screen is bound by
the slowest single request rather than by their sum.

**Failure policy:** only a failing `getUserViews` produces an error screen — without libraries
there is nothing to render. A single row that fails is left empty, matching jellyfin-web, which
omits a section it could not load instead of blanking the page.

## Requests are deliberately lean

List calls request only `PRIMARY_IMAGE_ASPECT_RATIO` plus `PRIMARY`/`BACKDROP`/`THUMB` artwork with
`imageTypeLimit = 1` and `enableTotalRecordCount = false`. Full field sets (media sources, streams,
chapters, trickplay) are fetched only on the detail and playback paths — the Swiftfin pattern the
plan adopts.

## Staying current while the user is elsewhere

The rows are loaded once and then kept true by three subscriptions, so the screen a user comes back
to is never the screen they left. None of them shows a spinner or touches `isRefreshing`.

| Signal | Source | Effect |
|---|---|---|
| `UserDataEventBus` | any watched/favourite/position write in the app | patch the cards in place, and re-fetch the two rows whose *membership* depends on watched state |
| `DownloadRepository.observeStates()` | the download engine | re-stamp every card's badge |
| `ConnectivityRefresher.connectivityChanged` | both online↔offline edges | full reload — the other source answers now |

### Watched state and row membership

*Continue watching* and *Next up* are rows of **unfinished** items, so a watched toggle can move
items in and out of them — something a patch, which can only rewrite a card that is already on
screen under that exact id, cannot express on its own. `HomeViewModel` handles it in two layers:

- **Instant, request-free:** `HomeUiState.withUserData` evicts an item from those two rows when the
  change says it is played (elsewhere — *Latest* — it patches as before). Marking a movie watched
  makes it leave *Continue watching* in the same frame, offline included.
- **Debounced silent refresh:** a change whose `played` flipped — or one for an item no row shows,
  which is exactly what *Mark watched* on a **series or season** page publishes — queues a
  re-fetch of `getResumeItems()` + `getNextUp()`, 1.5 s after the last such change. That is what
  advances *Next up* to the following episode, brings an un-marked item back, and fixes the rows
  after a container toggle; the debounce turns "mark a season watched" (one write per episode) into
  one pair of requests. Online only, and a row whose call fails keeps what it had.

Position-only writes deliberately do **not** trigger it (`PlaybackReporter` writes one every five
seconds); the played flag it sets when playback finishes does. Local changes are re-applied on top
of whatever the refresh fetches, so a read cannot overtake its own write — see
`docs/features/user-data.md` and DECISIONS.md, 2026-07-29.

## Artwork fallback

`ItemMapper` follows jellyfin-web's chain so rows never degrade into placeholders:

- **Primary:** own tag → series primary (episodes) → parent primary
- **Backdrop:** own first backdrop tag → parent backdrop
- **Thumb:** own thumb → series thumb → parent thumb

`ThumbCard` additionally falls back thumb → backdrop → primary at render time.

## Offline behaviour

None yet. M2 is the online-only milestone: `OnlineJellyfinRepository` is a pure network reader with
no Room write-through. The browse cache (`source=BROWSE_CACHE`), `OfflineJellyfinRepository` and
`DelegatingJellyfinRepository` arrive in M6, at which point the offline home rows come from Room
(resume = downloads with position > 0, next-up = next downloaded episode per series, latest =
recent downloads) behind this same `JellyfinRepository` interface — the screen does not change.

## Integration status

Wired into the app. `HomeViewModel` is `@HiltViewModel`, backed by the `org.jellyfin.sdk.api.client.ApiClient`
binding `:core:network` provides (`di/NetworkModule.kt`, `ApiClientModule`). `Routes.Home` in the
`:app` NavHost renders `HomeScreen(viewModel = hiltViewModel(), …)` directly; `onItemClick` pushes
`Routes.ItemDetail` and `onLibraryClick` pushes `Routes.LibraryGrid`. The screen draws no bar of its
own — since M9 the combined `AppTopBar` in `AppScaffold` carries the navigation, the app overflow
menu (offline toggle + Settings) and the offline status icon for every top-level destination, and
the intermediate `HomeRoute` composable it replaced is gone (DECISIONS.md 2026-07-29).
