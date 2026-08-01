# Design system — the 2026 refresh

The app-wide visual language ("2026 refresh"), integrated 2026-08-01 from the
claude.ai/design **Jellyboost Design System** project. Pure restyle of the existing
feature set plus five user-approved convenience displays. Source-of-truth chain:
**Kotlin theme → `design/` HTML mirror → remote claude.ai/design project** (the mirror is
the sync source; `design/_shared/tokens.css` restates the Kotlin tokens).

## Token layer (`core/ui/theme`)

- `JellyfinColors` — unchanged palette (jellyfin-web dark: `#101010`/`#202020`/`#292929`,
  primary `#00A4DC`, secondary `#AA5CC3`, error `#CF6679`, outline `#3C3C3C`).
- `GlassDefaults` (`GlassDefaults.kt`) — the glass language: white@6% fill, white@9% 1dp
  hairline, blur 18dp via **Haze 1.7.2** (`Modifier.glassSurface(shape, borderColor)`;
  `LocalHazeState` is provided by `AppScaffold` around the NavHost `hazeSource`; null →
  static fill, which is also the API < 31 story). `Modifier.mSurface(shape)` is the
  opaque sibling: surface fill + white@6% hairline for cards/panels.
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
  `BrandGlow`/`BrandGlowSide`/`BackdropScrim`/`ImagePlaceholder` unchanged.

## Component layer (`core/ui/component`)

- `JellyfinButtons.kt` — `PrimaryPillButton` (44dp pill, **white fill + #101010 content**;
  `small`, `leadingIcon`, `loading` spinner), `GhostPillButton` (glass + white@12% border),
  `GlassIconButton` (36dp glass circle; 44dp variant). `colorScheme.primary` deliberately
  stays `#00A4DC` for progress/selection/links (DECISIONS 2026-08-01).
- `PillChip.kt` — pill chips (selected = solid white) + `MPillBadge` mini outlined badge.
- `JellyfinTextField.kt` — filled field (white@4%, 12dp radius, hairline → white@22% when
  focused/filled), uppercase caption label above the well, leading/trailing icon slots.
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
