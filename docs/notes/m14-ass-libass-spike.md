# M14 track 6 — styled ASS/SSA under Media3 (libass)

Spike verdict: **feasible with no player-engine swap, and implemented behind a default-off
preference.** The on-device validation this note ends with was walked on 2026-08-29 and is
**four-sevenths done**: rendering, fonts, spacing, the sidecar path, HDR inertness and stability all
pass; the double-merge guard, sign timing and the cast path are still open. Until those close the
feature stays experimental and the switch stays off for every install.

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

## Device checklist — walked 2026-08-29

Walked on the test tablet against the dev server, debug build of `53e31530`, switch on (verified at
the datastore, not the switch's rendered state — see the caveat under (0) below). **Four of the seven
pass, one is partial, one could not be measured as specified, one is blocked.** The switch stays
default-off and the feature stays experimental until (3)'s guard branch, (4) and (7) are closed.

0. **Reading the switch.** `uiautomator`'s `checked=` on the settings row was misread twice during
   this walk and sent a whole pass down the wrong branch. The authority is the preference itself:
   `adb shell run-as dev.jellyboost.app.debug cat files/datastore/app_preferences.preferences_pb`,
   where `styled_ass_subtitles` is followed by `12 02 08 01` for on and `12 02 08 00` for off. Check
   it before and after any A/B, and force-stop between toggles — the preference is read once per
   player build, so a relaunch that reuses the process keeps the old player and the old answer.
1. ~~**Visible at all.**~~ **Passed 2026-08-28**, and the **spacing re-check passes 2026-08-29**:
   real gaps between words, no *"No usable fontconfig configuration file found"* anywhere in the
   logs, and `SubtitleRenderer: Using font provider fontconfig` on every session. Where a style's
   family is absent, the fallback now resolves to Roboto — see (3).
2. **Embedded MKV — PASS.** A direct-play H.264 MKV (`DIRECT PLAY 1036P`, 13 font attachments,
   18 styles, 1468 events, `\blur`×383 / `\fax`×169 / `\fad` / `\fscx` / `\pos`) draws its title card
   with the attached display faces at their two different scales, positioned over the original art,
   and its lyric lines in the attached face in yellow bold-italic with the ASS outline. The A/B with
   the switch off is unambiguous: the same line falls back to the system face inside an opaque box,
   which is `SsaParser` keeping only colour and italic. **Karaoke is untested and untestable here** —
   no `\k`/`\kf`/`\ko` tag exists in any ASS track in this library; "animation" is covered only as
   `\fad`/`\blur`/`\fscx`, not `\t`.
3. **External sidecar — PASS for the styled branch, guard branch NOT exercised.**
   - A transcoded download writes `subtitle.<index>.<lang>.ass` sidecars; libass parses
     `Format(external:2, …)` and the player reports `1:external:2` — **one** merge prefix, which
     ass-media's single strip matches. Switching mid-playback to the second sidecar gives
     `2:external:3`, also matched. The `external:<index>` scheme survives intact.
   - An `ORIGINAL` download keeps the whole MKV, so its **embedded** ASS renders styled offline too.
   - **New finding: a transcoded download loses the MKV's font attachments.** The server's output
     carries video + audio only (verified with `ffprobe` on the file's header: `h264`, `aac`, no
     attachment streams), and the sidecar is a bare `.ass`, so libass has no embedded provider to
     ask. It logs `fontselect: Using default font family: (AG Foreigner-Roman, 700, 0) ->
     /system/fonts/Roboto-Regular.ttf, Roboto-Bold`. Positioning, colour, scale, blur and fades all
     survive; the **typeface does not**. That the fallback is Roboto-Bold rather than an arbitrary
     face is `AssFontConfig`'s `sans-serif` alias doing exactly its job.
   - The guard's own shape could not be built from this library. Audio sidecars exist only when
     `quality.isTranscoded`, and `DownloadEnqueuer.planQuality` downgrades a transcode to `ORIGINAL`
     whenever it would not save 10 % (`ORIGINAL_THRESHOLD = 0.9`). Every multi-audio ASS item here is
     low-bitrate enough to be downgraded — a 2.3 Mbps x265 episode estimates *larger* at `LOW`
     (475 MB against 379 MB, logged verbatim) — and the one item that does transcode has a single
     audio track. **To close this, a multi-audio ASS source above ~4 Mbps is needed**; the guard
     stays pinned only by `AssMergeCompatTest`.
4. **Sign timing — NOT MEASURED, and not constructible as written.** The check asks for an offset
   "against server burn-in", but this app has **no burn-in path**: on a transcode it delivers
   subtitles as in-manifest VTT by design (the 2026-08-21 drift fix), so ASS never reaches the client
   there and there is nothing in-app to measure against. An attempt to measure against the source
   file instead — screen-record the player, match each captured frame back to a source frame, read
   the cue onset off the match — failed on content: the ASS-bearing material here is near-static
   dialogue (mean inter-frame |Δ| ≈ 0.6 of 255 in the matching region), so many captured frames match
   the same source frame and onset cannot be resolved better than several frames. No gross (~0.5 s)
   lateness was visible in any capture, but **no figure is established and this check is not passed.**
   To close it: render a burn-in reference locally (`ffmpeg -vf subtitles=`, which is libass itself)
   from a **high-motion** sign-heavy source, play both, and match frames; or add a temporary
   frame-counter overlay to the debug build.
5. **HDR / HEVC — PASS.** A 4 K HDR10 HEVC Main 10 title is transcoded by the server (as the profile
   intends: the tablet has no Main 10 decoder) and decoded by `c2.mtk.avc.decoder` at 2560x1440, the
   known cap. With the switch on, libass stays **inert** on that path — `subtitle track disabled`, no
   `libasskt.so` load, no renderer — and nothing regressed.
6. **Stability — PASS.** Four orientation changes, PiP enter and exit (`AssHandler` follows the
   surface 2560x1384 → 896x484 → 2560x1384), and a mid-playback subtitle-track switch, all with
   styled ASS drawing. Native heap 50.9 MB → 53.5 MB across the whole pass, which is the second track
   being parsed rather than a leak; `maxRenderPixels` was left alone because nothing pressed on it.
   **Zero** `FATAL EXCEPTION` / `ExoPlaybackException` / `CodecException` /
   `ExceptionInInitializerError` across every capture in the walk.
7. **Cast inertness — BLOCKED.** No Cast receiver was reachable on the network during the walk (the
   picker offered none), so neither direction of the transfer was exercised.

If (4) — once it is measurable — or (7) fails, the honest outcome is still to downgrade to *do not
implement*, keep the code behind its off switch or remove it, and record that in `DECISIONS.md`.
