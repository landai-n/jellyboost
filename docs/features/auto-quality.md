# Auto quality (measured bitrate cap)

## What it does

The player's quality picker has always let the user cap the stream (High/Medium/Low/
Lowest force the server to transcode anything above the cap). "Auto" used to send no
cap at all, letting the device profile's 120 Mbps ceiling stand — which direct-played
high-bitrate files over links that could not carry them, with no way to adapt (a
progressive direct-play stream has no ABR renditions). Auto now measures actual
throughput to the server and negotiates with the measured rate (×0.8 headroom) as
`maxStreamingBitrate`, so a constrained link gets a transcode automatically while a
strong LAN still direct-plays. Manual picks are unchanged, and the chip still reads
plain "Auto" — the measured number is not surfaced in the UI (user choice; see
DECISIONS.md, 2026-08-15).

## How it works

- `AutoBitrateDetector.currentCap()` fetches ramped chunks (500 KB → 1 MB → 3 MB) from
  the server's `/Playback/BitrateTest` endpoint, timing each and stopping early once a
  chunk takes over 1 s. The whole measurement sits inside a 5 s budget — playback start
  waits on it, once per TTL window. Result = last completed chunk's rate × 0.8, clamped
  to 720 kbps (LOWEST's rung, so the decoder-fallback ladder can always step down)
  … 120 Mbps (the profile ceiling). Cached in memory for 15 min (single-flight mutex);
  the last good value is persisted in DataStore as a prior for fresh app starts. A
  failed measurement degrades: stale in-memory value → persisted prior → `null`
  (= the old uncapped behavior).
- Auto-ness travels as an explicit `autoBitrate` flag on `PlaybackResolveRequest` and
  `RemotePlaybackMediaSource`, set by the route-open helper (`playbackResolveRequest`),
  SyncPlay's `loadItem`, and `selectQuality(AUTO)`. `PlaybackInfoResolver` fills the
  cap from the detector when the flag is set (the ViewModel never blocks on the
  measurement); the picker chip derives from the flag, not from reverse-mapping the
  bitrate — a measured 8 Mbps must not render as "Medium" or swallow a genuine Medium
  tap.
- Cast Auto is deliberately unchanged (uncapped, `CastDeviceProfile`'s own conservative
  ceiling): the link that decides whether a receiver copes is the receiver's, not this
  device's. Measured-cap-for-cast is a noted follow-up.
- The decoder-fallback ladder (`DecoderFallbackHandler`) steps a non-rung measured cap
  down to the next rung below it; when the ladder fires, the retry request clears the
  flag so the chip shows the rung that is actually playing.

## Key classes

- `:player` `AutoBitrateDetector`
  (`player/src/main/kotlin/dev/jellyboost/player/bitrate/AutoBitrateDetector.kt`) —
  the measurement, cache, clamps, and failure degradation.
- `:player` `PlaybackInfoResolver`
  (`player/src/main/kotlin/dev/jellyboost/player/resolve/PlaybackInfoResolver.kt`) —
  `withMeasuredCap()` fills the cap for Auto requests before negotiation.
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

- `AutoBitrateDetectorTest` (`player/src/test/.../bitrate/`) — ramp arithmetic and ×0.8,
  early exit, both clamps, TTL reuse/expiry, single-flight, persistence, and the full
  failure-degradation order (stale → prior → null), on an injected clock under
  `runTest`.
- `PlaybackInfoResolverTest` — measured cap lands in the DTO and the device profile
  and the source carries the flag; cast Auto stays uncapped and never consults the
  detector; a hand-picked cap is never second-guessed.
- `PlayerAutoQualityTest` (`player/src/test/.../ui/`) — the flag/guard decoupling: a
  measurement landing exactly on Medium's rung still reads as Auto, tapping that rung
  is still a real change, returning to Auto sends flag + null cap, Auto→Auto is a
  no-op, and the fallback ladder's rung reads as manual.
- `PlayerTrackPickerTest` / `PlayerReopenRecoveryTest` — the flag survives track
  switches and reopen recovery (contract evolution logged in DECISIONS.md 2026-08-15).
- `DecoderFallbackHandlerTest` — a non-rung measured cap steps down to the next rung.
- Gap: the real `/Playback/BitrateTest` round trip and the SyncPlay group's tolerance
  of the one-time ≤5 s cold-cache delay are device-only — owed to the next device walk.
