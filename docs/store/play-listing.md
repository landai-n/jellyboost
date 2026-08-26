# Play Store Listing Copy

Draft for review. Facts are grounded in `README.md`, `STATUS.md`, and the app/player/downloads
manifests — see each section for what backs it. Nothing here has been submitted to Play Console
yet.

## App title

Play Store titles are capped at **30 characters**.

| Candidate | Chars | Fits 30? |
|---|---:|:---:|
| `Jellyboost - A Jellyfin client` | 30 | **Yes — user's pick** |
| `Jellyboost for Jellyfin` | 23 | Yes |
| `Jellyboost: Jellyfin Client` | 27 | Yes |
| `Jellyboost — a client for Jellyfin` | 34 | No |

**Title: `Jellyboost - A Jellyfin client`** (user decision 2026-08-26) — exactly 30 characters,
so it fits the cap with nothing to spare; re-count before any edit. App name first, relationship
to Jellyfin stated plainly (helps with Play's unauthorized-content review, see below).
`Jellyboost for Jellyfin` (23) stays as the fallback if the cap or policy ever forces a change.
The name itself is settled (user decision 2026-08-26): of Jellyfin's two live branding pages,
the current one — jellyfin.org/docs/project/branding/ — expressly permits subcomponent names
("Jelly"/"fin" + another word, e.g. "Jellyseer", "Audiofin"), and it is treated as the truth
over the older page that discouraged the pattern.

## Short description

Play caps the short description at **80 characters**. Three candidates:

| Candidate | Chars |
|---|---:|
| `A 100% native Jellyfin client with offline downloads and SyncPlay.` | 66 |
| `Native Jellyfin client: seamless streaming, offline downloads, SyncPlay.` | 72 |
| `Stream or download from your own Jellyfin server. SyncPlay. Cast. Open source.` | 78 |

All three fit under 80. Recommendation: the first — it's the shortest, leads with "native" (the
key differentiator vs. the handful of WebView-based clients), and names the two features that
most distinguish Jellyboost (offline downloads, SyncPlay) without crowding in everything.

## Full description

Character budget: **4000**. Draft below is **3397 characters** (counted with `wc -m`).

Written defensively against Play's unauthorized-content / impersonation policy: the first
sentence states plainly that this is an unofficial, unaffiliated client that connects to a
server the user runs themselves, and that the app ships no content of its own. No language in
the draft implies free or unlimited access to commercial content — every mention of "your"
media, library, or server is deliberate.

```
Jellyboost is an unofficial, community-built client for Jellyfin, the free and open-source media server — it is not affiliated with, endorsed by, or sponsored by the Jellyfin project. Jellyboost connects to a Jellyfin server that you (or someone you trust) run yourselves, to play your own media library. The app ships with no content, no catalogue, and no media of its own: everything you see and play comes from the Jellyfin server you point it at.

WHY JELLYBOOST

Jellyboost is a 100% native Android app — Kotlin and Jetpack Compose from top to bottom, no WebView anywhere — built to feel like a first-class Android app rather than a wrapped web page.

Its defining feature is seamless online/offline integration in a single UI. Most Jellyfin clients treat "downloaded for offline" as a separate mode or a separate screen. Jellyboost doesn't: downloaded movies and episodes appear right alongside streamed content in the same Home, Library, Detail, and Search screens. Play what's on the server when you have a connection, play what's on the device when you don't, and never think about which is which.

FEATURES

• Unified streaming + offline UI — one Home, one Library, one Search, whether an item is streamed live or already downloaded to the device.

• Downloads with a quality selector — choose the quality or bitrate for a download right from the download action: the original file, or a smaller server-side re-encode, so a full season still fits when the original wouldn't.

• Full download management — an in-app queue with progress tracking, pause/cancel, storage-location choice, and easy deletion of what you no longer need.

• SyncPlay (watch-together) — join or start a Jellyfin SyncPlay group and watch in lockstep with other Jellyfin clients: play, pause, seek, and the queue itself are shared, coordinated by the server.

• SyncPlay with downloaded media — a movie or episode you've already downloaded can take part in a SyncPlay session from local storage, staying in sync with the group and reporting progress to the server like any other member.

• Chromecast support — cast to the default Google Cast receiver with play/pause/seek, audio and subtitle selection, the quality picker, resume, and progress reporting, all controlled from your phone or tablet.

• A full music experience — browse your Jellyfin music library, queue albums and tracks, shuffle and repeat, search your collection, and download music for offline listening, all with dedicated playback controls.

• Tablet-first design — built and tested on tablet as well as phone, with layouts that adapt to the extra space instead of just stretching a phone UI.

• 69 languages — the interface is localized into 69 languages beyond the base English, generated and reviewed as part of the build.

• No analytics, no ads, no tracking, no Jellyboost accounts — the app talks only to the Jellyfin server you configure. See the privacy policy for details.

OPEN SOURCE

Jellyboost is free and open-source software, licensed under the GNU General Public License v3.0 (GPL-3.0). The full source is available at github.com/landai-n/jellyboost — contributions, issue reports, and forks are welcome.

WHAT YOU NEED

A Jellyfin server (self-hosted or otherwise) that you have an account on. Jellyboost does not provide, host, or bundle any media, and cannot connect to anything other than a Jellyfin server you point it at.
```

## Grounding notes

- 100% native / no WebView, Compose + M3, Hilt/Room/Media3 architecture: `README.md`.
- Unified online/offline UI as the differentiator, download quality selector, SyncPlay (incl.
  with downloaded media), Chromecast: `README.md` "Highlights" section.
- Music (browse, queue, shuffle/repeat, search, offline downloads): `STATUS.md` M13 milestone
  entries.
- 69 locales: counted from `app/src/main/res/values-*` directories (69 translated locales beyond
  base `values/`).
- GPL-3.0: `LICENSE`.
- No analytics/ads/tracking/accounts, credentials on-device only, traffic only to the user's own
  server: no analytics/ads SDKs or third-party endpoints anywhere in the manifests or
  dependencies; `core/datastore`'s `EncryptedSecureCredentialStore.kt` is the only credential
  store, backed by `androidx.security.crypto`.
