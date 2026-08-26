# Jellyboost Privacy Policy

*Last updated: 2026-08-26*

Jellyboost is a native Android client for [Jellyfin](https://jellyfin.org), the open-source
media server. It is an independent, community project and is not affiliated with, endorsed
by, or sponsored by the Jellyfin project. Jellyboost has no back end of its own: it is a
window onto a Jellyfin server that you (or someone you trust) already run. This policy
explains, in plain language, what the app stores, what it sends, and to whom.

## What Jellyboost stores on your device

Everything Jellyboost keeps is stored locally, in the app's own private storage:

- **Server address** — the URL of the Jellyfin server you connect to.
- **Username and auth token** — the token the server issues after you sign in, so you don't
  have to re-enter your password every time. Stored encrypted, using Android's
  `androidx.security.crypto` (an encrypted, hardware-backed keystore where the device
  supports one).
- **Playback positions** — how far you've gotten into something, so playback can resume
  where you left off, on this device and (via your server) on your other Jellyfin clients.
- **Downloaded media** — video and audio files you chose to download for offline playback,
  plus their artwork and metadata, stored in the app's own storage area.
- **Preferences** — app settings such as playback quality defaults, download quality
  choices, and UI preferences.

None of this ever leaves your device except as described in the next section.

## What Jellyboost transmits, and to whom

Jellyboost talks to exactly one place: **the Jellyfin server address you configure**. It
does not talk to any Jellyboost-operated service, because there isn't one, and it does not
talk to any third-party analytics, advertising, or crash-reporting service.

What gets sent to your server:

- **Credentials at sign-in** — your username and password, sent once to authenticate and
  obtain an auth token. Your password itself is never stored on the device afterward; only
  the token is.
- **Playback progress** — how far you are into something, so your watch/listen state stays
  in sync across your own devices.
- **Library and playback requests** — the normal calls a Jellyfin client makes to browse
  your library, request a stream or transcode, download a file, or take part in a SyncPlay
  session.

If you use Chromecast, playback commands and the media URL are also exchanged with the Cast
device on your local network, following the same Jellyfin-server address — Jellyboost does
not route cast traffic through any intermediary of its own.

## What Jellyboost does not do

- **No analytics.** Jellyboost does not collect usage statistics, telemetry, or behavioral
  data of any kind.
- **No ads.** There are no advertisements, and no ad SDKs are included in the app.
- **No tracking.** Nothing about your usage is tracked, fingerprinted, or profiled.
- **No third-party data sharing.** Jellyboost has no third party to share data with — it
  does not sell, rent, or otherwise disclose any information to anyone.
- **No Jellyboost accounts.** There is no sign-up, no Jellyboost user database, and no
  identity Jellyboost itself manages. Your account lives entirely on your Jellyfin server,
  under its own privacy terms and the control of whoever administers it.

## Transit security

Whether data in transit between your device and your Jellyfin server is encrypted depends
entirely on how that server is configured. Jellyboost recommends connecting over HTTPS
whenever possible. Because many Jellyfin servers are self-hosted on a home network and
reached over plain HTTP on a local network — a very common setup for self-hosted software —
Jellyboost also supports cleartext (unencrypted) HTTP connections, so it does not lock users
out of servers configured that way. If your server only supports HTTP, traffic between the
app and that server is not encrypted; this is a property of your server's configuration, not
something Jellyboost can change on your behalf.

## Data deletion

- **Signing out** clears the server address, stored credentials, playback-position cache,
  and preferences from the device.
- **Deleting a download** removes that specific file and its local metadata.
- **Uninstalling the app** removes everything Jellyboost has ever stored on the device,
  including all downloads, without exception — Android guarantees this for an app's private
  storage.

Because Jellyboost has no server of its own, there is no remote copy of your data for
Jellyboost to delete on request: nothing about you exists outside your device and your own
Jellyfin server.

## Children's privacy

Jellyboost is not directed at children and does not knowingly collect information from
children. Since the app collects no personal information of its own — everything described
above is either local to the device or is traffic to a server the user or their
guardian/administrator controls — there is no Jellyboost-side data to collect from anyone,
child or adult.

## Changes to this policy

If this policy changes, the updated version will be published at the same location with a
revised "Last updated" date. Material changes will also be noted in the app's release notes
where practical.

## Contact

Questions about this policy can be sent to `<contact-email>`. For issues or feature
requests, use the project's issue tracker at
[github.com/landai-n/jellyboost](https://github.com/landai-n/jellyboost).
