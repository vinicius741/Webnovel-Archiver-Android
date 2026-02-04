# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Development Commands

- `npm start` - Start Expo development server
- `npm run android` - Run on Android device/emulator (requires expo run:android for native modules)
- `npm run ios` - Run on iOS device/simulator
- `npm test` - Run Jest tests with coverage (only covers RoyalRoadProvider and DownloadService per jest.config.js)

## Project Overview

Webnovel Archiver Android is a React Native app (via Expo SDK 54) that downloads, archives, and exports webnovels as EPUB files. It is **local-first** - all scraping, parsing, and EPUB generation happens on-device with no external backend servers.

## Architecture

### Layered Service Architecture

The app follows a strict separation of concerns:

```
UI Layer (app/) → Custom Hooks (src/hooks/) → Service Layer (src/services/) → Data Layer (AsyncStorage + expo-file-system)
```

**No global state management library** - state is managed through React hooks and singleton services. Components are pure; business logic lives in hooks.

### Source Provider Pattern
The app uses a `SourceProvider` interface (`src/services/source/types.ts`) to abstract different novel websites. Each provider implements:
- `isSource(url)` - Check if URL matches this provider
- `getStoryId(url)` - Extract unique story ID
- `parseMetadata(html)` - Parse title, author, cover, description, tags
- `getChapterList(html, url, onProgress)` - Fetch all chapter URLs (with pagination support)
- `parseChapterContent(html)` - Extract clean chapter content

New sources are registered in `src/services/source/SourceRegistry.ts`. Current implementations: RoyalRoadProvider.

### Download System Architecture

The download system is event-driven and concurrent:

- **DownloadQueue** (`src/services/download/DownloadQueue.ts`): Persistent AsyncStorage-backed job queue with state management
- **DownloadManager** (`src/services/download/DownloadManager.ts`): EventEmmiter-based worker pool with:
  - Configurable concurrency (default 3, set via AppSettings)
  - Story-level locking to prevent race conditions when updating chapters
  - Auto-resume on app start for pending jobs
  - Progress events: `job-started`, `job-completed`, `job-failed`, `queue-updated`, `all-complete`

Download flow:
1. Jobs added to queue → `queue-updated` event fires
2. Manager spawns workers up to concurrency limit
3. Each worker: fetches HTML → parses content → saves to disk → updates story metadata
4. Story updates are wrapped in mutex locks to prevent concurrent modification
5. Notifications update throughout process

### Service Layer Organization
- `src/services/source/` - Website scraping and parsing (providers)
- `src/services/download/` - DownloadQueue and DownloadManager
- `src/services/epub/` - EPUB generation (content processor, metadata, file system)
- `src/services/storage/` - File system operations (expo-file-system wrapper)
- `src/services/network/` - Fetcher with mobile User-Agent
- `DownloadService.ts` - High-level download orchestration (wraps DownloadManager)
- `EpubGenerator.ts` - EPUB generation orchestration
- `StorageService.ts` - AsyncStorage wrapper singleton

### EPUB Generation
Node.js EPUB libraries don't work in React Native. The app uses `jszip` with custom XML templating to build standard EPUB files. The logic is split across:
- `EpubMetadataGenerator.ts` - OPF manifest, NCX table of contents
- `EpubContentProcessor.ts` - Chapter HTML to EPUB XHTML conversion
- `EpubFileSystem.ts` - JSZip wrapper for EPUB structure

Supports volume splitting (configurable max chapters per EPUB, default 150).

### Custom Hooks Layer
Screen-specific logic is encapsulated in `src/hooks/`:
- `useAddStory.ts` - URL validation, metadata fetching, chapter list parsing
- `useLibrary.ts` - Library state management with search/filter/sort
- `useStoryDetails.ts` - Story metadata and chapter list
- `useStoryDownload.ts` - Download orchestration via DownloadService
- `useStoryEPUB.ts` - EPUB generation via EpubGenerator
- `useReaderNavigation.ts` - Chapter navigation and reading progress

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
AsyncStorage keys (versioned for migrations):
- `wa_library_v1` - Array of Story objects
- `wa_settings_v1` - AppSettings (downloadConcurrency, downloadDelay, maxChaptersPerEpub)
- `wa_sentence_removal_v1` - Array of sentence strings to filter
- `wa_tts_settings_v1` - TTSSettings (pitch, rate, voiceIdentifier, chunkSize)
- `wa_chapter_filter_settings_v1` - ChapterFilterSettings (filterMode: 'all' | 'hideNonDownloaded' | 'hideAboveBookmark')

File storage (expo-file-system):
- `/Documents/novels/{storyId}/` - Story directory
- Chapter files: `0000_title.html` (zero-padded 4-digit index)

## Important Implementation Details

### Performance
- Chapters saved to disk immediately, not held in memory
- DownloadManager uses setTimeout yields to prevent event loop blocking
- `expo-keep-awake` prevents sleep during active downloads
- Concurrency control prevents overwhelming both server and device

### Metro Configuration
Polyfills Node.js modules for React Native compatibility:
- `stream`, `events`, `string_decoder`, `buffer`, `url` (via stream-browserify, events, buffer, url packages)

### Testing
- Tests co-located in `__tests__/` directories within each module
- `jest-fetch-mock` for network mocking
- AsyncStorage and Expo Router mocked in `jest-setup.ts`
- Coverage **only** for `RoyalRoadProvider.ts` and `DownloadService.ts` (per jest.config.js)
- Run single test: `npm test -- --testNamePattern="test name"`

### TypeScript
Strict mode enabled with Expo base config. Absolute imports from `src/` preferred.

### UI Library
React Native Paper (Material Design) with theme in `src/theme/`. Theme mode ('system' | 'light' | 'dark') persisted in AsyncStorage.
