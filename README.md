# Webnovel Archiver for Android

A local-first Android app for downloading, archiving, and reading webnovels offline. Built with native Kotlin. Requires Android 8.0 or newer.

Supports **Royal Road, Scribble Hub, SpaceBattles, Sufficient Velocity, FanFiction.net, and Archive of Our Own (AO3)**.

## Main features

- Offline reading with saved progress, images, and background chapter downloads.
- Text-to-speech with sentence highlighting, playback controls, and per-story resume.
- New-chapter tracking with a next-release estimate from each novel's recent release dates (flagged in the library when a sync may find it), library tabs, search, filters, a tap-the-author view of their other novels in your library, and novel popularity trends.
- EPUB export, text cleanup, and full library backup and restore.
- Patreon early-access chapters available through your own account.
- Optional AI cover art and synopses using your own OpenRouter API key, with cost tracking.

## Install

Download the APK from the [latest release](https://github.com/vinicius741/Webnovel-Archiver-Android/releases/latest) and open it on your Android device. Allow installation from your browser if prompted.

After installation, use **Settings → App Updates** to check for new versions. *Download update* opens the new APK in your browser; install it over the existing app to keep your library.

## Development

Open `android/` in Android Studio. You need JDK 17 and Android SDK platform 37.

Run from the repository root:

```bash
# Build the debug app for the emulator
android/gradlew -p android :app:assembleDebug

# Run unit tests
android/gradlew -p android :app:testDebugUnitTest

# Run the full local quality gate
android/gradlew -p android :app:lintKotlin :app:ci
```

The debug APK is at `android/app/build/outputs/apk/debug/app-debug.apk`. Debug and release builds have separate app IDs and libraries. Use debug on the emulator and a signed release on your phone.

For release builds, signing, and GitHub Actions publishing, see the [release guide](docs/release/build-and-publish.md).

### Code layout

The app uses Android Views, Kotlin coroutines, OkHttp, Jsoup, and file-based JSON storage. Production packages live under `android/app/src/main/java/com/vinicius741/webnovelarchiver/`:

| Packages | Purpose |
|----------|---------|
| `app/`, `navigation/` | Startup, shared dependencies, and navigation |
| `feature/`, `ui/` | Screens, shared widgets, and theme |
| `domain/`, `data/` | Models, domain rules, persistence, and backups |
| `source/`, `sync/`, `download/` | Site providers, story updates, and download queue |
| `tts/`, `epub/`, `cleanup/`, `ai/` | Narration, export, text cleanup, and AI generation |
| `appupdate/`, `notification/`, `perf/` | App updates, notifications, and debug performance tracking |

Unit tests live in `android/app/src/test/`; device tests live in `android/app/src/androidTest/` and use an isolated instrumentation app.

## Documentation and contributing

Read the [repository instructions](AGENTS.md) and [Android development guide](android/AGENTS.md) before contributing. Active development targets `android/`.

- [Documentation index](docs/README.md) for feature details and architecture notes.
- [Adding a source](docs/sources/adding-a-source.md) for new novel sites.
- [Offline testing](docs/qa/offline-simulation.md) for testing without live sites or API charges.
