# Webnovel Archiver for Android

A local-first Android app for downloading, archiving, and reading webnovels offline. Built with native Kotlin. Requires Android 8.0 or newer.

## Main features

- Five destinations, on a bottom bar (or a rail beside the thumb on a foldable's inner screen): **Library**, **Updates**, **Listen**, **Downloads** and **Settings**, with the playing chapter docked above them.

- Offline reading with saved progress, images, and background chapter downloads.
- Listening with sentence highlighting, playback controls, per-story resume, and a choice of voice (any installed TTS app, or the built-in neural voices Kokoro v1.0 and Phonon, downloaded to the phone only when it should generate them itself), all set up on one Settings › Listening page.
- Pre-generated narration: chapter audio is generated in full, in the background, before it plays (bulk queue, keep the next chapters ready while listening, per-chapter voice, the headphones on a chapter generate it first and then play; generate, regenerate and delete from each novel's Chapters tab or the Listen screen; audio made from older text keeps playing), so playback never waits on the voice model. Built-in-voice chapters can also generate on your own computers over Wi-Fi or Tailscale (`scripts/narration-server`), several times faster than on the phone and with the same voice; choose several computers and they share each chapter, with the phone optionally standing in while none can be reached. Audio is stored compressed (AAC, about 30 MB per hour, measured indistinguishable from the original), and audio generated before compression is converted in the background.
- New-chapter tracking with a next-release estimate from each novel's recent release dates: the Updates screen groups novels whose next chapter may be out, is expected soon or is running late, and syncs one or all of them. Library tabs, search, filters, a tap-the-author view of their other novels in your library, and novel popularity trends.
- Trending (from the Updates app bar): the novels whose Patreon, readers, views or rating are rising right now. Rises fade after about a week so the list keeps changing, and novels you're caught up on are left out.
- Local folder (Add story › Local folder): pick a folder once; each subfolder (or book file) becomes a novel, with chapters from the EPUB's table of contents or the "Chapter 1" headings of a text or PDF. Scanned PDFs are read with on-device text recognition. Imported books are read in right away, and syncing picks up new files.
- EPUB export, text cleanup, and full library backup and restore.
- One shareable diagnostics export (the last hour of syncs, downloads, library changes and errors, plus source access) for troubleshooting, also saved to Downloads/Webnovel Archiver; the phone keeps 7 days.
- Patreon early-access chapters available through your own account.
- Optional AI cover art, synopses, a character list and character portraits using your own OpenRouter API key, with cost tracking.

## Install

Download the APK from the [latest release](https://github.com/vinicius741/Webnovel-Archiver-Android/releases/latest) and open it on your Android device. Allow installation from your browser if prompted.

After installation, use **Settings → App Updates** to check for new versions. *Download update* opens the new APK in your browser; install it over the existing app to keep your library.

To try upcoming changes, install **Webnovel Archiver Beta** from a [prerelease](https://github.com/vinicius741/Webnovel-Archiver-Android/releases). It is a separate app with its own purple icon and its own library, so it installs beside the stable app without changing it. See the [beta track](docs/release/build-and-publish.md#beta-track).

## Development

Open `android/` in Android Studio. You need JDK 17 and Android SDK platform 37.

Run from the repository root:

```bash
# Build the debug app for the emulator
android/gradlew -p android :app:assembleDebug

# Run unit tests
android/gradlew -p android :app:testDebugUnitTest

# Run the per-change quality gate (formatting, unit tests, detekt, debug build)
android/gradlew -p android :app:lintKotlin :app:ci

# Run the release gate (adds Android lint and the Kotlin file-size cap)
android/gradlew -p android :app:lintKotlin :app:releaseCheck
```

The debug APK is at `android/app/build/outputs/apk/debug/app-debug.apk`. Debug, release, and beta builds have separate app IDs and libraries. Use debug on the emulator and a signed release (or beta) on your phone.

For release builds, signing, and GitHub Actions publishing, see the [release guide](docs/release/build-and-publish.md).

### Code layout

The app uses Android Views, Kotlin coroutines, OkHttp, Jsoup, and file-based JSON storage. Production packages live under `android/app/src/main/java/com/vinicius741/webnovelarchiver/`:

| Packages | Purpose |
|----------|---------|
| `app/`, `navigation/` | Startup, shared dependencies, navigation, and the per-screen model store |
| `operations/` | Work several screens start and watch: story sync, text cleanup, EPUB and AI progress, and listen starts |
| `feature/`, `ui/` | Screens, each a screen model plus a View renderer (library, updates, trending, story page tabs, listen/player/reader, downloads, settings), the app shell, shared widgets, and theme |
| `domain/`, `data/` | Models, domain rules, persistence, and backups |
| `source/`, `sync/`, `download/` | Site providers (and `source/local/`, the Local folder source), story updates, and download queue |
| `tts/`, `narration/`, `epub/`, `cleanup/`, `ai/` | Playback, generated chapter audio, export, text cleanup, and AI generation |
| `appupdate/`, `notification/`, `perf/` | App updates, notifications, and debug performance tracking |

Unit tests live in `android/app/src/test/`; device tests live in `android/app/src/androidTest/` and use an isolated instrumentation app.

## Documentation and contributing

Read the [repository instructions](AGENTS.md) and [Android development guide](android/AGENTS.md) before contributing. Active development targets `android/`.

- [Documentation index](docs/README.md) for feature details and architecture notes.
- [Adding a source](docs/sources/adding-a-source.md) for new novel sites.
- [Offline testing](docs/qa/offline-simulation.md) for testing without live sites or API charges.
