# Investigation — can transcoded download size estimates beat the ceiling?

*2026-07-29 · answers "is there really no way to do better at estimating, including
adjusting the estimate during download?" All claims verified against jellyfin/jellyfin
`release-10.11.z` source, jellyfin-sdk-kotlin 1.8.12, and this repo's engine code —
not assumed. Analysis only; nothing below is implemented yet.*

## Verdict

**Pre-flight (before any bytes flow): no.** Jellyfin 10.11 exposes no size-prediction
surface at all — no endpoint answers "how big will this transcode be", progressive
transcode responses have no Content-Length by construction
(`FileStreamResponseHelpers.GetTranscodedFile` → chunked `ProgressiveFileStream`,
`Accept-Ranges: none`), and the vestigial `EstimateContentLength` plumbing is dead in
the progressive path. The deterministic ceiling + "up to X" wording is the right
enqueue-time answer.

**Mid-flight: yes, substantially, fully client-side.** The stream is MKV, whose
clusters carry absolute media timestamps (`0x1F43B675` / `0xE7`, ~every 5 s or 5 MB
with ffmpeg's muxer defaults). Scanning them as bytes arrive yields
`mediaSecondsReceived`, and

```
projectedBytes = bytesReceived × runtimeSeconds / mediaSecondsReceived
```

is exactly the "average output bitrate so far" — the same quantity ffmpeg itself
reports — with no extra requests, no session bookkeeping, no server-version
assumptions. Converges from today's 1.8–2.4× error to ±10–20 % within minutes and a
few percent by mid-file; clamped by the ceiling it can never be worse than today.

## Avenues, ranked

1. **Build (M):** MKV cluster-timestamp scanner (~150 lines + tests; a full
   `MatroskaExtractor` hookup is overkill) feeding a nullable `projectedBytes` on
   `DownloadEntity` (schema v6, additive) on the existing progress-throttle cadence.
   UI denominator `projectedBytes.coerceIn(bytesDownloaded, bytesTotal) ?: bytesTotal`;
   ratchet the displayed percent (never decrease), hold at 99 % until DOWNLOADED (the
   completion path already snaps totals to written bytes).
2. **Cheap add-ons (S each):**
   - *Sibling seeding:* completed episodes of the same series+quality are ground truth
     already in Room — after episode 1 lands, seed episodes 2..N's expected size from
     its bytes/runtime ("typically ~250 MB, based on 3 finished episodes"), alongside
     the ceiling. Conditioned and explainable — not the rejected global fudge factor.
   - *Remux detection:* we send `allowVideoStreamCopy=true`, so a HIGH download of an
     H.264 ≤1080p source under the cap is a stream copy — predictable from
     `mediaStreams` as ≈ source video bytes + 0.192 Mbps × runtime. Nearly exact.
   - *Pass `playSessionId`* on the transcode URL (SDK supports it; one line) —
     enables `stopEncodingProcess` and future correlation.
3. **Don't build:**
   - */Sessions polling* — real (`TranscodingInfo.Bitrate`/`CompletionPercentage` per
     deviceId) but a plain stream GET creates **no session**, so reports are silently
     dropped unless the client first posts capabilities; also last-writer-wins against
     simultaneous playback. Strictly dominated by the MKV scanner.
   - *Pre-flight probes* — no bounded-duration progressive request exists; a probe
     burns an ffmpeg spin-up and the opening minutes mismeasure (sibling episodes
     landed 1.80× vs 2.41× under identical settings). Transcodes restart-not-resume
     anyway, so the real download *is* the probe.
   - *Bytes/wall-time heuristics* — throttling is off by default server-side
     (`EnableThrottling = false`), so the encoder sprints ahead and the client can't
     tell whether it's network- or encoder-bound. No fixed relationship to media share.
   - *Global observed-ratio store* — stays rejected (DECISIONS.md 2026-07-29).

## Ecosystem check

jellyfin-web downloads originals only; Findroid doesn't transcode downloads;
Streamyfin's "accurate size" comes from a companion server that fully pre-transcodes
and serves a finished file. Nobody streams a transcode with an accurate live size —
the MKV-scanner approach would be novel but rests only on Matroska/ffmpeg muxer
invariants.
