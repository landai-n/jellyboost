# M14 track 6 — styled ASS/SSA under Media3 (libass)

Spike verdict: **feasible with no player-engine swap, and implemented behind a default-off
preference.** The on-device validation this note ends with is still owed; until it is walked the
feature is experimental and the switch stays off for every install.

## What the baseline actually is

Not burn-in. `DeviceProfileBuilder.DEFAULT_DIRECT_PLAY_ASS` has been `true` since M5, so `ssa` and
`ass` are advertised as `EMBED` + `EXTERNAL` and reach the client intact; `CodecHelpers` maps both to
`MimeTypes.TEXT_SSA` and Media3's own `SsaParser` renders them. Server-side burn-in (`Encode`)
happens to an ASS track only when the file is already being transcoded for an unrelated reason — an
unsupported video codec, a bitrate cap, a decoder fallback. The subtitle format itself has never
forced a transcode here.

So the thing being improved is **fidelity under direct play**, not the avoidance of burn-in.
`docs/PLAN.md`'s track-6 blurb said "versus today's transcode burn-in"; that phrase was corrected in
the same change that added this note.

What Media3's `SsaParser` keeps, verified against `SsaStyle.java`: `Alignment`, `PrimaryColour`,
`OutlineColour`, `Fontsize`, bold/italic/underline/strikeout, `BorderStyle`, and — among inline
override tags — only `\pos`, `\move` (end position, not animated) and `\an`. Everything else inside
braces is stripped: font *family*, `\fad`, `\t`, `\clip`, `\frz`, `\blur`, karaoke (`\k`/`\kf`/`\ko`),
`\org`, per-event colour overrides, `ScaleX/Y`, `Spacing`, `Angle`, `Shadow`, margins. MKV font
attachments are ignored entirely. Upstream's tracking issue (ExoPlayer#8435) has been open and
low-priority since 2021, and media3 1.9/1.10/1.11 contain no SSA work at all.

## Chosen versions

| Artifact | Version | Why |
|---|---|---|
| `io.github.peerless2012:ass-media` | 0.5.1 | The Media3 extension around libass; the same release jellyfin-androidtv and Wholphin ship. |
| `io.github.peerless2012:ass-kt` | 0.5.1 (transitive) | libass 0.17.4 + HarfBuzz + FreeType + FriBidi + fontconfig, one `libass.so` per ABI. |
| `androidx.media3:*` | **1.9.0, unmoved** | `ass-media-0.5.1.pom` declares media3 1.8.0, so 1.9.0 satisfies it. The 1.11.0 in the research brief is jellyfin-androidtv's catalog, not the library's. |
| `androidx.media3:media3-effect` | 1.9.0 | Pulled transitively by `ass-media`; declared so it cannot resolve at 1.8.0 while the rest sits at 1.9.0. |

The authorised Media3 bump to 1.11.0 turned out to be both unnecessary and impossible:
`org.jellyfin.media3:media3-ffmpeg-decoder` still publishes nothing past `1.9.0+1`, and the
2026-07-28 decision pins Media3 to whatever that decoder has a build for.

## Shape of the integration

Three insertion points, all in code we already own, and all of them *wrapping* rather than replacing:

1. `AssSubtitleSupport` (`:player`, `@Singleton`) reads the `styledAssSubtitles` preference and hands
   back an `AssHandler(AssRenderType.OVERLAY_OPEN_GL)` — or `null`.
2. `ExoPlayerHandle.buildPlayer` wraps its existing `DefaultRenderersFactory` in
   `AssRenderersFactory` (which *appends* a `NoSampleRenderer`; the ordinary `TextRenderer` stays),
   and builds a second `DefaultMediaSourceFactory` carrying `AssSubtitleParserFactory` plus
   `DefaultExtractorsFactory().withAssMkvSupport(...)` for embedded MKV ASS and its font attachments.
   Our own `DataSource.Factory` is still the one underneath, so auth headers survive.
3. `PlayerScreen`'s `VideoSurface` adds an `AssSubtitleView` **inside** the `PlayerView`'s
   `SubtitleView`, beside Media3's own cue output rather than in place of it.

`OVERLAY_OPEN_GL` matches jellyfin-androidtv: full animation, HDR-safe (the `EFFECTS_*` modes are
not — androidx/media#723), rasterised off the UI thread, roughly half the memory of
`OVERLAY_CANVAS`.

Because there is exactly one process-wide `ExoPlayer`, the preference is read **once per player
build**. `ExoPlayerHandle` builds lazily and releases only when the video session ends *and* the
playback service is gone, so a change in Settings never reaches the playback on screen, and reaches
the next one only if the player was rebuilt in between. A live music session (or a cast session)
keeps the same instance alive across the handover deliberately, so a toggle flipped there waits —
which is why the switch says "with nothing else playing". The accepted staleness and the rejected
rebuild are recorded in DECISIONS 2026-08-28.

`CastPlayerHandle` is untouched and unreachable from any of this: it is a separate `PlayerHandle`
with no local surface, and a receiver renders its own subtitles.

## Ceilings

- **Sign timing.** libass-android #71: signs render ~0.5 s late, reproduced in both Wholphin and
  jellyfin-androidtv. The root cause is androidx/media#2289 — `TextRenderer.render()` is not called
  on video-frame boundaries — which Google closed as *wont fix: infeasible*. Unfixable from here, and
  a genuine regression against server burn-in for sign-heavy content. Measuring it on our hardware is
  device check 4.
- **Transcodes.** On an HLS transcode this app deliberately delivers subtitles as in-manifest VTT
  renditions to kill the drift the 2026-08-21 fix closed, so ASS never reaches the client there.
  libass therefore buys styled ASS on direct play, direct stream and offline downloads only. The
  `hlsTextSubtitles` branch of `subtitleProfiles()` was **not** touched.
- **Double-merged sources.** `MergingMediaPeriod` republishes each child format as
  `childIndex + ":" + id`, and `ass-media` strips exactly one such prefix when matching a selected
  format against the track it parsed. An item with audio sidecars *and* side-loaded subtitles is
  merged twice (`0:1:external:2`), so no track ever matches and libass would draw nothing at all.
  `styledAssSurvivesMerge` detects that shape and routes those items through the plain media-source
  factory: unstyled, but on screen. That is a downloaded multi-audio item with subtitle sidecars —
  a plausible anime case, so the guard is not theoretical.
- **Track ids.** The module's README asks for numeric `SubtitleConfiguration` ids "128 or bigger".
  The real constraint is its `availableTracks` map, keyed on `Format.id` across embedded (numeric MKV
  track numbers) *and* side-loaded tracks. Our `external:<index>` ids cannot collide with an MKV
  track number, so the scheme is kept as-is and `TrackSelectionController` is unchanged.
- **Size.** `ass-kt` ships four ABIs; arm64-v8a costs 3.04 MB `libass.so` + 17 KB `libasskt.so`
  (`libc++_shared.so` is already in the APK via the ffmpeg decoder). **~+3 MB per ABI**, paid whether
  or not the switch is on — the `.so` is packaged, it is simply never loaded.

## Failure handling

`AssSubtitleSupport.createHandler` touches `AssHandler.ass`, which is what forces
`System.loadLibrary("asskt")`. A failure there is *permanent* for the process — a missing ABI, a
stripped release, an OpenGL stack the renderer refuses — and there is a working alternative, so it
degrades to Media3's own `SsaParser` with a warning instead of failing the playback. The handler's
own constructor is lazy and would otherwise defer that error to the first subtitle sample,
mid-playback.

One R8 rule comes with the library: `AssMatroskaExtractor` reads `MatroskaExtractor.extractorOutput`
and `subtitleSample` reflectively from a **static** initialiser, and `ass-media`'s `proguard.txt` is
empty — unkept, the first embedded-ASS MKV would be an `ExceptionInInitializerError` in release
builds only.

## Found on device and fixed — no spaces between words (2026-08-28)

The first device run answered check (1) in the affirmative — styled ASS *is* drawn on the production
`PlayerView`/`SurfaceView` path at `OVERLAY_OPEN_GL`, so the Wholphin #1049 failure does not
reproduce here — and immediately produced a second finding: **the words ran together.** Glyphs,
colours, outlines and positions were all correct; only the inter-word gaps were missing.

**Root cause: `ass-kt` 0.5.1 ships a `libass.so` whose fontconfig has no configuration it can find
on a device.** The binary carries its build-tree paths verbatim —
`.../lib_ass/.cxx/RelWithDebInfo/<hash>/<abi>/etc/fonts`, `.../share/fontconfig/conf.avail` and
`.../var/cache/fontconfig`, all under the CI runner's home directory. None exists on Android, so
fontconfig logs *"No usable fontconfig configuration file found, using fallback"* and falls back to
a built-in document that lists `/system/fonts` and `/product/fonts` — but **no `conf.d` at all**,
and a cache directory that is equally absent.

Losing `conf.d` loses the generic-family rules, and `sans-serif` is precisely what libass asks for.
`AssKt.c` calls `ass_set_fonts(renderer, NULL, "sans-serif", ASS_FONTPROVIDER_FONTCONFIG, NULL, 1)`,
and libass's fontconfig provider builds its entire fallback list once, by running `FcFontSort` over a
pattern whose only family is `sans-serif` (`ass_fontconfig.c: cache_fallbacks`). With no alias to
resolve it, that sort degenerates into an arbitrary ordering of every font on the device;
`get_fallback` then serves each codepoint from the first entry in that order which covers it. Letters
are covered only by real text fonts, so they still come out right — which is why the rendering looked
correct. **U+0020 is covered by nearly everything installed**, including icon and clock faces whose
space advance is zero, so every inter-word gap was measured against whichever of those sorted first.

`default_font` is `NULL` in that same call, so libass has no last-resort font file either; there is
nothing below the broken sort to catch this.

**Fix: give fontconfig a configuration that exists.** `AssFontConfig` writes a small `fonts.conf`
into the app's `filesDir` and names it in `FONTCONFIG_FILE` (via `android.system.Os.setenv`) from
`AssSubtitleSupport.createHandler()` — before anything can load libass, because that variable is read
once, inside the `ass_set_fonts` call `AssHandler` makes when it builds its renderer. The file
carries three things:

1. the Android font directories (`/system/fonts`, `/product/fonts`, `/system_ext/fonts`,
   `/system/font`, `/data/fonts`; fontconfig ignores the absent ones);
2. **the `sans-serif` alias** — `Roboto`, `Noto Sans`, `Droid Sans`, `DejaVu Sans` — which is the
   load-bearing part: it is what turns `cache_fallbacks`' arbitrary sort into a real one;
3. a `cachedir` under the app's `cacheDir`, which is writable, so a session's first subtitle no
   longer pays an uncached scan of `/system/fonts`.

Same remedy, same platform gap, as ffmpeg-kit's `FFmpegKitConfig.setFontconfigConfigurationPath`:
Android ships no fontconfig configuration, and a library that assumes one has to be handed one.

Two things this deliberately is **not**: a bundled fallback font (unnecessary — `/system/fonts` is
present and readable, only unreachable through a broken sort), and a library bump (0.5.1 is the
latest release; nothing upstream since it touches fonts, and libass-android has no issue reporting
this). No sibling: `sans-serif` is the only generic family libass ever asks fontconfig for, both as
`family_default` and as the `cache_fallbacks` pattern. Attached MKV fonts are unaffected — they
reach libass through `ass_add_font` and its embedded provider, which never consults fontconfig.

`AssFontConfigTest` pins the document's directories, the alias and its preference order, the writable
cache directory, the rewrite-only-on-change behaviour, and — as a source check, since an `AssHandler`
cannot be built off a device — that the environment is set *before* the handler that reads it.

## Device checklist — still owed

Nothing below has run on hardware. Until it has, the switch stays default-off and this feature is
experimental.

1. ~~**Visible at all.**~~ **Passed 2026-08-28.** Styled ASS is drawn on the production
   `PlayerView`/`SurfaceView` path at `OVERLAY_OPEN_GL`; the Wholphin #1049 failure — its first
   integration was reverted because ASS was completely invisible in direct-rendering /
   hardware-overlay mode — does not reproduce here. The same run found the missing inter-word spaces
   fixed above, so **re-check spacing while walking the rest**: real gaps between words, and the
   default font a style whose family is absent falls back to (it should now be Roboto rather than
   whatever sorted first).
2. **Embedded MKV.** A direct-play H.264 MKV with an embedded ASS track: attached fonts, animation
   and karaoke all rendering.
3. **External sidecar.** A downloaded item's `.ass` sidecar rendering identically, with the
   `external:<index>` mapping still selecting the right track; and the double-merge guard confirmed
   on a downloaded item that has audio sidecars too (subtitles present, unstyled).
4. **Sign timing.** Measured offset against server burn-in on sign-heavy content, to quantify #71 on
   this hardware and decide whether it is acceptable.
5. **HDR / HEVC.** No regression on the HDR path, against the tablet's known decoder limits (no Main
   10 HEVC, hardware decode capped at 2560x1440).
6. **Stability.** Repeated surface resize, orientation change, PiP enter/exit, mid-playback track
   switching, and a memory check with `maxRenderPixels` tuned if needed.
7. **Cast inertness.** Routing to a Cast receiver with the switch on: libass must be inert, the
   receiver's own subtitles unaffected, and the transfer back must not leave a dead overlay.

If (1) or (4) fails, the honest outcome is to downgrade to *do not implement*, keep the code behind
its off switch or remove it, and record that in `DECISIONS.md`.
