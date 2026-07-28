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
- `:feature:auth`: ServerSetupScreen/ViewModel (live UDP list + manual URL, jellyfin-android
  error copy) and LoginScreen/ViewModel (public users, disclaimer, password, Quick Connect
  dialog); resolved server handed over via a feature-internal `PendingServerStore`.
  20 unit tests. Strings in `feature/auth/res/values/strings.xml`; plain Material 3 only —
  `:core:ui` untouched (design system is on the parallel M2 branch).
- `:app`: MainViewModel (restore once, splash held while `SessionState.Unknown`),
  JellyfinNavHost (ServerSetup → Login → Home, logout redirect driven by session state),
  temporary HomePlaceholderScreen with sign-out. 3 unit tests.
- Runtime fixes found by running on the tablet: SLF4J binding for the SDK (UDP discovery
  crashed without it) and a network-security-config permitting cleartext + user CAs
  (targetSdk 36 blocked plain-HTTP LAN servers). Both in DECISIONS.md.

### Next
- **Blocked:** on-device DoD walk (run-as DB inspection, Dashboard→Devices session,
  download-policy confirmation) needs a server the pinned SDK accepts — see Known issues.
- `/document-feature auth` + `docs/ARCHITECTURE.md` refresh.
- Restyle the auth screens onto `:core:ui` when the M2 design system lands.

### Known issues
- **The test server is Jellyfin 10.10.7; jellyfin-sdk 1.8.12 requires ≥ 10.11.0.** The
  server on the LAN (`test-server`, `http://192.168.1.10:8096`) is discovered and reachable,
  but `getRecommendedServers` scores it below GOOD (`UnsupportedServerVersion`), so the
  ServerSetup screen correctly refuses it with "unsupported version or product" — the same
  behaviour jellyfin-android has (GREAT/GOOD only). M1's DoD cannot be walked until either
  the server is upgraded to 10.11.x (docs/PLAN.md's stated expectation) or a decision is
  logged to accept `OK`-scored servers / pin an older SDK. This is docs/PLAN.md risk #4.
