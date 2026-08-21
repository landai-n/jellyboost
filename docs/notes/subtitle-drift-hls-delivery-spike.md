# Subtitle drift on transcodes, and the HLS-rendition fix

Spike notes, 2026-08-21. Server under test: the dev Jellyfin, **10.11.11**.
Landed as DECISIONS.md 2026-08-21, "a transcode's text subtitles ride in the manifest, not beside it".

## The bug

On an HLS transcode, external text subtitles were sideloaded as
`MediaItem.SubtitleConfiguration`s. Sideloaded cues bypass Media3's `TimestampAdjuster`, while the
transcode's A/V timeline re-anchors to Jellyfin's nominal (inaccurate) `EXTINF` grid on every seek
and track toggle and absorbs sub-200 ms audio gaps at unsignaled ffmpeg restarts. The two clocks
are therefore not the same clock, and the gap between them only grows — subtitles drift
progressively, worse the heavier the transcode and the more the user seeks.

Not fixable upstream: ExoPlayer #9046 was closed as "bad media", jellyfin #11825 as "not planned".

The fix is to stop having two clocks. Delivered as in-manifest HLS WebVTT renditions
(`SubtitleDeliveryMethod.Hls`), cues pass through the same `TimestampAdjuster` as audio and video —
the server emits `X-TIMESTAMP-MAP` on each VTT segment and transcodes with `CopyTimestamps=true` —
so drift becomes structurally impossible rather than something to correct.

Direct play and direct stream keep EXTERNAL sideloading: there is no re-anchoring timeline there
and nothing to drift.

## Verified server behaviour

1. **The two profiles are exclusive, and External wins.** Advertising
   `SubtitleProfile(format = "vtt", method = Hls)` *alongside* the existing External profiles →
   the server still picks External. Advertising `{vtt, Hls}` with **no** text External profiles →
   every text stream (an embedded `subrip` in the probe) comes back with
   `deliveryMethod = Hls`, `deliveryUrl = null`, and the `transcodingUrl` gains
   `SubtitleMethod=Hls&SubtitleStreamIndex=<n>`.

   (`StreamBuilder.GetExternalSubtitleProfile` returns the first matching profile in profile order,
   and External is always first in ours.)

2. **One rendition per text stream, in MediaStream-index order.** The master playlist then contains
   one `#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs"` entry per text stream, carrying `NAME` (from
   `displayTitle`), `LANGUAGE`, `DEFAULT`, `FORCED`, `AUTOSELECT=YES`. All renditions are present
   even when no subtitle is selected; the server marks one `DEFAULT=YES` — its own default choice,
   not ours.

3. **The rendition media playlists are VOD, 30 s VTT segments**, fetched as
   `stream.vtt?CopyTimestamps=true&AddVttTimeMap=true&StartPositionTicks=…`. The first segment
   starts:

   ```
   WEBVTT
   X-TIMESTAMP-MAP=MPEGTS:900000,LOCAL:00:00:00.000
   ```

4. **Consequence of (1): the rendition profile cannot be the one global profile.** Dropping text
   External from it would make a direct-playable item with a sidecar `.srt` negotiate subtitle
   delivery `Encode` — burning the subtitle in and forcing a full transcode of a file that needed
   none. Hence the two-pass negotiation in `PlaybackInfoResolver`.

### Sample master playlist (shape)

```
#EXTM3U
#EXT-X-VERSION:3
#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="English",DEFAULT=YES,FORCED=NO,AUTOSELECT=YES,URI="…/Subtitles/2/subtitles.m3u8?…",LANGUAGE="eng"
#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="French",DEFAULT=NO,FORCED=NO,AUTOSELECT=YES,URI="…/Subtitles/3/subtitles.m3u8?…",LANGUAGE="fra"
#EXT-X-STREAM-INF:BANDWIDTH=…,CODECS="avc1…,mp4a…",SUBTITLES="subs"
main.m3u8?…
```

### Sample rendition media playlist (shape)

```
#EXTM3U
#EXT-X-PLAYLIST-TYPE:VOD
#EXT-X-TARGETDURATION:30
#EXT-X-VERSION:3
#EXT-X-MEDIA-SEQUENCE:0
#EXTINF:30.000000,
stream.vtt?CopyTimestamps=true&AddVttTimeMap=true&StartPositionTicks=0
#EXTINF:30.000000,
stream.vtt?CopyTimestamps=true&AddVttTimeMap=true&StartPositionTicks=300000000
…
#EXT-X-ENDLIST
```

## What this means for the client

- **Two-pass negotiation** (`PlaybackInfoResolver.negotiate`). Pass 1 is the honest question and its
  answer decides whether pass 2 is worth asking at all. Pass 2 runs only on
  `TRANSCODE ∧ something-side-loaded ∧ !castTarget`, and any way it disappoints keeps pass 1's
  answer. Neither pass starts an encoder: ffmpeg is spawned by the first *segment* fetch.
- **Selection is positional.** Media3 ids a subtitle rendition `"<GROUP-ID>:<NAME>"`, which carries
  no Jellyfin stream index, so `TrackSelectionController` matches renditions the way it matches
  embedded tracks — by position among the text groups, which is master-playlist order, which is
  MediaStream-index order, which is the order `subtitleTracks` is in. An HLS-delivered *sidecar
  file* is therefore **not** `isExternal`, whatever `MediaStream.isExternal` says.
- **Burned-in subtitles must not be counted.** Jellyfin builds renditions only for
  `IsTextSubtitleStream`, so a graphical subtitle it had to `Encode` has none; the resolver marks
  such a track side-loaded so it takes no place in the positional count and a selection of it
  re-resolves instead.
- **"Off" has to be explicit.** Renditions are `AUTOSELECT=YES` with one `DEFAULT=YES`, so a
  cleared selector picks one on its own — unlike a `SubtitleConfiguration`, which never carries
  `SELECTION_FLAG_DEFAULT`. The player already does the right thing:
  `ActiveSession.pendingSubtitleApply` is set on every open and `selectSubtitle(null)` disables the
  whole text renderer.
- **SSA/ASS lose their styling** on a transcode — the server converts them to WebVTT for the
  rendition. Accepted: ExoPlayer's SSA renderer ignores most positioning and styling anyway, and
  the alternative is drifting cues.
