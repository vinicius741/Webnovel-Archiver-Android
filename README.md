# Webnovel Archiver (Android)

Webnovel Archiver is a powerful, local-first Android application designed to download, archive, and export webnovels for offline reading. Built with **React Native** and **Expo**, it brings the power of Python-based scrapers to your mobile device, allowing you to build a personal library of your favorite stories.

## Key Features

- **Multi-Source Support**: Extensible `SourceProvider` architecture (currently RoyalRoad, easily add more via `SourceRegistry`).
- **Webnovel Scraping**: Automatically fetches chapters and metadata from supported sites.
- **Offline Reading**: Downloaded chapters are stored locally, accessible anytime without an internet connection.
- **Built-in Reader**: WebView-based chapter reader with TTS integration, image viewing, and last-read position tracking.
- **Text-to-Speech (TTS)**: Full TTS engine with playback state machine, sequential chunk queue, lock-screen media controls (custom native module), background playback via Foreground Service, session persistence across app restarts, and configurable pitch/rate/voice/chunk size.
- **EPUB Export**: Generate EPUB 2.0 files with automatic volume splitting, configurable chapter ranges, progress reporting, and save via Android Storage Access Framework. Compatible with Moon+ Reader, Kindle, and other e-readers.
- **Background Downloads**: Concurrent download engine with persistent job queue (survives app restart), configurable concurrency, pause/resume/retry, and Android Foreground Service for reliable background operation.
- **Text Cleanup**: Sentence removal (exact match) and regex cleanup rules with per-target scope (download, TTS, or both). Includes a quick-builder for common patterns, advanced raw-regex mode with ReDoS-safe validation, and JSON export/import.
- **Library Organization**: Custom tabs for grouping stories, multi-select to move between tabs, search, tag filtering, and sort (title, date, author).
- **Smart Updates**: Checks for new chapters, merges intelligently preserving download state, and marks EPUBs as stale when content changes.
- **Story Archiving**: Create archived snapshots of stories (e.g., when source removes chapters) with reason tracking.
- **Backup & Restore**: JSON-based library export/import with versioned format, tab support, and merge-on-import.
- **Theming**: System, Light, or Dark mode with Material Design 3 theming.
- **Privacy Focused**: All data lives on your device. No external servers or accounts required.

## Tech Stack

- **Framework**: [React Native](https://reactnative.dev/) (via [Expo SDK 54](https://expo.dev/))
- **Language**: TypeScript (strict mode)
- **Navigation**: [Expo Router](https://docs.expo.dev/router/introduction/) (File-based routing)
- **UI Library**: [React Native Paper](https://callstack.github.io/react-native-paper/) (Material Design 3)
- **Parsing Engine**: `cheerio` (HTML parsing for source providers)
- **Networking**: Native `fetch` API + `SourceProvider` pattern for site-specific parsing logic
- **Storage**: `expo-file-system` (chapter content, EPUB files) & `@react-native-async-storage/async-storage` (metadata, settings, queue)
- **EPUB Engine**: Custom implementation using `jszip` with modular file system, content processor, and metadata generator
- **TTS Engine**: `expo-speech` + custom native `tts-media-session` module (Kotlin) for Android Media Session integration
- **Notifications**: `@notifee/react-native` for download and TTS notifications with interactive actions
- **Foreground Service**: Custom Expo config plugin for Android FGS with `dataSync | mediaPlayback` types

## Prerequisites

- **Node.js** (LTS version recommended)
- **npm** (comes with Node.js)
- **Expo Go** app on your Android/iOS device (for development)

## Installation

1. Clone the repository:
   ```bash
   git clone https://github.com/vinicius741/Webnovel-Archiver-Android.git
   cd Webnovel-Archiver-Android
   ```

2. Install dependencies:
   ```bash
   npm install
   ```

## How to Run

### Development (Expo Go)

1. Start the development server:
   ```bash
   npx expo start
   ```

2. **Android**: Scan the QR code with the **Expo Go** app.
3. **iOS**: Scan the QR code with the **Camera** app.

### Build Local APK

To generate an installable APK file directly on your machine (requires Android SDK):

```bash
npm install -g eas-cli
npx eas build -p android --profile preview --local
```

## Quality Gates

| Command | Description |
|---------|-------------|
| `npm run lint` | ESLint on `src/` and `app/` TypeScript files |
| `npm run lint:fix` | ESLint with auto-fix |
| `npm run typecheck` | TypeScript validation with `tsc --noEmit` |
| `npm test` | Run all Jest tests |
| `npm run test:coverage` | Run Jest with coverage collection |
| `npm run check` | Run lint, typecheck, and coverage in sequence |

### Running Single Tests

```bash
npm test -- src/services/__tests__/EpubGenerator.test.ts
npm test -- --testPathPattern=EpubGenerator
npm test -- --testNamePattern="should generate epub"
```

## Usage Guide

### 1. Adding a Story
- Tap the **"+"** button on the home screen.
- Paste the URL of the webnovel (e.g., a RoyalRoad fiction page).
- Choose a tab (optional) and tap **"Add"**. The app fetches metadata and adds it to your library.

### 2. Downloading Content
- Open a story from your library.
- Tap **"Download All"**, **"Update"**, or use the **download range** dialog to select specific chapters.
- Chapters are queued in the download manager. Monitor progress from the **Download Manager** screen.
- Text cleanup rules (sentence removal + regex) are applied automatically during download.

### 3. Reading
- Tap a downloaded chapter to open it in the built-in reader.
- Navigate between chapters with prev/next buttons.
- Start TTS from the reader to listen with synchronized chunk highlighting.
- Media controls appear in the notification for background playback.

### 4. Exporting EPUB
- Open a story and tap **"Generate EPUB"**.
- Configure the EPUB (chapter range, max chapters per volume, start after bookmark).
- The file is saved to your chosen directory and can be opened with any e-reader app.

### 5. Text Cleanup
- Go to **Settings > Text Cleanup** to manage sentence removal and regex rules.
- Use the **Quick Builder** to generate common patterns, or switch to **Advanced** mode for raw regex.
- Rules can target downloads only, TTS only, or both.
- Export/import rules as JSON for backup.

### 6. Organizing with Tabs
- Go to **Settings > Tab Management** to create, reorder, or delete tabs.
- Long-press stories on the library screen to multi-select and move them between tabs.
- Swipe between tabs on the library screen.

### 7. Backup & Restore
- Go to **Settings** and use **Export Backup** / **Import Backup**.
- Backups include library metadata and tabs (not chapter content).

## Project Structure

```
app/                                  # Expo Router screens
  _layout.tsx                         # Root layout (providers, FGS, TTS init)
  index.tsx                           # Library (home) screen
  add.tsx                             # Add story modal
  settings.tsx                        # Settings screen
  download-manager.tsx                # Download queue management
  sentence-removal.tsx                # Text cleanup rules
  tab-management.tsx                  # Tab CRUD management
  details/[id].tsx                    # Story details screen
  reader/[storyId]/[chapterId].tsx    # Chapter reader screen

src/
  components/                         # UI components
    details/                          #   Story details (info panel, chapters, actions, dialogs)
    reader/                           #   Reader (container, header)
    library/                          #   Library (tab bar, move-to-tab dialog, selection bar)
    downloads/                        #   Download manager (queue list, items, settings)
    sentence-removal/                 #   Text cleanup (sentence list, regex rules, quick builder)
    tabs/                             #   Tab management (tab dialog)
    ScreenContainer.tsx               #   Safe area wrapper
    StoryCard.tsx                     #   Library story card
    SortButton.tsx                    #   Sort dropdown
    ProgressBar.tsx                   #   Progress indicator
    ReaderContent.tsx                 #   WebView chapter renderer
    ReaderNavigation.tsx              #   Prev/next chapter navigation
    TTSController.tsx                 #   TTS playback controls
    TTSSettingsModal.tsx              #   TTS settings dialog
    HeadlessWebView.tsx               #   Headless WebView for JS execution
    ImageViewer.tsx                   #   Cross-platform image viewer

  hooks/                              # Custom React hooks
    details/useStoryDetailsViewState.ts   # Details screen view state
    reader/useReaderScreenController.ts   # Reader screen business logic
    useAddStory.ts                    # Add story by URL
    useDownloadProgress.ts            # Download progress tracking
    useDownloadQueue.ts               # Download queue state & actions
    useExportRules.ts                 # Export regex rules to JSON
    useLibrary.ts                     # Library loading, search, filter, sort, tabs
    useLibrarySelection.ts            # Multi-select for moving stories
    useReaderContent.ts               # Reader content loading & TTS prep
    useReaderNavigation.ts            # Chapter navigation
    useRegexRuleManagement.ts         # Regex rule CRUD UI state
    useScreenLayout.ts                # Responsive column calculation
    useSentenceManagement.ts          # Sentence removal CRUD
    useSentenceRemovalData.ts         # Load/save sentence removal data
    useSettings.ts                    # App settings + backup import/export
    useStoryActions.ts                # Sync, download, archive, delete
    useStoryDetails.ts                # Story details loading
    useStoryDownload.ts               # Download range management
    useStoryEPUB.ts                   # EPUB generation
    useTTS.ts                         # TTS playback control
    useTabManagement.ts               # Tab CRUD operations
    useTabs.ts                        # Tab loading and selection
    useWebViewHighlight.ts            # WebView TTS chunk highlighting

  services/                           # Business logic & external integrations
    download/                         #   Download engine
      DownloadManager.ts              #     Concurrent download engine
      DownloadQueue.ts                #     Persisted job queue
      DownloadNotificationManager.ts  #     Download notification updates
      DownloadStoryCache.ts           #     Batched story write cache
      types.ts                        #     DownloadJob, JobStatus, QueueStats
    epub/                             #   EPUB generation
      EpubFileSystem.ts               #     JSZip-based file builder
      EpubContentProcessor.ts         #     Chapter XHTML/CSS generation
      EpubMetadataGenerator.ts        #     OPF, NCX, TOC metadata generation
    network/                          #   HTTP
      fetcher.ts                      #     Page fetcher with UA spoofing
    source/                           #   Novel source providers
      SourceRegistry.ts               #     Provider registry (singleton)
      types.ts                        #     SourceProvider interface
      providers/RoyalRoadProvider.ts  #     RoyalRoad.com implementation
    storage/                          #   Focused storage modules
      storageKeys.ts                  #     Centralized key constants
      libraryStorage.ts               #     LibraryStorage (CRUD, archive, tabs)
      preferencesStorage.ts           #     PreferencesStorage (settings, TTS, filters, tabs)
      fileSystem.ts                   #     File operations (chapters, EPUB via SAF)
      regexCleanupRulesStorage.ts     #     Regex rules with validation
    story/                            #   Story sync
      storySyncOrchestrator.ts        #     Fetch, merge, build logic
    tts/                              #   TTS engine
      TTSPlaybackController.ts        #     Playback state machine
      TTSQueue.ts                     #     Sequential chunk playback
      TTSSessionPersistence.ts        #     Session save/restore
      TTSStateEmitter.ts             #     Debounced state broadcasting
    StorageService.ts                 #   Facade over all storage modules
    DownloadService.ts                #   Legacy download service
    EpubGenerator.ts                  #   EPUB generation orchestrator
    BackupService.ts                  #   Library import/export
    BackgroundService.ts              #   Notifee background event handler
    ForegroundServiceCoordinator.ts   #   FGS lifecycle (download + TTS)
    NotificationService.ts            #   Notification facade
    TTSStateManager.ts                #   TTS singleton state manager
    TTSLifecycleService.ts            #   Watchdog + app state recovery
    TTSNotificationService.ts         #   TTS notification display
    TTSFeatureFlags.ts                #   Feature flags for TTS reliability
    TtsMediaSessionService.ts         #   Android media session integration
    NotifeeTypes.ts                   #   Type-safe Notifee loader

  types/                              # TypeScript type definitions
    index.ts                          #   Story, Chapter, DownloadStatus, EpubConfig, etc.
    sentenceRemoval.ts                #   Regex cleanup rule types & helpers
    tab.ts                            #   Tab type

  utils/                              # Pure utility functions
    htmlUtils.ts                      #   HTML parsing, TTS content prep, text extraction
    textCleanup.ts                    #   Regex cleanup, sentence removal
    stringUtils.ts                    #   sanitizeTitle()
    mergeChapters.ts                  #   Chapter merge logic for sync
    regexBuilder.ts                   #   Quick-builder pattern generation/parsing
    regexValidation.ts                #   Regex validation, sanitization, risk detection
    storyValidation.ts                #   Story/chapter/range validation
    platform.ts                       #   isExpoGo(), isAndroidNative()
    saveAndNotify.ts                  #   saveAndNotify helper

  context/                            # React contexts
    AlertContext.tsx                   #   Global alert dialog provider

  theme/                              # Theming
    ThemeContext.tsx                   #   Theme provider (system/light/dark)
    light.ts                          #   Light theme config
    dark.ts                           #   Dark theme config

  constants/                          # App constants
    epub.ts                           #   EPUB limits
    default_sentence_removal.json     #   Default cleanup sentences

modules/
  tts-media-session/                  # Custom native module (Kotlin)
    android/                          #   Android Media Session for lock-screen controls
    src/index.ts                      #   JS/TS API

plugins/
  withAndroidForegroundService.js     # Expo config plugin for Android FGS types

documentation/                        # Detailed tech docs and decision logs
```

## Import Paths

TypeScript path aliases are configured in `tsconfig.json` for `src/*` and `app/*`.

## Contributing

Contributions are welcome! Please read the `documentation/` folder to understand the architecture before submitting a PR.
