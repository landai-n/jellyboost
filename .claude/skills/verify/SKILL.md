---
name: verify
description: Run the full quality gate (ktlint, detekt, unit tests, accessibility lint, debug assemble) for Jellyboost, fix any failures, and mark the tree as freshly verified. Use before every commit, or whenever asked to "verify", "check the build", or confirm the code is green.
---

# /verify

Run the project's full quality gate from the repo root and get it fully green before
declaring the task done.

## Steps

1. From the repo root, run:

   ```bash
   source "../env.sh" && ./gradlew ktlintCheck detekt testDebugUnitTest :app:lintDebug assembleDebug
   ```

   Judge the result ONLY by the explicit `BUILD SUCCESSFUL` / `BUILD FAILED` line in the
   command's own output — never by a wrapper's exit code or a task notification (both have
   reported success for failed runs; don't pipe through `tail -N`, which can clip the verdict).

   Then run the audit-derived guardrail scripts (each sub-second, all must pass):

   ```bash
   python3 scripts/check_docs.py && python3 scripts/check_identifiers.py && \
   python3 scripts/check_patterns.py && python3 scripts/check_redaction.py
   ```

   `check_patterns.py` is a ratchet: if it flags a file you changed, fix the code rather
   than updating the baseline; a baseline update is allowed only as a deliberate, explained
   act in the same commit. `check_identifiers.py` failing means a personal/infrastructure
   identifier is in the tree — remove it, never work around the check.

2. If any task fails:
   - Read the failure output carefully (ktlint formatting, detekt findings, test
     failures, lint errors, or compile errors).
   - **Fix the code.** Never weaken, delete, disable, or `@Ignore` a test just to make
     it pass.
   - If a test is genuinely wrong (asserts behavior that contradicts `docs/PLAN.md`, or
     was written against a since-changed requirement), **stop and run `/diverge` first**
     to log the discrepancy before changing or removing the test. Only then adjust it.
   - Re-run the full command above (all five tasks) after fixing — don't assume a fix
     for one task didn't affect another.

3. Repeat until `ktlintCheck`, `detekt`, `testDebugUnitTest`, `:app:lintDebug` and
   `assembleDebug` are all green in the same run.

4. On success, mark the tree as verified:

   ```bash
   mkdir -p .claude/state && touch .claude/state/last-verify && rm -f .claude/state/verify-stale
   ```

5. Report a one-line summary, e.g.:
   `verify: green (ktlint 0, detekt 0 findings, tests 42/42 passed, lint 0 errors, assembleDebug OK)`
   or, if you had to fix things along the way, a one-line summary of what failed and was
   fixed before going green.

## Notes

- **`:app:lintDebug` is the accessibility gate** (accessibility audit 2026-08-05, CR-7).
  Severities live in `config/lint/lint.xml`, one file for all 17 modules: the a11y checks
  are errors, the four issue families the project has never enforced are warnings, and an
  unknown issue id is an error so the list cannot rot silently. `:app` and not the
  whole-project `lintDebug` — `checkDependencies = true` makes the app's run cover every
  library in one analysis pass (~80s cold, ~2s when nothing changed).
- The **instrumented** accessibility suite (`*/src/androidTest`) is *not* part of this
  gate — it needs a connected device. Run it at milestone DoD instead:
  `./gradlew connectedDebugAndroidTest` (wake the tablet's screen first).

- This is what the `pre-commit-gate.sh` hook checks for before allowing `git commit`, and
  what `session-start.sh` reports as stale/fresh at the start of a session. Running this
  skill is the only way to clear `.claude/state/verify-stale` and refresh
  `.claude/state/last-verify`.
- Do not touch `.claude/state/last-verify`/`verify-stale` by hand except via the commands
  above — they are gitignored state markers, not artifacts to edit.
