---
name: verify
description: Run the full quality gate (ktlint, detekt, unit tests, debug assemble) for jellyfin-native, fix any failures, and mark the tree as freshly verified. Use before every commit, or whenever asked to "verify", "check the build", or confirm the code is green.
---

# /verify

Run the project's full quality gate from the repo root and get it fully green before
declaring the task done.

## Steps

1. From the repo root, run:

   ```bash
   source "../env.sh" && ./gradlew ktlintCheck detekt testDebugUnitTest assembleDebug
   ```

2. If any task fails:
   - Read the failure output carefully (ktlint formatting, detekt findings, test
     failures, or compile errors).
   - **Fix the code.** Never weaken, delete, disable, or `@Ignore` a test just to make
     it pass.
   - If a test is genuinely wrong (asserts behavior that contradicts `docs/PLAN.md`, or
     was written against a since-changed requirement), **stop and run `/diverge` first**
     to log the discrepancy before changing or removing the test. Only then adjust it.
   - Re-run the full command above (all four tasks) after fixing — don't assume a fix
     for one task didn't affect another.

3. Repeat until `ktlintCheck`, `detekt`, `testDebugUnitTest`, and `assembleDebug` are all
   green in the same run.

4. On success, mark the tree as verified:

   ```bash
   mkdir -p .claude/state && touch .claude/state/last-verify && rm -f .claude/state/verify-stale
   ```

5. Report a one-line summary, e.g.:
   `verify: green (ktlint 0, detekt 0 findings, tests 42/42 passed, assembleDebug OK)`
   or, if you had to fix things along the way, a one-line summary of what failed and was
   fixed before going green.

## Notes

- This is what the `pre-commit-gate.sh` hook checks for before allowing `git commit`, and
  what `session-start.sh` reports as stale/fresh at the start of a session. Running this
  skill is the only way to clear `.claude/state/verify-stale` and refresh
  `.claude/state/last-verify`.
- Do not touch `.claude/state/last-verify`/`verify-stale` by hand except via the commands
  above — they are gitignored state markers, not artifacts to edit.
