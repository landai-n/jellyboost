# Polish punch list

All items below were addressed and device-verified on the test tablet on 2026-07-29
(two verification walks; evidence in the walk reports). Divergences and design
decisions are logged in DECISIONS.md; per-feature details in docs/features/.

# Bugs founds — all fixed ✅
- ~~Search page top texts are showing under the system icons~~ — fixed by the combined app bar (search content now sits below it).
- ~~Switching to offline mode still lists online media on the Home screen~~ — refresh signal now fires on both connectivity edges; verified switching in the same frame.
- ~~System status-bar text/icons drawn in black over the dark UI~~ — system bars pinned to dark style (light icons) incl. the splash window; verified in both system themes.
- ~~Downloading a season fails with error 400~~ — a season/series tap now expands into one download per episode; folder items can never reach the file endpoint; verified E1…E26 in order.
- ~~Download speed showing crazy numbers (100–180 MB/s)~~ — speed measured over ≥1 s windows; verified accurate against byte-count ground truth (~34 MB/s real).
- ~~Download pausing doesn't work~~ — PAUSED no longer overwritten to QUEUED on cancellation; verified bytes frozen across 20 s and clean resume.

# Polishing — all done ✅
- ~~Offline status bar takes real estate~~ — now a status icon in the app bar (per-reason icon + tap-for-snackbar with action; snackbar auto-dismisses).
- ~~Media description/metadata missing offline~~ — three fixes: browse writes can no longer gut a download's rich metadata; a full detail fetch repairs it; a standing sync refreshes all downloaded items' metadata each online stretch (also picks up server-side edits).
- ~~Media title showing twice~~ — title only in the content header now.
- ~~Media hero image positioning~~ — explicit center-crop.
- ~~Downloads page duplicate movie header~~ — only series get group headers; episode rows under a header drop the series prefix.
- ~~"Wifi only" toggle spacing~~ — fixed (toggle now lives in the storage header row under the combined bar).
- ~~No delete confirmation~~ — confirmation dialogs on the Downloads screen and the detail screen.
- ~~Combined navbar and top bar waste space~~ — one combined top bar for top-level destinations; bottom nav removed (~140dp reclaimed).
- ~~Settings subcategories look clickable~~ — choice-group labels restyled as subsection headings.
- ~~Scrolling media lists not smooth~~ — contentType everywhere, per-cell subcomposition removed, Coil memory+disk cache configured, artwork requested at display resolution. Measured: release build hits 0.49 % janky / 7 ms median at 90 Hz on a 500-poster grid — the residual lag is debug-build overhead (judge scroll feel on the release build; R8 + baseline profiles land in M10).

# Next steps — done ✅
- ~~General quality setting for offline downloads~~ — Original/High/Medium/Low (bitrate-capped H.264/AAC in MKV — the server's streamed mp4 mux is not playable as a file); quality stamped per download row (schema v5); transcodes restart rather than resume, by design. Verified: LOW episode downloads as `(low).mkv` and direct-plays offline.

# Todo next run
- Download size estimate are off my a wide margin, shouldn't be so hard to mulitply the bitrate by the duration of the media to get a rough estimate of the size.
  (Walk data point: a LOW episode estimated 552 MB, landed at 232 MB — the estimate uses the bitrate cap, not typical encoder output.)
- Media banner could take more space in portrait mode, right now we have a lot of empty space at the bottom
- Cancelling an in-progress season download silently deletes the episodes that already finished — should that ask for confirmation? (observed on the final device walk)
