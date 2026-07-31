# Polish punch list

All items below were addressed and device-verified on the test tablet on 2026-07-29
(two verification walks; evidence in the walk reports). Divergences and design
decisions are logged in DECISIONS.md; per-feature details in docs/features/.

# Bugs founds — all fixed ✅
- ~~Search page top texts are showing under the system icons~~ — fixed by the combined app bar (search content now sits below it).
- ~~Switching to offline mode still lists online media on the Home screen~~ — refresh signal now fires on both connectivity edges; verified switching in the same frame.
- ~~System status-bar text/icons drawn in black over the dark UI~~ — system bars pinned to dark style (light icons) incl. the splash window; verified in both system themes.
- ~~Downloading a season fails with error 400~~ — a season/series tap now expands into one download per episode; folder items can never reach the file endpoint; verified E1…E26 in order.
- ~~Download speed showing crazy numbers (100–180 MB/s)~~ — speed measured over ≥1 s windows; verified accurate against byte-count ground truth (~34 MB/s real).
- ~~Download pausing doesn't work~~ — PAUSED no longer overwritten to QUEUED on cancellation; verified bytes frozen across 20 s and clean resume.
- ~~Fresh install says "can't reach the server" right after a successful login (until restart)~~ — the reachability probe ran before login and its verdict was never invalidated; the connection state now re-probes on every session change (sign-in, restore, sign-out, server switch).

# Polishing — all done ✅
- ~~Offline status bar takes real estate~~ — now a status icon in the app bar (per-reason icon + tap-for-snackbar with action; snackbar auto-dismisses).
- ~~Media description/metadata missing offline~~ — three fixes: browse writes can no longer gut a download's rich metadata; a full detail fetch repairs it; a standing sync refreshes all downloaded items' metadata each online stretch (also picks up server-side edits).
- ~~Media title showing twice~~ — title only in the content header now.
- ~~Media hero image positioning~~ — explicit center-crop.
- ~~Downloads page duplicate movie header~~ — only series get group headers; episode rows under a header drop the series prefix.
- ~~"Wifi only" toggle spacing~~ — fixed (toggle now lives in the storage header row under the combined bar).
- ~~No delete confirmation~~ — confirmation dialogs on the Downloads screen and the detail screen.
- ~~Combined navbar and top bar waste space~~ — one combined top bar for top-level destinations; bottom nav removed (~140dp reclaimed).
- ~~Settings subcategories look clickable~~ — choice-group labels restyled as subsection headings.
- ~~Scrolling media lists not smooth~~ — contentType everywhere, per-cell subcomposition removed, Coil memory+disk cache configured, artwork requested at display resolution. Measured: release build hits 0.49 % janky / 7 ms median at 90 Hz on a 500-poster grid — the residual lag is debug-build overhead (judge scroll feel on the release build; R8 + baseline profiles land in M10).

# Next steps — done ✅
- ~~General quality setting for offline downloads~~ — Original/High/Medium/Low (bitrate-capped H.264/AAC in MKV — the server's streamed mp4 mux is not playable as a file); quality stamped per download row (schema v5); transcodes restart rather than resume, by design. Verified: LOW episode downloads as `(low).mkv` and direct-plays offline.

# Second run — done ✅
- ~~Download size estimate are off my a wide margin~~ — `DownloadEnqueuer.expectedBytes` now uses min(quality cap, source `mediaSources[0].bitrate`) when the source bitrate is known/positive, falling back to the cap when it isn't; ORIGINAL unchanged. Unit-verified (3 tests, one re-pinned with its source bitrate above the cap); device-verified 2026-07-29 — see the third-run walk notes below (working as designed; capped rows now read "up to X").
- ~~Media banner could take more space in portrait mode~~ — banner is now 0.40 × viewport height in portrait, coerced between the old width-derived value (220dp narrow / 320dp wide) and 560dp; landscape unchanged. Device-verified on the test tablet, both orientations: portrait now ~449dp (was 220dp) with the header directly below; landscape confirmed unchanged at exactly 320dp.
- ~~Cancelling an in-progress season download silently deletes the episodes that already finished~~ — resolved better than a confirmation dialog: Cancel on an in-flight season now deletes only queued/transferring/paused/failed rows and keeps `Downloaded` ones, with a snackbar ("Download cancelled — N finished episode(s) kept"); Remove still deletes everything. A partly-kept season aggregates back to NotDownloaded, so the button then offers Download for the missing episodes (pre-existing, test-pinned behavior). Unit-verified (4 tests added/updated); device-verified 2026-07-29 — snackbar and kept files confirmed, see the walk notes below.

# New run — done ✅ (all device-verified 2026-07-29)
- ~~The top back arrow is too close to the system icon~~ — the detail screen's overlaid back button was the only back arrow with no status-bar inset; it (and the same screen's snackbar, which could sit under the nav bar) now pad out of the system bars. Commit b0177c6.
- ~~The storage path should be configurable (e.g., to an SD card)~~ — Settings → Downloads gains a *Storage location* picker backed by secondary volumes (`getExternalFilesDirs`, no SAF): volumes shown with free space, choice persisted as a stable token, ejected card falls back to internal with a visible warning, and the plan's v1 guard is enforced (switch with downloads present requires "Delete and switch"). The group hides itself when only one volume exists — so it only appears on the tablet with an SD card mounted. Commit 47bf76d.
- ~~Changing watched state of a media item doesn't update the home screen, this needs a fix~~ — two layers: a played change instantly evicts the item from *Continue watching*/*Next up* (request-free, works offline), and a debounced silent re-fetch of just those two rows fixes what a patch can't know (the next episode for *Next up*, un-marking, series/season toggles). Commit 2dc3e4e.
- ~~Find if we can fetch the homescreen section list as configured for the user on server side~~ — yes, and now **implemented**: Home renders the row order/visibility configured in jellyfin-web (Settings → Home), read from DisplayPreferences (`"usersettings"`/`client="emby"`) with jellyfin-web's per-slot defaults and an offline-persisted layout cache; hidden rows aren't even fetched. Research in `docs/notes/home-sections-feasibility.md`; commits 07ccdbe + febd896. Device-verified: the call fires on load and the rows render in the resolved order.

Device-verification notes (2026-07-29, test tablet, signed in after the device-id fix):
- Back arrow: clear of the status bar on movie/episode/series detail, portrait and landscape (45 px clearance under a 70 px inset).
- Watched → Home membership: marking HotD S3:E1 watched advanced *Next Up* to S3:E2 via the debounced refresh; un-marking restored server state. Found & fixed along the way: tab re-selection stacked duplicate back-stack entries because `popUpTo(findStartDestination())` no-ops on a signed-out launch (11 `Ignoring popBackStack` lines in the pre-fix log) — every watched toggle fired double refresh pairs and every tab tap reloaded Home. Now pops to `Routes.Home` (649a7c8); post-fix log shows exactly one NextUp+Resume pair per toggle and zero reloads on tab re-selection.
- Season cancel: "Download cancelled — 2 finished episodes kept." verbatim; finished files intact on disk, queue cleared, button back to *Download*. Confirmed twice.
- Size estimate: the min(cap, source-bitrate) fix is working as designed, but on sources *above* the cap the figure is a deterministic ceiling and the encoder undershoots on easy content (552 MB estimated vs 229–306 MB landed on 4.3 Mbps animation at the 3.2 Mbps LOW cap; ORIGINAL was exact, 741.0 vs 741.1 MB). Queued/in-progress rows now say **"up to 552,4 MB"** for capped qualities (ee490d0); whether estimation can genuinely beat the ceiling was investigated and answered: mid-flight yes (client-side MKV timestamp projection), pre-flight no — see `docs/notes/download-size-estimation.md`.
- Storage picker: correctly hidden with a single volume (no SD card in the tablet); storage line shows used/free. SD-card row rendering awaits a card being inserted.

## Phone sizes (2026-07-31 sweep — simulated viewport on the test tablet)

No phone hardware exists in the project, so a phone viewport was simulated on the test tablet:
`adb shell wm size 1080x2400 && adb shell wm density 480` → **360×800dp portrait**, size swapped to
`2400x1080` for **800×360dp landscape**; `wm size reset && wm density reset` afterwards
(auto-rotate pinned off for the session and restored). Every screen was screenshot-audited via
`adb exec-out screencap` in portrait, plus the orientation-sensitive subset in landscape.

**Fixed (each with a DECISIONS entry, a JVM sizing test, and a phone-width `@Preview`):**
- Libraries tab: one full-width column at 360dp → 150dp adaptive floor below 600dp (2×~158dp).
- Item detail, phone landscape: wide/tablet layout + 320dp banner on a ~330dp-tall viewport →
  wide layout now also requires ≥480dp height; short-landscape banner = 0.5 × height (~165dp).
- Episode rows: 160dp thumb left ~110dp of text at 360dp → 128dp thumb below 480dp width.
- Player bottom bar: five labelled sheet buttons fit an 800dp bar with zero slack → icon-only
  below 840dp of measured row width (tablet portrait intentionally included; tablet landscape
  keeps labels).
- SyncPlay queue sheet: fixed 420dp list cap exceeded the phone-landscape sheet → min(420dp,
  60% of sheet height).
- Downloads queue rows: four 48dp actions left ~64dp of title ("Hous…") → two-tier layout below
  480dp (title/progress full-width, actions end-aligned underneath).

**Verified fine as-is (portrait 360×800 unless noted):** server setup, login (keyboard never
covers the fields), Home rows, library item grid (2 columns; 5 in landscape), search, movie
detail portrait (banner 40% of height, buttons FlowRow-wrap into two rows), downloads
header/tabs/bulk bar and Downloaded rows, settings, SyncPlay groups screen + create dialog +
group/audio sheets, player transport + gesture zones (landscape), overflow menu.

**Re-verified on the fixed build:** all six fixes at phone sizes, then a tablet-native pass
(Libraries 3 columns portrait, detail wide layout + 40% portrait banner, player landscape bar
still labelled) — zero tablet regression. Screenshot evidence in the session scratchpad
(`sweep/*.png`); skip-intro button padding (F5 in the plan) was unverifiable — no media with
intro segments on the dev server — and was deliberately left unchanged.

### Round 2–3 (same day, user feedback on the fixed build)
"Screens work in theory but use the space inefficiently / don't look polished":
- Home: thumb cards 210dp → 160dp below 600dp (two full cards + a peek at 360dp).
- Detail: portrait banner 0.40 → 0.32 of height on compact; actions became one
  edge-to-edge row (Play labelled + three icon-only circles, 12/16dp compact paddings —
  Material's 24dp default ellipsized "Resume"); overview clamps to 5 lines with
  tap-to-expand. All device-verified at 360×800dp; wide/tablet paths byte-identical.
Commits: fc0ffb5, 57e9c1c, 9943008, e5d9ee2.
