# M14 track 1 — multi-server & account switching: Claude Design brief

Status: ready to run (2026-08-28). Paste the prompt below into the **Jellyboost Design
System** project on claude.ai/design (same project the 2026 refresh came from), or hand it
to `/design`. Implementation follows the saved canvas per the workflow rule in CLAUDE.md.

Engineering context the design must respect (verified against the code 2026-08-28):

- **Single-active-session backend.** `SessionState`/`SessionStateHolder` model exactly one
  signed-in identity; a "switch" is sign-out-of-A + sign-in-as-B under the hood, even when
  the UI hides the round trip. The design may present instant switching, but must include
  in-progress / failed states.
- **Token store is single-slot today.** `SecureCredentialStore` holds one
  {serverId, userId, token}. Making it multi-slot is a prerequisite backend workstream —
  the design should show the fast path (token still held) *and* the re-auth path
  (chip tap lands on password/Quick Connect for that user).
- **Watch state already survives switching.** `UserDataEntity` is keyed by (itemId, userId);
  the design should make the identity change legible (e.g. a brief, explicit refresh of
  Continue Watching) so the swap reads as intentional.
- **Downloads belong to the device, not the account** (today's sign-out dialog offers
  "delete downloads too"; downloads are not user-scoped). The design must state what the
  Downloads tab shows after a switch — recommended: unchanged, shared-tablet model.
- **Offline data is not yet server-scoped.** `downloads`/`items`/`user_data` carry no
  serverId, and the pending watch-state sync queue drains against whichever server the
  app is currently pointed at. The shared-device Downloads stance depends on the backend
  workstream below landing first; the design should assume it does (server provenance is
  available per download row).
- **Servers are persisted only after a successful sign-in** — a "saved servers" list is a
  list of servers with at least one signed-in account, not merely resolved ones.
- **Mid-playback switching** must be specified: the music queue is process-wide; video may
  be playing. Recommended: confirm-and-stop dialog.

## Prompt

---

Design the **multi-server & account switching** surfaces for Jellyboost (M14 track 1),
extending the existing artboards — reuse `server-setup.html` / `login.html`'s
AuthScreenScaffold language (brand hero, stacked phone layout + two-pane ≥840dp tablet
landscape), the glass/pill component vocabulary, and the dark token set in
`_shared/tokens.css`. Produce phone-portrait and tablet-landscape variants where layout
differs.

**Surfaces to design:**

1. **Server Setup with "Saved servers"** — a new section alongside the live "Discovered on
   this network" list and the manual-address field. Card language mirrors
   DiscoveredServerCard (badge icon, name, last-used address, chevron) but sourced locally.
   Decide: ordering, how a saved server that is also currently broadcasting is merged
   (recommended: one card, "on this network" affordance), first-run empty state, and the
   forget-server gesture (recommended: overflow/long-press → confirm).
2. **Per-server saved accounts** — after tapping a saved server: avatar chips for accounts
   previously signed in on this device (local `UserEntity` data). Must be visually distinct
   from Login's *live* public-users row (different source, different meaning). Each chip
   needs three states: fast path (token held — one tap switches), needs re-auth (badge;
   tap lands on password/Quick Connect prefilled), and remove/forget.
3. **Quick-switch entry point** — pick and design one: a bottom-sheet switcher off the
   Settings account rows (recommended), or a chrome-level affordance. States: list of
   (server → accounts), switch-in-progress, failure (expired token → falls through to
   re-auth), "add another server" escape hatch to Server Setup.
4. **Settings ▸ Account section, extended** — today: two read-only rows (user, server) +
   Sign out. Add: Switch account, Manage servers entries; keep the sign-out dialog's
   "delete downloads too" checkbox precedent.
5. **Edge-case states** — switching while video/music is playing (confirm-and-stop dialog
   copy), post-switch Home refresh moment (identity change made legible), expired-token
   chip badge.
6. **Downloads tab with rows from a non-current server** — the shared-device union view,
   specified per row state:
   - Completed downloads stay visible and playable regardless of which server is active
     (playback is local). Rows carry a server provenance label **only when downloads
     from more than one server exist on the device** — single-server users never see it.
   - Tapping a foreign-server download opens the offline/downloaded detail rendering
     (local data), never a live query of the current server; server-dependent actions
     are absent there, exactly as when offline.
   - Unfinished/queued downloads from a non-current server are held, shown as
     "Waiting for <server>", resuming automatically when that identity is active again.
   - Delete always works, including foreign rows.

**Constraints:** dark theme tokens as-is (#101010 ground, glass fills, #00A4DC/#AA5CC3
brand, white selected pills); switching is sign-out+sign-in under the hood, so every
instant-looking path needs an in-progress and a failure state; downloads and their tab are
unaffected by a switch (shared-device model) — say so in the UI only where the user would
otherwise wonder; per-account watch state survives switching and the design should let
that show (Continue Watching refreshes to the new user).

---

## After the canvas is saved

Implementation order: (1) the backend workstream — no UI, and a hard prerequisite for
any switching surface (verified against the code 2026-08-28; without it, switching
loses offline watch state and cross-wires servers):

- Multi-slot `SecureCredentialStore` + additive DAO list queries (`ServerDao` list-all,
  `UserDao` list-per-server).
- `serverId` column on `downloads`, `items`, `user_data`, `library_views` (Room
  auto-migrations; root-cause-at-the-data-layer house rule). Resolution by
  `(serverId, itemId)` wherever a bare GUID primary key could collide across servers.
- `UserDataDao.getPendingSync()` keyed by the active `(serverId, userId)` — today it is
  `WHERE toBeSynced = 1` with no identity filter, so a switch drains one identity's
  pending rows against the other's server, and the syncer's 404-abandon path silently
  destroys the offline change. Rows for a non-current identity are never abandoned;
  they wait for that identity's next online stretch.
- Download queue gating: rows whose `serverId` ≠ current session are held, not run
  (download URLs are rebuilt per run from the live ApiClient, so an unguarded resume
  fetches from the wrong server).
- `DownloadedMetadataRefresher` / `SubtitleSidecarTopUp` filter to the current server's
  rows.
- The switch path runs sign-out hygiene minus credential deletion (browse-cache wipe
  scoped to the outgoing identity — otherwise HYG-2 reopens via the no-round-trip
  switch).
- Settings "delete downloads too" filters by the signing-out `(serverId, userId)`
  instead of deleting every identity's rows.
- `SessionGate.ensureSession()` verifies *which* identity the client is on, not just
  that a token is non-blank.

(2) surfaces above; (3) device DoD per PLAN.md M14: second server added, switched both
directions, per-account watch state intact — including a dirty offline sync queue
surviving a switch.
