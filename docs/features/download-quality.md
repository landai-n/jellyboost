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

`DownloadEnqueuer.sizeEstimate` fills the gap at enqueue time: for `ORIGINAL` it uses
`mediaSources[0].size`, the exact figure the server already knows; for a transcoded step it estimates
`runTimeTicks × (min(cap, source bitrate) + 192 kbps) / 8` — the item's duration times the bitrate
that will actually bind, since a transcode can never need more bits per second than the source
already carries (DECISIONS.md, 2026-07-29). `DownloadQueue`'s private `ItemProgress` class then uses
that estimate as a **floor** for as long as *any* file belonging to the item still has an unknown
real size, which for a transcode is the whole transfer. It stops using the floor — and the exact sum
of real sizes wins
instead — the moment every file has reported a real size, which is what stops a generous estimate
from leaving a finished item stuck showing something short of 100%.

An item with no `runTimeTicks` at all (a rare, malformed item) falls back to `0` rather than to the
source's own size: reporting the *original* size for a file the user is not going to receive would
promise a number that is simply wrong, where `0` renders as an honest indeterminate bar.

The estimate is a correct upper bound, not a prediction — the encoder routinely undershoots it on
easy content, which is why it is no longer the whole story a transcoded row tells. It is now only the
**opening** answer: a queued or freshly-started transfer still shows *"Waiting · up to 552,4 MB"* and
nothing else, because until bytes arrive there genuinely is nothing else to go on. From there a row
can do better in three different ways, and `DownloadItem.sizeCertainty` (`EXACT` / `APPROXIMATE` /
`CEILING`) is what decides which of three wordings — plain, `~`, or "up to" — is honest for the
figure underneath.

**The MKV scanner turns bytes already being copied into a live measurement.** A transcode is
Matroska, and Matroska writes an absolute media timestamp at the head of every cluster (`Cluster`,
`0x1F43B675`, and its `Timestamp` child, `0xE7`) roughly every five seconds or five megabytes with
ffmpeg's own muxer defaults. `MkvClusterScanner` (`data/downloads/.../engine/MkvClusterScanner.kt`)
is fed the same 64 KB buffers `FileDownloader` is already writing to disk — a new `MediaChunkSink`
tap wired into `FileDownloader.copy()`, and only when `appendFrom` is `0`, because a sink reading a
resumed transfer from its middle would see the tail of the stream and mistake it for the start — and
it keeps nothing more than the most recent of those timestamps. It is the timestamp of the newest
cluster *started* rather than finished, so it slightly understates the media actually received, which
in turn keeps the projection built on it slightly generous — the safe direction for a figure sitting
next to a progress bar. `TranscodeSizeProjector` then turns "bytes received" and "media time received"
into a size the same way ffmpeg reports its own progress on itself: `projectedBytes = bytesReceived ×
runtimeMillis / mediaMillisReceived`, recomputed on the existing `ProgressThrottle` cadence (every
500 ms or every 1 %) and clamped into `[bytesReceived, ceiling]`, the ceiling being the same
enqueue-time estimate this section already describes. The result is `null` until a first cluster has
been read, which is what keeps the opening state honest, and a projector is only ever built for a
transcoded, non-exact row with a known runtime (`DownloadQueue.projectorFor`) — an `ORIGINAL`
download's size is already exact, and a row the enqueue step already recognised as a stream copy
(below) would only be made worse by a measurement second-guessing an arithmetic answer.

A full `MatroskaExtractor` was deliberately not used for this. Media3 ships a complete one, but it
wants a `SeekMap`, an `ExtractorOutput` and a `DataSource`, it allocates per sample, and it gives up
on a container it does not fully understand — everything a general-purpose demuxer needs and nothing
this job does. The scanner needs exactly one integer and needs to survive garbage, so instead of
parsing the container it looks for two elements and refuses to believe a match until it has passed
every check going. A four-byte pattern occurs by chance roughly every 4 GB of random data, and
considerably more often inside real compressed frame data, so a candidate cluster is only accepted
once its size is a well-formed EBML size varint of one to eight bytes; once that size is either the
all-ones "unknown size" sentinel a live mux legitimately writes, or a plausible length of no less than
3 bytes and no more than 256 MB; once the cluster's first child element id is exactly `0xE7`, because
Matroska orders `Timestamp` first and every muxer in practice honours that ordering, which is what
makes a stray `1F 43 B6 75` turning up inside a video frame essentially impossible to accept; once the
timestamp's own length varint falls in `0x81..0x88`, a one-to-eight-byte unsigned integer; once every
one of those bytes is actually present in the buffer — a candidate that runs off the end of a chunk is
dropped whole and retried on the next one through a 21-byte carry buffer rather than half-consumed,
one byte short of the 22-byte longest candidate the scanner ever looks at; once the decoded value is
non-negative and no more than 48 hours, since nothing in a real library runs longer; and once the
resulting media time is not earlier than the newest one already accepted, because ffmpeg writes the
file linearly and a timestamp that goes backwards is a false positive rather than a rewind.
`TimestampScale` (`0x2AD7B1`) is read the same way, but only before the first cluster is seen — which
is where Matroska's Segment Info always sits — so a byte triple that happens to occur inside frame
data later can never move the clock; absent or implausible, it is left at the spec's own default of
1 000 000 ns per tick, one millisecond, which is also what ffmpeg writes.

**Sibling seeding gives an episode a head start before a single byte of it has arrived.** When a row
of a series has `DOWNLOADED` rows at the *same* quality to learn from, `SiblingSeeder`
(`data/downloads/.../SiblingSeeder.kt`) sets its `projectedBytes` from the finished ones: the
median of `landedBytes / runtimeMillis` over up to the eight newest completed siblings
(`DownloadDao.completedSiblings(seriesName, quality, limit)`, ordered `updatedAt DESC`, with runtimes
read back from the `items` cache), multiplied by this episode's own runtime and clamped by the same
ceiling every row already carries. The median rather than the mean, so one outlying clip-show episode
moves the estimate rather than setting it. This is deliberately **not** the global, learned
observed-ratio store that stayed rejected when the enqueue-time estimate was settled on
`min(cap, source bitrate)` instead of an empirical fudge factor (DECISIONS.md, 2026-07-29, *"the
transcode size estimate uses the source bitrate when it is under the cap"*): it is conditioned on
this show *and* this quality rather than applied globally, it is computed from rows the user can see
for themselves on the Downloads screen rather than from a hidden running average, and it can only
ever move the seeded figure *down* from the same deterministic ceiling every row already carries. A
film gets nothing — there are no siblings, and a director's other work is not evidence — and neither
does an `ORIGINAL` row, nor a row the enqueue step already recognised as an exact remux.

The seed is asked for at **three** moments, and it only works because it is all three. Enqueue time
alone was the shipped version, and it cannot serve the case the feature exists for: a whole season
queued in one tap has no finished sibling at the instant it is written, so every one of its rows
started — and stayed — on "up to X" however many episodes went on to land. So (1) `DownloadEnqueuer`
still seeds each row it writes, from whatever had finished before the tap; (2) `DownloadQueue` asks
again when it picks a row up, so the item now transferring starts from the seed instead of waiting
out the tens of seconds until the MKV scanner has a cluster to measure; and (3) when an item reaches
`DOWNLOADED`, `SiblingSeeder.seedPendingSiblingsOf` walks the rows still waiting on the same show at
the same quality (`DownloadDao.unseededSiblings`, which returns only `QUEUED`/`PAUSED` rows that have
no projection and no exact size) and seeds each one against its own runtime and its own ceiling. Both
new writes go through `DownloadDao.setProjectedBytesIfAbsent`, whose `projectedBytes IS NULL` clause
means a seed can never overwrite a live scanner measurement or an earlier seed — re-seeding is
additive or it is nothing — and neither touches `bytesTotal`, which stays the deterministic bound the
enqueue step promised. The row that is *already downloading* when a sibling lands keeps its in-memory
figure for the rest of that transfer: the DB is re-seeded under it, but its `ItemProgress` was built
at the start, and its own scanner is about to produce something better than a seed anyway.

**Remux detection turns some transcodes into an exact figure instead of an estimate at all.** The
transcode URL always sends `allowVideoStreamCopy=true`, so whenever the server can pass the source's
video track straight through instead of re-encoding it, the output size is arithmetic:
`runtime × (videoStream.bitRate + 192 kbps) / 8`. `DownloadEnqueuer.remuxBytes` claims this only when
all four of the following hold: the source's video codec is `h264`, matched case-insensitively and
exactly; the source stream reports a `height` at or under the quality step's `maxHeight`; the source
stream reports a `bitRate` greater than zero and at or under the step's `videoBitRate`; and the source
container is not `avi`. These were checked against `EncodingHelper.CanStreamCopyVideo` in jellyfin
`release-10.11.z` itself rather than assumed: that method runs roughly a dozen gates in sequence, and
for the exact URL this app sends, all but these four are inert, because the client never sends
`profile`, `level`, `maxRefFrames`, `maxVideoBitDepth`, `videoRangeType`, `framerate`, `maxWidth`,
`deInterlace`, `requireNonAnamorphic` or a subtitle stream index — every gate keyed to one of those
parameters simply does not fire for us. Two asymmetries are worth stating plainly, since both read as
surprising until the server's own logic is in front of you: a **null** stream height fails the check
exactly as a too-tall one would, and a **null** stream bitrate **also** fails it — there is a
`LiveStreamId` escape hatch in the server's own gate, and a download never has one — which is why this
rule requires the per-stream bitrate to be present rather than falling back to deriving video bytes
from the source's total file size, the way the plain ceiling estimate does. A row this identifies is
marked exact and is never handed a projector at all (`DownloadQueue.projectorFor`): an arithmetic
answer outranks a measured one, and re-measuring it would only turn a plain figure into a hedged one
for nothing.

Put together, a transcoded row now shows one of three things, and it is `DownloadItem.sizeCertainty`'s
job to decide which. `"X"`, plain, when the size is exact — an `ORIGINAL` download, or a remux the
enqueue step recognised outright. `"~X"`, the new `downloads_progress_of_approx` /
`downloads_size_approx` wording, once a projection exists — measured from the stream itself, or seeded
from finished siblings before the first byte has even landed. `"up to X"`, the wording this section
used to end on, is now only the opening state: what a queued or just-started transcode shows before
either mechanism has anything to say. And because the denominator behind that number can now grow as
well as shrink — a busy scene the encoder is working harder on, a sibling seed corrected upward by a
better measurement — the percentage shown on the Downloads screen no longer simply follows it.
`DownloadProgressRatchet` (`:feature:downloads`) keeps the displayed percentage for an item id
monotone for the session, holding it at 99 % until the row reaches `DOWNLOADED`, since nothing short
of that is allowed to draw a full bar. The deliberate consequence is that a restarted transcode —
which restarts from zero, since the server ignores `Range` on this URL — holds its bar at the height
the abandoned attempt reached rather than visibly retreating; an item that leaves the list, deleted
and later downloaded again, is simply forgotten and starts its ratchet over.

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

Schema v6 adds two more columns, both additive again: `projectedBytes`, a nullable `INTEGER` that
needs no default at all, since `NULL` already means "no projection yet" the moment a fresh row is
read; and `sizeIsExact`, a `NOT NULL INTEGER` with a SQL default of `0`. That default is the honest
reading of every row a pre-v6 build ever wrote: none of them could have known whether their size was
exact, so `false` — the size is a ceiling — is the only default that does not silently promise
something a v5 row was never in a position to prove. Both defaults being expressible in SQL is what
keeps this an `@AutoMigration(5, 6)` rather than a hand-written one: Room adds the columns and
backfills them with no migration code of its own, `DatabaseConstants.DATABASE_VERSION` moves 5 → 6,
and an existing install's whole queue reads back exactly as it did before the upgrade — a transcoded
row already mid-transfer simply starts the new session with no projection and its old "up to" wording,
and picks up the new one on its very next progress sample.

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
| `DownloadQueueTest` | ("download quality (M9)") the plan being built from the quality on the row, not from the live preference; a 403 on a transcoded download not being retried on the static stream; an unknown file size falling back to the enqueue step's estimate; a generous estimate not leaving a finished item short of complete. ("the live size projection, schema v6") a projection over the ceiling being clamped to the ceiling the enqueue step promised; a projection well under it being the one the row ends up carrying; the projection cleared once the media file is whole; an original download never projecting anything; a row the enqueue step marked exact not being second-guessed by the scanner; a seeded projection holding until the stream has a cluster of its own to offer; and a transcode of an item with no runtime having nothing to extrapolate to. ("sibling seeding at the moments the queue owns") a row starting with no projection being seeded before its first byte and starting its transfer from that seed rather than from the ceiling; a row that already carries one being left alone; an original download never being seeded; a finished item re-seeding the rows still waiting on it, and doing so *after* its own row is `DOWNLOADED` so it counts as evidence; a failed item seeding nothing; and a re-seed that throws not failing the download that triggered it |
| `DataStoreAppPreferencesTest` | ("download quality (M9)") download quality defaulting to the original file; a download quality surviving a round trip through storage; an unrecognised stored download quality degrading to the original file; every download quality change reaching observers |
| `SettingsViewModelTest` | the download quality picker writing through to the preference store; a download quality changed upstream being picked up while the screen is open |
| `MkvClusterScannerTest` | reading a cluster timestamp out of a single chunk, out of a stream fed one byte at a time, and out of a cluster or timestamp element split across a chunk boundary; the newest of several clusters winning and a repeated timestamp being harmless, which is what makes re-scanning the carry safe; a multi-byte timestamp decoded big-endian; `TimestampScale` defaulting to one millisecond per tick, being read once from Segment Info, and never being moved by a byte pattern occurring after the first cluster; and every rejection path — a cluster id occurring inside payload data, an invalid size varint, a first child that is not the timestamp, an implausible cluster size, a timestamp length Matroska cannot store, a timestamp going backwards, and one beyond any real runtime — leaving the last known value in place, alongside an unknown-size cluster (a live mux's own sentinel) being accepted |
| `TranscodeSizeProjectorTest` | no projection before the first cluster or the first byte; a zero media clock treated as no evidence rather than divided by; the projection reading as the observed bitrate extended over the whole runtime, and converging on the true size as more of the file arrives; the projection never exceeding the enqueue-time ceiling and never falling below the bytes already on disk; bytes past a ceiling that was too small projecting to themselves; a longer item projecting a larger file from the same measured bitrate; and bytes handed to the projector being passed straight through to the scanner |
| `DownloadEnqueuerSizeTest` | (split out of `DownloadEnqueuerTest` to keep that class under detekt's size limit) an original download's size being exact because the server measured it, against a re-encoded download's size being only ever a ceiling; a stream-copyable source being sized as video plus one AAC track, exactly, and every way a source fails to qualify — the wrong video codec, taller than the quality's `maxHeight`, above the quality's video bitrate, no per-stream bitrate at all, no video stream at all, an `avi` container — falling back to the ordinary ceiling instead, with an original download never treated as a remux whatever its streams say; sibling seeding from the median of finished episodes at the same quality, an even sibling count averaging the two middle rates, the seed scaled by this episode's own runtime rather than the siblings', the seed never exceeding the ceiling, siblings downloaded at another quality not counting as evidence, a series' first episode having nothing to be seeded from, a sibling whose runtime is not cached being skipped rather than guessed at, and a film, an original download, and a remux-exact episode each never being seeded |
| `SiblingSeederTest` | the median rate of finished siblings sizing one item, scaled by that item's own runtime and clamped to its ceiling; an item never being evidence for itself, a film never being asked about, an original download never being seeded, a sibling whose runtime is not cached being skipped, and a row with no ceiling having nothing to clamp to; and the pass a finished episode triggers — every waiting row of the same show seeded, each against its own runtime and its own ceiling, only the show and quality that finished being asked for, a waiting row with no cached runtime or no ceiling left alone, nothing written when nothing is waiting or when no sibling can be turned into a rate, and a finished film or original download seeding nothing |
| `SeasonSeedingScenarioTest` | the user-reported scenario end to end over a map standing in for Room: a season queued in one tap starting with nothing to be seeded from (and every row carrying the `seriesName` the sibling query matches on); the first episode to finish seeding the ones still waiting behind it, within its ceiling and without touching `bytesTotal`; a row already carrying a projection not being overwritten by a later sibling; rows of another show or another quality left alone; and `DOWNLOADED` or `ERROR` rows not counting as rows waiting for a size |
| `DownloadProgressRatchetTest` | a rising percentage passing straight through; a growing projection unable to make the bar retreat; the highest percentage reached this session being the one that keeps being shown; a transferring item held at 99 % however far it has run, and only a finished download drawing a full bar; a paused item keeping the height it reached rather than dropping; a restarted transcode holding its bar instead of falling back to zero; an item that leaves the list being forgotten, so a re-download starts over; each item ratcheting on its own; and an unknown total reading as zero rather than as complete |
| `DownloadRepositoryImplTest` | a projected size reaching the row that has to divide by it (denominator *and* wording together); a row with no projection still reporting its ceiling and saying so; a stream-copy row carried through as exact rather than as a ceiling — the three columns the wording is decided from surviving the Room round trip intact |
| `DownloadRowsTest` | which size a queue row shows — the ceiling with no projection, the projection replacing it, the projection clamped to the bytes already on disk and to the ceiling, and progress measured against the projection rather than the ceiling — and the four states its wording can be in: an original download and a stream copy both stating the figure plainly, a projection hedged as `~`, a bound stated as "up to", and a projection on an exact row unable to downgrade it |
| `SchemaMigrationTest` | (in `:core:database`, which has no androidTest source set, so this diffs the exported schema JSONs instead of running a real migration) the exported schema being the version the constants declare; v5 to v6 adding the projection columns and touching nothing else; v5 to v6 dropping no column and changing no type; `projectedBytes` being nullable, so an older row simply has no projection; `sizeIsExact` being `NOT NULL` with a SQL default, which is what keeps the bump automatic; and v6 adding no table and removing none |
