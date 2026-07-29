# Offline read path (M6)

The differentiator, stated as a rule: **there is no offline mode.** Every screen is the same
screen with or without a server, and the only thing that changes underneath is which repository
answered. No banner-driven navigation, no separate offline UI, no "download manager" the user has
to go into to watch what they downloaded.

This document covers what M6 built, plus the M9 refresh that closed its one known gap
("Following the connection", below). Downloads themselves are M7; offline *playback* and the
most-recent-wins sync worker are M8.

---

## The three moving parts

```
ConnectivityMonitor ─┐
                     ├─► ConnectionStateProvider ─► DelegatingJellyfinRepository ─► Online / Offline
ServerReachability ──┤                          └─► OfflineBanner (AppScaffold)
        Probe        │
AppPreferences ──────┘
 (forceOffline)
```

### 1. Knowing whether we are online — `:core:network/connectivity`

| class | question it answers |
|---|---|
| `ConnectivityMonitor` / `AndroidConnectivityMonitor` | Is there a network at all? `registerDefaultNetworkCallback`, `NET_CAPABILITY_INTERNET`. |
| `ServerReachabilityProbe` | Does *our* server answer? 3 s `getPublicSystemInfo` per candidate address. |
| `ConnectionStateProvider` | The single `StateFlow<ConnectionState>` everything reads. |

`ConnectionState` resolves in a fixed priority order:

1. `OFFLINE_FORCED` — the user pinned offline mode; outranks everything, including a perfect network.
2. `OFFLINE_NO_NETWORK` — the monitor says there is no usable network.
3. `OFFLINE_SERVER_UNREACHABLE` — network up, server silent.
4. `ONLINE`.

**Deliberately not `NET_CAPABILITY_VALIDATED`.** A self-hosted Jellyfin on a LAN with no internet
uplink is the app's bread-and-butter case; treating an unvalidated Wi-Fi as "no network" would lock
exactly that setup offline. Whether the server answers over the network is the probe's question.

**Address rotation.** A server usually has several `ServerAddressEntity` rows (LAN, remote/tunnel,
whatever was typed at setup). The probe tries the address the client is already using first, then
every other candidate, and re-points the shared `ApiClient` at whichever one answers
(`ApiClientProvider.useAddress`, which keeps the access token — unlike `useServer`, which drops it).
Walking out of the house is therefore "use the other address", not "go offline".

**Probing is requested, never run inline.** A conflated `Channel` feeds a single consumer that runs
one probe and then waits `PROBE_DEBOUNCE_MS` (2 s), so a screenful of ViewModels all reporting the
same failed request produces one `getPublicSystemInfo`, not twelve. Probes are triggered by:

- a network becoming available (including at app start),
- `refresh()` — app resume (`LifecycleResumeEffect` in `AppScaffold`) and the banner's *Retry*,
- `reportFailure()` — the delegating repository, after a transport-level failure.

### 2. Choosing a source per call — `:data/DelegatingJellyfinRepository`

Bound as `JellyfinRepository`; nothing outside `:data` injects the online or offline implementation
directly.

| server answer | what happens |
|---|---|
| success | returned as is |
| transport failure (IO, timeout, TLS) | `reportFailure()` + this call retried offline |
| 502 / 503 / 504 | same — a proxy in front of a stopped server is indistinguishable from a dead one |
| **401 / 403** | **surfaced unchanged** so the session layer can re-authenticate |
| 404, 500, anything else | surfaced unchanged |

Swallowing a 401 into an offline fallback would be the worst outcome available: the user would
silently see only downloaded media while their session quietly stayed expired.

Every online call is additionally bounded by `ONLINE_CALL_TIMEOUT_MS` (10 s) — see DECISIONS.md,
"a 10-second ceiling on every online repository call".

`getItemsPaged` is a stream, so the choice is re-made on every connection change rather than once at
subscription: losing the network mid-scroll swaps the grid over to downloaded items instead of
freezing on a failed page. The decision is `distinctUntilChanged` on *online-ness*, so flipping
between two offline reasons does not rebuild the `Pager` and throw the user to the top of the grid.

### 3. Serving from Room — `:data/OfflineJellyfinRepository`

Scope, from the plan's "Confirmed decisions": **downloaded items only**, with one exception —
"cached parents of downloaded items still open, e.g. series page of a downloaded episode". So every
*list* filters on `source = DOWNLOAD` while `getItem` serves any cached row.

| surface | offline definition |
|---|---|
| My Media | cached `library_views`, in the server's own order |
| Continue watching | downloads this device has a resume position for, most recent first |
| Next up | first unwatched downloaded episode of each series |
| Latest *library* | most recently downloaded items of that library's **kinds** |
| Library grid | downloaded items of that library's **kinds**, name-ordered (DECISIONS.md) |
| Search | name / series-name `LIKE` over downloaded items |
| Item detail | any cached row; `getSimilarItems` is empty offline |

**`getUserViews` does not know about the download filter.** My Media is the one row above that
isn't scoped to downloaded items — it answers from the full cached `library_views` table, the same
rows an online session would see, whether or not anything in a given library has actually been
downloaded. Left alone, that means an offline My Media card can open onto a grid with nothing in
it. `HomeViewModel` corrects this one level up: a library is dropped from the *My Media* cards when
its *Latest* call for that library **succeeded and came back empty**, and kept when that call
**failed**. The *Latest* shelves were already filtered on `items.isNotEmpty()`, so this only makes
the cards agree with the shelves below them; distinguishing failure from emptiness is what stops
one flaky `getLatestMedia` call while online from making a library's card vanish.

**Which library an offline row belongs to is decided by its type, not by `parentId`.** A downloaded
row is stored with `parentId NULL` (the enqueue-time DTO carries no usable `ParentId`), and even
when a server sends one it is the item's containing *folder*, not the library-view id the grid asks
about — so the old `parentId = <library>` predicate matched nothing and the offline Films grid was
empty next to a downloaded film. `OfflineJellyfinRepository` resolves the library's `CollectionKind`
from the cached `library_views` and narrows the item types instead (movies → `MOVIE`, TV → `SERIES`,
plus `EPISODE` for Latest). Exact for the movie/TV libraries v1 supports; a documented v1
simplification otherwise (DECISIONS.md 2026-07-28). Series → season → episode navigation is
unaffected — it runs on `seriesId`/`seasonId`, which the DTOs do carry.

**It never throws and never reports a missing item as an error.** `getItem` answers with a
placeholder carrying `available = false` — `JellyfinItem`'s own vocabulary for "known of, but not
openable right now". A repository that failed here would turn every stale deep link into a
crash-shaped error screen.

**Local user data wins.** Rows are overlaid with `user_data` before being returned: a position
written with no network exists nowhere else yet.

Until M7 nothing writes `source = DOWNLOAD` rows, so on a real device these lists are empty today.
The behaviour is pinned by `OfflineJellyfinRepositoryTest`, which seeds Room directly.

---

## Following the connection — refresh on every connectivity change (M9)

Choosing a source *per call* is the right rule for a call that has not happened yet, and no rule at
all for a screen that already fetched. A home screen opened in airplane mode kept showing downloaded
media after the network returned, until the user navigated away and back (STATUS.md, M6 known
issues). That was the original gap, but it turned out to be only half of it: switching to offline
mode, or simply losing the network, left an already-loaded screen showing its *online* rows —
server items the app can no longer play, sitting right next to a banner saying so. Going offline
needs the same correction as coming back, and for the same reason: what a screen is showing must
match what it can currently do. Both directions are handled by one signal now.

```
ConnectionStateProvider.state ─► onlineStateChanges() ─► ConnectivityRefresher.connectivityChanged ─► ViewModels
      StateFlow<ConnectionState>      Flow<Boolean>                Flow<Unit> (@Singleton)               re-load
```

**`onlineStateChanges()` (`:core:network/connectivity`)** maps the state to online-ness,
`distinctUntilChanged`s it, **drops the initial value** and emits the new online-ness — `true` or
`false` — on every change after that, in both directions. The two things it does not do are the
point:

- *no emission for the state a screen starts with.* Every ViewModel already loads in its `init`, so
  an initial emission would make an ordinary online launch fetch everything twice. This is exactly
  where it diverges from `UserDataSyncTrigger`, which deliberately *does* act on its initial value —
  its consumer is guarded by a cheap `COUNT(*)`, and it has an app-start case to cover. See
  DECISIONS.md, 2026-07-29.
- *no emission per intermediate state.* Swapping between two offline reasons (say,
  `OFFLINE_NO_NETWORK` to `OFFLINE_FORCED`) is not a connectivity change as far as a screen's data is
  concerned, and a connection that flaps twice produces two refreshes, not one per state it passed
  through.

**`ConnectivityRefresher` (`:data`)** is the `@Singleton` handle feature modules inject:
`val connectivityChanged: Flow<Unit>`. It discards the `Boolean` — every ViewModel below reruns the
same reload whichever way the edge ran — and its surface is a bare `Flow<Unit>` and nothing else on
purpose: feature modules depend on `:data`, not on `:core:network`, so no ViewModel needs to see
`ConnectionState` to know "refresh now". No `build.gradle.kts` changed to wire this up.

Each ViewModel collects it in `init` and re-runs **the load path it already had** — nothing here is
a second, connectivity-only code path, and the same call fires whether the edge just went offline or
just came back online:

| ViewModel | on a connectivity change |
|---|---|
| `HomeViewModel` | `refresh()` — every row |
| `LibrariesViewModel` | `refresh()` — the library list |
| `ItemDetailViewModel` | `refresh()` — the item it is showing (`itemId` comes from its `SavedStateHandle`) |
| `SearchViewModel` | `retry()`, re-running the current term — **only when the field is non-blank**; the text is kept either way |
| `LibraryViewModel` | `retryFacets()` — the filter facets, and **only** them |

**The library grid is the special case.** `getItemsPaged` already re-decides online/offline on every
connection change and hands out a new `Pager` (see §2 above), so the grid swaps to the right side —
server or downloaded — on its own, in either direction, with no wiring: re-triggering it here would
only make it fetch twice. The filter facets are a one-shot call the sheet makes, so they are the part
that stays stale on either edge; they are re-loaded only if the sheet was already opened once
(`areFacetsLoaded || facetsError != null`), because a
screen whose sheet was never opened has nothing stale to replace and still fetches on first open.

---

## The cache — `:core:database` schema v3

`ItemEntity` (`items`) is a single table for every item kind ([D] in the plan — deliberately not
Findroid's four typed tables), split into two halves with different jobs:

- **structured columns** (`type`, `sortName`, `productionYear`, `parentId`/`seriesId`/`seasonId`,
  index numbers, image tags, `genres`, …) exist purely so lists can be *queried*: filtered, sorted,
  searched, grouped by series. Nothing reconstructs an item from them.
- **`dto`** is the complete `BaseItemDto` as JSON. It is what an item is actually rebuilt from, by
  running it through *the same* `ItemMapper` the online path uses — so a cached item and a freshly
  fetched one are indistinguishable downstream, artwork fallback chain and all. That identity is the
  mechanism behind the one seamless UI, not an optimisation.

Image **tags** are stored rather than image URLs: a URL embeds the server's base address, which
changes when the probe rotates to another address.

`ItemSource` is the eviction contract:

- `BROWSE_CACHE` — written through by `OnlineJellyfinRepository` after any successful read.
  Disposable; first thing an eviction pass removes.
- `DOWNLOAD` — downloaded, or a series/season parent of a downloaded episode. **Never evicted, and
  never downgraded.**

`LibraryViewEntity` (`library_views`) caches the user's libraries with the server's ordering
(`sortIndex`), so *My Media* looks the same either way.

Migration: `@AutoMigration(2, 3)`, purely additive, schema exported to
`core/database/schemas/dev.jellyfinnative.core.database.JellyfinDatabase/3.json`.

### Write-through — `:data/cache/BrowseCacheWriter`

One rule, and it is the reason the class exists rather than a `@Transaction` DAO method (which could
only be tested on a device):

> A browse write must never downgrade a download — neither its `source` nor its `cachedAt`.

Keeping the source stops a casual scroll past a downloaded film from making its row evictable and
orphaning gigabytes of files on disk. Keeping `cachedAt` stops that same scroll from reshuffling the
offline "recently downloaded" rows, which order by exactly that column. Metadata *is* still
refreshed.

Writes are fire-and-forget on the application scope: a home screen must not wait on a disk write to
draw, and a failed cache write is a logged warning, never a failed read.

---

## The offline banner and the force-offline setting

`AppScaffold` hosts the single app-wide `OfflineBanner`, above the bottom navigation bar
(DECISIONS.md). Copy and action depend on *why* we are offline:

| state | message | action |
|---|---|---|
| `OFFLINE_NO_NETWORK` | "No network — showing downloaded media" | — |
| `OFFLINE_SERVER_UNREACHABLE` | "Can't reach the server — showing downloaded media" | **Retry** → re-probe |
| `OFFLINE_FORCED` | "Offline mode is on — showing downloaded media" | **Go online** → clears the preference |

`AppPreferences.forceOffline` (DataStore, `:core:datastore`) is the persisted setting; it feeds
`ConnectionStateProvider` and is toggled from the home top bar's overflow menu until Settings lands
at M9.

Both `AppScaffold` and `HomeRoute` read their own `ConnectionViewModel` — a thin view over the
`@Singleton` `ConnectionStateProvider`, so the two instances observe and mutate the same state with
no wiring threaded through the NavHost.

---

## Verifying it

Unit tests (JVM, no device):

| class | covers |
|---|---|
| `ConnectionStateProviderTest` | the state priority matrix, probe triggers, debounce (virtual clock) |
| `ServerReachabilityProbeTest` | candidate rotation, per-address 3 s budget, signed-out short-circuit |
| `DelegatingJellyfinRepositoryTest` | the full online/offline/forced/fallback/401 matrix, the 10 s ceiling, paged-grid swap |
| `OfflineJellyfinRepositoryTest` | every row shape, `getItem` cache hits, missing-item behaviour |
| `BrowseCacheWriterTest` | the never-downgrade-a-download rule, library pruning |
| `ItemEntityMapperTest` | blob round trip against the online mapper, unreadable-blob handling |
| `DataStoreAppPreferencesTest` | force-offline round trip through a real DataStore file |
| `ConnectivityEdgesTest` | the edge semantics: nothing for the initial value (online and offline), `true` on coming back online, `false` on losing the connection, `false` when the user pins offline mode, nothing for a flap between two offline reasons, and `false,true,false,true` for a connection that flaps twice |
| `ConnectivityRefresherTest` | the `:data` handle passes both directions through and adds nothing: fires when the connection comes back, fires when it is lost, fires when the user pins offline mode |
| `HomeViewModelTest`, `LibrariesViewModelTest`, `ItemDetailViewModelTest`, `SearchViewModelTest`, `LibraryViewModelTest` | the expected reload runs exactly once on each edge — coming back online and losing the connection alike — and nothing runs before the change (plus search's blank-query no-op and the grid's leave-it-to-the-`Pager` case); `HomeViewModelTest` additionally covers the empty-library-card rule: a card is kept when its *Latest* call only failed, and dropped — on either an online refresh or on losing the connection — when the call succeeded with nothing behind it |

On device (M6 definition of done, walked by the orchestrator):

- toggling airplane mode swaps the app within ~1 s with no crashes — driven by the default-network
  callback, not by a request timing out;
- stopping the server with Wi-Fi up degrades without a 30 s hang — the 3 s probe demotes the state,
  and the 10 s ceiling covers any call already in flight.
