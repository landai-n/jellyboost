---
name: document-feature
description: Create or update docs/features/<name>.md for a feature, and refresh docs/ARCHITECTURE.md if module structure changed. Use after adding or materially changing a feature. Args - feature name.
---

# /document-feature

**Arguments:** feature name (e.g. `home`, `downloads`, `offline-sync`).

## Steps

1. Create or update `docs/features/<name>.md` with these sections:
   - **What it does** — a short user/product-level description.
   - **Key classes** — the load-bearing classes/files for this feature, with their paths
     (e.g. `:feature:downloads` `DownloadRepository`
     (`data/downloads/src/main/kotlin/.../DownloadRepository.kt`)).
   - **Server endpoints used** — which jellyfin-sdk-kotlin API calls this feature makes
     (e.g. `itemsApi.updateItemUserData`, `libraryApi.getDownloadUrl`).
   - **Offline behavior** — how this feature behaves when offline (if it has no offline
     behavior, say so explicitly rather than omitting the section).
   - **Test coverage** — what's tested and where (unit tests per repository/ViewModel/
     mapper per `docs/PLAN.md`'s testing policy), and any notable gaps.

2. If this change altered the module map (new module, moved responsibility between
   modules, changed a module's dependencies in a way that affects the architecture),
   update the module table in `docs/ARCHITECTURE.md` to match. If module structure is
   unchanged, leave `docs/ARCHITECTURE.md` alone.

3. Keep the doc grounded in what the code actually does today — don't describe planned
   future behavior as current; note it as a known gap instead if relevant.

## Notes

- This is normally run as part of `/checkpoint` for feature-shaped changes, but can be run
  standalone to backfill or refresh documentation for an existing feature.
- Reference `docs/PLAN.md`'s "Screens" and module skeleton sections for the intended shape
  of a feature before describing what was actually built, so divergences are visible.
