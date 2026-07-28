# Feature: auth (server setup, login, session)

Landed in **M1**. Covers server discovery/resolution, password + Quick Connect sign-in,
secure session persistence, session restore and sign-out.

## What it does

First-run flow: the ServerSetup screen lists Jellyfin servers found on the LAN (UDP
broadcast, live) and accepts a manual URL; connecting resolves the input through the SDK's
address-candidate scoring and moves to Login. The Login screen shows the server's public
users (tap to pre-fill), the server's login disclaimer if configured, and offers password
login plus Quick Connect (code displayed, approval polled). A successful sign-in is
persisted so the app starts straight into the (placeholder) Home screen next time, fully
offline-capable; sign-out returns to ServerSetup.

## Key classes

- `:core:network` (`core/network/src/main/kotlin/dev/jellyfinnative/core/network/`)
  - `ApiClientProvider` — owns the SDK `Jellyfin` instance + the single mutable `ApiClient`
    (`useServer` / `useSession` / `clearSession`); exposes `deviceId`.
  - `ServerDiscoveryRepository` — `discoverLocalServers(): Flow<DiscoveredServer>` and
    `resolveServerAddress(input)`; pure candidate selection in
    `RecommendedServerSelection.kt` (GREAT, else first GOOD, else
    `AppError.ServerResolution` partitioned unreachable/incompatible).
  - `AuthRepository` — `fetchLoginContext`, `loginWithPassword`, `initiateQuickConnect`,
    `observeQuickConnectState` (cold finite flow, 5 s poll / 5 min cap),
    `loginWithQuickConnect`. All success paths funnel through one private
    `completeAuthentication` that persists Room rows (token-free), saves the token to
    `SecureCredentialStore`, authenticates the `ApiClient` and publishes
    `SessionState.LoggedIn`. Logs server version + `enableContentDownloading` policy
    (INFO, tag `AuthRepository`) per plan risk #4.
  - `SessionRepository` — `sessionState: StateFlow<SessionState>`; `restoreSession()`
    (purely local: `SecureCredentialStore` + Room, no network); `signOut()` (best-effort
    `reportSessionEnded`, wipe credential store, keep token-free Room rows — see
    DECISIONS.md).
  - `JellyfinApiFacade` / `SdkJellyfinApiFacade` — one-method-per-SDK-call seam so
    repositories are unit-testable with MockK.
- `:core:datastore` — `SecureCredentialStore` / `EncryptedSecureCredentialStore`
  (EncryptedSharedPreferences; the ONLY place the access token is ever persisted),
  `StoredSession` (serverId + userId + token as one atomic record).
- `:core:database` — `ServerEntity`, `ServerAddressEntity` (multi-URL per server),
  `UserEntity` (no token column by design), `ServerDao`, `UserDao`.
- `:feature:auth` (`feature/auth/src/main/kotlin/dev/jellyfinnative/feature/auth/`)
  - `ServerSetupScreen`/`ServerSetupViewModel`, `LoginScreen`/`LoginViewModel`,
    `PendingServerStore` (feature-internal `@Singleton` handing the `ResolvedServer` from
    ServerSetup to Login; `Routes.Login` stays parameterless), `AuthErrorMessage`
    (AppError → user copy incl. the partitioned "unable to reach" / "unsupported version"
    lists modeled on jellyfin-android).
  - Styling: plain Material 3, self-contained — `:core:ui` deliberately untouched while
    the M2 design-system branch is in flight (DECISIONS.md 2026-07-28).
- `:app` — `MainViewModel` (calls `restoreSession()` once; splash held while
  `SessionState.Unknown`), `JellyfinNavHost` (start destination from session state;
  `LaunchedEffect` redirects to ServerSetup when the session flips to LoggedOut outside
  the auth flow — covers sign-out today and 401-driven logout later),
  `HomePlaceholderScreen` (temporary; shows user/server/version + sign-out until
  `:feature:home` (M2) and Settings (M9) take over).

## Server endpoints used

`jellyfin.discovery.discoverLocalServers` (UDP 7359), `jellyfin.discovery.getAddressCandidates`
+ `getRecommendedServers`, `userApi.getPublicUsers`, `brandingApi.getBrandingOptions`,
`quickConnectApi.getQuickConnectEnabled` / `initiateQuickConnect` / `getQuickConnectState`,
`userApi.authenticateUserByName` / `authenticateWithQuickConnect`,
`sessionApi.reportSessionEnded` (sign-out, best-effort). No `getCurrentUser` after
authentication — `AuthenticationResult.user` already carries the full DTO (DECISIONS.md).

## Offline behavior

Session restore is fully offline: `restoreSession()` reads only the credential store and
Room, so a stored session opens the app signed-in with no network. Discovery/resolution and
both login paths require the server. Sign-out works offline (the server-side
`reportSessionEnded` failure is swallowed; local state is still wiped). A stored token whose
Room rows are missing is treated as inconsistent and discarded (→ LoggedOut).

## Test coverage

57 unit tests across the involved modules (JUnit5 + MockK + Turbine, virtual time where
polling/timing matters):

- `:core:network` (29): `RecommendedServerSelectionTest`, `ServerDiscoveryRepositoryTest`,
  `AuthRepositoryTest` (incl. token-hygiene assertions: DAO entities carry no token; failed
  login persists nothing), `AuthRepositoryQuickConnectTest` (exactly 60 polls / 300 000 ms
  on a virtual clock), `SessionRepositoryTest` (restore variants, sign-out despite server
  error).
- `:feature:auth` (20): `ServerSetupViewModelTest` (discovery accumulation/dedupe, resolve
  success/failure, error copy), `LoginViewModelTest` (context load, password success /
  unauthorized, Quick Connect happy path, expiry, failure, cancel).
- `:app` (3): `MainViewModelTest` (restore-once, state passthrough).
- `:core:database` (5): `UuidConverterTest`.

Known gaps: DAO queries and `EncryptedSecureCredentialStore` are Android-framework-bound
and have no JVM tests (no Robolectric in the stack); compose UI is untested (no screenshot/
instrumented UI tests in v1 scope); the on-device M1 DoD walk is blocked pending the
server-version decision (STATUS.md → Known issues).
