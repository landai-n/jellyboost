# STATUS

## Current milestone: M0 — Bootstrap + quality infrastructure

**Definition of done:** `./gradlew assembleDebug detekt` green; app installs and shows dark
themed empty screen; hooks fire; skills invocable; all VERIFY versions resolved and recorded
in DECISIONS.md.

### Done
- Repo initialized, governance files (PLAN/DECISIONS/STATUS/CLAUDE) in place.
- Version resolution: Media3 1.9.0 + ffmpeg-decoder 1.9.0+1, Hilt 2.60.1, Room 2.8.4,
  Compose BOM 2026.06.01, KSP 2.3.10, Kotlin 2.4.10, AGP 9.3.1, SDK 1.8.12.

### Next
- Gradle skeleton: build-logic convention plugins, version catalog, 16 modules.
- Hooks (.claude/hooks) + skills (.claude/skills).
- Themed empty MainActivity; detekt/ktlint config; first green build + install.

### Known issues
- (none yet)
