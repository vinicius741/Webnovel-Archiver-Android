# Webnovel Archiver (Android)

A local-first Android app for downloading, archiving, and reading webnovels offline. Built with **native Kotlin**.

> **Note:** This app was originally developed with React Native / Expo and has since been fully rewritten in native Kotlin. The legacy React Native source has been removed; active development targets the native app under `android/`.

## Key Features

- **Multi-Source Support** — Extensible `SourceProvider` architecture (RoyalRoad, Scribble Hub; add more via `SourceRegistry`).
- **In-App Browser** — Embedded WebView for browsing supported sites and importing novels with a single click.
- **Offline Library** — Download chapters for reading without an internet connection.
- **Built-in Reader** — WebView-based reader with TTS highlighting, image support, and last-read position tracking.
- **Text-to-Speech** — Foreground service with media playback, notification controls, configurable voice/rate/pitch, and auto-resume.
- **EPUB Export** — Generate EPUB 2.0 files with volume splitting and configurable chapter ranges.
- **Background Downloads** — Foreground service with concurrent engine, persistent queue, notification controls, automatic recovery, and error classification with exponential backoff retry.
- **Text Cleanup** — Sentence removal and regex rules with scoped targets (download, TTS, or both).
- **Library Organization** — Custom tabs with swipe-between-tabs navigation, search, tag filtering, sort controls, and archive snapshots.
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

### Build an APK to Install on a Phone

```bash
cd android
./gradlew :app:assembleRelease
```

Install the signed release APK from `apk-output/WebnovelArchiver-Native-release.apk`.

Its package ID is `com.vinicius741.webnovelarchiver.nativeapp`, and its launcher name is `Webnovel Archiver Native`.

If Android shows only `App not installed`, first remove any previous install of `Webnovel Archiver Native` and try the generated APK again. Android will reject an update when an already-installed app has the same package ID but was signed with a different certificate, or when the installed app has a newer version code. With USB debugging enabled, the equivalent cleanup command is:

```bash
adb uninstall com.vinicius741.webnovelarchiver.nativeapp
```

Make sure the file copied to the phone is the current Gradle output, `apk-output/WebnovelArchiver-Native-release.apk`. The release APK produced by this project is only a few MB; a large `build-*.apk` file is usually an older or unrelated artifact.

Do not use `./gradlew :app:assembleDebug` for the APK you send to your phone. That command creates `apk-output/WebnovelArchiver-Test-debug.apk`, which is a development build signed with the Android debug key and labeled `Webnovel Archiver Native Test`. When sideloaded, Play Protect is expected to warn that it has not seen the developer before.

Sideloaded release APKs can still receive a Play Protect "unknown developer" warning until the signing certificate/app gains reputation or the app is distributed through Google Play testing/production channels, but `assembleRelease` is the correct command for a normal signed APK.

### First-Time Release Signing Setup

Release signing is already configured on this machine through `android/local.properties`. If you move to a new computer or delete the local keystore, recreate the release keystore and keep it outside the repo.

Create the keystore:

```bash
keytool -genkeypair -v \
  -keystore "$HOME/.android/webnovel-archiver-release.jks" \
  -alias webnovel-archiver \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000
```

Then add these values to `android/local.properties`, or export them before running Gradle:

```bash
WEBNOVEL_RELEASE_STORE_FILE=$HOME/.android/webnovel-archiver-release.jks
WEBNOVEL_RELEASE_STORE_PASSWORD=your-keystore-password
WEBNOVEL_RELEASE_KEY_ALIAS=webnovel-archiver
WEBNOVEL_RELEASE_KEY_PASSWORD=your-key-password
```

### Development

Open the `android/` directory in Android Studio. The project uses:
- `compileSdk` 36, `minSdk` 26, `targetSdk` 36
- Kotlin 2.1.20, AGP 8.13.2, Gradle wrapper 9.0

For a side-by-side development build:

```bash
cd android
./gradlew :app:assembleDebug
```

This produces `apk-output/WebnovelArchiver-Test-debug.apk` with package ID `com.vinicius741.webnovelarchiver.nativeapp.debug`.

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
```

## Contributing

Contributions are welcome! Please read `AGENTS.md` before submitting a PR. All changes should target the native Kotlin app under `android/`.
