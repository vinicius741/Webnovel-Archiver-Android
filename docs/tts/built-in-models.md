# Built-in voice models: Kokoro v0.19, Kokoro v1.0 and Phonon

Since 2026-11-16 the app offers three built-in voice models, each a separate engine in
Voice & Speech → Engine:

| Engine | Model | Runtime | Download | Voices |
|---|---|---|---|---|
| Kokoro (built in) | `kokoro-en-v0_19`, sherpa-onnx export | sherpa-onnx 1.12.21 (AAR) | 320 MB archive, 369 MB installed | 11 (US and UK) |
| Kokoro v1.0 (built in) | `kokoro-multi-lang-v1_0`, sherpa-onnx export, US lexicon | sherpa-onnx 1.12.21 (AAR) | 350 MB archive, 401 MB installed | 20 American (`af_*`, `am_*`); default Heart |
| Phonon (built in) | Gradium `phonon-31m-en`, f32 weights | `libptts_ffi.so` (ptts 0.7.1) through JNA | 104 MB of separate files | Harper (default), Everly, Bryony, Hadley, Juniper |

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
Phonon's speed test measured 3.8x. Phonon's per-core speed suggests a phone could keep ahead of
1.8x listening on its own; not yet measured on the Fold7. Phonon's own figures: 24x on a Snapdragon
8 Elite Gen 5.

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

Live speech (story descriptions, the Voice & Speech sample) runs on the phone. While the chosen
built-in model is not installed there, `TtsSpeechEngineHost.speaking` replaces the settings with
`TtsEnginePlanning.standIn`: the engine, voice and pitch of a saved profile that speaks with an
Android engine, Google's first; the listening speed and pauses stay the user's. Without such a
profile the phone's default engine speaks.

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
  profile).
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
