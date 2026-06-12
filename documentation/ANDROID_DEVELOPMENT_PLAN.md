# Android Development Plan

> **Status: COMPLETE.** The native Kotlin app has been fully implemented under `android/`. This document is retained for historical reference.

This document was the original development plan for the Android port. All phases have been completed in the native Kotlin rewrite.

## Completed Phases

### Phase 1: Foundation & UI Shell ✅
- **Project Init**: Native Android project with Kotlin 2.1.20, AGP 8.13.2, Gradle 9.0.
- **UI System**: Programmatic Android Views (no XML layouts). Single `MainActivity` with screen-based navigation.
- **Core Components**: Library screen, story details, add story, browser (WebView), reader, queue, settings.

### Phase 2: Core Archiving Engine ✅
- **Networking**: OkHttp client with mobile User-Agent, per-host rate limiting, retry logic.
- **HTML Parsing**: Jsoup-based parsing for RoyalRoad and Scribble Hub.
- **Source Providers**: `RoyalRoadProvider` and `ScribbleHubProvider` implementing `SourceProvider` interface.
- **Storage**: File-based JSON persistence via Gson (`AppStorage`).

### Phase 3: Library & Data Persistence ✅
- **Data Model**: Kotlin data classes in `core/Models.kt` — `Story`, `Chapter`, `Tab`, `AppSettings`, etc.
- **Persistence**: Gson JSON files under `context.filesDir` (stories/, novels/, epubs/, backups/).
- **Library Features**: Custom tabs, search, tag/source filtering, sort controls, bulk selection.

### Phase 4: EPUB Generator ✅
- **Engine**: `EpubEngine` using `java.util.zip` for ZIP generation.
- **Structure**: EPUB 2.0 compliant — mimetype, container.xml, content.opf, toc.ncx, XHTML chapters, CSS.
- **Features**: Volume splitting, cover embedding, configurable chapter ranges, start-after-bookmark.

### Phase 5: Background Tasks ✅
- **Download Service**: `DownloadForegroundService` with `dataSync` foreground service type, persistent notification with pause/resume/stop actions.
- **TTS Service**: `TtsForegroundService` with `mediaPlayback` foreground service type, notification with play/pause/next/previous/stop actions.
- **Recovery**: Interrupted jobs recover to pending on service startup. Exponential backoff retry for transient errors.

### Phase 6: Optimization & Polish ✅
- **Error Handling**: Download error classification, retryable transient error detection, user-facing error categories.
- **Archive Snapshots**: Automatic read-only snapshots when source sync detects removed chapters.
- **Backup System**: JSON metadata export/import and full ZIP backup/restore with validation and merge logic.
- **Text Cleanup**: Sentence removal and regex cleanup rules with download/TTS/both targeting.

## Verification

### Automated Tests
- **36 JUnit 4 test files** under `android/app/src/test/java/` with 1:1 coverage of all planning modules.
- Tests cover: text cleanup, source parsing, URL validation, browser planning, tab management, network requests, archive snapshots, EPUB generation, download scheduling, queue control, error classification, story sync, backup validation, and more.
- Run with: `./gradlew :app:testDebugUnitTest`

### Build Verification
- `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` — All pass.

### Remaining Runtime Validation
- Full device/emulator flow validation for long-running downloads, WebView navigation, file sharing, TTS audio, and EPUB opening in third-party readers. See `documentation/NATIVE_DEVICE_TEST_CHECKLIST.md`.
