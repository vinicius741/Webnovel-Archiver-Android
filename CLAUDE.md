# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Development Commands

- `npm start` - Start Expo development server
- `npm run android` - Run on Android device/emulator (requires expo run:android for native modules)
- `npm run ios` - Run on iOS device/simulator
- `npm test` - Run Jest tests with coverage

## Project Overview

Webnovel Archiver Android is a React Native app (via Expo SDK 54) that downloads, archives, and exports webnovels as EPUB files. It is **local-first** - all scraping, parsing, and EPUB generation happens on-device with no external backend servers.

## Architecture

### Source Provider Pattern
The app uses a `SourceProvider` interface (`src/services/source/types.ts`) to abstract different novel websites. Each provider implements:
- `isSource(url)` - Check if URL matches this provider
- `getStoryId(url)` - Extract unique story ID
- `parseMetadata(html)` - Parse title, author, cover, description, tags
- `getChapterList(html, url)` - Fetch all chapter URLs
- `parseChapterContent(html)` - Extract clean chapter content

New sources are registered in `src/services/source/SourceRegistry.ts`. Current implementations: `RoyalRoadProvider`, `ScribbleHubProvider`.

### Service Layer Organization
- `src/services/source/` - Website scraping and parsing (providers)
- `src/services/download/` - Download queue and batch management
- `src/services/epub/` - EPUB generation (content processor, metadata, file system)
- `src/services/storage/` - File system operations
- `src/services/network/` - Fetcher with WebView fallback for Cloudflare protection
- `DownloadService.ts` - Main download orchestration
- `EpubGenerator.ts` - EPUB generation orchestration
- `StorageService.ts` - Persistence layer
- `BackgroundService.ts` - Expo task manager for background downloads
- `NotificationService.ts` - Download progress notifications

### EPUB Generation
Node.js EPUB libraries don't work in React Native. The app uses `jszip` with custom XML templating to build standard EPUB files. The logic is split across:
- `EpubMetadataGenerator.ts` - OPF manifest and NCX table of contents
- `EpubContentProcessor.ts` - Chapter HTML conversion to EPUB XHTML
- `EpubFileSystem.ts` - File operations during packaging

### Custom Hooks Layer
Screen-specific logic is encapsulated in `src/hooks/`:
- `useAddStory.ts` - URL validation and metadata fetching
- `useLibrary.ts` - Library state management
- `useStoryDetails.ts` - Story metadata and chapter list
- `useStoryDownload.ts` - Download orchestration
- `useStoryEPUB.ts` - EPUB generation
- `useReaderNavigation.ts` - Chapter navigation

### Routing
Expo Router file-based routing (`app/` directory):
- `index.tsx` - Library/home screen
- `add.tsx` - Add story by URL
- `details/[id].tsx` - Story details and download
- `reader/[storyId]/[chapterId].tsx` - Reading screen
- `sentence-removal.tsx` - Content cleaning utility
- `settings.tsx` - App settings

## Important Implementation Details

### Cloudflare Handling
Sites protected by Cloudflare may block direct fetch. The network layer (`src/services/network/fetcher.ts`) falls back to a headless WebView that executes JavaScript challenges and returns the HTML.

### Performance
- Download processing batches chapters to prevent UI freezes
- Chapters are saved to disk immediately, not held in memory
- `expo-keep-awake` prevents sleep during long downloads

### Metro Configuration
Polyfills Node.js modules for React Native compatibility:
- `stream`, `events`, `string_decoder`, `buffer`, `url`

### Testing
- Tests co-located in `__tests__/` directories
- `jest-fetch-mock` for network mocking
- AsyncStorage and Expo Router mocked in `jest-setup.ts`
- Coverage tracking for critical services (see `jest.config.js`)

### TypeScript
Strict mode enabled with Expo base config. Absolute imports from `src/` preferred.

### UI Library
React Native Paper (Material Design) with theme in `src/theme/`.
