# Design system — the 2026 refresh

The app-wide visual language ("2026 refresh"), integrated 2026-08-01 from the
claude.ai/design **Jellyboost Design System** project. Pure restyle of the existing
feature set plus five user-approved convenience displays. Source-of-truth chain:
**Kotlin theme → `design/` HTML mirror → remote claude.ai/design project** (the mirror is
the sync source; `design/_shared/tokens.css` restates the Kotlin tokens).

## Token layer (`core/ui/theme`)

- `JellyfinColors` — the dark palette (jellyfin-web dark: `#101010`/`#202020`/`#292929`,
  primary `#00A4DC`, secondary `#AA5CC3`, error `#CF6679`, outline `#6E6E6E`).
- `JellyfinLightColors` — its light sibling (M14): `#F6F7F8` page, `#FFFFFF` card,
  `#ECEEF0` surfaceVariant, `#101010` ink, `onSurfaceVariant` black@72%, error `#B3261E`,
  outline `#858585`. Not an inversion — a light page is a near-white ground with a
  *whiter* card on it — and the brand hues **darken** to `#00769E` / `#6B2F7F` because
  `#00A4DC` measures 2.67:1 on `#F6F7F8` and `#AA5CC3` 3.89:1, under the 4.5:1 body text
  and links owe. Every constant carries its arithmetic; `ContrastRatioTest` pins both
  schemes. Which one is drawn: [`theme.md`](theme.md).
- `GlassDefaults` (`GlassDefaults.kt`) — the glass language: white@6% fill, white@9% 1dp
  hairline, blur 18dp via **Haze 1.7.2** (`Modifier.glassSurface(shape, borderColor)`;
  `LocalHazeState` is provided by `AppScaffold` around the NavHost `hazeSource`; null →
  static fill, which is also the API < 31 story). `Modifier.mSurface(shape)` is the
  opaque sibling: surface fill + white@6% hairline for cards/panels. Every colour here is
  a `Dark`/`Light` pair behind a `@Composable` accessor of the old name — light fill is
  white@**55%** over the blur (6% of white over a near-white page is nothing) while every
  hairline flips to **black**, so the two sides are not mirror images.
- `PageInk.kt` — `pageInk(darkAlpha, lightAlpha)` for a translucent tint drawn on the page
  (hairline, well, progress track, disabled label). Two alphas, never one reused: 0.48 is
  5.00:1 on `#101010` and 3.66:1 on `#F6F7F8`.
- `JellyfinElevation.kt` — `cardShadow`/`popShadow` approximations (12dp/24dp, black@45/55%
  ambient+spot). Hairlines, not shadows, are the primary separators on `#101010`.
- `JellyfinTypeExtras.kt` — bespoke roles outside the stock M3 scale: `Eyebrow` (11sp/600,
  +0.14em, callers uppercase), `SectionTitle` (17sp/600, −0.01em), `SeeAll`, `MPill`,
  `HeroTitleCompact`/`Expanded` (34/44sp 700 −0.02em), `ScreenTitle`/`Large` (28/30sp 700),
  `Wordmark` (30sp 700). M3 `Typography()` itself is untouched.
- `Theme.kt` — M3 `Shapes` themed to the refresh radii: 6/12/16/20dp.
- `Dimens` — poster **128×192**, thumb **232×130**, `CardCornerRadius` **12dp** (DECISIONS
  2026-08-01 supersedes the jellyfin-web-parity footprints), plus the refresh constants
  (`PillHeight` 44/36, `PanelRadius` 16, `OverlayInset` 10, `InsetProgressHeight` 3,
  `LibraryTileWidth/Height` 232/64, `CastHeadshotSize` 72, `DetailPosterWidth/Height`
  190/285, `RadiusXl` 20).
- `JellyfinGradients` — adds `HeroHalo` (radial at 78%/18%) and `ScreenGlow`; existing
  `BrandGlow`/`BrandGlowSide`/`BackdropScrim`/`ImagePlaceholder` unchanged. The scrims and
  the placeholder resolve against `MaterialTheme.colorScheme` rather than the static
  palette — a scrim fading to `#101010` over a light page is a seam, not a transition —
  and the halo/glow run at roughly two-fifths of their alpha on a light page, where the
  same wash reads as a stain. The accent gradient is brand and identical in both schemes.

## Component layer (`core/ui/component`)

- `JellyfinButtons.kt` — `PrimaryPillButton` (44dp pill, **`onBackground` fill +
  `background` content**; `small`, `leadingIcon`, `loading` spinner), `GhostPillButton`
  (glass + white@12% border), `GlassIconButton` (36dp glass circle; 44dp variant). The
  pill's fill is the page's own ink inverted, which in the dark scheme *is* the white fill
  + `#101010` content the refresh drew; on a light page it becomes a near-black pill rather
  than white-on-white. Its five siblings follow the same rule (`PillChip`'s selected chip,
  the downloads segmented tab, `MusicLibraryScreen`'s segment, and the selected-tab pills in
  `GlassTopNav` / `GlassBottomNav`); the player's and NowPlaying's play discs stay
  literal white, because they sit on video and album art. `colorScheme.primary` deliberately
  stays `#00A4DC` in the dark scheme for progress/selection/links (DECISIONS 2026-08-01);
  the light scheme darkens it to `#00769E` and Material You replaces it while the user has
  that on (DECISIONS 2026-08-28).
- `PillChip.kt` — pill chips (selected = the brand pill's inversion) + `MPillBadge` mini
  outlined badge.
- `JellyfinTextField.kt` — filled field (white@4%, 12dp radius, hairline → white@22% when
  focused/filled), uppercase caption label above the well, leading/trailing icon slots.
  The well is 50dp so a 48dp trailing target (password reveal, search clear) fits *inside*
  it: a field with a trailing button is exactly as tall as one without, and the 14dp
  vertical padding is the text's, not the well's.
- `PillSnackbar.kt` (no action affordance), `ErrorBanner.kt` (error@10% fill, error@28%
  border, `#F0A3AE` content).
- `MediaCardArtwork.kt` — 12dp radius, `cardShadow`, inner white@7% hairline; overlay
  params `topStartBadge` (S1 · E10), `timeChipText` (22m left), `ratingBadge` (star +
  communityRating); **inset** 3dp progress bar (10dp side insets); watched tick = 22dp
  solid-primary circle top-END, stacked in a column with the `DownloadBadge` (which keeps
  the `DownloadForOffline` glyph — a check would collide with the watched tick); selection
  = 2dp primary inset outline + primary@22% tint + top-START indicator.
- `LibraryCard.kt` — the 232×64 library tile (glyph well + name + optional "N items"
  count); `libraryIcon(CollectionKind)` is the shared type→icon mapping.
- `SelectionAppBar.kt` — floating glass pill (self-pads its status-bar inset).
- `MediaRow.kt` (SectionTitle + muted See-all + optional eyebrow), `StateViews.kt`
  (36dp spinner, white Retry pill, optional dashed panel).

## Chrome (`:app`)

Below 560dp: floating glass bottom-nav pill (60dp, 20dp margins; selected tab = white
pill) + a top-right floating action cluster (connection status, Cast, SyncPlay, overflow).
At/above 560dp: 64dp glass top nav (fin mark, labeled pill tabs, trailing glass actions).
The chrome reserves no layout space — `LocalAppChromePadding` (in `core/ui`) tells
top-level screens how much scrollable `contentPadding` to add; pushed destinations get
zero and keep their own glass headers (back + home `GlassIconButton`s + `ScreenTitle` —
`LibraryGridScreen` is the template). One `hazeSource` on the NavHost root; the top nav is
glass-in-pieces (capsule + circles), never a nested blurred slab. See the AppScaffold KDoc
and the two DECISIONS entries of 2026-08-01 for the full contract.

## Convenience displays (user-approved, data pre-existing)

Home continue-watching hero (Resume plays via `HomeActions.onPlay`; Details opens the
item), card overlay metadata (episode badge / time-left / grid rating), library tiles with
`itemCount` (a per-library recursive count query, not `ChildCount`; null offline → line
hidden — DECISIONS 2026-08-01), library grid "N items" (first-page-only
`enableTotalRecordCount`, DECISIONS 2026-08-01), detail Cast rail (`people` +
`primaryImageUrl`), downloads wide-layout `QueueStats` summary.

## Deliberately not built (mock elements without a matching feature)

In-library search, notifications bell, share, snackbar Undo, 4K/HDR pills (no
media-stream metadata on `JellyfinItem`), player chapter ticks (no chapter data),
"Manage" libraries link. Kept despite the mocks omitting them: queue move-down,
connection-status indicator, favourite, Cast, SyncPlay, overflow, See-all.

## Mirror workflow

`design/` holds the `@dsCard` HTML previews (`_shared/modern.css` is the class library on
top of `tokens.css`). Cards are code-faithful: change the Kotlin first, then the card,
then push via DesignSync — remembering the remote `_ds_manifest.json` must be refreshed in
the same pass or new cards stay invisible in the pane.
