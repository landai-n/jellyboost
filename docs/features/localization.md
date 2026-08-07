# Localization

Jellyboost ships fully translated UI resources for the 69 locales supported by the official
jellyfin-android client, plus the English source strings.

## How it works

- **String resources** live per module, next to the code that uses them:
  `app`, `core/ui`, `data/downloads`, `feature/{auth,home,library,detail,search,downloads,settings}`,
  and `player` each have `src/main/res/values/strings.xml` (English source) and one
  `values-<locale>/strings.xml` per translated locale. There are no hardcoded user-facing
  strings in Compose code; everything goes through `stringResource(...)` /
  `pluralStringResource(...)` / `context.getString(...)`.
- **Copy a ViewModel decides** goes through `core/ui`'s `UiText` (`text/UiText.kt`) — a resource
  id plus its format arguments, resolved by the screen at draw time. A `ViewModel` has no
  `Context` and no locale, so state holding a `String` has already been resolved into whatever
  language the *build* was written in, and `MissingTranslation` cannot see a Kotlin literal.
  That is exactly how home, detail and playback shipped English error copy on all 68 other
  locales until audit H8 — see "Error copy" in [`ARCHITECTURE.md`](../ARCHITECTURE.md).
  `UiText.Raw` is the one escape hatch, for wording that arrives already-worded from outside the
  app (an ExoPlayer or Cast error string) and has no resource to point at.
- **Locale selection** follows the platform:
  - Android 13+ (API 33): the OS *App languages* setting (Settings → System → Languages →
    App languages → Jellyboost). The list the OS offers is generated at build time by AGP's
    `generateLocaleConfig = true` (`app/build.gradle.kts`) from the `values-*` folders; the
    default locale is declared in `app/src/main/res/resources.properties`
    (`unqualifiedResLocale=en-US`).
  - Below API 33 (minSdk 26): the app follows the system locale list, standard Android
    resource resolution.
  - There is deliberately **no in-app language picker** — see the 2026-07-31 DECISIONS entry
    (a picker would require AppCompat/`FragmentActivity` changes out of proportion to the
    feature; the OS setting covers the supported-API majority).
- **RTL**: `ar`, `fa`, `iw` (Hebrew), `ur`, `dv` are RTL; layout mirroring is handled by
  Compose/Android (`supportsRtl` default), translations contain no manual directional marks.

## Locale set

The 69 `values-*` qualifiers mirror the official jellyfin-android app's translation set,
excluding its `values-chn` and `values-lzh` folders (not valid Android locale qualifiers).
Legacy qualifiers are used where Android requires them: `in` (Indonesian), `iw` (Hebrew),
`tl` (Filipino). Regional variants: `en-rGB`, `es-rAR`, `es-rMX`, `b+es+419`, `pt-rBR`,
`pt-rPT`, `bn-rBD`, `nb-rNO`, `zh-rCN`, `zh-rTW`, `b+yue+Hant`.

## Translation provenance and quality

Translations are machine-generated (Claude, 2026-07) following the official Jellyfin
client's terminology conventions per language (e.g. de "Direktwiedergabe", fr "Lecture
directe", zh-CN 直接播放). Brand and format names (Jellyboost, Jellyfin, SyncPlay,
Chromecast, HDR, HEVC, AV1, codec names) are never translated. Plurals use the CLDR
quantity categories required per language (e.g. ru/uk/be/pl `one/few/many/other`, ar all
six, CJK `other` only).

They are a solid baseline, **not** native-speaker reviewed. If community review is wanted
later, the per-module `strings.xml` layout is Weblate-compatible (one component per module).

## Validation

`scripts/validate_i18n.py` checks every `values-<locale>/strings.xml` against its English
source: XML well-formedness, string/plurals name parity (respecting `translatable="false"`),
format-placeholder parity (`%1$s`-style, exact for strings, subset allowed for plural
items), legal plural quantities incl. `other`, and unescaped apostrophes/quotes. Run:

```
python3 scripts/validate_i18n.py --root . [--locales fr,de,...]
```

It runs green as part of the i18n change's verification; `aapt` re-validates escaping and
format-string safety on every build.

## Adding / changing strings

1. Add the English string to the owning module's `values/strings.xml` (mark
   `translatable="false"` if it must not be translated — e.g. `app_name`).
2. Add the translation to each `values-<locale>/strings.xml`, or accept the English
   fallback until translations catch up (missing entries fall back to the default locale
   at runtime; the validator will flag them as missing so the gap is visible).
3. Run the validator before committing.
