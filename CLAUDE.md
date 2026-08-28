# Jellyboost — agent instructions

100% native Android Jellyfin client (Kotlin, Jetpack Compose/M3, Hilt, Room, Media3).
The approved plan is **`docs/PLAN.md`** — it is the source of truth for architecture,
scope, and milestones.

## Governance (HARD RULES)
1. Before any non-trivial implementation decision, check it against `docs/PLAN.md`.
2. Any divergence from the plan MUST be logged in `DECISIONS.md` (date, scope, plan-said,
   done-instead, reason) **before or with** the diverging change. Use the `/diverge` skill.
   No silent divergence.
3. Keep `STATUS.md` current: milestone, done/next, known issues. Update at every checkpoint.
4. Never weaken or delete a test to make it pass — if a test is genuinely wrong, log via
   `/diverge` first.
5. **Identifiers:** never write personal names, server hosts/IPs, or device identifiers
   anywhere in the repo (code, docs, mocks, fixtures, commit messages). Use the established
   placeholders: "test-server", "test tablet", "the OEM ROM", `192.168.1.10`, generic first
   names for account fixtures. Two full history rewrites came from breaking this; the gate is
   `scripts/check_identifiers.py` (denylist lives outside the repo, next to `env.sh`).
6. `scripts/check_patterns.py` is a **ratchet** over audit-hazard patterns (plain
   `runCatching`, hardcoded `Dispatchers.*`, `runBlocking`, `!!`, no-locale `.uppercase()`,
   `composed {}`, …). If it flags your change, fix the code; updating
   `scripts/pattern-baseline.json` is allowed only as a deliberate, explained act in the
   same commit. Never work around it.

## Audit-derived review checklist (hazards no tool catches — check before finishing any change)
Distilled from the 2026-07…2026-08 audits; the recidivist classes:
- **Fix the sibling too.** Most reintroduced bugs were one branch of a duplicated pattern
  getting the fix while its twin didn't (compact vs wide layout, audio vs subtitle path,
  local vs cast handle, one volume vs two). Before closing: grep for the pattern's siblings.
- **Classify failures.** Any new catch/error path: is this failure transient (retry/backoff,
  attempt counter) or permanent (clear state, honest copy)? Defaulting everything to
  permanent-ERROR — or to silent-ignore — caused repeated data-loss findings. And rethrow
  `CancellationException` first in every broad catch (`runCatchingUnlessCancelled`).
- **DAO read-then-write = transaction.** Any read-modify-write across suspension points goes
  through `TransactionRunner.inTransaction` or a SQL-guarded statement (the
  `markDownloadingIfRunnable` pattern). Check-then-act on shared state needs an identity guard.
- **State writes need a verified source.** Progress/position reporters must refuse a snapshot
  whose source identity can't be confirmed (stale `detachedSource`, wrong `mediaId`, replayed
  nav-arg) — verify identity before writing user-visible or server state.
- **Don't park state where its lifetime is wrong.** `remember` inside an
  `AnimatedVisibility`/conditionally-composed host dies with it; hoist to the owner whose
  lifetime matches (the PlayerPanel lesson). Saveable if it must survive recreation.
- **Every doc claim needs a test.** If a KDoc/comment/user-copy asserts a guarantee
  ("always retried", "released on dispose", "restored after death"), pin it with a test in
  the same change — several audit bugs were comments describing code that didn't exist.
- **Hot-flow hygiene.** New `Flow` exposure: `distinctUntilChanged`+`flowOn` (repository),
  `shareIn/stateIn` for callback flows collected more than once; never key expensive work
  (file walks, blob parses, full-table scans) on a per-progress-tick emission.
- **Compose params: pass what it draws.** Whole-UiState params defeat skipping under strong
  skipping; pass scalars/narrow value types, remember callback bundles once.
- **Secrets never reach a `toString()`, log, or URL.** Any new type carrying a token,
  password, or server-issued URL gets a redacting `toString()` pinned by a test
  (NET-02/SEC-12 precedent); URLs are signed with headers, not query params, wherever the
  consumer allows.
- **Await what you start.** A returned `Operation`/`ListenableFuture`/`PendingResult`/`Job`
  that is dropped is a fire-and-forget bug waiting for its interleaving (STAB-04, DL-09,
  CAST-03) — await it, or document precisely why abandonment is safe.
- **Dynamic a11y ships with the surface.** New async state (loading, errors, progress,
  group membership) announces via `liveRegion`/`stateDescription`/`progressBarRangeInfo`;
  new cards/rows merge descendants with one spoken sentence; new screens get an ATF
  `androidTest` case and survive a fontScale-2.0 pass. Static lint cannot see Compose
  semantics — the instrumented suite is the only gate that can (CR-1..6).
- **A gate that isn't wired is a wish.** Any new script/config/check must be referenced by
  `/verify`, the pre-commit hook, or CI in the same commit that adds it.

## Build environment
- **`gradlew-remote` is the Gradle entry point — use it everywhere instead of `./gradlew`.**
  It sets the toolchain environment itself (no `source "../env.sh"` needed, and it resolves
  correctly from a worktree, where that relative path never did), runs the build on a
  configured build host when one is reachable, and falls back to the local `./gradlew`
  transparently when it is not. Tasks that need the attached tablet (`install*`,
  `connected*`, baseline profile) always run locally. Setup for a new machine lives outside
  the repo, next to `env.sh`.
- Build: `gradlew-remote assembleDebug`
- Quality: `gradlew-remote ktlintCheck detekt testDebugUnitTest :app:lintDebug`
  (`:app:lintDebug` is the accessibility gate — severities in `config/lint/lint.xml`.)
- Instrumented a11y suite (device only, milestone DoD — not part of `/verify`):
  `gradlew-remote connectedDebugAndroidTest`.
- Install: `gradlew-remote installDebug` (device/emulator via adb; always local).
- If `gradlew-remote` is not installed on this machine, `source "../env.sh" && ./gradlew …`
  from the repo root is the equivalent — but never use it to route around a wrapper failure.

## Test device
A tablet is connected via adb and available for installs, instrumented tests, and
milestone DoD verification: **test tablet** (model [redacted-model], codename `[redacted]`),
Android 16 / API 36, serial `[redacted-serial]`. Use it for `installDebug`,
`connectedDebugAndroidTest`, and manual playback/download checks. Being a tablet,
also sanity-check tablet/landscape layouts when touching UI.

## Subagent delegation (DEFAULT)
Implementation work is delegated to cheaper-model subagents by default (user directive,
usage optimization); the main (Fable) context orchestrates, reviews, and verifies.
- `model: "opus"` — fiddly/iterative work: build system changes, tricky debugging,
  complex features (player, download pipeline, sync logic).
- `model: "sonnet"` — mechanical work from a precise spec: boilerplate, DAOs/entities,
  tests from templates, UI from an established design system, docs.
- Every subagent prompt must include the governance rules above (check `docs/PLAN.md`,
  log divergences in `DECISIONS.md`, never weaken tests) and the build-env note
  (`gradlew-remote` is the entry point; never substitute bare `./gradlew`).
- The orchestrator independently verifies results (`/verify`) before committing —
  never trust an agent's green-build claim.

## Workflow expectations
- **Strong UI changes / new UI go through Claude Design first.** Before implementing a
  new screen, a redesign, or any significant visual reshape, the design is settled on a
  Claude Design canvas: either the orchestrator seeds one via the `/design` skill, or it
  hands the user a ready-made prompt to run in Claude Design and waits for the result.
  Implementation follows the saved canvas. Minor tweaks and non-visual work are exempt.
- `/verify` before every commit (the pre-commit hook enforces it).
- `/checkpoint` at least once per completed sub-task: verify → docs → small conventional commit
  (prefixes: feat/fix/refactor/test/docs/chore/build).
- `/adversarial-review` at the end of every feature/fix wave, before its branch merges to
  main (or before the wave's final checkpoint when working directly on main): the
  multi-agent semantic review gate. Division of labour is strict to avoid duplicated
  checks — `/verify` and the guardrail scripts own everything mechanical; this gate owns
  only the audit-checklist classes no tool catches (its skill file carries the ownership
  table). Orchestrator-run, so the hook cannot enforce it: treat "merged without review"
  like "committed without verify". Docs-only or purely mechanical waves may skip it,
  stated explicitly.
- `/milestone` to start/finish a milestone (DoD verification on a real device, `git tag m<N>`).
- `/document-feature` when adding or materially changing a feature
  (`docs/features/<name>.md`, `docs/ARCHITECTURE.md`).
- Unit tests accompany every repository/ViewModel/mapper (JUnit5 + MockK + Turbine);
  densest coverage on the download pipeline and offline sync.

## Key references (read before touching the matching area)
- Playback: `../jellyfin-android/app/src/main/java/org/jellyfin/mobile/player/`
  (DeviceProfileBuilder.kt, source/MediaSourceResolver.kt — note the dashless
  mediaSourceId quirk at line 58, queue/QueueManager.kt, PlayerViewModel.kt:410-562).
- Downloads engine: `../jellyfin-android/.../downloads/DownloadQueue.kt`, `FileDownloader.kt`.
- Server discovery: `../jellyfin-android/.../setup/ConnectionHelper.kt`.
