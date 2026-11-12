# Built-in Kokoro voices

Webnovel Archiver can speak with [Kokoro-82M](https://huggingface.co/hexgrad/Kokoro-82M), a neural
voice model that runs on the phone, as well as with any Android TTS app. Kokoro appears as
**Kokoro (built in)** in Voice & Speech → Engine. Its 11 English voices appear in the voice picker by
name, and each profile can use a different one.

## Why built in

The owner compared engines by ear on a Galaxy Z Fold 7 (November 2026). Kokoro sounded clearly better
than Google's voices, and sherpa-onnx's Kitten sounded worse. Kokoro installed as a separate Android
TTS app (sherpa-onnx's "Next-gen Kaldi: TTS Engine") has three limits:

- It reports one voice to other apps. Its speaker is a global setting in its own app, so profiles
  could not choose one.
- The audio cache could not see a speaker change, so old sentences replayed in the previous voice.
- Only one sherpa-onnx engine app can be installed at a time, because they share a package name.

Running the model inside the app fixes all three: the voice is part of the settings, the profile and
the cache key.

## Pieces

| Piece | Role |
|-------|------|
| `com.k2fsa:sherpa-onnx:1.12.21@aar` | sherpa-onnx's official Android AAR (Kotlin API + native ONNX Runtime). Published only as a GitHub release asset, so `android/settings.gradle` declares an `exclusiveContent` Ivy repository for that one module. Lock-pinned in `app/gradle.lockfile`. |
| `org.apache.commons:commons-compress` | Unpacks the model, which is published only as `.tar.bz2`. |
| `tts/kokoro/KokoroVoices` | Engine id `builtin:kokoro-en-v0_19` (stored in `TtsSettings.enginePackage`), the 11 speakers, and voice ids `kokoro-en-v0_19:<code>`. |
| `tts/kokoro/KokoroModelInstaller` | Process-wide (in `AppContainer`): downloads, verifies and unpacks the model. Publishes `KokoroModelState`. |
| `tts/kokoro/KokoroRuntime` | Process-wide (in `AppContainer`): the loaded model, shared by playback and the settings sample, reference-counted, with all native calls serialized. |
| `tts/kokoro/KokoroSynthesizer` | The `TtsSpeechSynthesizer` for Kokoro: one sentence in, a mono 16-bit WAV out (`TtsWavWriter`). |
| `TtsSpeechEngineHost` | Starts either an Android `TextToSpeech` or the Kokoro model, depending on the chosen engine. |
| `feature/settings/SettingsTtsEngine.kt` | Engine picker, Kokoro card (download, progress, remove), and voice picker. |

## Model download

- **Source:** `https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-en-v0_19.tar.bz2`
  - 319,625,534 bytes.
  - SHA-256 `912804855a04745fa77a30be545b3f9a5d15c4d66db00b88cbcd4921df605ac7`.
  - The model is Apache-2.0.
- **When:** only when the user chooses Kokoro, or taps Download on the Kokoro card. Nothing is
  bundled in the APK.
- **Where:** in `filesDir/tts_models/kokoro-en-v0_19/`, about 369 MB unpacked.
  - It is outside the library storage root, so full backups never include it.
  - The app has `allowBackup="false"`.
  - The system never clears the folder as cache.
- **Resuming:** the download writes `kokoro-en-v0_19.tar.bz2.part` and resumes it with an HTTP
  `Range` request. Cancel, process death or a lost connection all keep the partial file. A complete
  archive left behind by an interrupted unpack is re-verified and reused rather than downloaded again.
- **Checks:**
  - The archive must match the pinned length and SHA-256.
  - Tar entries must stay inside the model folder (`KokoroInstallPlanning.entryTarget`).
  - Links and device entries are skipped.
- **Install:** unpacking goes into a `.staging` folder. Once the required files are confirmed, a
  `.installed` marker is written and the folder is renamed into place. A half-finished install is
  never loaded.
- **Free space:** the installer needs the rest of the download, plus the unpacked files, plus a
  200 MB margin.
- **Offline QA:** in the offline simulation, the installer uses the simulation's guarded client, which
  denies the request.

## Playback

Since 2026-11-11, Kokoro generates chapter audio ahead of time in the background, and chapters
play from that audio without loading the model (see `narration-pregeneration-plan.md`). The
real-time path below still serves story descriptions and the settings sample. The speed test
pauses generation while it runs.

Chapter generation converts Kokoro output directly to 16-bit PCM and writes each clip inline:
inference is nearly all of the time. The model's thread count is capped at the cores Android lets
the app use at load time, and generation re-fits it when that changes (see "Generation speed" in
`narration-pregeneration-plan.md`). Completed jobs log audio length, elapsed generation time,
rendering time, native thread count, allowed cores and process importance in the activity log.

- **Engine selection:**
  - Choosing Kokoro before its model is downloaded saves the choice. Until the install finishes, the
    phone's engine speaks. `TtsEnginePlanning.resolve` treats Kokoro as available only once installed.
  - When the install finishes, a loaded chapter switches to Kokoro from where it is.
  - After Remove, the setting returns to the phone's engine.
- **Rendering:**
  - Kokoro renders through the same cached pipeline as Android engines (see
    `tts-audiobook-playback.md`): one sentence at a time, at 1x, with speed applied at playback.
  - The cache key is `builtin:kokoro-en-v0_19|speaker=<id>|text=<KokoroTextPlanning.VERSION>`. Kokoro
    has no pitch control, so pitch is not part of the key, and the Pitch field is hidden while Kokoro is
    selected. Bump the version when the text sent to the model changes, so old clips re-render.
  - Text cleanup (`KokoroTextPlanning.spokenText`, measured with sherpa-onnx on 2026-11-11):
    - sherpa-onnx treats anything after a sentence's final `.`, `!` or `?` as one more sentence. The
      closing quote of `“Go home.”` (also `’`, `"`, `)`, `]`, `』`, and spaced dots like `. . .`)
      rendered about 0.2 s of noise that ended at full volume. Listeners heard a glitch at the end of
      the line, then a hard cut into the pause. About 14% of sentences in the owner's library end this
      way. The trailing run is reduced to its terminators, for example `“Go home.`.
    - Kokoro voices `*` (about 0.5 s each), so asterisks are removed.
    - Quotes and brackets inside a sentence render cleanly and are kept.
  - There is no live-speech fallback for Kokoro. If rendering fails, playback stops with a synthesis
    error.
- **Loading:**
  - The model loads off the main thread when playback, or the settings sample, first needs it. The
    engine-start watchdog allows 90 s for the load instead of 15 s.
  - It is freed on a background thread when no host holds it. A sentence in progress finishes first.
- **Threads:** the model computes on one thread per fast core, capped at 8 and at least 2
  (`KokoroThreads.forClocks`), and never more than the cores the app may use when it loads
  (`KokoroThreads.forNow`, from `/proc/thread-self/status`). A core counts as fast when its top clock is at least 70% of the
  fastest core's. The engine splits each sentence evenly across threads, so a thread on a slow
  efficiency core holds the rest back. Unreadable clocks (and emulators, which report placeholders)
  fall back to 4, the fixed count used before 1.0.16. On the Galaxy Z Fold7 (Snapdragon 8 Elite,
  eight fast cores), the speed test measured 1.72x real time at 4 threads, below the owner's 1.8x
  listening speed, so playback paused between sentences. It measured 2.51x at 8 threads.
- **Measured on the x86_64 emulator (debug build):**
  - Rendering stayed ahead of 1.8x playback.
  - The app used about 1.3 GB of memory while Kokoro was speaking (native heap about 1.2 GB).
  - Phones are expected to be similar or better. The owner's sherpa-onnx test on the Fold 7 measured
    an RTF of 0.54.

## Speed test

Settings → Voice & Speech → Kokoro voices → **Test speed** measures whether Kokoro can generate
faster than the listener plays it, on the phone itself (release builds included). It answers whether
gaps between sentences come from generation speed, and how much of the phone it leaves unused.

- **What runs** (`KokoroBenchmark`, plan in `KokoroBenchmarkPlanning.plan`): narration stops (a
  paused chapter keeps rendering ahead, which would share the CPU). The test then loads its own
  copies of the model, one step at a time:
  - the app's setting (`KokoroRuntime.threads`) first, while the phone is coolest;
  - 2, 4, 6 and 8 threads (except the app's setting), up to the core count;
  - two copies generating at once (half the cores each, at most 4 threads), which tests whether
    rendering sentences in parallel would beat one at a time. It is skipped below 1.5 GB of free RAM;
  - the app's setting again, so heat throttling shows as a drop between its two runs.

  Each step speaks one warm-up sentence, then five novel-style test sentences (about 30 s of audio).
- **What it records:**
  - Speed: seconds of audio per second of real time. Below the listening rate, playback must wait.
  - Load time, the first and the slowest sentence.
  - Cores busy (process CPU time over real time).
  - Which cores the work ran on, from sampling each thread's core in `/proc/self/task/*/stat`.
  - Each core's average clock relative to its top clock (left out where unreadable, or on emulators,
    which report placeholder clocks).
  - Free RAM and the memory one model copy adds (PSS).
  - Android's thermal status and headroom at start and end.
- **Export:** the results dialog has **Share** (plain text through the share sheet) and **Copy**. The
  text ends with a `--- data ---` JSON block (`KokoroBenchmarkJson`, `format` 1) for analysis. The
  last report is kept in `files/kokoro_speed_test.txt` and reopens from **Last results**.
- **Galaxy Z Fold7 (2026-11-11, release 1.0.15, 1.8x listening):**
  - Speed: 4 threads 1.72x (3.5 cores busy, about 70% of top clock), 2 threads 1.36x, 6 threads
    1.73x, 8 threads 2.51x (6.8 cores busy).
  - Two copies at 4 threads each made 2.45x combined. That is no better than one copy at 8 threads,
    and it costs a second ~700 MB copy and slower sentences, so narration keeps one copy.
  - Thermal headroom rose from 0.70 to 0.96 during the two-minute test, with no measurable
    throttling. Free RAM was 4.7 GB; one copy uses about 730 MB.
- **Emulator check (2026-11-11, `webnovel_api36`, 4 cores):** the app's setting ran at 2.47x, with
  3.8 of 4 cores busy. 2 threads ran at 1.67x. One model copy added about 718 MB, so the parallel
  step was skipped for lack of free RAM.

## Native code and release builds

- The app ships native code for `arm64-v8a` and `armeabi-v7a` (phones) and `x86_64` (emulator). This
  is set by `abiFilters` in `app/build.gradle`.
- An APK with native code cannot install on a device that matches none of its ABIs, so the filter
  must keep every phone ABI. Only 32-bit x86 devices, which are all but extinct, are left out.
- If the native library still fails to load, Kokoro reports "cannot load" instead of crashing, and
  `KokoroModelFiles.isDeviceSupported` hides Kokoro on any ABI outside the list.
- sherpa-onnx's JNI reads the Kotlin config classes' fields by name, and the AAR ships no ProGuard
  rules. `proguard-rules.pro` therefore keeps `com.k2fsa.sherpa.onnx.**`. A local R8 release build
  confirmed that those classes and fields keep their names.

## Licences

- sherpa-onnx and the Kokoro model are Apache-2.0.
- sherpa-onnx's Android build statically links **espeak-ng (GPL-3.0)**, which converts text to
  phonemes for Kokoro. That fits a personal app whose source is public. If the app is ever
  distributed under terms incompatible with GPL-3.0, revisit this dependency.

## Verified (2026-11-11, `webnovel_api36`, debug build)

- Engine picker lists "Kokoro (built in) · 319 MB download"; the confirmation explains the size.
- Live progress shown. Cancel kept 45 MB; continuing resumed from there.
- SHA-256 check, unpack, and `.installed` marker; the archive was deleted afterwards.
- The voice picker lists all 11 voices by name.
- The settings sample rendered all 7 sentences. A clip analysed as speech-like (2.7 s, no clipping).
- Resuming a chapter played continuously with Kokoro.
- Switching Bella → George mid-chapter continued playback in the new voice without errors.
- Remove deleted the model and switched settings back to the phone's engine.
- A complete archive already in the partial-download slot was re-verified and unpacked without
  downloading again.
- Kokoro chosen during playback, before its install finished: the phone's engine kept speaking. When
  the install finished, the playing chapter moved to Kokoro in place (`TtsEngine.refreshEngine`,
  triggered from `AppContainer`) without stopping.
- The R8 release build kept the sherpa-onnx classes.

**Not yet verified:** the owner's phone (arm64), long sessions and battery or heat, and the app in the
background under memory pressure.
