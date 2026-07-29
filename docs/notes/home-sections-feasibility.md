# Feasibility note — server-configured home screen sections

*2026-07-29 · answers docs/POLISH.md "New run": "Find if we can fetch the homescreen
section list as configured for the user on server side."*

> **STATUS: IMPLEMENTED (2026-07-29).** Everything below held up; the "suggested v1
> shape" was built essentially as described, with one deviation (the layout lives in a
> `HomeLayoutRepository` of its own rather than in `JellyfinRepository` /
> `OnlineJellyfinRepository` — it has an offline answer that is a cache rather than a
> Room query, and it must never fail). See `docs/features/home.md` for the shipped
> behaviour and DECISIONS.md, 2026-07-29, for the plan divergence.

## Verdict

**Possible, and precedented.** jellyfin-web's Settings → Home configuration is stored
server-side in DisplayPreferences and is readable with one call from the SDK we already
ship (`jellyfin-sdk-kotlin` 1.8.12). jellyfin-androidtv honors it with exactly this
call; Findroid and Streamyfin do not (they use app-local section config).

## The call

```kotlin
apiClient.displayPreferencesApi.getDisplayPreferences(
    displayPreferencesId = "usersettings", // any other string gets MD5-hashed into an unrelated record
    client = "emby",                       // legacy partition key — REQUIRED to read the web-configured record
).content.customPrefs                      // Map<String, String>
```

Both magic strings are load-bearing: prefs are stored per `(userId, itemId, client)`,
and every client that shares the user's web-configured home sections passes the literal
`"emby"` (verified in jellyfin-web, jellyfin-androidtv, and the neighboring
jellyfin-android repo's `Constants.DISPLAY_PREFERENCES_CLIENT_EMBY`). Using our own
client name would read/write an isolated, always-empty record.

## The contract

`customPrefs` carries `homesection0` … `homesection9` (`MAX_SECTIONS = 10` in web).
Values (`HomeSectionType`, identical server- and web-side):

| value | meaning | our status |
|---|---|---|
| `smalllibrarytiles` / `librarybuttons` | My Media | ✅ have (always-on libraries row) |
| `resume` | Continue Watching (video) | ✅ have |
| `nextup` | Next Up | ✅ have |
| `latestmedia` | Latest per library | ✅ have |
| `resumeaudio` / `resumebook` | Continue Listening / Reading | ❌ out of v1 scope (video-only) |
| `livetv` / `activerecordings` | Live TV rows | ❌ out of scope |
| `none` | empty slot | trivial |

**Critical edge case:** a user who never opened Settings → Home has *no* `homesectionN`
keys at all — missing keys mean "apply client defaults", which must be hardcoded
identically to jellyfin-web's `DEFAULT_SECTIONS`:

```
0 smalllibrarytiles · 1 resume · 2 resumeaudio · 3 resumebook · 4 livetv
5 nextup · 6 latestmedia · 7-9 none
```

(plus a legacy `"folders"` value web treats as an alias for slot 0's default).

Row *type and order* is all DisplayPreferences gives. Per-library inclusion/order
(`LatestItemsExcludes`, `MyMediaExcludes`, `OrderedViews`, `HidePlayedInLatest`) lives
in `User.Configuration` via `GET /Users/{id}` — full web parity needs both calls.

## Server-version notes

- Contract unchanged through 10.11 (the EF-Core `HomeSection` entity landed in 10.10
  but the wire format is still the flat `customPrefs` map). Our test-server server
  (10.11.11) serves it as described.
- A real server-driven home-sections API (`/Users/{id}/HomeSections`,
  jellyfin/jellyfin#13820) was **closed unmerged** in July 2026 — nothing new to build
  against.

## v1 shape — as built

Fetched once per full Home load (initial, pull-to-refresh, connectivity edge — no
polling), the 10 slots resolved with the per-slot default fallback, and only the
supported types rendered, in the configured order. The user's ordering and hiding of
My Media / Continue Watching / Next Up / Latest is honored; unsupported types are
carried through the resolution (so they keep the rows after them in place) and skipped
at render time. Rows the layout hides are not even *fetched*.

| Piece | Where |
|---|---|
| `HomeSectionType` (the ten values + forgiving decode) | `:core:common` |
| `resolveHomeSections`, `DEFAULT_HOME_SECTIONS` (jellyfin-web's `DEFAULT_SECTIONS`) | `:data`, `homelayout/HomeSections.kt` |
| `HomeLayoutRepository` (fetch → resolve → persist; never fails) | `:data`, `homelayout/` |
| `HomeLayoutStore` / `SharedPreferencesHomeLayoutStore` (`home_layout` prefs file) | `:core:datastore` |
| `HomeUiState.sections` + row iteration | `:feature:home` |

Deviation from the suggestion above: it is **not** a method on `JellyfinRepository`.
That interface is the browse contract, split online/offline and delegated per call;
this is one piece of configuration whose offline answer is a cache of the last layout
seen rather than a Room query, and which must never surface a failure. Keeping it
separate left both browse implementations untouched.

Reference implementation pattern: jellyfin-androidtv `UserSettingPreferences` +
`HomeRowsFragment`; nearest local precedent for the SDK call:
`../jellyfin-android/.../player/PlayerViewModel.kt:184-187`.

## Deliberately still out of scope

- **Per-library inclusion/order** from `User.Configuration` (`LatestItemsExcludes`,
  `MyMediaExcludes`, `OrderedViews`, `HidePlayedInLatest`) via `GET /Users/{id}`. Full
  web parity needs this second call; the app currently shows every movie/TV library, in
  the server's own order, in both *My Media* and the *Latest* rows.
- **`librarybuttons` as large buttons.** Both it and `smalllibrarytiles` render as the
  existing tile row; a layout containing both draws that row once (two items under one
  lazy-list key would crash).
- **Writing the configuration back** — no home-layout editor in the app. The one place
  to change it stays jellyfin-web's Settings → Home.
