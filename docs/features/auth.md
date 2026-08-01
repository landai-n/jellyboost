# Feature: auth (server setup, login, session)

Landed in **M1**. Covers server discovery/resolution, password + Quick Connect sign-in,
secure session persistence, session restore and sign-out.

## What it does

First-run flow: the ServerSetup screen lists Jellyfin servers found on the LAN (UDP
broadcast, live) and accepts a manual URL; connecting resolves the input through the SDK's
address-candidate scoring and moves to Login. The Login screen shows the server's public
users with their real profile pictures (tap to pre-fill), the server's login disclaimer if
configured, and offers password login plus Quick Connect (code displayed, approval polled).
Both screens are branded: the gradient Jellyboost mark sits in a faint accent halo, large on
ServerSetup and inline above the server name on Login. A successful sign-in is
persisted so the app starts straight into the (placeholder) Home screen next time, fully
offline-capable; sign-out returns to ServerSetup.

## Key classes

- `:core:network` (`core/network/src/main/kotlin/dev/jellyboost/core/network/`)
  - `ApiClientProvider` — owns the SDK `Jellyfin` instance + the single mutable `ApiClient`
    (`useServer` / `useSession` / `clearSession`); exposes `deviceId`, supplied explicitly
    from `DeviceIdProvider` rather than left to the SDK default (see below).
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
  `StoredSession` (serverId + userId + token as one atomic record),
  `DeviceIdProvider` + `DeviceIdStore` / `SharedPreferencesDeviceIdStore` (the device id:
  a random UUID generated once and persisted in a plain `device_identity` preferences file;
  not a secret, not cleared on sign-out).

## Device identity

A Jellyfin server keys **one access token per (user, device id)**: a sign-in with a device id
that is already registered revokes the token previously issued for it. The SDK's default device
id is `Settings.Secure.ANDROID_ID`, which Android scopes per *signing key*, not per package —
so the `.debug` install and the locally debug-signed `dev.jellyboost.app` release variant
used for profiling presented the *same* device id and silently revoked each other's session on
every sign-in (every authenticated call then 401s). The app therefore supplies its own device
id: a random UUID, generated on first run and persisted per installation, stable across
restarts and sign-outs. Changing the id makes the server treat the app as a new device, so the
one-time upgrade past this change requires signing in again.
- `:core:database` — `ServerEntity`, `ServerAddressEntity` (multi-URL per server),
  `UserEntity` (no token column by design), `ServerDao`, `UserDao`.
- `:feature:auth` (`feature/auth/src/main/kotlin/dev/jellyboost/feature/auth/`)
  - `ServerSetupScreen`/`ServerSetupViewModel`, `LoginScreen`/`LoginViewModel`,
    `PendingServerStore` (feature-internal `@Singleton` handing the `ResolvedServer` from
    ServerSetup to Login; `Routes.Login` stays parameterless), `AuthErrorMessage`
    (AppError → user copy incl. the partitioned "unable to reach" / "unsupported version"
    lists modeled on jellyfin-android).
  - Styling: Material 3 on the `:core:ui` design system. `AuthScreenScaffold` (in
    `ServerSetupScreen.kt`) is the shared frame — `JellyfinGradients.BrandGlow` halo, safe-drawing
    + IME padding, and a header slot + content slot. Portrait / narrow windows stack the two in
    one scrolling column; windows ≥ 840dp wide that are wider than tall (a landscape tablet) get
    side-by-side panes — branding/identity centred on the left, form on the right, each scrolling
    on its own — because stacked they overflowed the short viewport. Every pane's column is
    capped at `AuthContentMaxWidth` so the form is never stretched. `res/drawable/ic_jellyboost_logo.xml` is the tight-viewport in-app
    variant of the launcher mark (`logo/ic_launcher_foreground.svg` stays the geometry's source of
    truth; the launcher vector keeps its adaptive-icon safe-zone padding and is unusable inline).
    The feature started out self-contained while the M2 design-system branch was in flight
    (DECISIONS.md 2026-07-28); that constraint is gone.
  - `publicUserAvatarUrl(serverAddress, user, maxWidth)` (in `LoginViewModel.kt`) — the one place
    a public user's `primaryImageTag` is turned into a `/Users/{id}/Images/Primary` URL, tolerating
    a trailing slash on the address. `null` (no tag, or no server yet) keeps the initial-letter
    circle; anything else renders through `:core:ui`'s `JellyfinAsyncImage`.
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
instrumented UI tests in v1 scope). The full M1 DoD was walked manually on the test tablet
against Jellyfin 10.11.11 on 2026-07-28 (see STATUS.md) — discovery, both login paths,
token hygiene via `run-as`, restore, sign-out, Dashboard→Devices all passed.
