# Tech Stack Decisions

### Project: Webnovel Archiver (Native Android)

### 1. Overview

This document outlines the technical architecture and technology stack for the native Kotlin Android implementation of the Webnovel Archiver. The app was originally built with React Native / Expo and has been fully rewritten in native Kotlin for better performance, native API access, and reduced complexity.

**Core Philosophy:**
- **Local-First:** No external backend servers. All processing (scraping, packaging) happens on the user's device.
- **Native Performance:** Direct access to Android APIs for file I/O, networking, TTS, and foreground services.
- **Testability:** Pure-logic "planning" functions separated from stateful "engines" for comprehensive unit testing.

---

### 2. The Tech Stack

#### 2.1 Core Framework

- **Decision:** **Native Kotlin Android**
- **Rationale:**
  - Direct access to all Android APIs (foreground services, TTS, FileProvider, WebView) without native module bridges.
  - Eliminates React Native bridge overhead for file I/O and HTML parsing operations.
  - Single-language codebase (Kotlin) with no JavaScript/TypeScript bridge complexity.
  - Better control over memory management for large operations (EPUB generation, chapter downloads).

#### 2.2 UI

- **Decision:** **Programmatic Android Views** (no XML layouts, no Jetpack Compose)
- **Rationale:**
  - Single `MainActivity` with screen-based method navigation keeps the architecture simple.
  - No layout inflation overhead. Direct view construction with Kotlin code.
  - No Compose learning curve or version churn for a single-developer project.
  - **Trade-off:** The `MainActivity` file is large (~1400 lines). Future work may extract screens into separate classes.

#### 2.3 Networking & Scraping Engine

- **HTTP Client:** **OkHttp 4.12**
  - Mature, well-tested HTTP client with interceptor support.
  - Per-host rate limiting and retry logic built on OkHttp interceptors.
  - Custom User-Agent headers mimicking mobile Chrome.
- **HTML Parsing:** **Jsoup 1.21**
  - jQuery-like API for DOM traversal and manipulation.
  - Purpose-built for HTML scraping (unlike general XML parsers).
  - No React Native compatibility constraints.

#### 2.4 File Management & Storage

- **Storage:** **File-based JSON via Gson**
  - `AppStorage` manages story metadata, settings, queue, and session data as JSON files.
  - Directory structure: `stories/`, `novels/`, `epubs/`, `backups/` under `context.filesDir`.
  - **Trade-off:** No Room/SQLite. JSON files are simpler but may need migration to Room if library scales to 100+ novels with complex queries.

#### 2.5 EPUB Generation

- **Decision:** **`java.util.zip` + Custom XML generation**
- **Rationale:**
  - Standard library ZIP support — no third-party EPUB dependencies.
  - Full control over EPUB structure (mimetype, container.xml, OPF, NCX, XHTML).
  - EPUB 2.0 spec compliance with volume splitting support.

#### 2.6 Async & Background Work

- **Coroutines:** **kotlinx-coroutines-android 1.10**
  - All async work runs on `CoroutineScope(SupervisorJob() + Dispatchers.Main)`.
  - No RxJava complexity.
- **Foreground Services:**
  - `DownloadForegroundService` (type: `dataSync`) for background downloads.
  - `TtsForegroundService` (type: `mediaPlayback`) for background TTS playback.
  - Both use `START_STICKY` for process-death recovery.

#### 2.7 Testing

- **Framework:** **JUnit 4**
- **Approach:** 1:1 test files for every planning module. Pure functions are trivially testable without mocking frameworks.
- **Coverage:** 36 test files covering all planning modules, source parsing, network requests, EPUB generation, download scheduling, error classification, backup validation, and more.

---

### 3. Architecture Diagram (Local-Only Flow)

```
┌──────────────────────────────────────────────────┐
│  MainActivity (UI + Navigation)                   │
│  ├─ Library Screen                                │
│  ├─ Story Details Screen                          │
│  ├─ Add Story Screen                              │
│  ├─ Browser Screen (WebView)                      │
│  ├─ Reader Screen (WebView)                       │
│  ├─ Queue Screen                                  │
│  └─ Settings Screen                               │
├──────────────────────────────────────────────────┤
│  Core Engines                                     │
│  ├─ StorySyncEngine ── fetch & merge stories      │
│  ├─ DownloadEngine ─── queue-based downloads      │
│  ├─ EpubEngine ─────── EPUB generation (ZIP)      │
│  └─ TtsEngine ──────── Android TTS wrapper        │
├──────────────────────────────────────────────────┤
│  Core Planning (pure functions)                   │
│  ├─ DownloadQueuePlanning ─ queue manipulation     │
│  ├─ StorySyncPlanning ── chapter merge logic      │
│  ├─ BackupMergePlanning ─ import merge logic      │
│  ├─ ArchiveSnapshotPlanning ─ snapshot creation    │
│  └─ 25+ other planning modules                    │
├──────────────────────────────────────────────────┤
│  Infrastructure                                   │
│  ├─ AppStorage ──── Gson JSON persistence          │
│  ├─ NetworkClient ── OkHttp + rate limiting        │
│  ├─ SourceProviders ── RoyalRoad + ScribbleHub     │
│  ├─ DownloadForegroundService                      │
│  └─ TtsForegroundService                           │
├──────────────────────────────────────────────────┤
│  Android OS                                       │
│  ├─ FileProvider (share/open intents)              │
│  ├─ TextToSpeech API                               │
│  └─ WebView                                        │
└──────────────────────────────────────────────────┘
```

---

### 4. Known Trade-offs & Risks

| Feature | Challenge | Mitigation |
|---------|-----------|------------|
| **Large MainActivity** | ~1400 lines in a single file. | Extract screens into separate Activity or Fragment classes as needed. |
| **No Room/SQLite** | JSON files may not scale for very large libraries. | Migrate to Room if query performance becomes an issue. |
| **Programmatic UI** | No visual layout editor. | Keep view construction systematic and well-commented. |
| **No DI framework** | Manual engine instantiation. | Keep constructors explicit; consider Hilt/Koin if complexity grows. |
| **Single test framework** | JUnit 4 only, no Robolectric or instrumented tests. | Add Robolectric for Android-component tests, or instrumented tests for UI flows. |
