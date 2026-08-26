# Play Console — App Access Instructions

Draft text for Play Console's "App access" declaration, which asks how a reviewer can reach
all of the app's functionality. Jellyboost has no login of its own — it's a client for a
Jellyfin server — so these instructions point the reviewer at Jellyfin's own public demo
server, which requires no account request or waiting period.

## Reviewer instructions (primary — public demo server)

1. Install and open Jellyboost.
2. On the server-connection screen, enter the server address:
   `https://demo.jellyfin.org/stable`
3. Sign in with:
   - **Username:** `demo`
   - **Password:** leave blank (no password is set on this account)
4. Tap Sign In. No further setup is required — the demo server already has a populated
   library.

**Note on server resets:** `demo.jellyfin.org` is a public Jellyfin demo instance that resets
its state roughly hourly. If a review session spans a reset and the above credentials
unexpectedly stop working mid-review, waiting a few minutes and retrying is normally
sufficient — the server self-heals to the same `demo` / blank-password login within the
hour. This is a property of Jellyfin's public demo server, not of Jellyboost.

## What can be exercised on the demo server

- **Streaming** — browsing the library and playing movies/episodes/music directly.
- **Transcoding / quality picker** — the playback quality selector, which requests a
  server-side transcode when a non-original quality is chosen.
- **Downloads** — starting a download from an item's detail screen, including the quality
  selector described in the Play listing. Note: the public demo server denies
  `/Items/{id}/Download` requests (its `enableContentDownloading` policy is off for the
  shared account), so the app's fallback path — the same bytes fetched through the video/audio
  stream endpoint instead (see `DownloadUrlFactory.videoStreamUrl` /
  `DownloadUrlFactory.staticAudioUrl` in `data/downloads/src/main/kotlin/dev/jellyboost/data/downloads/plan/`)
  — is what will actually run during a demo-server download. This is expected behavior, not
  a bug: the download still completes and plays back offline, just via the fallback route.
- **SyncPlay** — creating or joining a SyncPlay group; since the demo account is shared, a
  reviewer testing with a second demo session (or a second device) can see two clients follow
  the same play/pause/seek state.
- **Music** — the music library browse/queue/shuffle/search experience, and offline music
  downloads (also via the streaming fallback above).

## What cannot be fully exercised on the demo server

- **Chromecast** — casting requires a physical or virtual Chromecast/Google Cast target on
  the same network as the reviewer's device; it cannot be demonstrated without such hardware
  present. The Cast button will appear whenever a Cast-capable device is discoverable, and
  standard Cast framework behavior (play/pause/seek/audio/subtitle selection, resume) applies
  once connected.

## Fallback — a server you control

*(Fill in before submitting, if the public demo server proves unsuitable for review — e.g.
if Play requires a dedicated non-shared account, or a server with `enableContentDownloading`
enabled so the direct `/Items/{id}/Download` path can be shown instead of the fallback.)*

1. Server address: `https://<your-instance>`
2. Username: `<your-instance-username>`
3. Password: `<your-instance-password>`
4. Notes for the reviewer: `<any server-specific notes — e.g. library contents, whether
   downloads/SyncPlay are enabled for this account>`

If this fallback block is used, remove or de-prioritize the public-demo-server section above
in the actual Play Console submission so the reviewer isn't given two conflicting sets of
credentials.
