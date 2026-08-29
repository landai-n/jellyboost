# Feature: Home (online) — M2

The app's landing destination. Mirrors jellyfin-web's home layout so that a side-by-side
comparison shows the same sections, items and ordering (the M2 definition of done) — including,
since 2026-07-29, the **order and visibility the user configured server-side** in jellyfin-web's
Settings → Home.

## Rows

| Row | `homesectionN` value | Source call | Limit |
|---|---|---|---|
| My Media | `smalllibrarytiles` / `librarybuttons` (`folders` legacy alias) | `getUserViews()`, filtered to `MOVIES` / `TVSHOWS` | – |
| Continue Watching | `resume` | `getResumeItems()` | 20 |
| Next Up | `nextup` | `getNextUp()` | 20 |
| Latest &lt;library&gt; | `latestmedia` | `getLatestMedia(parentId)`, one row per library | 16 |

Default order — what an account that never opened Settings → Home gets — is My Media, Continue
Watching, Next Up, then the *Latest* rows, exactly as before.

Empty rows are not rendered — jellyfin-web omits an empty shelf rather than showing a blank one,
and `MediaRow` returns early on an empty list to match.

**Continue Watching is video only, at the source.** Music has its own resume row (*Continue
Listening*, `RESUME_AUDIO`), and the two must not overlap: an in-progress track that reached the
video row became the hero, drew an "S1 · E14" line built from its disc and track numbers, and its
resume button opened the video player. Both resume sources therefore state their side of the line
themselves — online through `mediaTypes` **and** a client-side re-check of the returned kinds,
offline through `ItemDao.resumeDownloaded`'s explicit video kinds — rather than leaving it to the
UI. Two guards sit behind that: `episodeNumberLabel()` answers for episodes only, and the hero's
resume tap goes through `:app`'s `playbackRouteFor(type)`, so an audio item that ever reappears here
still resumes in the music queue.

## The configured layout

jellyfin-web stores Settings → Home in **DisplayPreferences**, as `homesection0` … `homesection9`
inside `customPrefs`. `HomeLayoutRepository` reads it with one call:

```kotlin
apiClient.displayPreferencesApi.getDisplayPreferences(
    displayPreferencesId = "usersettings", // any other id is MD5-hashed into an unrelated record
    client = "emby",                       // legacy partition key — every client that shares the
)                                          // web-configured layout passes this literal
```

Both strings are load-bearing: preferences are partitioned by `(userId, itemId, client)`, so this
app's own client name would read a private, permanently empty record. Research and provenance:
`docs/notes/home-sections-feasibility.md`.

**Resolution** (`resolveHomeSections`, `:data`): each of the ten slots is resolved independently —
a missing key, an empty value or one this build does not recognise falls back to *that slot's*
jellyfin-web default (`smalllibrarytiles, resume, resumeaudio, resumebook, livetv, nextup,
latestmedia, none, none, none`). A user who never opened Settings → Home has **no** keys at all, so
"missing" has to mean "client defaults", not "empty home screen". `none` is then dropped and the
list de-duplicated, first occurrence winning.

**Sections this app has no row for** — `resumeaudio`, `resumebook`, `livetv`, `activerecordings`
(v1 is movies and TV) — are carried through the resolution faithfully and skipped at render time.
Dropping them earlier would silently reorder everything after them.

**Failure policy:** `HomeLayoutRepository.getHomeSections()` never throws and never returns
nothing usable. Online it fetches and persists; on any fetch or parse failure, and offline, it
answers from the persisted layout, and from the defaults if there is none (fresh install, first
launch, no network). The shape of the home screen is not worth an error state.

**Freshness:** resolved on every *full* load — first open, pull-to-refresh, and the connectivity
edge — and never polled. Changing Settings → Home in jellyfin-web then pulling to refresh shows
the new layout.

**Out of scope** (documented in the feasibility note): the per-library exclusions in
`User.Configuration` (`LatestItemsExcludes`, `MyMediaExcludes`, `OrderedViews`,
`HidePlayedInLatest`), rendering `librarybuttons` as large buttons rather than the tile row, and
writing the configuration back from the app.

## Key classes

| Class | Module | Responsibility |
|---|---|---|
| `HomeScreen` / `HomeContent` | `:feature:home` | Stateful wrapper + stateless rendering |
| `HomeViewModel` / `HomeUiState` | `:feature:home` | Loads and holds the rows |
| `HomeSectionType` | `:core:common` | The ten row kinds jellyfin-web can configure |
| `HomeLayoutRepository`, `resolveHomeSections` | `:data` | Reads and resolves the configured layout |
| `HomeLayoutStore` / `SharedPreferencesHomeLayoutStore` | `:core:datastore` | Caches the last resolved layout (`home_layout` prefs file) |
| `JellyfinRepository` | `:data` | The home-scope data contract |
| `OnlineJellyfinRepository` | `:data` | SDK-backed implementation |
| `ItemMapper` | `:data` | `BaseItemDto` → `JellyfinItem` / `LibraryView` |
| `ImageUrlFactory` / `SdkImageUrlFactory` | `:data` | Builds image URLs via the SDK's `imageApi` |
| `MediaRow`, `PosterCard`, `ThumbCard`, `LibraryCard` | `:core:ui` | The row and card design system |

## Loading strategy

The layout is resolved first, and then **only the rows it contains are fetched**: a hidden *Next
Up* costs no `getNextUp` call, and a layout with neither the libraries row nor *Latest* skips
`getUserViews` altogether. Libraries load before the rest (every *Latest* row is keyed off one),
then *Continue Watching*, *Next Up* and every *Latest* row are fetched concurrently in a
`coroutineScope`, so the screen is bound by the slowest single request rather than by their sum.

`getUserViews` itself costs one extra round trip: the tiles' "N items" subtitle has no source in
the `/UserViews` response (its `ChildCount` counts the library's media folders, not its titles —
3 for a 177-movie library), so the repository fires a `limit=0` count query per supported library,
all concurrently, and reports the totals as `LibraryView.itemCount` (DECISIONS 2026-08-01). A count
that fails is swallowed per library — that tile draws its name alone.

**Failure policy:** only a failing `getUserViews` produces an error screen — without libraries
there is nothing to render. A single row that fails is left empty, matching jellyfin-web, which
omits a section it could not load instead of blanking the page. A failing *count* is not a failing
`getUserViews`: it costs a subtitle, never the row.

One deliberate consequence: the *My Media* cards are filtered to libraries with something behind
them using the *Latest* answers, so a layout that hides *Latest* shows every library the user can
see — offline that includes libraries with no downloads in them. Asking anyway would undo the
saving the hidden row buys.

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
| `UserDataEventBus` | any watched/favourite/position write in the app | patch the cards in place, and re-fetch the two rows whose *membership* depends on watched state — skipping either if the configured layout hides it |
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
recent downloads, with downloaded **episodes grouped into their series** the way the server's
`GroupItems` does online — see `docs/features/offline-read.md`) behind this same
`JellyfinRepository` interface — the screen does not change.

## Integration status

Wired into the app. `HomeViewModel` is `@HiltViewModel`, backed by the `org.jellyfin.sdk.api.client.ApiClient`
binding `:core:network` provides (`di/NetworkModule.kt`, `ApiClientModule`). `Routes.Home` in the
`:app` NavHost renders `HomeScreen(viewModel = hiltViewModel(), …)` directly; `onItemClick` pushes
`Routes.ItemDetail` and `onLibraryClick` pushes `Routes.LibraryGrid`. The screen draws no bar of its
own — since M9 the combined `AppTopBar` in `AppScaffold` carries the navigation, the app overflow
menu (offline toggle + Settings) and the offline status icon for every top-level destination, and
the intermediate `HomeRoute` composable it replaced is gone (DECISIONS.md 2026-07-29).
