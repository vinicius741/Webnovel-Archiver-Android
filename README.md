# Webnovel Archiver (Android)

A local-first Android app for downloading, archiving, and reading webnovels offline. Built with **native Kotlin**.

> **Note:** This app was originally developed with React Native / Expo and has been fully rewritten in native Kotlin. The legacy React Native source (`app/`, `src/`, `modules/`) remains in the repository for reference. Active development targets the native app under `android/`.

## Key Features

- **Multi-Source Support** — Extensible `SourceProvider` architecture (RoyalRoad, Scribble Hub; add more via `SourceRegistry`).
- **In-App Browser** — Embedded WebView for browsing supported sites and importing novels with a single click.
- **Offline Library** — Download chapters for reading without an internet connection.
- **Built-in Reader** — WebView-based reader with TTS highlighting, image support, and last-read position tracking.
- **Text-to-Speech** — Foreground service with media playback, notification controls, configurable voice/rate/pitch, and auto-resume.
- **EPUB Export** — Generate EPUB 2.0 files with volume splitting and configurable chapter ranges.
- **Background Downloads** — Foreground service with concurrent engine, persistent queue, notification controls, automatic recovery, and error classification with exponential backoff retry.
- **Text Cleanup** — Sentence removal and regex rules with scoped targets (download, TTS, or both).
- **Library Organization** — Custom tabs, search, tag filtering, sort controls, and archive snapshots.
- **Smart Updates** — New-chapter detection with intelligent merge and stale-EPUB marking.
- **Backup & Restore** — JSON metadata export/import with merge-on-import, plus full ZIP backup/restore.
- **Pluggable Theme System** — Obsidian, Midnight, Forest, and Classic Light themes with custom typography.

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Kotlin 2.1.20 |
| UI | Programmatic Android Views (no XML layouts) |
| Build | Gradle 9.0 + AGP 8.13.2 |
| HTTP Client | OkHttp 4.12 |
| HTML Parsing | Jsoup 1.21 |
| JSON | Gson 2.13 |
| Async | Kotlin Coroutines 1.10 |
| Storage | File-based JSON (no Room/SQLite) |
| TTS | Android TextToSpeech API |
| Services | Foreground Services (dataSync, mediaPlayback) |
| Testing | JUnit 4 |

## Getting Started

### Prerequisites

- Android Studio (latest)
- JDK 17
- Android SDK with compileSdk 36

### Build & Run

```bash
cd android
./gradlew :app:assembleDebug
```

Install the debug APK from `android/app/build/outputs/apk/debug/app-debug.apk`.

### Development

Open the `android/` directory in Android Studio. The project uses:
- `compileSdk` 36, `minSdk` 26, `targetSdk` 36
- Kotlin 2.1.20, AGP 8.13.2, Gradle wrapper 9.0

### Testing

```bash
cd android
./gradlew :app:testDebugUnitTest              # All unit tests
./gradlew :app:testDebugUnitTest --tests "*.TextCleanupTest"  # Single test class
./gradlew :app:lintDebug                       # Android lint
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug  # Full check
```

See `AGENTS.md` for architecture details, coding standards, and development guidelines.

## Project Structure

```
android/
  app/
    src/
      main/
        java/com/vinicius741/webnovelarchiver/
          MainActivity.kt              # Single-activity app, programmatic UI
          WebnovelArchiverApp.kt       # Application class
          core/
            Models.kt                  # Data classes (Story, Chapter, etc.)
            Engines.kt                 # Stateful engines (Sync, Download, EPUB, TTS)
            Storage.kt                 # AppStorage — file-based JSON persistence
            Sources.kt                 # Source providers + network client
            *Planning.kt              # Pure-logic planning functions
            *.kt                       # Utilities, validators, renderers
          download/
            DownloadForegroundService.kt
          tts/
            TtsForegroundService.kt
        AndroidManifest.xml
      test/
        java/com/vinicius741/webnovelarchiver/core/
          *Test.kt                     # 1:1 test files for each core module
    build.gradle
  build.gradle
  settings.gradle

app/                  # Legacy React Native screens (reference only)
src/                  # Legacy React Native services/hooks/components (reference only)
modules/              # Legacy Expo native modules (reference only)
documentation/        # Technical documentation
```

## Legacy React Native Codebase

The directories `app/`, `src/`, `modules/`, and the root config files (`package.json`, `tsconfig.json`, `metro.config.js`, etc.) belong to the original React Native / Expo implementation. They are preserved for reference but are **not actively maintained**. The `npm` scripts (`npm run check`, `npm start`, etc.) operate on this legacy code only.

## Contributing

Contributions are welcome! Please read `AGENTS.md` and the `documentation/` folder before submitting a PR. All changes should target the native Kotlin app under `android/`.
