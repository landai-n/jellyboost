# STATUS

## Current milestone: M2 — Design system + Home (online) (in progress on a parallel branch)

**Definition of done (M2):** design system in `:core:ui` + Home screen (online);
verify side-by-side vs jellyfin-web home — same rows/items/order.
Note: `:core:ui` was frozen during the tail of M1 (DECISIONS.md 2026-07-28); the auth
screens must be restyled onto the design system at M2 integration.

## Previous milestone: M1 — Auth & session (DONE, tagged m1)

**DoD walk on test tablet (2026-07-28), all pass:**
- UDP discovery: "Servers on this network" lists test-server (screenshot-verified).
- Manual/candidate resolution: `Resolved http://192.168.1.10:8096 (score GREAT,
  version 10.11.11)`.
- Password login (fresh install) and Quick Connect login (code approved in web UI,
  signed in as approving user) both land on Home.
- Token hygiene via `run-as`: DB schema/WAL contain no token column and no token-shaped
  strings; `secure_credentials.xml` fully encrypted (Tink AES-SIV keys / AES-GCM values).
- Session restore: force-stop → relaunch → straight to signed-in Home
  (`Restored session for 'Alex' on 'test-server'`), no network.
- Sign-out: credential entries wiped (only keyset metadata remains), app returns to
  ServerSetup; server/user Room rows kept per DECISIONS.md.
- Dashboard→Devices: user confirmed "jellyfin-native 0.1.0" session in web UI.
- Server version 10.11.11 (upgraded from 10.10.7 during M1); download policy
  `enableContentDownloading=true` — risk #4 cleared, download pipeline (M7) unblocked.

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
- M2 (parallel branch): design system in `:core:ui`, Home screen, restyle auth screens
  onto the design system at integration.

### Known issues
- adb `input tap` injection is flaky on the test tablet (the OEM ROM): roughly one tap in two is
  silently dropped — retry loops with logcat confirmation needed when driving the UI.
