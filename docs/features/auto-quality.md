# Auto quality (measured bitrate cap)

## What it does

The player's quality picker has always let the user cap the stream (High/Medium/Low/
Lowest force the server to transcode anything above the cap). "Auto" used to send no
cap at all, letting the device profile's 120 Mbps ceiling stand — which direct-played
high-bitrate files over links that could not carry them, with no way to adapt (a
progressive direct-play stream has no ABR renditions). Auto now measures actual
throughput to the server and negotiates with the measured rate (×0.8 headroom) as
`maxStreamingBitrate`, so a constrained link gets a transcode automatically while a
strong LAN still direct-plays. When that negotiation *does* come back a transcode, the
cap is walked back to High's 20 Mbps rung — the measurement describes the link, not the
encoder. Manual picks are unchanged, and the chip still reads plain "Auto" — the
measured number is not surfaced in the UI (user choice; see DECISIONS.md, 2026-08-15).

## How it works

- `AutoBitrateDetector.currentCap()` fetches ramped chunks (500 KB → 1 MB → 3 MB) from
  the server's `/Playback/BitrateTest` endpoint, timing each and stopping early once a
  chunk takes over 1 s. The whole measurement sits inside a 5 s budget — playback start
  waits on it, once per TTL window. Result = the **cumulative** rate (all bytes fetched
  over all time spent, the ramp-ending chunk included) × 0.8, clamped to 720 kbps
  (LOWEST's rung, so the decoder-fallback ladder can always step down)
  … 120 Mbps (the profile ceiling). Cached in memory for 15 min (single-flight mutex);
  the last good value is persisted in DataStore as a prior for fresh app starts. A
  failed measurement degrades: stale in-memory value → persisted prior → `null`
  (= the old uncapped behavior). Cumulative rather than last-chunk because a few
  megabytes are answered largely out of TCP's window: on the reference link the 3 MB
  chunk timed ~81 Mbps where a 30 MB pull sustained ~55 (DECISIONS.md, 2026-08-15
  amendment).
- **A measured cap is never a transcode's target.** When an Auto negotiation comes back
  `TRANSCODE` with a cap above HIGH's 20 Mbps rung, `PlaybackInfoResolver` re-negotiates
  once at that rung and returns the second answer. The cap doubles as the bitrate the
  server is asked to *produce*, and no measurement of the link can say whether the
  encoder-plus-link chain can produce it in realtime — measured 0.76× realtime at a
  64.7 Mbps target versus 2.50× at 20 Mbps. Direct play and direct stream keep the full
  measured cap (no re-encode to keep up with, and the high cap is the whole point);
  manual picks and cast are never touched; the re-negotiated request keeps
  `autoBitrate = true`, so the chip still reads "Auto". The abandoned first negotiation
  starts no encode — ffmpeg spawns on the first segment fetch, not on `PlaybackInfo`.
- Auto-ness travels as an explicit `autoBitrate` flag on `PlaybackResolveRequest` and
  `RemotePlaybackMediaSource`, set by the route-open helper (`playbackResolveRequest`),
  SyncPlay's `loadItem`, and `selectQuality(AUTO)`. `PlaybackInfoResolver` fills the
  cap from the detector when the flag is set (the ViewModel never blocks on the
  measurement); the picker chip derives from the flag, not from reverse-mapping the
  bitrate — a measured 8 Mbps must not render as "Medium" or swallow a genuine Medium
  tap.
- **Resolution is capped by the decoder, not just the bitrate.** `MediaCodecProbe` reads
  each hardware decoder's maximum frame size and `DeviceProfileBuilder` advertises it as
  `Width`/`Height` `LessThanEqual` conditions, so the server downscales transcodes to a
  hardware-decodable size and refuses oversize direct play (on the reference tablet:
  2560×1440-class; a 3840-wide transcode used to fall back to software decode and
  stutter regardless of bitrate). The codec profiles carrying these conditions are
  emitted **containerless, one per codec** — load-bearing, not tidiness: server 10.11.11
  was measured dropping container-bound codec profiles when sizing a Dolby Vision
  transcode (DECISIONS.md, 2026-08-15 second amendment and 2026-08-16 third amendment).
  Known edge: decoders report per-axis ranges (2560×2560), so portrait 4K video is
  under-constrained on the height axis.
- Cast Auto is deliberately unchanged (uncapped bitrate, `CastDeviceProfile`'s own
  per-receiver-class ceilings — see `docs/features/chromecast.md`): the link and decoder
  that decide whether a receiver copes are the receiver's, not this device's.
  Measured-cap-for-cast is a noted follow-up.
- The decoder-fallback ladder (`DecoderFallbackHandler`) steps a non-rung measured cap
  down to the next rung below it; when the ladder fires, the retry request clears the
  flag so the chip shows the rung that is actually playing.

## Key classes

- `:player` `AutoBitrateDetector`
  (`player/src/main/kotlin/dev/jellyboost/player/bitrate/AutoBitrateDetector.kt`) —
  the measurement, cache, clamps, and failure degradation.
- `:player` `PlaybackInfoResolver`
  (`player/src/main/kotlin/dev/jellyboost/player/resolve/PlaybackInfoResolver.kt`) —
  `withMeasuredCap()` fills the cap for Auto requests before negotiation, and
  `negotiateUnderTranscodeCeiling()` walks an Auto transcode back to
  `AUTO_TRANSCODE_CEILING` (= `PlaybackQuality.HIGH`'s rung).
- `:player` `PlaybackResolveRequest` / `RemotePlaybackMediaSource` — carry the
  `autoBitrate` flag through resolve → source → re-negotiation.
- `:player` `PlayerViewModel`
  (`player/src/main/kotlin/dev/jellyboost/player/ui/PlayerViewModel.kt`) —
  selection guard compares picker entries (not bitrates), `qualityOf()` derives the
  chip from the flag, fallback clears the flag.
- `:player` `PlayerApi.getBitrateTestBytes` (`player/src/main/kotlin/dev/jellyboost/player/api/`)
  — the probe seam.
- `:core:datastore` `AppPreferences.maxStreamingBitrate`
  (`core/datastore/src/main/kotlin/dev/jellyboost/core/datastore/`) — the persisted
  prior, under the long-reserved `max_streaming_bitrate` key.

## Server endpoints used

- `mediaInfoApi.getBitrateTestBytes(size)` — `GET /Playback/BitrateTest`, the
  throwaway payload the measurement times (same endpoint jellyfin-web's Auto uses).
- `mediaInfoApi.getPostedPlaybackInfo` — unchanged, but an Auto request now posts the
  measured value in both the DTO's `maxStreamingBitrate` and the device profile.

## Offline behavior

Local playback never enters `PlaybackInfoResolver`, so no measurement runs and nothing
changes offline: a downloaded item plays from disk with the quality control hidden, as
before. The persisted prior survives offline periods untouched and is only read when a
remote Auto resolve happens with no in-memory measurement.

## Test coverage

- `AutoBitrateDetectorTest` (`player/src/test/.../bitrate/`) — cumulative ramp arithmetic
  and ×0.8, early exit (and that the chunk which ended it still counts, bytes and time
  both), both clamps, TTL reuse/expiry, single-flight, persistence, and the full
  failure-degradation order (stale → prior → null), on an injected clock under
  `runTest`.
- `PlaybackInfoResolverTest` — measured cap lands in the DTO and the device profile
  and the source carries the flag; cast Auto stays uncapped and never consults the
  detector; a hand-picked cap is never second-guessed. Plus the transcode ceiling: an
  Auto transcode above the rung is negotiated twice (second at 20 Mbps, flag kept), a
  direct play keeps the full measured cap, and an Auto transcode already under the
  ceiling or a manual cap above it is negotiated once.
- `PlayerAutoQualityTest` (`player/src/test/.../ui/`) — the flag/guard decoupling: a
  measurement landing exactly on Medium's rung still reads as Auto, tapping that rung
  is still a real change, returning to Auto sends flag + null cap, Auto→Auto is a
  no-op, and the fallback ladder's rung reads as manual.
- `PlayerTrackPickerTest` / `PlayerReopenRecoveryTest` — the flag survives track
  switches and reopen recovery (contract evolution logged in DECISIONS.md 2026-08-15).
- `DecoderFallbackHandlerTest` — a non-rung measured cap steps down to the next rung.
- Gap: the real `/Playback/BitrateTest` round trip and the SyncPlay group's tolerance
  of the one-time ≤5 s cold-cache delay are device-only — owed to the next device walk.
