# Investigation — can transcoded download size estimates beat the ceiling?

*2026-07-29 · answers "is there really no way to do better at estimating, including
adjusting the estimate during download?" All claims verified against jellyfin/jellyfin
`release-10.11.z` source, jellyfin-sdk-kotlin 1.8.12, and this repo's engine code —
not assumed. Avenue 1 and all three of avenue 2's items shipped the same day this was
written, 2026-07-29 — see `docs/features/download-quality.md` ("What a transcoded
download gives up" → "No exact size") for what actually landed, and "What shipped, and
what the build learned" below for what the build found that this analysis did not.*

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
   completion path already snaps totals to written bytes). **Implemented** —
   `MkvClusterScanner` + `TranscodeSizeProjector` + schema v6 (the scanner came in
   nearer 200 lines than 150 once every rejection path in "Don't build" below and the
   carry buffer earned their own tests, and the ratchet needed its own class,
   `DownloadProgressRatchet`, rather than a formula — see "What shipped" below).
2. **Cheap add-ons (S each):**
   - *Sibling seeding:* completed episodes of the same series+quality are ground truth
     already in Room — after episode 1 lands, seed episodes 2..N's expected size from
     its bytes/runtime ("typically ~250 MB, based on 3 finished episodes"), alongside
     the ceiling. Conditioned and explainable — not the rejected global fudge factor.
     **Implemented** — `DownloadEnqueuer.siblingSeed` + `DownloadDao.completedSiblings`
     (median of up to eight newest, not a simple average — one clip-show episode
     should not set the estimate).
   - *Remux detection:* we send `allowVideoStreamCopy=true`, so a HIGH download of an
     H.264 ≤1080p source under the cap is a stream copy — predictable from
     `mediaStreams` as ≈ source video bytes + 0.192 Mbps × runtime. Nearly exact.
     **Implemented** — `DownloadEnqueuer.remuxBytes` (turned out to be exact rather
     than "nearly": see the `CanStreamCopyVideo` gate list under "What shipped" below).
   - *Pass `playSessionId`* on the transcode URL (SDK supports it; one line) —
     enables `stopEncodingProcess` and future correlation. **Implemented** —
     `DownloadUrlFactory.transcodedVideoUrl` (a fresh `UUID.randomUUID()` per request;
     stored nowhere yet, so `stopEncodingProcess` itself remains future work).
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

   All four verdicts still stand after the build: nothing implemented above needed a
   session, a probe, or a wall-time heuristic, and sibling seeding stayed conditioned
   on show and quality rather than becoming the global store this rejects.

## Ecosystem check

jellyfin-web downloads originals only; Findroid doesn't transcode downloads;
Streamyfin's "accurate size" comes from a companion server that fully pre-transcodes
and serves a finished file. Nobody streams a transcode with an accurate live size —
the MKV-scanner approach would be novel but rests only on Matroska/ffmpeg muxer
invariants.

## What shipped, and what the build learned

This section records what implementation established that the analysis above had no way
to know, because none of it could be settled by reading source — it needed the build to
exist and, in one case, a real device.

**The exact `CanStreamCopyVideo` gate list, and its two null asymmetries.** The analysis
above treated remux detection as "nearly exact"; reading `EncodingHelper.CanStreamCopyVideo`
in jellyfin `release-10.11.z` end to end turned that into a hard rule with four conditions,
none of them a guess. The one worth remembering is the pair of asymmetries: a **null**
stream height fails the gate exactly as a too-tall one would, and a **null** stream bitrate
**also** fails it — the server's only escape hatch for a missing bitrate is a `LiveStreamId`,
and a download never has one. That is why `DownloadEnqueuer.remuxBytes` requires the
per-stream bitrate to be present rather than doing what would otherwise be the obvious
thing, deriving video bytes from the source's total file size: the server itself would not
have copied the stream under those conditions, and claiming an exact remux it would not have
granted would be worse than the estimate this replaces.

**The projection needed a monotone ratchet in the UI — a failure mode this analysis never
considered.** The old fixed ceiling could only ever be *raised* by later information (an
estimate proven too small), never lowered, so the displayed percentage could only ever
climb. A live projection breaks that assumption in a direction the analysis did not
anticipate: it can grow as well as shrink — a harder-to-encode scene, a sibling seed
corrected upward — and a growing denominator lowers the percentage shown even though not a
byte has been lost. Nothing in "Avenues, ranked" flagged this, because it only reasoned
about the number getting better, not about what displaying that number does to a progress
bar the user is watching move. `DownloadProgressRatchet` (`:feature:downloads`) exists
purely to absorb it: the shown fraction is the highest reached this session, held at 99 %
until the row is `DOWNLOADED`.

**The scanner's decisive validation rule is the cluster's first child id.** The analysis
above took the four-byte `Cluster` id (`0x1F43B675`) as the thing being scanned for and
said nothing about how a chance occurrence of it inside ordinary compressed frame data
would be told apart from the real thing — a question building the scanner could not avoid.
Of the seven checks `MkvClusterScanner` runs before believing a candidate, the one that
does the most work is requiring the cluster's first child element id to be exactly `0xE7`
(`Timestamp`). Matroska orders it first and every muxer in practice honours that, so four
id bytes plus this one fifth byte all having to line up is what makes a chance hit inside
frame data essentially impossible to accept, without the scanner ever needing to parse the
container at all.
