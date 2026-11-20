# Narration audio compression

Generated chapter audio was stored as raw 16-bit PCM: about 170 MB per hour. On the owner's phone,
294 novels' audio reached 20.6 GB (2026-11-19). Since 2026-11-20 every clip is stored as AAC-LC at
64 kbit/s, about 30 MB per hour, and packs made before that are converted in the background. This
document records how the codec was chosen and how it is stored.

## Choosing the codec (measured 2026-11-19/20)

Source: the app's own output, Kokoro v1.0 (sherpa-onnx `kokoro-multi-lang-v1_0`) voice Bella,
one sentence per request, for the whole of Netherwitch chapter 9 (about 8 minutes). Each codec
encoded every trimmed sentence separately, as packs store them. The decoded sentences were then put
back together with the app's pauses (50 ms phrase, 300 ms sentence, 450 ms paragraph, 500 ms
scene) and compared with the uncompressed chapter, paragraph by paragraph:

- **ViSQOL v3**, audio mode at 48 kHz (Google's full-reference codec metric; 4.73 means identical).
  It was built from source.
- **PESQ-WB** (ITU-T P.862.2; 4.64 means identical). It only hears up to 8 kHz.
- **UTMOS** (a naturalness predictor trained on synthetic speech), as the change from the original.

| Option | Size vs PCM | 20.6 GB becomes | ViSQOL | PESQ-WB | UTMOS change |
|---|---|---|---|---|---|
| PCM (before) | 1× | 20.6 GB | 4.73 | 4.64 | 0 |
| FLAC | 1/1.6 | 13.2 GB | 4.73 | 4.64 | 0 |
| AAC-LC 96k | 1/3.7 | 5.6 GB | 4.73 | 4.50 | 0.00 |
| **AAC-LC 64k (chosen)** | **1/5.4** | **3.8 GB** | **4.72** | 4.27 | −0.02 |
| AAC-LC 48k | 1/7 | 2.9 GB | 4.58 | 4.20 | −0.05 |
| Opus 48k, 48 kHz input | ~1/7.5 | 2.8 GB | 4.52 | 4.30 | +0.18 |
| Opus 32k, 48 kHz input | ~1/11 | 1.9 GB | 4.50 | 4.19 | +0.14 |
| Opus 32k, 24 kHz input | 1/11.5 | 1.8 GB | 4.27 | 4.19 | +0.14 |
| Opus 16k | 1/22 | 0.9 GB | 3.93 | 3.69 | −0.11 |
| Opus 12k | 1/29 | 0.7 GB | 2.29 | 3.41 | −0.33 |

Findings:

- Kokoro's output has energy up to 12 kHz, the top of a 24 kHz file, including a narrow vocoder
  hum near 10.8 kHz. Opus at 24 kHz input cuts everything above about 11 kHz at any bitrate, so its
  score levels off near 4.25. The uncompressed original with only that band removed scores 4.29,
  which is almost all of Opus's measured loss. Fed 48 kHz input (full-band), Opus keeps the band.
- AAC-LC 64k is the smallest option the metric cannot tell from the original. The owner's rule is
  that voice quality comes first, so it was chosen over Opus's extra 2× saving. Opus full-band
  (Android 10+ encoder, plus a resampling step) remains the option for going further, after a blind
  listening test.
- Android's AAC encoder (`c2.android.aac.encoder`, Fraunhofer FDK) does not enable afterburner.
  With it off, AAC 64k scores 4.716 against 4.719 with it on.
- The player's phrase pauses come from quiet gaps of at least 80 ms below amplitude 103. They were
  identical (all 179 sentences, 16 breaks) after AAC 64k.
- PEAQ (`peaqb`) was tried and dropped: it rated near-transparent files as annoying and the lossless
  file above the original. PESQ at 1.8× speed is unusable as well: time-stretching the original and
  the decoded copy separately throws their alignment off.

## Storage

`NarrationClipFormat` defines the clip files. A compressed clip (`NNNN.clip`, `title.clip`) is:

```
"WNAC" · version 1 · codec (1 = AAC-LC) · sample rate · frames · delay frames · setup length
setup bytes (the AudioSpecificConfig)
per access unit: 4-byte length + bytes
```

All integers are little-endian. `frames` is the exact length of the trimmed PCM. `delay frames` is
the codec's start-up delay. Decoding drops the delay and keeps exactly `frames`, so a clip plays and
times exactly like the PCM it came from, and pack indexes compute durations from headers without
decoding anything. A clip's access units must cover the delay plus its frames (1024 frames each,
`NarrationClipFormat.covers`): an encoder that dropped a sentence's end is caught at encoding (the
clip is stored as PCM), and a damaged length is rejected on reading. On FDK every clip has 14 to
1014 frames to spare. `read` and the header-only `info` share one header check.

Earlier packs hold `NNNN.pcm`: a 4-byte sample rate, then PCM. `WNAC` read as a sample rate is
about 1.1 billion, so the first four bytes tell the layouts apart. `NarrationStore.clipFile` returns
the `.pcm` name when that file exists, and the `.clip` name otherwise. So a version that an update
interrupted mid-chapter resumes with both kinds, and every older pack still plays.

## Codec (`AacNarrationCodec`)

- Mono AAC-LC at 64 kbit/s, raw access units (no ADTS, no MP4). The platform's Fraunhofer codecs
  are preferred so every phone stores the same audio: `c2.android.aac.*` (Android 10+), then the
  same codec under its OMX name `OMX.google.aac.*` (Android 8 and 9), then whatever
  `MediaCodecList` offers. The name that worked is kept for the process.
- Android reports no encoder delay. The first clip at each sample rate measures it: 12,000 frames
  of deterministic noise (`NarrationCodecProbe`) go through both codecs, and cross-correlation finds
  the lag (2048 frames for FDK). The match must be strong: the probe correlates at 0.96 through
  FDK AAC 64k and unrelated noise at most about 0.07, so the threshold is 0.5 and a codec returning
  garbage never yields a random delay. Each clip stores the delay it was encoded with. A failed
  measurement is remembered for the process, so a phone whose codec fails stores PCM without
  probing again for every sentence.
- The synchronous MediaCodec loop queues an input without waiting, then takes every output that is
  ready, and waits (2 ms at most, woken by the first output) only when it could not queue anything.
  The first version took one output per round and waited on input. The codec then sat on full
  output buffers, about 10 ms per AAC frame, 20 times slower. A stall is 2,500 rounds in a row with
  nothing moving, counted in rounds rather than time, so a process Android froze mid-clip does not
  fail when it thaws.
- Each clip gets its own codec instance. On the emulator, creating one costs about 8 ms, and a
  whole sentence encodes in about 20 ms.
- AAC-LC's standard sample rates (8–48 kHz) are compressed. A clip at any other rate, or one the
  encoder fails on, is stored as PCM, and the pack's index then says `"codec": "pcm"`.
- An empty clip is written as a compressed clip of 0 frames, with no access units.

## Generation and reuse

`NarrationGenerator` writes each new sentence through `NarrationStore.writeClip`, which compresses
it. When a chapter is regenerated in the same voice, unchanged sentences are copied with
`copyClip`. A compressed clip is copied byte for byte, never decoded and encoded again (a second
lossy pass would degrade it). A PCM clip from an older pack is compressed on the way. The pack
index (`indexFor`) reads durations from clip headers, and its `codec` is `aac` only when every clip
is compressed.

## Converting existing packs (`NarrationRecompressor`)

Once the library is ready at startup, the coordinator starts one pass over every pack whose index
still says `pcm`, and keeps at it until none is left that it can convert:

- One chapter at a time, largest first, on its own thread at background priority. That priority
  covers the app's feeding loop; Android's software codecs run in the system's media process.
- Before each chapter it waits until the generator is idle, and before each clip it checks again.
  Generation starting mid-chapter abandons that chapter's conversion without publishing anything
  (so the generator can still reuse the old clips). It counts neither as skipped nor as a failure,
  and the chapter is converted once the generator is idle again.
- `NarrationStore.recompress` writes a new version with the same recipe, durations and
  `generatedAt`, and says how it ended (`Recompression`). It never falls back to PCM: the first
  clip that cannot be compressed ends that chapter's conversion. It publishes the version only if
  the chapter's pack is still the one it converted (`publishIfCurrent`), and only if the generator
  is still idle at that moment. A regeneration or deletion in the meantime wins, and the
  conversion is dropped.
- Publishing, conversion and deletion take the store's publish lock for their file writes. The
  lock that guards the in-memory map is held only for map reads and updates, so screens reading
  packs never wait on a disk sync.
- Playback keeps reading the version it retained until it releases it. `retain` is atomic with the
  map: when a conversion replaced the pack after playback looked it up, playback retains and reads
  the replacement (same recipe and durations) instead of a folder that is being deleted.
- If the process dies mid-chapter, only that chapter's unfinished version is lost; the startup
  sweep removes it. The next start continues with the remaining PCM packs.
- A pack that cannot be compressed (a damaged clip, a sample rate AAC does not define) is skipped
  and stays as PCM; it never holds back the packs after it.
- Codec failures pause the pass instead of ending it. On the owner's phone (2026-11-20) the
  platform encoder threw a bare `CodecException` from `queueInputBuffer` four times while a
  chapter was playing, after 146 chapters converted fine; the old rule ("three in a row stop the
  pass until the next start") then left about half the PCM in place for days, because the process
  rarely restarts. Now three failures in a row pause the pass for 1, 5, 15, then 60 minutes; the
  chapters that failed are tried again after the pause, and a chapter is given up on (until the
  next start) only after three failures of its own. Each failure is logged as
  `narration_compression_failed` with the chapter and the codec's `errorCode`, `isTransient`,
  `isRecoverable` and diagnostic info; each pause as `narration_compression_paused`.
- Each conversion is logged as `narration_compressed` (bytes before and after), and the pass ends
  with `narration_compression_finished` (converted, saved, given up, remaining). The pass's thread
  ends with it.
- `NarrationRecompressor.status` (`NarrationCoordinator.compression`) publishes the phase
  (compressing, waiting for generation, paused until a time, done), chapters and bytes still PCM,
  and what this start converted and saved. Settings' "On this phone" card and Listen › Generated
  show it live through `CompressionStatusView`, worded by `NarrationCompressionText`: what is
  left, a progress bar, the space saved so far and where the audio total is heading (the
  remaining PCM shrunk by the ratio seen so far, or 17.5% before any conversion).

Free-space estimates for new chapters use 9 bytes per audio millisecond (64 kbit/s plus framing).

## Verification

- Unit tests: `NarrationClipFormatTest` (layout, damage, header info, trimming, delay search),
  `NarrationStoreTest` (compressed and legacy clips, mixed versions, copies, indexes, conversion
  with playback retention, regeneration and deletion races), `NarrationRecompressorTest`, and the
  generator's reuse tests. They use a lossless fake codec, because MediaCodec does not run on the
  JVM.
- Device test `AacNarrationCodecDeviceTest` runs Android's real codecs on the emulator. For 24 and
  22.05 kHz it checks the exact length, alignment (SNR above 15 dB on a voiced test signal), level
  (within 10%) and size (under a quarter of the PCM). It also checks an empty clip and the store's
  round trip.
- Emulator QA (2026-11-20): the debug library's 14 PCM chapters converted on the first start, with
  the same durations and `generatedAt`, and played back. The clips shrank about 5.6× (a chapter:
  18.5 MB to 3.3 MB; 245 MB saved in all). On disk the narration folder
  went from 300 MB to 67 MB (4.5×), because file-system blocks round up the small clip files.
  One chapter's clips, decoded on a computer, lined up at the stored 2048-frame delay and scored
  ViSQOL 4.71 against the original. A chapter of about 8 minutes converts in under 4 seconds on
  the emulator. The first build took 75 seconds, because its codec loop took one output per round
  and waited on input while the codec sat on full output buffers.
