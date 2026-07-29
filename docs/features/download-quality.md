# Download quality (M9)

docs/PLAN.md lists transcoded downloads under "Not v1", and the download pipeline's own file plan
(line 79) describes exactly one route to the media file: `/Items/{id}/Download`, "the original file
untouched". That is still the default and still the only thing the plan itself describes. What
shipped on top is a **download quality** preference that lets the user trade that exact copy for a
smaller server-side re-encode when the alternative is a season that does not fit on the device — see
DECISIONS.md, 2026-07-29, *"transcoded downloads ship after all, as a download quality setting"*. The
plan's exclusion was a scoping decision about M7's effort, not a judgement that the feature is wrong,
and the escape hatch is honoured: `ORIGINAL` is the default, it is byte-for-byte what the pipeline
always did, and every other step is one branch inside `DownloadFilePlanner.media()`.

---

## The ladder

`DownloadQuality` (`:core:common/model/DownloadQuality.kt`) is a four-entry enum:

| entry | video bitrate | max height | resumable | size known up front |
|---|---|---|---|---|
| `ORIGINAL` | — (source, untouched) | — | yes | yes, exactly |
| `HIGH` | ~20 Mbps | 1080p | no | no, estimated |
| `MEDIUM` | ~8 Mbps | 1080p | no | no, estimated |
| `LOW` | ~3 Mbps | 720p | no | no, estimated |

The three transcoded steps all share one stereo AAC track at 192 kbps — audio is a rounding error
next to the video, and stepping it down as well would only make dialogue worse for no measurable
saving. `maxHeight` travels with the bitrate rather than being left to the server, because 3 Mbps of
a 4K source is worse to watch than 3 Mbps of 720p.

**The video bitrates deliberately match `PlaybackQuality`'s** (`:player/model/PlaybackQuality.kt`),
the same 20 / 8 / 3 Mbps steps the in-app quality picker offers for streaming. A user who has already
learned what "Medium" looks like in the player is not asked to learn a second scale for downloads.

Every transcode targets the same shape regardless of step: H.264 video, AAC audio, muxed into
**Matroska** (`DownloadQuality.CONTAINER`) — the one codec combination every Android decoder handles
without a fallback, in the one container that is still a valid file while it is being written. See
*"Why the container is mkv and not mp4"* below; the short version is that mp4 was tried first and
produced a file Media3 refuses to open.

---

## The path a download takes

```
Settings screen                         AppPreferences.downloadQuality
  DownloadQualityGroup                    DataStore key "download_quality"
        │                                            │
        │ setDownloadQuality(quality)                │ read ONCE, on tap
        ▼                                            ▼
SettingsViewModel.setDownloadQuality  ──────►  DownloadEnqueuer.enqueue()
                                                      │
                                                      ▼
                                        DownloadEntity.quality  (stamped, Room)
                                                      │
                                     ── every later drain reads the ROW, never the preference ──
                                                      ▼
                                          DownloadQueue.reconcile()
                                                      │
                                                      ▼
                                        DownloadFilePlanner.media(quality = download.quality)
                                             │                          │
                                     quality == ORIGINAL          quality.isTranscoded
                                             │                          │
                                             ▼                          ▼
                              DownloadUrlFactory.mediaUrl()   DownloadUrlFactory.transcodedVideoUrl()
                              (/Items/{id}/Download)          (/Videos/{id}/stream.mkv?static=false…)
```

`AppPreferences.downloadQuality` is read exactly once per item — by `DownloadEnqueuer`, at the moment
the user taps *Download* — and stamped onto `DownloadEntity.quality`. Nothing downstream of that
point ever asks the preference again for that item: `DownloadQueue.reconcile` rebuilds the file plan
on every run (the server's base address rotates between LAN and remote addresses), but it always
rebuilds it with `quality = download.quality`, the value on the row. Changing the setting in Settings
therefore affects the *next* download the user starts, never the one already queued or running.

---

## Why the quality lives on the row, not read live

This is the one design decision worth dwelling on, and it mirrors a rule the pipeline already
followed for the file *name*: **Room holds the file plan, Room wins.**

`reconcile` has to be able to rebuild a download's URL on every drain — that part is real, and
necessary. If it also re-read the *quality* preference on every drain, a user who changed the
setting while a transcoded download was half-finished would have its next run resume
`/Items/{id}/Download` with `Range: bytes=<partial length>-`. That endpoint honours `Range` requests
against the *original* file. The result would not be an error: the server would happily serve
original bytes starting at the offset a transcode had written to, those bytes would be appended to
the transcoded ones already on disk, and the item would finish, get marked `DOWNLOADED`, and be a
silently corrupt file that plays for a while and then breaks or desyncs — with nothing anywhere
recording that anything went wrong.

Stamping the quality onto `DownloadEntity.quality` at enqueue time and never re-reading the
preference for an existing row closes that off: the row's quality is as immutable, for the same
reason, as the file name `DownloadQueue.reconcile`'s KDoc already documents keeping fixed across
retries (a re-plan that renamed a 1.38 GB partial file orphaned it and restarted the transfer from
zero, found on the M7 device walk — see that KDoc). Both are cases of the same rule: once bytes exist on disk under a
name, at a quality, the plan that produced them is the only plan allowed to keep touching them.

---

## The transcode URL

Requested through `videosApi.getVideoStreamByContainerUrl` as `/Videos/{id}/stream.mkv`, never as
`/Videos/{id}/stream?container=mkv`. **The container is in the path, not a query parameter** — that
is what makes the response one progressive `.mkv` file rather than an HLS playlist the download
pipeline has no way to reassemble.

Every encoding parameter is spelled out; a download has no `PlaybackInfo` session behind it and no
device profile to negotiate from, so the only way the bytes are predictable is to ask for exactly one
shape:

| parameter | value | job |
|---|---|---|
| `static` | `false` | this *is* the transcode request — `static = true` is what the plain video-stream fallback uses for the untouched original |
| `videoCodec` | `h264` | the one format every Android decoder handles without a fallback ladder |
| `audioCodec` | `aac` | decoded natively by every Android device, and legal in Matroska; no container remux surprises |
| `videoBitRate` | the step's bitrate | the ceiling the server encodes to |
| `maxHeight` | the step's height | paired with the bitrate so a low step is not just a blurrier full-resolution frame |
| `audioBitRate` | 192 kbps | fixed across every step |
| `maxAudioChannels` | `2` | a downloaded file is watched on the device's own speakers or headphones, never a home-theatre rig |
| `allowVideoStreamCopy` | `true` | if the source already fits the request (a 1080p H.264 file asked for at HIGH), the server copies the video track instead of re-encoding — a free remux |
| `allowAudioStreamCopy` | `false` | audio always re-encodes to the fixed AAC target, so every transcoded file's audio is uniform regardless of the source |
| `context` | `EncodingContext.STATIC` | see below |

**`context = STATIC` is the one non-obvious parameter, and it is the one that matters most.** A
`STREAMING` transcode is throttled by the server to roughly real time — the pace a player consumes
it at — which would make a two-hour film take two hours to download. `STATIC` tells the server to
produce the file as fast as it can, which is what a download (nobody watching it arrive) actually
wants.

---

## Why the container is mkv and not mp4

This shipped as `mp4` first, and every non-`ORIGINAL` download it produced was **unplayable**. The
reason is structural, not a server misconfiguration:

An MP4 keeps its sample index (`moov`) separate from its sample data (`mdat`), and the index cannot
be written until the size of the data is known. A muxer writing to a file it can seek back into
simply patches the header afterwards. A muxer producing bytes it is sending as it makes them cannot
— so it writes `ftyp → free → mdat` with the `mdat` size left as `0`, the encoding that means "this
box runs to the end of the file", and appends the `moov` behind it. That is exactly what came off the
test tablet for a `LOW` download.

Media3's `Mp4Extractor` takes the zero-sized `mdat` at its word: it treats the box as running to EOF,
which swallows the trailing `moov`, so the index is never found and preparation never completes. The
player reports

```
ParserException: Loading finished before preparation is complete, contentIsMalformed=true
```

Offline the item simply failed to play. Online it silently fell back to server streaming, which is
why the fault survived the first pass of testing — the download appeared to work everywhere the user
was likely to look.

**Matroska has no equivalent ordering constraint.** Every element declares its own size as it is
written, which is what makes it the basis of WebM and of every live-streamed mkv: the byte stream is
a valid file at every prefix and a complete one when the transfer ends. Media3 ships a full
`MatroskaExtractor`; `mkv` is already in this app's own
`DeviceProfileBuilder.SUPPORTED_CONTAINER_FORMATS` with h264 among its codecs; jellyfin-android's
device profile offers `mkv` as a transcoding container too. Server and device were always going to
agree on it.

MPEG-TS (`.ts`) would have been progressively valid as well and was the runner-up. It loses on three
counts: no duration metadata in the container, roughly 4 % packetisation overhead on every byte
downloaded, and it is a worse file for the user who plugs the tablet into a computer — which is the
stated point of the whole file-naming scheme.

Nothing else about the request changed: same codecs, same bitrate ceilings, same
`EncodingContext.STATIC`, same `static = false`. And `ORIGINAL` never went near any of this — it
fetches `/Items/{id}/Download` and keeps the source's own container and filename, exactly as before.

---

## What a transcoded download gives up

Two properties `ORIGINAL` has for free, and a transcode cannot:

### No exact size

The server has not encoded the file yet when the download starts, and a file being produced on the
fly has no `Content-Length` to declare — the response is chunked. `FileDownloader` already has a
convention for this: a declared length of `-1` (chunked) reports total `0`, its existing "unknown"
signal.

`DownloadEnqueuer.expectedBytes` fills the gap at enqueue time: for `ORIGINAL` it uses
`mediaSources[0].size`, the exact figure the server already knows; for a transcoded step it estimates
`runTimeTicks × (videoBitRate + 192 kbps) / 8` — the item's duration times the target bitrate,
divided into bytes. `DownloadQueue`'s private `ItemProgress` class then uses that estimate as a
**floor** for as long as *any* file belonging to the item still has an unknown real size, which for a
transcode is the whole transfer. It stops using the floor — and the exact sum of real sizes wins
instead — the moment every file has reported a real size, which is what stops a generous estimate
from leaving a finished item stuck showing something short of 100%.

An item with no `runTimeTicks` at all (a rare, malformed item) falls back to `0` rather than to the
source's own size: reporting the *original* size for a file the user is not going to receive would
promise a number that is simply wrong, where `0` renders as an honest indeterminate bar.

The estimate is a correct upper bound, not a prediction — the encoder routinely undershoots it on
easy content, so a Downloads screen row for a transcoded (non-`ORIGINAL`) download presents it as a
ceiling rather than an exact figure: *"75,0 MB of up to 552,4 MB"* while transferring, *"Waiting · up
to 552,4 MB"* while queued. An `ORIGINAL` row keeps the plain, exact wording, since its number is the
server's own reported size (DECISIONS.md, 2026-07-29).

### No resume

`/Videos/{id}/stream.mkv?static=false` ignores an HTTP `Range` header — it cannot seek into a file it
has not finished producing yet. `FileDownloader` already has to handle a server that ignores `Range`
for other reasons (some proxies do): when a ranged request comes back `200 OK` instead of
`206 Partial Content`, it truncates the file and rewrites it from zero rather than appending a second
copy on top of the first. A transcoded download interrupted midway hits exactly that path on its next
attempt — the failure mode is a repeated transfer, never a corrupt file.

`ORIGINAL` keeps the byte-exact, `Range`-honouring resume the M7 definition of done measures. The
guarantee is not weakened; it is a property of the quality steps a user has to deliberately opt into.

---

## File naming

`DownloadPaths.mediaFileName` names a transcoded media file `<directory> (<quality>).mkv` — for
example `Arrival (2016) (medium).mkv` — rather than reusing the source's own filename and container.
The source's name and extension describe a file that is never going to arrive; the transcode is a
different set of bytes with a different container, and naming it as if it were the original would be
a lie the user only discovers by opening it. Putting the quality in the name also means a later
re-download at a different quality lands next to the old file instead of silently overwriting it —
the same reasoning `reconcile` applies to keeping a stored file name fixed once bytes exist under it.

---

## The 403 fallback does not apply to a transcode

`DownloadQueue.downloadEssential` re-plans a `403` on the media file onto the plain static video
stream — the fallback for a user whose `enableContentDownloading` policy is off. That fallback is
skipped when `download.quality.isTranscoded`: a transcoded row never requested
`/Items/{id}/Download` in the first place, so a `403` on it is the server refusing the *transcode*
request itself, and re-planning onto the static-original fallback would silently hand the user the
full-size file they specifically asked the server to shrink. The 403 is simply an error for a
transcoded row.

---

## Schema

`downloads.quality` — `TEXT NOT NULL DEFAULT 'ORIGINAL'` — added by `@AutoMigration(4, 5)`,
`DatabaseConstants.DATABASE_VERSION` 4 → 5. Purely additive, so an existing install keeps its whole
queue across the upgrade: every row a pre-M9 build wrote reads back with `quality = ORIGINAL`, which
is the only value that build ever understood anyway.

`DownloadQualityConverter` persists the enum by `name`, the same convention every enum column in this
schema follows, with an unknown stored name decoding to `ORIGINAL` — a downgrade or an unrecognised
value can never leave a row unreadable.

---

## The settings UI

The Downloads section of the Settings screen (`:feature:settings`) gains a `SettingsChoiceGroup`,
`DownloadQualityGroup`, between the Wi-Fi-only switch and the storage line. One row per
`DownloadQuality` entry, the bitrate spelled out in the label itself rather than in a supporting
line — *"High — about 20 Mbps, 1080p"* — because the choice **is** four numbers, and a caveat line
underneath stating plainly that anything but the original is converted on the way out, its size
while downloading is an estimate, and an interrupted transfer starts again.

The setting applies to the **next** download the user starts, never the one currently running or
queued — `SettingsViewModel.setDownloadQuality`'s own KDoc says so, and it is exactly the
row-not-preference design above: `DownloadEnqueuer` stamps the quality once, at the moment of the
tap, and nothing downstream reconsiders it.

---

## Tests

| class | covers |
|---|---|
| `DownloadFilePlannerTest` | ("download quality (M9)") a quality below the original asking for a transcode instead of the download endpoint; every transcoded step carrying its own bitrate and height; a denied download policy *not* downgrading a transcode to the static stream; the transcoded media file being named for the container the server actually sends; quality changing the media file and nothing else in the plan |
| `DownloadPathsTest` | a transcoded download being named for the container it will actually receive; a transcoded download never being named `.mp4` (the regression above, pinned across every step); each quality getting its own file name |
| `DownloadEnqueuerTest` | ("download quality (M9)") the preference in force when the user taps Download being stamped on the row; an original download keeping the exact size the server reported; a transcoded download being sized from its runtime and bitrate instead; a transcoded download of an item with no runtime falling back to an unknown size |
| `DownloadQueueTest` | ("download quality (M9)") the plan being built from the quality on the row, not from the live preference; a 403 on a transcoded download not being retried on the static stream; an unknown file size falling back to the enqueue step's estimate; a generous estimate not leaving a finished item short of complete |
| `DataStoreAppPreferencesTest` | ("download quality (M9)") download quality defaulting to the original file; a download quality surviving a round trip through storage; an unrecognised stored download quality degrading to the original file; every download quality change reaching observers |
| `SettingsViewModelTest` | the download quality picker writing through to the preference store; a download quality changed upstream being picked up while the screen is open |
