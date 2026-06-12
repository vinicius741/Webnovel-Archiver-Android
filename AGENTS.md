# AGENTS.md

This file provides guidelines for agentic coding assistants working on this codebase.

## Project Overview

Webnovel Archiver Android is a **native Kotlin Android app** that downloads, archives, and exports webnovels as EPUB files. It is **local-first** — all scraping, parsing, and EPUB generation happens on-device with no external backend servers.

The app was originally built with React Native / Expo and has been fully rewritten in native Kotlin. The legacy React Native source (`app/`, `src/`, `modules/`) remains in the repository for reference but is **no longer the active implementation**. All active development targets the native Kotlin app under `android/`.

---

## Build / Lint / Test Commands

### Core Commands
- `./gradlew :app:assembleDebug` — Build the debug APK.
- `./gradlew :app:testDebugUnitTest` — Run all unit tests.
- `./gradlew :app:lintDebug` — Run Android lint checks.
- `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` — Full build + test + lint (recommended CI command).

### Running a Single Test
- `./gradlew :app:testDebugUnitTest --tests "com.vinicius741.webnovelarchiver.core.TextCleanupTest"` — Run a specific test class.
- `./gradlew :app:testDebugUnitTest --tests "*.TextCleanupTest.cleanupRemovesScripts"` — Run a specific test method.

### Output
- Debug APK: `android/app/build/outputs/apk/debug/app-debug.apk`
- Test reports: `android/app/build/reports/tests/testDebugUnitTest/`
- Lint reports: `android/app/build/reports/lint-results-debug.html`

### Legacy React Native Commands (reference only)
- `npm run check` — Runs lint, typecheck, coverage, and quality checks for the old RN codebase.
- `npm start` — Starts the Expo dev server (legacy).
- `npm run android` — Runs the RN app on a device/emulator (legacy).

---

## Core Architecture & Patterns

### 1. Single-Activity Architecture
```
MainActivity.kt (~1400 lines) → Core Engines → AppStorage → File System
```
- All UI is built **programmatically** (no XML layouts) using `LinearLayout`, `ScrollView`, `WebView`, `AlertDialog`, etc.
- Navigation is handled by method-based screen switching within `MainActivity`.
- A `CoroutineScope(SupervisorJob() + Dispatchers.Main)` drives all async work.

### 2. Planning + Engine Pattern
The core architecture separates **pure-logic planning functions** (static, easily testable) from **stateful engine classes**:

| Layer | Location | Description |
|-------|----------|-------------|
| **Models** | `core/Models.kt` | Data classes: `Story`, `Chapter`, `Tab`, `AppSettings`, `DownloadJob`, etc. |
| **Engines** | `core/Engines.kt` | Stateful classes: `StorySyncEngine`, `DownloadEngine`, `EpubEngine`, `TtsEngine`, `DownloadScheduler` |
| **Planning** | `core/*Planning.kt` | Pure functions for queue control, sync merge, backup merge, archive snapshots, etc. |
| **Sources** | `core/Sources.kt` | `SourceProvider` interface, `RoyalRoadProvider`, `ScribbleHubProvider`, `NetworkClient`, `NetworkRequests` |
| **Storage** | `core/Storage.kt` | `AppStorage` — file-based JSON persistence via Gson |
| **Utilities** | `core/*.kt` | Rendering, validation, normalization, MIME types, etc. |

### 3. Source Provider Pattern
- All novel sites implement `SourceProvider` (interface in `core/Sources.kt`).
- New providers must be registered in `SourceRegistry`.
- Current implementations: `RoyalRoadProvider`, `ScribbleHubProvider`.
- HTTP requests use `OkHttp` with per-host rate limiting and mobile User-Agent headers.

### 4. Download System
Foreground-service-based download runner:
- **DownloadForegroundService** (`download/DownloadForegroundService.kt`): Foreground service with `dataSync` type, persistent notification with pause/resume/stop actions.
- **DownloadEngine**: Queue-based chapter downloader with global and per-source concurrency control, per-source delay, retry logic with exponential backoff, and error classification.
- **DownloadScheduler**: Selects eligible jobs respecting concurrency and delay constraints.
- Interrupted `downloading` jobs are recovered to `pending` on service startup.

### 5. Storage & Files
- **AppStorage** (`core/Storage.kt`): Gson-based JSON persistence. No Room/SQLite.
- Directory structure under `context.filesDir`:
  - `stories/` — per-story JSON metadata
  - `novels/{storyId}/` — chapter HTML files named `{0000}_{chapterId}.html`
  - `epubs/` — generated EPUB files
  - `backups/` — backup export files
- JSON backup export/import and full ZIP backup export/restore are supported.
- `FileProvider` exposes files for Android share/open intents.

### 6. Archive Snapshots
- When source sync detects removed chapters, an archived snapshot is created.
- Copies the story to a new ID with `__archive_` suffix, sets `isArchived: true`, and saves the archive reason.
- Archived snapshots are read-only for sync and download but still allow reading/export.

### 7. EPUB Generation
- Built with `java.util.zip` — custom ZIP/XML generation.
- EPUB 2.0 structure: `mimetype`, `META-INF/container.xml`, `content.opf`, `toc.ncx`, XHTML content, CSS.
- Supports volume splitting based on configurable max chapter count (clamped to 10–1000).

### 8. Text-to-Speech
- **TtsForegroundService** (`tts/TtsForegroundService.kt`): Foreground service with `mediaPlayback` type.
- **TtsEngine**: Wraps Android `TextToSpeech` with paragraph-oriented chunking, pause/resume/skip controls, session persistence, and auto-resume on app restart.
- TTS-target regex cleanup applied before chunking.

---

## Essential Constraints & Platform Details

### Build Requirements
- **Gradle**: 9.0.0
- **Android Gradle Plugin**: 8.13.2
- **Kotlin**: 2.1.20
- **compileSdk / targetSdk**: 36
- **minSdk**: 26
- **Java target**: 17

### Key Dependencies
| Purpose | Library |
|---------|---------|
| HTTP Client | OkHttp 4.12.0 |
| HTML Parsing | Jsoup 1.21.2 |
| JSON Serialization | Gson 2.13.2 |
| Coroutines | kotlinx-coroutines-android 1.10.2 |
| AndroidX | core-ktx, activity-ktx, appcompat |

### Manifest Permissions
- `INTERNET`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `POST_NOTIFICATIONS`, `WAKE_LOCK`

### Native Modules (legacy)
`modules/tts-media-session/` contains an Expo native module from the React Native era. It is **not used** by the native Kotlin app and can be considered legacy code.

---

## Coding Style & Standards

- **Language**: Kotlin (strict, idiomatic).
- **Architecture Pattern**: Planning + Engine — keep logic in pure planning functions; engines orchestrate state and I/O.
- **Naming**: Planning functions use descriptive verb names (e.g., `planArchiveSnapshot`, `mergeBackup`). Engine classes use noun names (e.g., `DownloadEngine`, `EpubEngine`).
- **Tests**: JUnit 4 tests co-located under `android/app/src/test/java/` mirroring the source package structure. Every planning module has a 1:1 `*Test.kt` file.
- **No XML Layouts**: All UI is programmatic in `MainActivity.kt`. Do not create layout XML files.
- **No Dependency Injection**: Engines are manually instantiated. Keep constructor dependencies explicit.
- **Imports**: Standard Kotlin import ordering. Use qualified imports; avoid wildcard imports in production code.
