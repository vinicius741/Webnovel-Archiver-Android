# AGENTS.md

This file provides guidelines for agentic coding assistants working on this codebase.

## Project Overview

Webnovel Archiver Android is a React Native app (via Expo SDK 54) that downloads, archives, and exports webnovels as EPUB files. It is **local-first** — all scraping, parsing, and EPUB generation happens on-device with no external backend servers.

## Build/Lint/Test Commands

### Available Scripts
- `npm test` - Run all Jest tests (coverage disabled by default)
- `npm run test:coverage` - Run Jest tests with coverage collection
- `npm start` - Start Expo development server
- `npm run android` - Run on Android device/emulator
- `npm run ios` - Run on iOS device/simulator
- `npm run web` - Run web version
- `npm run lint` - Run ESLint on all TypeScript files
- `npm run lint:fix` - Run ESLint with auto-fix
- `npm run typecheck` - Run TypeScript validation with `tsc --noEmit`
- `npm run quality` - Check code quality metrics against the committed baseline
- `npm run quality:report` - Generate and print code quality metrics without failing on regressions
- `npm run quality:baseline` - Refresh the committed code quality baseline intentionally
- `npm run check` - Run lint, typecheck, coverage, and quality checks in sequence

### Running Single Tests
- `npm test -- src/services/__tests__/EpubGenerator.test.ts` - Run specific test file
- `npm test -- --testPathPattern=EpubGenerator` - Run tests matching pattern
- `npm test -- --testNamePattern="should generate epub"` - Run tests matching name

TypeScript is configured with strict mode.

### Code Quality Baseline
- After making code changes, run `npm run quality` or the full `npm run check`.
- If `npm run quality` reports that quality is worse than the baseline, continue improving the code by reducing duplication, file size, circular dependencies, or unused-code issues until the gate passes.
- Do not refresh the baseline as the default response to a regression. Use `npm run quality:baseline` only for intentional baseline maintenance.
- The quality gate includes both app code and tests.

## Architecture

### Layered Service Architecture

The app follows a strict separation of concerns:

```
UI Layer (app/) → Custom Hooks (src/hooks/) → Service Layer (src/services/) → Data Layer (AsyncStorage + expo-file-system)
```

No global state management library — state is managed through React hooks and singleton services. Components are pure; business logic lives in hooks.

### Source Provider Pattern
The app uses a `SourceProvider` interface (`src/services/source/types.ts`) to abstract different novel websites. Each provider implements:
- `isSource(url)` - Check if URL matches this provider
- `getStoryId(url)` - Extract unique story ID
- `parseMetadata(html)` - Parse title, author, cover, description, tags
- `getChapterList(html, url, onProgress)` - Fetch all chapter URLs (with pagination support)
- `parseChapterContent(html)` - Extract clean chapter content

New sources are registered in `src/services/source/SourceRegistry.ts`. Current implementations: RoyalRoadProvider, ScribbleHubProvider.

### Download System Architecture

The download system is event-driven and concurrent:

- **DownloadQueue** (`src/services/download/DownloadQueue.ts`): Persistent AsyncStorage-backed job queue with state management
- **DownloadManager** (`src/services/download/DownloadManager.ts`): EventEmitter-based worker pool with configurable concurrency (default 3)
- **DownloadStoryCache** (`src/services/download/DownloadStoryCache.ts`): In-memory story cache with batched storage flushes and story-level mutex locks
- **DownloadNotificationManager** (`src/services/download/DownloadNotificationManager.ts`): Throttled download progress notification lifecycle

Download flow:
1. Jobs added to queue → `queue-updated` event fires
2. Manager spawns workers up to concurrency limit
3. Each worker: fetches HTML → parses content → saves to disk → updates story metadata
4. Story updates are batched in DownloadStoryCache with story-level mutex locks
5. Notifications update throughout process via DownloadNotificationManager

### Service Layer Organization
- `src/services/source/` - Website scraping and parsing (providers)
- `src/services/download/` - DownloadQueue, DownloadManager, DownloadStoryCache, DownloadNotificationManager
- `src/services/epub/` - EPUB generation (content processor, metadata, file system)
- `src/services/storage/` - Focused storage modules (`libraryStorage.ts`, `preferencesStorage.ts`, `fileSystem.ts`, `regexCleanupRulesStorage.ts`, centralized `storageKeys.ts`)
- `src/services/story/` - Story synchronization orchestration (`storySyncOrchestrator.ts`)
- `src/services/network/` - Fetcher with mobile User-Agent
- `src/services/tts/` - TTS queue management, playback controller, session persistence, and state emitter
- Top-level services in `src/services/`:
  - `DownloadService.ts` - High-level download orchestration (wraps DownloadManager)
  - `EpubGenerator.ts` - EPUB generation orchestration
  - `StorageService.ts` - AsyncStorage wrapper (still actively used; new code should prefer focused modules in `storage/`)
  - `BackgroundService.ts` / `ForegroundServiceCoordinator.ts` - Background download and foreground service lifecycle
  - `NotificationService.ts` - Download notifications via Notifee
  - `TTSStateManager.ts` / `TTSLifecycleService.ts` / `TTSNotificationService.ts` / `TtsMediaSessionService.ts` - TTS playback state, lifecycle, notifications, and media session integration
- Shared utilities in `src/utils/`:
  - `textCleanup.ts` - HTML content cleanup and regex-based sentence removal
  - `regexValidation.ts` - Regex rule validation, normalization, and ReDoS risk detection
  - `saveAndNotify.ts` - Shared save-to-storage-then-notify-callback helper used across hooks

### Architecture Patterns

#### Container Component Pattern
Complex screens are refactored into container components that separate concerns:
- **Container components** (e.g., `StoryDetailsScreenContainer`, `ReaderScreenContainer`) handle business logic and data fetching
- **View state hooks** (e.g., `useStoryDetailsViewState`, `useReaderScreenController`) manage UI state and interactions
- **Presentational components** focus purely on rendering

#### Storage Module Pattern
Storage is organized into focused modules instead of a monolithic service:
- `LibraryStorage` - Story CRUD, archive snapshots, chapter tracking
- `PreferencesStorage` - App settings, TTS configuration, tabs
- Each module uses centralized `STORAGE_KEYS` from `storageKeys.ts`
- Clear separation between file system operations (`fileSystem.ts`) and data storage

#### Story Sync Orchestration
Story synchronization logic is extracted into `storySyncOrchestrator.ts`:
- `prepareStorySyncData()` - Fetches metadata and prepares merge data
- `buildStoryForAdd()` - Creates new story from sync data
- `buildStoryForSync()` - Updates existing story with new data
- `mergeChapters()` utility - Handles chapter list merging with existing downloads

### EPUB Generation
Node.js EPUB libraries don't work in React Native. The app uses `jszip` with custom XML templating to build standard EPUB files. The logic is split across:
- `EpubMetadataGenerator.ts` - OPF manifest, NCX table of contents
- `EpubContentProcessor.ts` - Chapter HTML to EPUB XHTML conversion
- `EpubFileSystem.ts` - JSZip wrapper for EPUB structure

Supports volume splitting (configurable max chapters per EPUB, default 150).

### Custom Hooks Layer
Screen-specific logic is encapsulated in `src/hooks/`. Key hooks include `useAddStory`, `useLibrary`, `useStoryDetails`, `useStoryDownload`, `useStoryEPUB`, and `useReaderNavigation`. Complex screens use nested view-state hooks (e.g., `hooks/details/useStoryDetailsViewState.ts`, `hooks/reader/useReaderScreenController.ts`).

### Routing
Expo Router file-based routing (`app/` directory):
- `_layout.tsx` - Root layout with theme provider and notification setup
- `index.tsx` - Library/home screen (story grid)
- `add.tsx` - Add story by URL (modal presentation)
- `details/[id].tsx` - Story details, chapter list, download/generate actions
- `reader/[storyId]/[chapterId].tsx` - Reading screen
- `sentence-removal.tsx` - Content cleaning utility
- `settings.tsx` - App settings, TTS configuration, theme toggles

### Storage Schema
AsyncStorage keys are versioned for migrations and centralized in `src/services/storage/storageKeys.ts`. File storage uses `expo-file-system` under `/Documents/novels/{storyId}/` with chapter files named `{0000}_title.html` (zero-padded 4-digit index).

### Archive Snapshots
When a story's source URL changes or chapters are removed, the app creates an archived snapshot:
- Preserves all downloaded chapter files
- Creates new story ID with `__archive_` suffix
- Marks with `isArchived: true`, `archiveReason`, and `archiveOfStoryId`
- Prevents data loss when syncing from changed sources

## Important Implementation Details

### Platform Detection
Use `src/utils/platform.ts` for environment detection:
- `isExpoGo()` - Returns true when running in Expo Go (limited native module support)
- `isAndroidNative()` - Returns true on Android with full native module support
- Use these instead of checking `Constants.executionEnvironment` directly

### Performance
- Chapters saved to disk immediately, not held in memory
- DownloadStoryCache batches storage writes (flush threshold of 3 updates) to reduce AsyncStorage overhead
- DownloadManager uses setTimeout yields to prevent event loop blocking
- `expo-keep-awake` prevents sleep during active downloads
- Concurrency control prevents overwhelming both server and device

### Metro Configuration
Polyfills Node.js modules for React Native compatibility:
- `stream`, `events`, `string_decoder`, `buffer`, `url` (via stream-browserify, events, buffer, url packages)

### Native Modules (modules/)

The `modules/` directory contains **native Android code** written in Kotlin using the Expo Modules API. This code runs outside the JavaScript/React Native runtime and requires native compilation.

#### tts-media-session
An Expo Module providing Android media session support for TTS playback. This enables:
- Media button events (play/pause from headphones, Bluetooth devices, car controls)
- Integration with Android's MediaSession framework
- Proper foreground service behavior for TTS playback

**Structure:**
```
modules/tts-media-session/
├── android/src/main/java/expo/modules/ttsmediasession/
│   ├── TtsMediaSessionModule.kt        # Main module interface
│   ├── TtsMediaSessionService.kt       # Foreground service for media session
│   └── TtsMediaSessionEventEmitter.kt  # Event emission to JS
├── android/build.gradle                # Native build configuration
├── expo-module.config.json             # Expo module manifest
└── src/index.ts                        # TypeScript/JS interface
```

**When modifying this module:**
1. Changes to Kotlin files (`*.kt`) require rebuilding the native app: `npm run android` (not just `npm start`)
2. The module is Android-only (`platforms: ["android"]`) - no iOS implementation
3. Use `expo-modules-api` for the bridge between JS and native code
4. Follow Expo Modules conventions for native module development

## Code Style Guidelines

### TypeScript & Types
- Strict TypeScript mode is enabled
- Define interfaces for all component props and complex objects
- Use `as const` for enum-like objects (e.g., `DownloadStatus`)
- Export types from `src/types/index.ts` for shared types
- Mark optional fields with `?`

### Imports
- Order: React first, then third-party libraries, then internal modules
- Use absolute imports from `src/` for internal modules
- Named exports preferred for components, services, and utilities

### Components
- Functional components with TypeScript interfaces for props
- Props interface defined inline before component
- Use React hooks for state and side effects
- Named exports (e.g., `export const StoryCard = ...`)
- Styles defined at bottom with `StyleSheet.create()`

### Naming Conventions
- Components: PascalCase (`StoryCard`, `TTSController`)
- Hooks: camelCase with `use` prefix (`useAddStory`, `useStoryDetails`)
- Services/Modules: camelCase (`epubGenerator`, `storageService`)
- Interfaces/Types: PascalCase (`Story`, `Chapter`, `NovelMetadata`)
- Constants/Enums: PascalCase (`DownloadStatus`)
- Functions/Variables: camelCase (`sanitizeTitle`, `handleAdd`)
- Test files: `.test.ts` extension in `__tests__/` directories

### File Organization
- `src/components/` - UI components (grouped by feature)
  - `components/details/` - Story details screen components and containers
  - `components/reader/` - Reader screen components and containers
  - `components/library/` - Library screen components
  - `components/downloads/` - Download manager components
  - `components/tabs/` - Tab management components
- `src/hooks/` - Custom React hooks
  - `hooks/details/` - Details screen hooks (e.g., `useStoryDetailsViewState`)
  - `hooks/reader/` - Reader screen hooks (e.g., `useReaderScreenController`)
- `src/services/` - Business logic and external integrations
  - `services/download/` - Download management
  - `services/source/` - Novel source providers
  - `services/storage/` - Focused storage modules
    - `storage/libraryStorage.ts` - Story/library CRUD operations
    - `storage/preferencesStorage.ts` - Settings, TTS, tabs storage
    - `storage/fileSystem.ts` - File system operations
    - `storage/regexCleanupRulesStorage.ts` - Regex cleanup rules
    - `storage/storageKeys.ts` - Centralized storage key constants
  - `services/story/` - Story synchronization orchestration
  - `services/tts/` - Text-to-speech services
- `src/types/` - TypeScript type definitions
- `src/utils/` - Pure utility functions
  - `utils/platform.ts` - Platform detection utilities (`isExpoGo`, `isAndroidNative`)
- `src/context/` - React contexts
- `src/theme/` - Theme configurations
- Tests co-located in `__tests__/` directories alongside implementation

### Error Handling
- Use try-catch blocks for async operations
- Log errors with `console.error()`
- Provide user-friendly messages through AlertContext or notifications
- Graceful degradation (e.g., check `Constants.executionEnvironment` for Expo Go)

### Testing
- Jest with jest-expo preset
- Mock external dependencies (fileSystem, StorageService, native modules)
- Clear mocks in `beforeEach()`
- Describe blocks to group related tests
- Test names should describe expected behavior ("should generate epub")
- Test both success and error cases
- Coverage disabled by default; use `npm run test:coverage` for coverage
- Tests cover: services, hooks, components, utilities
- `jest-fetch-mock` for network mocking; AsyncStorage and Expo Router mocked in `jest-setup.ts`
- ~60 test files

### Additional Notes
- No comments added unless necessary (minimal comment approach)
- Use `expo-router` for navigation
- Use `react-native-paper` for UI components (Material Design 3)
- Use `expo-constants` to detect runtime environment
- Cheerio for HTML parsing in providers
- Container component pattern: Extract screen logic into container components (e.g., `StoryDetailsScreenContainer`, `ReaderScreenContainer`)
- View state hooks: Separate view state management from business logic (e.g., `useStoryDetailsViewState`, `useReaderScreenController`)
- Storage modules: Use focused storage classes (`LibraryStorage`, `PreferencesStorage`) instead of monolithic `StorageService`
- Platform utilities: Use `isExpoGo()` and `isAndroidNative()` from `utils/platform.ts` for environment detection
- TypeScript: Expo base config. Absolute imports from `src/` preferred
