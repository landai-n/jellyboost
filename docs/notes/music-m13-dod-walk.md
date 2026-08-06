# M13 Music — device DoD walk

For the user to run on the test tablet (test tablet, Android 16 / API 36) against dev server 10.11,
once Phase 6 lands. Consolidates the plan note's 12-item Verification list
(`docs/notes/music-m13-plan.md`) with every per-phase "owed device check" recorded in `STATUS.md` —
this is the one walk to run instead of five partial ones. `git tag m13` still waits on M11 and M12
closing (docs/notes/music-m13-plan.md, "Gating").

## Setup

- Install the current build (`./gradlew installDebug`); wake the screen first — the OEM ROM refuses
  `adb install` with the screen off.
- Sign in against the dev server, and confirm the account has at least one music library with a
  multi-disc album, an artist with 5+ albums, a playlist containing tracks, and — for item 11 — a
  track the server has lyrics for (check `jellyfin-web` → the track's three-dot menu → "View
  lyrics", or that a `.lrc`/embedded-lyrics file exists next to the media).
- Logcat filter, running in a second terminal for the whole walk:
  ```
  adb logcat | grep -iE "jellyboost|MusicPlayback|PlaybackHandover|MusicStreamResolver|MusicSessionCallback|InstantMix|Lyrics|PlaybackReporter"
  ```
  Watch it for uncaught exceptions and for the two things called out per-step below (session
  ids, stop reports) rather than reading it end to end.

## Walk

**1. Library tile → tabs.**
Home/Libraries → the music library's tile opens `MusicLibraryScreen` with Albums/Artists/Playlists
tabs, square album cards. Rotate the tablet: grid column count adapts, nothing crops.
`[ ] PASS  [ ] FAIL`

**2. Album → tracks → artist → playlist.**
Open a multi-disc album: tracks are grouped under "Disc N" headers in disc/track order. Tap track 3
— playback starts there; letting it run advances through the rest of the album in order. Open the
artist from the album's byline: albums row + top tracks (5, "Show more" reveals the rest). Open a
playlist: view-only (no reorder/remove/add affordance), tracks in playlist order.
`[ ] PASS  [ ] FAIL`

**3. Background playback + lock screen.**
Start a track, background the app, turn the screen off. Playback continues. Lock screen /
notification shows art, title, artist, and working prev/pause/next. Unplug a wired/BT headset (or
use the notification's own stop if no headset is available) — playback pauses.
`[ ] PASS  [ ] FAIL`

**4. Shuffle / repeat.**
Shuffle an album from its header button: track order is visibly randomised (not the album's own
order), and the *first* track played is not always track 1 across repeated shuffles. In
`jellyfin-web` → Dashboard → Activity/Sessions, confirm the session reports shuffle on. Repeat-one
loops the same track past its end; repeat-all wraps from the last track back to the first.
`[ ] PASS  [ ] FAIL`

**5. Mini-player + queue sheet.**
With something playing, the mini-player docks above the bottom nav (or at the chrome edge in the
wide/landscape layout) on every top-level tab, survives navigating between tabs, and tapping it
opens NowPlaying. In the queue sheet (compact: the queue button; wide: the inline list): tapping a
track jumps to it, the remove button drops a track (removing the current one advances), the
up/down buttons reorder.
`[ ] PASS  [ ] FAIL`

**6. Direct play vs transcode.**
Play a flac track: the dashboard session reports **DirectPlay**. Play a track in a codec/container
this device profile does not accept directly (or force one, if the library has none): the dashboard
reports **Transcode**, and audio still plays with no gap or error.
`[ ] PASS  [ ] FAIL`

**7. Video ⇄ music handoff.**
Start music, then start a video (from Home or a library) while it plays: music stops. In the
dashboard, confirm there is **one** session for the handoff, not two overlapping ones (the logcat
filter should show exactly one stop report around the handoff, from `PlaybackHandover`). Back out of
the video to music (mini-player) and press play: the queue resumes where it left off, not from the
top. Repeat in the other direction (music while a video is open) — same clean single-session
behaviour.
`[ ] PASS  [ ] FAIL`

**8. Search.**
Search "artist" / "album" / "song" terms that exist in the library: sectioned results
(Artists/Albums/Songs/Playlists), each result navigates to the right screen.
`[ ] PASS  [ ] FAIL`

**9. Music downloads + offline (consolidates the Phase 5 owed items).**
- Download an album from its detail screen. Confirm in logcat / the Downloads tab that the request
  actually reaches `/Items/{id}/Download` for an audio item (not a fallback stream URL) — **this
  specific check was never verified against the dev server in Phase 5 and is the highest-priority
  item in this whole walk.**
- Also download a whole artist and a playlist.
- Airplane mode on. Browse artist → album → tracks entirely offline; play with full queue controls
  (next/previous/shuffle/repeat); album art shows on every offline card, not a placeholder.
- Delete the downloaded album, then delete one track of the downloaded artist's other album; confirm
  the offline artist page still shows the artist and whatever tracks remain — it must not vanish or
  dead-end.
- On the Downloads tab, confirm albums are grouped under a heading the way series episodes are, and
  this reads correctly in both orientations on the tablet.
`[ ] PASS  [ ] FAIL`

**10. Continue Listening.**
Play a track partway, back out without finishing it. Home shows it in *Continue Listening* and
tapping it resumes at the saved position, online. Repeat with a **downloaded** track, then go to
airplane mode first — Continue Listening still resumes it at the right position offline.
`[ ] PASS  [ ] FAIL`

**11. Instant Mix + lyrics (new in Phase 6).**
- Album screen → "Start radio": the queue is replaced by a server-generated mix seeded from the
  album (not the album's own tracks in order) and starts playing. Repeat from an artist's "Start
  radio" and from NowPlaying's. If the seed has nothing to build a mix from, a snackbar says so
  rather than nothing happening.
- Open NowPlaying on the track you confirmed has lyrics (see Setup): the lyrics affordance is
  visible (compact: a toggle button; wide: a Lyrics tab next to Queue) and switching to it shows
  the lyrics scrolling and highlighting in time with playback, not just static text. On a track
  with **no** lyrics, confirm the affordance is simply absent — no empty pane, no error.
`[ ] PASS  [ ] FAIL`

**12. Edges.**
- Join or start a SyncPlay group, then try to play music: refused with an on-screen message, and the
  group is unaffected.
- Favorite a track, an album, and an artist from within the app; confirm all three show as favorited
  in `jellyfin-web`.
- Kill the app (swipe away, not just background) while music is playing, then relaunch: the app
  comes back cleanly — either the mini-player/notification is gone or a sane idle state, no crash.
`[ ] PASS  [ ] FAIL`

## Minified build (`assembleRelease`)

Install the **release** build (`./gradlew installDebug -PbuildType=... ` — or `adb install` the
release APK directly) over the debug one and repeat items **3, 6, and 9** from the walk above:

- **3 (background/lock screen):** notification, prev/pause/next, background continuation.
- **6 (direct play vs transcode):** both play methods still resolve and play.
- **9 (downloads + offline):** an album download and the offline artist→album→tracks walk both
  still work under R8.

Watch the logcat filter above for `ClassNotFoundException` / `NoClassDefFoundError` /
`NoSuchMethodError` / a Room `_Impl` failure / a `kotlinx.serialization` failure — any of those
means a keep rule is missing; add a **targeted** rule and log it in DECISIONS.md rather than
widening `app/proguard-rules.pro` broadly.
`[ ] PASS  [ ] FAIL`

## After this walk

- Every item PASS: update this file's checkboxes, note the result in `STATUS.md`'s M13 section, and
  the milestone is device-verified. `git tag m13` still waits on M11 and M12 (see STATUS.md).
- Any FAIL: file it the way every other device-walk finding in this repo is recorded — a DECISIONS.md
  entry if it changes a decision, otherwise a STATUS.md known-issue — and re-run only the failed
  item(s) once fixed.
