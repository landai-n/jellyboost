# Play Console Foreground Service Declarations

Two foreground service types in Jellyboost require a Play Console declaration beyond the
manifest permission itself: `FOREGROUND_SERVICE_SPECIAL_USE` (SyncPlay presence) and
`FOREGROUND_SERVICE_DATA_SYNC` (WorkManager's download-notification service). Draft text for
both, plus the demo-video shot list Play requires for each.

## (a) FOREGROUND_SERVICE_SPECIAL_USE — SyncPlay presence

**Service:** `dev.jellyboost.player.syncplay.presence.SyncPlayPresenceService`
(`player/src/main/AndroidManifest.xml`).

The manifest already carries the required API 34+ `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` string,
which is the base of this declaration:

> Keeps a real-time SyncPlay watch-together group session alive while the app is in the
> background and nothing is playing. Leaving the group, or playback starting, releases it.

### Play Console "specialUse" justification (draft)

> Jellyboost implements Jellyfin's SyncPlay feature: a watch-together group where multiple
> Jellyfin clients play the same media in lockstep, coordinated by the user's own Jellyfin
> server. Membership in a SyncPlay group is a live, server-coordinated session — the server
> needs to be able to reach the device at any moment to relay a play/pause/seek command from
> another group member, or to add the device's own commands to the group.
>
> When the user backgrounds the app while a member of a group but nothing is currently
> playing (e.g. paused, or waiting for another member to start), Android's normal background
> network restrictions would drop the process's connection within roughly a minute, silently
> ending the user's group membership without any action on their part. This service exists
> solely to keep the process's network alive for that window — it does no other work, holds
> no additional permissions, and posts a persistent notification (with a Leave action) for
> the whole time it runs.
>
> It stops itself the moment either condition it exists for stops applying: the user leaves
> the group, or playback starts (at which point the existing `mediaPlayback` foreground
> service, already required for playback, takes over the same job).

### Why not another type (for the "why specialUse" field)

- **`mediaPlayback`** does not fit: this service exists specifically for the case where
  *nothing is playing*. Declaring it as media playback while no media session is active is
  Play's canonical example of misuse.
- **`connectedDevice`** does not fit: there is no Bluetooth/USB/network *device* being
  managed. SyncPlay group membership is a session with the user's own Jellyfin server (a
  normal HTTPS/WebSocket peer), not a connected accessory, and the permission
  `connectedDevice` requires would be broader than this service needs.
- **`dataSync`** does not fit, for two reasons: it is deprecated as of Android 15 and is
  capped at six hours of use per day starting with `targetSdk` 35, which does not match a
  session that can reasonably run for a long viewing session; and more fundamentally, this
  isn't a data transfer at all — no file or payload is being synced. It is a standing
  real-time coordination channel for a group session, which `specialUse` is precisely the
  escape hatch for.

### Demo video shot list

1. Sign in to a Jellyfin server that has SyncPlay enabled (or use the demo server — see
   `docs/store/app-access.md`).
2. From an item's detail screen, create or join a SyncPlay group.
3. With the group joined and media **not currently playing** (paused, or before playback
   starts), send the app to the background (home button / recent apps).
4. Show the persistent SyncPlay notification remaining visible while backgrounded, and
   ideally show group state (e.g. another member's action, or elapsed time) proving the
   session is still alive rather than dropped.
5. Bring the app back to the foreground and show the group membership intact — no
   re-join was necessary.

## (b) FOREGROUND_SERVICE_DATA_SYNC — download notification (Pause/Cancel)

**Service:** `androidx.work.impl.foreground.SystemForegroundService`, merged into
`dataSync` type by `data/downloads/src/main/AndroidManifest.xml` (WorkManager's own
service, promoted to foreground by Jellyboost's download worker so a multi-gigabyte
transfer survives past WorkManager's 10-minute background execution limit).

### Play Console "dataSync" justification (draft)

> Jellyboost lets users download their own media (movies, episodes, music) from their own
> Jellyfin server for offline playback. This is an explicit, user-initiated action — the
> user taps a Download control on an item they choose — never an automatic or background
> sync of unrequested content.
>
> While a download is in progress, WorkManager runs the transfer as foreground work (via
> its own `SystemForegroundService`) so the transfer isn't killed by WorkManager's
> background execution time limit, and shows a persistent, user-visible notification with
> real-time progress (percentage/bytes) and two controls: **Pause** and **Cancel**, both
> backed by `DownloadActionReceiver`. This matches Play's `dataSync` use case for "user
> initiated data transfer that should complete even if the user navigates away from the
> app," and it is scoped strictly to that: the service starts when a download is enqueued
> and stops once the queue is empty or the user cancels.

### Demo video shot list

1. Sign in to a Jellyfin server (demo server acceptable — see `docs/store/app-access.md`).
2. From an item's detail screen, start a download (pick a quality if the selector appears).
3. Show the download notification appearing with progress (percentage or a progress bar)
   updating over time.
4. Tap **Pause** on the notification; show the notification/queue reflecting the paused
   state.
5. Resume (or start a second download), then tap **Cancel**; show the notification
   dismissing and the item no longer in the active download queue.
