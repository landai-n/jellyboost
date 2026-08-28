# Theme (M14)

`Theme.kt` used to open with *"Dark only by design; a light scheme is out of scope for v1."* That was
a scoping decision, not an architectural one, and docs/PLAN.md M14 track 4 is the revisit it invited:
a full light `ColorScheme` beside the dark one, optional Material You on API 31+, and a
system/light/dark preference in Settings. What follows is what actually resolves, and — as much of
this doc's weight — what deliberately does **not**.

The wrinkle worth naming up front: an app is not one surface. Chrome and pages follow the scheme;
video frames, album art and the scrims drawn on top of them do not, in either scheme. Getting that
line in the right place is most of the work.

---

## The two schemes

| role | dark (`JellyfinColors`) | light (`JellyfinLightColors`) |
|---|---|---|
| `background` | `#101010` | `#EEF1F7` |
| `surface` | `#202020` | `#FFFFFF` |
| `surfaceVariant` | `#292929` | `#E3E8F2` |
| `onBackground` / `onSurface` | `#FFFFFF` | `#191B22` |
| `onSurfaceVariant` | white@70% | `#1F2330`@78% |
| `primary` | `#00A4DC` | `#00769E` |
| `onPrimary` | `#000000` | `#FFFFFF` |
| `secondary` | `#AA5CC3` | `#9A4DB4` |
| `error` / `onError` | `#CF6679` / `#000000` | `#B3261E` / `#FFFFFF` |
| `outline` | `#6E6E6E` | `#788AB0` |

The light column is the saved M14 design canvas's light-theme foundations
(`design/foundations/colors-light.html`), with three values moved off the canvas's own hex where the
measurement forbade it — each keeps the canvas's hue and moves only lightness or alpha, by the
minimum `ContrastRatioTest` asks for (DECISIONS 2026-08-28).

The light side is **not an inversion**. Two things fall out of that:

- **A light page is a cool near-white ground with a *whiter* card on it.** The dark scheme lifts each
  layer by getting brighter; the light one cannot keep going up past white, so the page steps *down*
  to `#EEF1F7` and the card stays `#FFFFFF`.
- **The brand blue darkens.** `#00A4DC` measures 2.53:1 on `#EEF1F7` and the canvas's own `#0089B8`
  3.52:1, both under the 4.5:1 that body text and links owe (WCAG 1.4.3) — and white on a `#0089B8`
  pill is 3.98:1. `#00769E` (4.54:1, and 5.14:1 under white) is the lightest point on that hue's ramp
  that clears both. The canvas's `#9A4DB4` secondary clears it as drawn (4.53:1) and is untouched.
  The pinned brand primary (DECISIONS 2026-08-01) survives as an *identity*; its hex cannot survive a
  ground it was never measured against.
- **The outline is a boundary, not a tint.** `outline` is what WCAG 1.4.11 asks 3:1 of, and the
  canvas's `#D4DAE6` is a 1.24:1 seam. Its hue (220°) and saturation (26.5%) are kept and its
  lightness taken from 86.7% to 58%: `#788AB0`, 3.06:1 on the page and 3.46:1 on a card.

Every constant carries the arithmetic that chose it in its KDoc, and `ContrastRatioTest` pins both
schemes — see [Tests](#tests).

---

## What the scheme cannot express

M3's `ColorScheme` has no role for a glass fill, a hairline, or the ink of a disabled label, and none
of the three can be derived from a role that does exist: a glass **fill** gets *lighter* in light mode
while a **hairline** flips to black. So one bit is provided alongside the scheme:

```
JellyfinTheme(themeMode, dynamicColor)
  ├── themeMode.resolvesDark()            SYSTEM → isSystemInDarkTheme(); LIGHT/DARK answer directly
  ├── CompositionLocalProvider(LocalIsLightTheme provides !dark)
  └── MaterialTheme(colorScheme = …)
        ├── GlassDefaults.Fill / Hairline / GhostBorder / ChromeFill / …   (Dark/Light pairs)
        ├── pageInk(darkAlpha, lightAlpha)                                 (translucent page ink)
        └── JellyfinGradients.BackdropScrim / TopChromeScrim / …           (read colorScheme directly)
```

`LocalIsLightTheme` is `internal` to `:core:ui` and `static`: it changes once per theme switch, and
every glass surface in the app reads it. Call sites never read it — they read `GlassDefaults` or
`pageInk`, which is what keeps the pairs in one file where the contrast test can measure them.

**`pageInk(darkAlpha, lightAlpha)`** takes two alphas rather than reusing one, because black loses
far more contrast per unit of alpha over a light ground than white gains over a dark one. The
disabled pill label is the worked example: 0.48 is 5.00:1 on `#101010` and only 3.04:1 on `#EEF1F7`,
so the light side runs at 0.65 (5.08:1) to owe 1.4.3 the same 4.5:1.

**The worst case flips.** Chrome floats over arbitrary artwork, so the dark chrome fill is measured
over a fully *white* frame — the brightest thing its subtractive tint can sit on. The light fill is
additive, so its worst case is the *darkest* frame, where `#EEF1F7`@72% composites to rgb(171,174,178)
and the light ink reads 7.70:1 on it. That ground is the binding constraint on `onSurfaceVariant`'s
alpha — the canvas's 60% reads 3.06:1 there, 78% reads 4.53:1. The test measures each side against
its own worst case.

---

## The brand pill

Six surfaces used to hardcode a white fill with `Color(0xFF101010)` content. They are now one
semantic — **fill `onBackground`, content `background`** — which in the dark scheme renders pixel for
pixel what the 2026 refresh drew, and on a light page inverts to a near-black pill instead of white
on white:

- `JellyfinButtons.PrimaryPillContainer` / `PrimaryPillContent`
- `PillChip.ChipSelectedFill` / `ChipSelectedContent`
- `DownloadsScreen`'s selected segmented tab
- `GlassTopNav`'s selected tab pill **and** `GlassBottomNav`'s
- `MusicLibraryScreen`'s segment

---

## What stays literal, permanently

These are decisions, not gaps. Each is over media that is dark in both schemes, or draws before a
preference can be read.

| surface | why |
|---|---|
| `PlayerControls.OVER_MEDIA_DISC` + `PLAY_GLYPH`, `NowPlayingScreen.OverMediaDisc` + `PlayGlyphColor` | the play disc sits on the video frame and on album art — a photograph and a letterbox, neither of which follows Settings. Both pairs are pinned by `ContrastRatioTest`'s mirror list. |
| `PlayerScreen` (`OVERLAY_SCRIM`, `BACKDROP_SCRIM`, the video surface), `PlayerControls` (`SCRIM`, `VIDEO_GLASS_FILL`, `THUMB_SHADOW_COLOR`), `PlayerGestureLayer`, `TrickplayPreview` | video letterboxing and the scrims over it. A light scrim over a film frame is not a light theme, it is a fogged screen. |
| `MediaCardArtwork`'s four scrims and its white-on-scrim text, `DownloadBadge` | always drawn over artwork the card has already scrimmed. |
| `JellyfinElevation.cardShadow` / `popShadow` | shadows are black-based in light UI too. Deliberately unchanged, recorded here so a future audit does not read it as a missed sibling. |
| `themes.xml` — `Theme.Jellyboost` and `Theme.Jellyboost.Starting` | **the permanent exception.** Both draw before Compose exists and before DataStore can answer, so there is nothing to read a preference from. `Theme.Jellyboost.Starting` additionally force-sets `windowLightStatusBar=false` to stop `Theme.SplashScreen`'s DayNight parent following the *system* independently of the app. A light-mode user therefore sees one dark splash frame. Closing this would mean a synchronous `SharedPreferences` mirror of a DataStore key, kept in step by hand, to save a frame. |
| `colors.xml`'s `launcher_background` | the brand navy the mark was drawn against, not the app background — its own comment already warns against conflating the two. |

---

## The window

`enableEdgeToEdge` runs in `onCreate`, before `setContent` and before DataStore answers, so it keeps
the dark style for the first frame — matching the splash it draws over. A `LaunchedEffect` keyed on
the resolved darkness re-invokes it once the preference lands:

```
MainActivity.onCreate
  applyBarStyle(dark = true)                    ← first frame, matches the dark-locked splash
  setContent {
    theme      = viewModel.themePreference       ← StateFlow<ThemePreference>, initial = store default
    dark       = theme.mode.resolvesDark()
    LaunchedEffect(dark) { applyBarStyle(dark) } ← corrects the icons
    JellyfinTheme(theme.mode, theme.dynamicColor) { … }
  }
```

**`SystemBarStyle.auto()` is never used.** It derives icon appearance from the *system's* night-mode
setting, so a light-mode system drew black icons over this app's dark UI — the bug the hardcoded call
was written to defeat, and one that would return in a different shape now that the app has its own
answer. `ThemeMode.resolvesDark()` lives in `:core:ui` and is read by both `JellyfinTheme` and
`MainActivity`, so the window and the composition cannot drift about which scheme is drawn.

---

## Material You

`dynamicColor` picks `dynamicDarkColorScheme` / `dynamicLightColorScheme` on API 31+ and is ignored
below, where the platform has no wallpaper palette. It **replaces the brand primary while it is on** —
there is no version of Material You that keeps `#00A4DC`, and half-applying it (wallpaper backgrounds
under a brand accent) reads as a bug. So it defaults to **off**: the pinned primary is still what the
app ships with and what every screenshot describes, and the wallpaper palette is something a user opts
into. DECISIONS 2026-08-28 records the supersession against the 2026-08-01 pin.

The Settings row is *absent* below API 31 rather than disabled, so the row and the theme agree on
where the feature exists.

---

## The preference

`ThemeMode { SYSTEM, LIGHT, DARK }` (`:core:common/model`) and a `dynamicColorEnabled` boolean, both
in `:core:datastore` alongside `downloadQuality` and `SegmentSkipMode`. The enum persists by `.name`
and decodes through `fromNameOrDefault`, so a constant this build does not know — a downgrade, a
rename — reads as `SYSTEM` rather than crashing. `SYSTEM` is the default because the device setting
is an answer the user already gave once, for every app.

```
DataStore ──► AppPreferences.themeMode / .dynamicColorEnabled
                 ├──► SettingsViewModel  ──► SettingsUiState  ──► AppearanceSection   (write)
                 └──► MainViewModel      ──► ThemePreference  ──► JellyfinTheme       (read)
```

Two readers, one store, no cache in either: a mode changed in Settings is already correct in the
window, and vice versa, without cross-screen wiring.

---

## Design mirror

`design/_shared/tokens.css` carries a `[data-theme="light"]` block overriding exactly the custom
properties that change — the accent gradient is brand and appears once, while the backdrop scrim and
the image placeholder are restated because they resolve against the background. The Kotlin is the
source of truth; the mirror follows it, per the workflow in
[`design-system.md`](design-system.md).

---

## Tests

| Suite | Covers |
|---|---|
| `ContrastRatioTest` (`:core:ui`) | every drawn token pair in **both** schemes: the palette roles, the outline, the ghost border, the focus ring, both progress tracks, the chip and pill inversions, the placeholder, the banner, and the glass chrome — dark measured over the brightest frame, light over the darkest. Sub-3:1 tokens are frozen as `Exempt` with their reason, or as `KnownViolation` with the debt recorded so it cannot grow quietly. Its mirror list pins the literals it copies from other modules, including the two over-media discs, so the "stays white in both themes" claim has a test rather than a comment. |
| `DataStoreAppPreferencesTest` (`:core:datastore`) | `themeMode` and `dynamicColorEnabled`: the default, a round trip through a second store instance, an unrecognised stored name degrading to `SYSTEM`, and every change reaching a Turbine observer. |
| `SettingsViewModelTest` (`:feature:settings`) | both preferences reaching `SettingsUiState`, both setters forwarding to the store, and a mode changed upstream reaching an open screen. |
| `MainViewModelTest` (`:app`) | `themePreference` starting at the store's own default and following both flows. |

**Not unit-testable, owed on a device:** which scheme actually renders. `JellyfinTheme` is a
composable and `isSystemInDarkTheme()` reads a configuration, so the M14 DoD walk covers light and
dynamic themes across every screen at fontScale 2.0 — including the player overlays and download
badges this doc claims are theme-independent.
