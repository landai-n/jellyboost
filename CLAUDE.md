# jellyfin-native — agent instructions

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

## Build environment
- Always `source "../env.sh"` first (sets JAVA_HOME=openjdk@21, ANDROID_HOME).
- Build: `./gradlew assembleDebug` • Quality: `./gradlew ktlintCheck detekt testDebugUnitTest`
- Install: `./gradlew installDebug` (device/emulator via adb).

## Workflow expectations
- `/verify` before every commit (the pre-commit hook enforces it).
- `/checkpoint` at least once per completed sub-task: verify → docs → small conventional commit
  (prefixes: feat/fix/refactor/test/docs/chore/build).
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
