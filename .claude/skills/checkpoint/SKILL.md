---
name: checkpoint
description: Verify, document, and commit the current coherent chunk of work with a small conventional commit. Use at least once per completed sub-task, or when asked to "checkpoint", "commit this", or "save progress".
---

# /checkpoint

Land the current unit of work as a small, well-documented, verified commit.

## Steps

1. **Run `/verify`.** It must be fully green (ktlint, detekt, unit tests, assembleDebug).
   Never commit with failing or stale verification — if `/verify` cannot be made green,
   stop and surface why instead of committing anyway.

2. **Add/refresh KDoc** on any new or materially changed public API (classes, functions,
   properties exposed outside their module or file) touched by this change. Skip
   private/internal implementation details that don't need it.

3. **Update docs for the touched feature and current state:**
   - `docs/features/<feature>.md` for whichever feature area this change affects (create
     it via `/document-feature` if it doesn't exist yet and the change is feature-shaped).
   - `STATUS.md`: update the **Done**, **Next**, and **Known issues** sections to reflect
     reality after this change.

4. **Commit a coherent change set.** `git add` only the files that belong to this unit of
   work (avoid `git add -A`/`git add .` sweeping in unrelated changes). Write a small,
   scoped, conventional commit message:

   ```
   <feat|fix|refactor|test|docs|chore|build>(<scope>): <summary>
   ```

   Keep the commit focused — prefer several small checkpoints over one large one.

## Notes

- The `pre-commit-gate.sh` hook will deny the `git commit` if verification is stale or the
  message lacks a conventional prefix — this skill exists precisely to satisfy that gate,
  so do the steps in order rather than trying to commit first.
- If the divergence from `docs/PLAN.md` was logged via `/diverge` earlier in this task,
  make sure the `DECISIONS.md` entry is included in (or already committed alongside) this
  checkpoint.
