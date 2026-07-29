# Feasibility note — server-configured home screen sections

*2026-07-29 · answers docs/POLISH.md "New run": "Find if we can fetch the homescreen
section list as configured for the user on server side." Research only — nothing here
is implemented yet; building it needs a DECISIONS.md entry (the plan's Home section
doesn't mention DisplayPreferences).*

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

## Suggested v1 shape (when/if built)

Fetch prefs once per Home load (cache last-known for offline); resolve the 10 slots
with the default fallback; render only the types we support, in the configured order —
i.e. the user's ordering and hiding of My Media / Continue Watching / Next Up / Latest
is honored, unsupported types are skipped. New repository method in `:data`
(`OnlineJellyfinRepository`), plumbed into `HomeViewModel.fetchRows`. Reference
implementation pattern: jellyfin-androidtv `UserSettingPreferences` +
`HomeRowsFragment`; nearest local precedent for the SDK call:
`../jellyfin-android/.../player/PlayerViewModel.kt:184-187`.
