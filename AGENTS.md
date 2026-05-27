# AGENTS.md

This file provides guidelines for agentic coding assistants working on this codebase.

## Project Overview

Webnovel Archiver Android is a React Native app (via Expo SDK 54) that downloads, archives, and exports webnovels as EPUB files. It is **local-first** — all scraping, parsing, and EPUB generation happens on-device with no external backend servers.

---

## Build / Lint / Test Commands

### Core Commands
- `npm run check` - Runs lint, typecheck, coverage, and quality checks in sequence.
- `npm start` - Starts the Expo development server.
- `npm run android` - Runs on Android device/emulator. Required after Kotlin module changes.
- `npm run quality` - Checks code quality metrics against the committed baseline.
- `npm run quality:baseline` - Refreshes the committed code quality baseline. Use only for intentional maintenance, not to bypass regressions.

### Running Single Tests
- `npm test -- src/services/__tests__/EpubGenerator.test.ts` - Run a specific test file.
- `npm test -- --testPathPattern=EpubGenerator` - Run tests matching a path pattern.
- `npm test -- --testNamePattern="should generate epub"` - Run tests matching a description.

---

## Core Architecture & Patterns

### 1. Layered Service Architecture
```
UI Layer (app/) → Custom Hooks (src/hooks/) → Service Layer (src/services/) → Data Layer (AsyncStorage + expo-file-system)
```
- State is managed via React hooks and singleton services. Components should focus on presentation; business logic belongs in hooks.

### 2. Source Provider Pattern
- All novel sites implement `SourceProvider` (`src/services/source/types.ts`).
- New providers must be registered in `src/services/source/SourceRegistry.ts`.
- Current implementations: `RoyalRoadProvider`, `ScribbleHubProvider`.

### 3. Download System
Event-driven and concurrent download runner:
- **DownloadQueue**: AsyncStorage-backed persistent job queue.
- **DownloadManager**: EventEmitter-based worker pool (concurrency limit: 3).
- **DownloadStoryCache**: In-memory cache with story-level mutex locks and batched writes (flush threshold: 3 updates) to reduce AsyncStorage overhead.
- **Live Updates**: The library UI subscribes to `DownloadManager` using `withLiveDownloadProgress()` inside `useLibrary.ts`.

### 4. Storage & Files
- Do not use the monolithic `StorageService` for new code; use focused modules: `LibraryStorage`, `PreferencesStorage`, `RegexCleanupRulesStorage`, `CentralStorage`.
- Centralize storage keys in `src/services/storage/storageKeys.ts`.
- Downloaded chapters are saved immediately under `/Documents/novels/{storyId}/` with files named `{0000}_title.html`.

### 5. Archive Snapshots
- When a story's source URL changes or chapters are removed, an archived snapshot is created.
- Copies the story to a new ID with `__archive_` suffix, sets `isArchived: true`, and saves the archive reason.

### 6. EPUB Generation
- Uses `jszip` with custom XML templating (`EpubMetadataGenerator.ts`, `EpubContentProcessor.ts`, `EpubFileSystem.ts`).
- Supports volume splitting based on a configurable max chapter count (default: 150).

---

## Essential Constraints & Platform Details

### Platform Detection
Use `src/utils/platform.ts` to detect the runtime environment:
- `isExpoGo()` - True when running in Expo Go (limited native modules).
- `isAndroidNative()` - True on Android with full native support.
- **Rule**: Never reference `Constants.executionEnvironment` directly.

### Native Modules (`modules/`)
Contains Kotlin code using the Expo Modules API (`modules/tts-media-session/`).
- **Rule**: Any changes to Kotlin files (`*.kt`) require a native app rebuild via `npm run android`.

### Custom Image Viewer
`src/components/ImageViewer.tsx` wraps image viewing. Double-tap/pinch zoom are enabled, but swipe-to-close is disabled to avoid gesture conflicts.

---

## Coding Style & Standards

- **Imports**: Use absolute imports from `src/` for internal modules. Order: React -> Third-party -> Internal.
- **Exports**: Named exports are preferred for components, hooks, services, and utilities.
- **Container Component Pattern**: Separate container screens (data/logic fetching) from presentational components (render only).
- **Tests**: Jest tests are co-located in `__tests__/` directories alongside the code they test. Ensure any new native modules, router APIs, or storage components are mocked using patterns in `jest-setup.ts`.
