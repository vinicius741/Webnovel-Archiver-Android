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

- **Engine selection:**
  - Choosing Kokoro before its model is downloaded saves the choice. Until the install finishes, the
    phone's engine speaks. `TtsEnginePlanning.resolve` treats Kokoro as available only once installed.
  - When the install finishes, a loaded chapter switches to Kokoro from where it is.
  - After Remove, the setting returns to the phone's engine.
- **Rendering:**
  - Kokoro renders through the same cached pipeline as Android engines (see
    `tts-audiobook-playback.md`): one sentence at a time, at 1x, with speed applied at playback.
  - The cache key is `builtin:kokoro-en-v0_19|speaker=<id>`. Kokoro has no pitch control, so pitch is
    not part of the key, and the Pitch field is hidden while Kokoro is selected.
  - There is no live-speech fallback for Kokoro. If rendering fails, playback stops with a synthesis
    error.
- **Loading:**
  - The model loads off the main thread when playback, or the settings sample, first needs it. The
    engine-start watchdog allows 90 s for the load instead of 15 s.
  - It is freed on a background thread when no host holds it. A sentence in progress finishes first.
- **Measured on the x86_64 emulator (debug build):**
  - Rendering stayed ahead of 1.8x playback.
  - The app used about 1.3 GB of memory while Kokoro was speaking (native heap about 1.2 GB).
  - Phones are expected to be similar or better. The owner's sherpa-onnx test on the Fold 7 measured
    an RTF of 0.54.

## Native code and release builds

- The app ships native code for `arm64-v8a` (phones) and `x86_64` (emulator) only. This is set by
  `abiFilters` in `app/build.gradle`.
- On other ABIs the app still installs. Kokoro is simply not offered there
  (`KokoroModelFiles.isDeviceSupported`), and a failed native load reports "cannot load" instead of
  crashing.
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
