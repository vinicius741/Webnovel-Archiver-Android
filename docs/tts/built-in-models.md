# Built-in voice models: Kokoro v0.19, Kokoro v1.0 and Phonon

Since 2026-11-16 the app offers three built-in voice models, each a separate engine in
Settings › Listening → Engine (Kokoro v0.19 retired on 2026-11-17, see below):

| Engine | Model | Runtime | Download | Voices |
|---|---|---|---|---|
| Kokoro (built in) | `kokoro-en-v0_19`, sherpa-onnx export | sherpa-onnx 1.12.21 (AAR) | 320 MB archive, 369 MB installed | 11 (US and UK) |
| Kokoro v1.0 (built in) | `kokoro-multi-lang-v1_0`, sherpa-onnx export, US lexicon | sherpa-onnx 1.12.21 (AAR) | 350 MB archive, 401 MB installed | 20 American (`af_*`, `am_*`); default Heart |
| Phonon (built in) | Gradium `phonon-31m-en`, f32 weights | `libptts_ffi.so` (ptts 0.7.1) through JNA | 113 MB of separate files | All 37 English voices of the catalog: 12 American, 13 British, 12 characters; default Harper |

## Why

Kokoro v0.19 was chosen in 2026-11 for running in real time on the phone. Once chapters were
pre-generated on the owner's computers, that limit went away, so on 2026-11-16 the owner compared
models by ear on a whole real chapter (Netherwitch ch. 9, all female voices, the app's own pauses,
equal loudness): Kokoro v0.19 Bella (the app's pack), Kokoro v1.0 through its official pipeline,
Phonon 31M and Ming-3.0-tts 0.5B. Verdict: Kokoro v1.0 is as good as or better than v0.19 (its
earlier rejection came from a poor setup), Phonon's Harper is liked, Ming was rejected. Heavier
models (Fish S2 Pro, Voxtral, Breeze, 3-5B) were left out: they do not fit the 16 GB Bazzite.

The official v1.0 pipeline (PyTorch + misaki) cannot run on a phone, and holds 1.6-2 GB per copy on
a computer. The app and the narration server therefore run v1.0 through sherpa-onnx with its US
English lexicon in front of espeak-ng. A speech-recognition check found the two equally clear
(0.61% of words heard differently for Heart, against 0.20% for v0.19 Bella); the owner's ear check
of the sherpa-onnx version is the "Kokoro v1.0 · app runtime" entry of the comparison page. British
v1.0 voices are not offered: the app loads the US lexicon, which would give them American
pronunciations.

## Speed (Bazzite, Ryzen Z1 Extreme, whole chapter, 2026-11-16)

| Engine | 1 copy × 8 threads | 4 copies × 2 threads | One core | Memory per copy |
|---|---|---|---|---|
| Kokoro v0.19 | 91 s | 65 s | 1.4x | 0.65 GB |
| Kokoro v1.0 (sherpa-onnx) | 73 s | 54 s | 1.6x | 0.65 GB |
| Phonon f32 | 36 s | 25 s | 7.3x | 0.35-0.65 GB |

In the narration server (one shared model, `--bench`): Kokoro v1.0 7.7x and Phonon 20.4x real
time with four sentences at once (peaks 1.0 GB and 0.75 GB). On the x86_64 emulator's four cores
Phonon's speed test measured 3.8x. Phonon's own figures: 24x on a Snapdragon 8 Elite Gen 5.

On the owner's Galaxy Z Fold7 (Snapdragon 8 Elite: six 3.53 GHz and two 4.47 GHz cores, all
"fast", so 8 threads), the speed test measured on 2026-11-16 (app 1.0.31, voice Harper):

| Run | Speed | Cores busy | Clock (% of top) |
|---|---|---|---|
| 1 copy × 8 threads, cool | 9.20x | 5.7 of 8 | 84 / 82 |
| 2 copies × 8 threads | 10.30x | 6.2 of 8 | 71 / 60 |
| 1 copy × 8 threads, warm | 5.78x | 5.5 of 8 | 55 / 41 |

The phone keeps far ahead of 1.8x listening even warm. The copies share Phonon's one thread pool
(`RAYON_NUM_THREADS`, sized once per process), so a second copy adds no threads and fills the gaps a
single sentence leaves: more throughput at lower clocks. Chapter generation on the phone therefore
runs Phonon two sentences at a time (`PhononCopies`: at least 6 fast cores and 1.5 GB free before
the second copy, about 170 MB, loads; one on the shared runtime and one on a narration-only copy).
Other thread counts cannot be measured in the app (the pool is fixed per process), and the warm
run shows sustained generation runs near 6x.

## How it fits together

- `tts/kokoro/BuiltInModels.kt`: `BuiltInModel` (engine id, label, `dirName` = folder under
  `tts_models` and the model's name on a server, runtime, voices, pinned download) and the
  `KokoroVoice` list per model. Voice identifiers are `<dirName>:<code>`. Voice keys:
  Kokoro `builtin:<model>|speaker=<n>|text=<KokoroTextPlanning.VERSION>` (v0.19's are unchanged, so
  existing packs and caches stay valid), Phonon `builtin:phonon-31m-en|voice=<Name>|text=<PhononTextPlanning.VERSION>`.
- `KokoroVoices` is the lookup facade: `isEngine` is true for any built-in model, `model(engine)`,
  `forIdentifier`, `voiceFor(model, id)`.
- `BuiltInEngines` (one in `AppContainer`): a `KokoroModelInstaller` and a `KokoroRuntime` per
  model. A runtime loads only while something speaks with it.
- `KokoroRuntime` wraps a `BuiltInEngine`: sherpa-onnx's `OfflineTts` (v1.0 with `lexicon`) or
  `PhononEngine` over `tts/phonon/PhononTTS.kt` (Gradium's JNA wrapper, kept as published).
  Phonon's runtime sizes its thread pool once per process (`RAYON_NUM_THREADS`), so it is always
  set to one thread per fast core (`PHONON_THREADS`), `refitThreads` does nothing for it, and its
  speed test runs only that setting. Each model keeps its own last speed-test report.
- `KokoroModelInstaller`: a sherpa-onnx `.tar.bz2` is downloaded, checked and unpacked as before;
  Phonon's separate files are each downloaded into staging (resumable), checked by SHA-256, then
  marked and swapped in.
- Text: Kokoro keeps `KokoroTextPlanning`; Phonon gets `PhononTextPlanning` (asterisks removed,
  quotes kept: its runtime normalizes numbers and splits sentences itself).

## No download needed

Choosing Kokoro v1.0 or Phonon does not ask for a download: chapters generate on the user's
computers. Every built-in model is offered on every phone, even one that cannot run it itself. The
model card offers **Download to this phone** where the phone can run it, which lets the phone
generate (alone, or standing in while no computer answers; without the model the stand-in is not
offered, and the Generate on card says so).

Story-description read-aloud and voice profiles were removed in November 2026. Live speech that
still asks for a built-in model that is not on the phone falls back to the phone's own engine
(`TtsEnginePlanning.standIn`).

Kokoro v0.19 is retired the same month (`BuiltInModel.retired`): it is no longer offered as an
engine, settings that chose it load as Kokoro v1.0 with the same voice when v1.0 has it
(`TtsEnginePlanning.withoutRetiredEngine`), and its generated audio keeps playing, since playback
never needs the engine. An installed copy can still be removed in Settings › Listening.

The Settings › Listening sample never stands in: it is there to hear the chosen voice. With the model not
on the phone (`TtsSamplePlanning.place`), the Try it card shows a chip per computer (one or several,
defaulting to Listen's Generate on choice, then all). Play sample sends every sentence to them
through `NarrationSampleRenderer`: one shared list, so two computers split the sentences, and once
it is empty an idle computer also takes a sentence another is still on, so a slow one does not hold
the sample up. A dropped connection gets one retry on whichever address answers; a computer that
cannot be reached, stays short of memory for 30 s on the clock, or fails unexpectedly leaves its
sentences to the others; a sentence fails the sample only after every live computer failed it twice.
Requests time out after 45 s instead of the chapters' 3 minutes. Clips are trimmed like a chapter's.
The card shows "Generating on … n of m sentences" (only the computers still working) with Cancel,
then plays the clips through a fresh `TtsPcmPlayer` with the draft's speed and pauses. Without a
computer it says how to add one instead of playing anything.

## Phonon's voices

All 37 English voices of Gradium's `phonon-voices` catalog (pinned revision): the owner's first
five picks (Harper, Everly, Bryony, Hadley, Juniper) first, then the other American and British
voices, then 12 stylized character voices labelled "(character)". A voice's code is its catalog
name without spaces (`CaptainSilasBlackwater`), which is also its file name, `voices/<code>.safetensors`,
on the phone and on servers; `BuiltInModels.kt` (`PHONON_VOICES`) and `setup.sh` list the same
voices with the same SHA-256 sums, and `BuiltInModelsTest` checks they agree. The catalog's 65
Spanish, French, German and Portuguese voices are left out: the app runs the English-only model.

A phone that installed Phonon before a voice was added keeps working through computers and shows
"New voices are available" with **Download new voices**: `KokoroModelInstaller` moves the files it
already has into staging (`KokoroModelFiles.missingFromOlderInstall`) and downloads only the new
ones (about 8 MB for the 32 added on 2026-11-16). A server gets them by running `setup.sh` again.

## Native code

- `android/app/src/main/jniLibs/{arm64-v8a,x86_64}/libptts_ffi.so` are built from xn-ptts v0.7.1 by
  `scripts/phonon/build-android-lib.sh` (Rust, cargo-ndk, NDK r28+). arm64 uses the dotprod and
  fp16 kernels (ARMv8.2) and 16 KB pages; on a CPU without them the library dies with SIGILL and
  takes the app with it. `KokoroModelFiles.isDeviceSupported(PHONON)` therefore requires a 64-bit
  primary ABI and, on arm64, `asimddp` plus `asimdhp`/`fphp` on every core in `/proc/cpuinfo`
  (`PhononCpu`); the runtime refuses to load otherwise. The x86_64 emulator running the arm64
  release build through translation reports those features but cannot run them (seen 2026-11-16):
  test Phonon there with the debug build, which ships the x86_64 library.
- JNA (`net.java.dev.jna:jna:5.17.0@aar`) calls it; `proguard-rules.pro` keeps JNA and
  `tts.phonon`.
- Licences: Phonon 31M weights and voices Apache-2.0; ptts MIT OR Apache-2.0; Kokoro Apache-2.0.

## Verified (2026-11-16, `webnovel_api36`, debug build)

- The engine picker lists Phonon and Kokoro v1.0 as "Generates on your computers"; choosing one
  asks for no download.
- The sample with Phonon chosen and not installed spoke through Google's engine (the saved Google
  profile). Replaced the same day: the sample now generates on the chosen computers first. With
  Phonon removed from the emulator, the Try it card showed the Bazzite chip; Play sample showed
  Cancel while Bazzite loaded Phonon and generated, then played (Stop playing, one audio stream
  started), and Stop ended the audio.
- A Gunwitch chapter generated on Bazzite's 3.0 server in Phonon Harper (6 min in about 30 s) and
  played; another in Kokoro v1.0 Heart (8 min).
- The connection test generated 15.2x real time with Phonon.
- Phonon downloaded (104 MB) and Kokoro v1.0 downloaded and unpacked (349 MB) on the emulator;
  both loaded there (Phonon's worker threads running; v1.0 with its lexicon).
- Phonon's speed test on the emulator: 3.8x with four threads.
- Release build (R8) signed with the debug key: JNA, `tts.phonon` and `NarrationServerModel` keep
  their names; Phonon was reached through JNA (then hit the translation SIGILL above).
- Reviewed by Fable 5.2; its findings were fixed (per-model memory learning on the server, idle
  models unloaded when another is needed, the missing-model hint, Phonon's fixed thread count, the
  voice choosing its own runtime, blank text, per-model speed-test reports, setup.sh checksums).

Not yet verified: Phonon and v1.0 on the Fold7 (arm64), and the release build on a phone.
