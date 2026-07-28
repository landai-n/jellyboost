# STATUS

## Current milestone: M0 — Bootstrap + quality infrastructure (code-complete)

**Definition of done:** `./gradlew assembleDebug detekt` green; app installs and shows dark
themed empty screen; hooks fire; skills invocable; all VERIFY versions resolved and recorded
in DECISIONS.md.

### Done
- Repo initialized, governance files (PLAN/DECISIONS/STATUS/CLAUDE) in place.
- Version resolution complete and recorded in DECISIONS.md: Media3 1.9.0 + ffmpeg-decoder
  1.9.0+1, Hilt 2.60.1, androidx.hilt 1.4.0, Room 2.8.4, Compose BOM 2026.06.01,
  lifecycle 2.11.0, KSP 2.3.10, Kotlin 2.4.10, AGP 9.3.1 (built-in Kotlin), Gradle 9.6.1,
  compileSdk 37 / targetSdk 36 / minSdk 26, SDK 1.8.12.
- Gradle skeleton: build-logic (7 convention plugins), version catalog, all 16 modules
  compiling with real stub sources; `:app` = Hilt Application + dark-themed MainActivity.
- Quality gate green (verified independently by orchestrator, not just the build agent):
  `assembleDebug detekt ktlintCheck testDebugUnitTest`. Debug APK 34.8 MB,
  `dev.jellyfinnative.app.debug`.
- Hooks (.claude/hooks: session-start, post-edit, pre-commit-gate, stop-gate) and skills
  (/verify /checkpoint /diverge /milestone /document-feature) created and smoke-tested,
  incl. deny paths and stop_hook_active loop guard.
- Test device documented in CLAUDE.md: test tablet ([redacted]), Android 16 / API 36, via adb.

### Next
- **Enable "Install via USB" in the OEM ROM/the OEM ROM developer settings on the test tablet**
  (requires physical interaction) — installs currently fail with
  `INSTALL_FAILED_USER_RESTRICTED`. Then `./gradlew installDebug`, confirm dark empty
  screen, and `git tag m0`.
- **Restart Claude Code from this directory** (`jellyfin-native/`) so the hooks and skills
  actually load — they are inert in sessions started from the parent directory.
- Then M1: Auth & session (discovery UDP+manual, password + Quick Connect, tokens only in
  EncryptedSharedPreferences, session restore; confirm server version + download policy).

### Known issues
- On-device install blocked by the OEM ROM "Install via USB" restriction (device-side setting,
  not a build problem). APK manifest verified via aapt as a substitute; m0 tag deferred
  until the on-device check passes.
