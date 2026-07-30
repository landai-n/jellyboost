# Offline multi-track downloads — design study (2026-07-30)

> **Implemented 2026-07-30 (phases 0–1).** Phase 2 is untouched and still awaits the `/Audio`
> endpoint check. Where the code differs from what is written below:
>
> - **Phase 0 is quality-conditional, not unconditional.** The `stream.isExternal` filter is gone
>   as described, but an *embedded* stream only earns a sidecar when the row's quality is
>   **transcoded**. At `ORIGINAL` the file being fetched already contains that track, so a sidecar
>   would be a duplicate download — and a second route to one picker entry, with the `external:`
>   id-match silently winning over the container's own copy. External streams are unchanged at every
>   quality.
> - **Selection did need work after all**, though not in `TrackSelectionController`, which already
>   tries the id-match first and is therefore correct by construction. What was missing was upstream:
>   `MediaStream.isExternal` is `false` for a sidecar-played *embedded* track, so the resolver would
>   have handed the controller a track flagged embedded and had it counted among container groups a
>   transcode does not have. `toTrack` gained a `sideLoaded` parameter and `LocalPlaybackResolver`
>   passes "has a sidecar". Pinned by two new `TrackSelectionControllerTest` cases.
> - **The DECISIONS question is answered: silent top-up.** `SubtitleSidecarTopUp`, driven by
>   `DownloadedMetadataRefresher`, fetches the missing sidecars of *finished* rows once per stretch of
>   connectivity. Re-queueing the row — the obvious alternative — would re-download the whole film:
>   a transcode ignores `Range`, so `FileDownloader` truncates and rewrites from zero. No UI action
>   was added.
> - **Phase 1 records the pin at enqueue and derives it at plan time.** `DownloadEntity`
>   `bakedAudioStreamIndex` (schema v8, `@AutoMigration(7, 8)` — the note below says 6→7, but the
>   retry work landed `attemptCount` as v7 first) is written by `DownloadEnqueuer`;
>   `DownloadFilePlanner` derives the same index from the same DTO through the shared
>   `BaseItemDto.downloadAudioStreamIndex` rule, because `DownloadQueue` (which rebuilds the plan on
>   every run) could not be touched in this change. `plan(audioStreamIndex = …)` is the seam for
>   passing the row's own column — and for the preferred-language choice phase 1 deliberately does
>   not ship.
> - The pin is `null` for `ORIGINAL`, **including** a row the transcode-fallback downgraded, and for
>   an item with no audio streams (the URL then omits the parameter).

Research follow-up to the offline track-selection fix (commit `27e9edc`). Question:
can a transcoded download keep all audio tracks and subtitles? **Not as a single
file — the server API makes that impossible — but yes overall, via sidecars.**
Nothing here is implemented; every phase needs a DECISIONS entry first, and none
of it belongs inside M10 as it stands (scoped divergence or M11 — user's call).

## Hard ceiling

`/Videos/{itemId}/stream.{container}` (SDK 1.8.12 `VideosApi`, full param list
checked) takes exactly **one** `audioStreamIndex` and **one** `subtitleStreamIndex`;
no repeatable form, no "all tracks" parameter. Jellyfin's transcoder maps one audio
stream and at most one subtitle. So the extra tracks must route *around* the video
file as separate downloads that Media3 merges back at playback.

## What the server already advertises (Élémentaire dto, server 10.11.11)

Embedded SRT streams 6 (FR forced) and 7 (FR full) have `IsTextSubtitleStream=true`
and `SupportsExternalStream=true` — `/Videos/{id}/{msId}/Subtitles/6/Stream.srt`
works; the server extracts with ffmpeg on demand. `DownloadFilePlanner` never asks
because it filters on `stream.isExternal`. Stream 8 (PGS) is the one genuine
casualty: bitmap, no OCR, ExoPlayer cannot side-load `.sup` — bitmap subtitles
survive only in an ORIGINAL download.

## Phases, in cost order

### Phase 0 — every text subtitle, offline, at any quality (small; no schema change)
`DownloadFilePlanner.subtitles()`: drop the `stream.isExternal` filter, keep the
`TEXT_SUBTITLE_CODECS` one, guard on `stream.supportsExternalStream`. Filenames
(`subtitle.<index>.<lang>.<format>`) and `streamIndex` already carry everything.
`LocalPlaybackResolver` then offers any subtitle whose sidecar is on disk (two-line
filter change). Selection needs no work — the `external:<index>` id round-trip is
verified and test-pinned. Élémentaire gains FR forced + FR full offline; this is
most of the perceived "subtitles are broken" complaint.

**DECISIONS question:** rows already on disk won't retroactively grow sidecars —
an old MEDIUM download holds fewer subtitles than a fresh one, with no repair path
short of re-downloading. Accept / "fetch missing subtitles" action / silent top-up
on next online open?

### Phase 1 — the *right* audio track, not all of them (small, high value)
Pass `audioStreamIndex` to `transcodedVideoUrl` (chosen from the item default or a
new preferred-audio-language preference) and record it in a new
`DownloadEntity.bakedAudioStreamIndex` column (additive `@AutoMigration` 6→7, same
pattern as the existing five). `LocalPlaybackResolver` labels the baked track from
the recorded index instead of assuming `DefaultAudioStreamIndex` (removes the
assumption flagged in the fix). Still one audio track per file, but the one the
user wanted — covers "give me the VO, not the dub" at a fraction of Phase 2's cost.

### Phase 2 — genuinely all audio tracks (the fiddly one)
Each additional audio stream as its own audio-only download, merged at playback:
- **Fetch:** `/Audio/{itemId}/stream.mka?audioStreamIndex=N&mediaSourceId=…&audioCodec=aac&maxAudioChannels=2&audioBitRate=192000&context=STATIC`
  (`AudioApi.getAudioStreamByContainerUrl`; `mka` not `m4a` for the moov-at-end
  reason documented on `DownloadQuality.CONTAINER`). ~90 MB per 2-hour track.
- **Storage:** new `DownloadFileType.AUDIO`; **no migration** — the converter
  decodes unknown names to the least-essential kind, so old builds degrade safely.
- **Playback:** `ExoPlayerHandle.prepare` builds
  `MergingMediaSource(adjustPeriodTimeOffsets=false, clipDurations=true, …)`
  instead of `setMediaItem`; `clipDurations` absorbs duration drift.
- **Track mapping:** `MergingMediaPeriod` prefixes each child's `TrackGroup.id`
  with `"<childIndex>:"` (confirmed in Media3 1.9.0 bytecode) — same trick as
  `external:` for subtitles, one level up.

Risks: `IllegalMergeException` on period-count mismatch is device-only glue unit
tests can't reach (needs the test tablet); the `MediaItem` API has no audio analogue
of `SubtitleConfiguration`, so `MediaSource` assembly moves out of
`ExoMediaSourceFactory` into `ExoPlayerHandle` — a real dent in the "URL selection
is pure and testable" property, which deserves an explicit decision.
**Load-bearing unverified assumption:** that `/Audio/{id}/stream.mka` accepts a
Movie item id on 10.11.11 and returns audio-only (the shared transcode path
suggests yes; one authenticated `curl` confirms it — do that before committing).

### Escape hatch that already exists
ORIGINAL downloads keep every track including bitmap subs, today. If Phase 2 looks
expensive, surface it in the quality picker — "keep all languages and subtitles →
Original" — so the trade is the user's rather than a silent loss.

## Suggested sequencing
Land 0 + 1 (small, no new server semantics, together they close the complaint),
verify the `/Audio` endpoint, then decide 2 on real evidence.
