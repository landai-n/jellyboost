# STATUS

## Current milestone: M1 — Auth & session

**Definition of done:** server discovery (UDP broadcast + manual URL with address-candidate
scoring), password + Quick Connect login, access token stored ONLY in
EncryptedSharedPreferences (never Room), session restore on app start, sign-out.
Verify on device: `run-as` inspection shows no token in the DB; session appears in server
Dashboard→Devices. Also confirm server version (10.11.x expected) and the user's download
policy (risk #4).

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
- On-device DoD check passed: `installDebug` OK, app launches with dark #101010 screen
  (screenshot-verified). An earlier `INSTALL_FAILED_USER_RESTRICTED` was transient —
  "Install via USB" is enabled and working on this device.

### Done (M1 so far)
- `:core:database`: session schema — ServerEntity, ServerAddressEntity (multi-URL, CASCADE),
  UserEntity (NO token column), upsert DAOs, JellyfinDatabase v1 (schema exported),
  UuidConverter + unit tests, Hilt module.
- `:core:datastore`: SecureCredentialStore interface + EncryptedSharedPreferences impl
  (AES256_GCM/SIV, IO-dispatched, corrupt-keyset recreate-once recovery), StoredSession
  (serverId+userId+token as one atomic record), Hilt binding.
- `:core:network`: ApiClientProvider (createJellyfin + single mutable ApiClient),
  ServerDiscoveryRepository (UDP discovery Flow + address-candidate scoring),
  AuthRepository (password + Quick Connect w/ 5s-poll/5-min-cap Flow; download policy
  logged per risk #4), SessionRepository (local-only restore, best-effort sign-out),
  SessionStateHolder, JellyfinApiFacade (testability seam), AppError.ServerResolution.
  29 unit tests (token-hygiene, poll timing on virtual clock, restore/sign-out paths).
  2 DECISIONS entries (no getCurrentUser round-trip; sign-out keeps Room rows).

### Next
- `:feature:auth`: ServerSetup + Login screens/ViewModels (+ tests); NavHost wiring in
  `:app` (session-state-driven start destination, temporary sign-out on placeholder home).
- `:feature:auth`: ServerSetup + Login screens/ViewModels; wire NavHost in `:app`
  (auth flow vs. placeholder home based on session state).
- Unit tests for repositories/ViewModels (JUnit5 + MockK + Turbine).
- On-device DoD walk: run-as DB inspection, Dashboard→Devices session, server version +
  download policy confirmation.

### Known issues
- (none)
