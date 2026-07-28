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

Not yet reachable in the APK. `HomeViewModel` is not `@HiltViewModel` and `:app`'s NavHost is not
wired, both pending M1's `ApiClient` binding — see DECISIONS.md, 2026-07-28.
