# Android Port Development Plan

This plan outlines the phased development implementation for porting the Webnovel Archiver to Android, mirroring the functionalities of the existing Python CLI and adhering to the specified tech stack.

## Proposed Development Phases

### Phase 1: Foundation & UI Shell
**Goal**: Establish the project structure, navigation, and core UI theme.
- **Project Init**: Initialize Expo project with TypeScript.
- **Navigation**: Setup `expo-router` with file-based routing.
    - `/` (Home/Library)
    - `/add` (Add New Story)
    - `/details/[id]` (Story Details)
    - `/settings` (Settings/Backup)
- **UI System**: Install and configure `react-native-paper`. Setup theme (Dark/Light mode).
- **Core Components**: Create reusable components (StoryCard, ProgressBar, ScreenContainer).

### Phase 2: Core Archiving Engine (Scraping)
**Goal**: Implement the logic to fetch and parse content locally.
- **Networking Utility**:
    - Implement `fetch` wrapper with custom `User-Agent`.
    - Implement `Headless WebView` component for Cloudflare fallback.
- **Parsing Logic**:
    - Port `BeautifulSoup` logic to `cheerio`.
    - Implement `MetadataFetcher` (Title, Author, Cover).
    - Implement `ChapterListFetcher`.
    - Implement `ContentFetcher` (Chapter text cleaning).
- **Storage Layer (Files)**:
    - Setup `expo-file-system` to write raw/processed HTML to `AppContext.documentDirectory`.

### Phase 3: Library & Data Persistence
**Goal**: Persist data across app restarts and manage the library state.
- **Data Model**: Define TypeScript interfaces (`Story`, `Chapter`, `DownloadStatus`).
- **Persistence**:
    - Implement `StorageService` using `@react-native-async-storage/async-storage`.
    - Store/Retrieve the list of stories.
    - Implement `progress_status` tracking logic.
- **UI Integration**:
    - Connect Home Screen `FlatList` to the storage data.
    - Implement "Pull to Refresh" or Resume functionality for interrupted downloads.

### Phase 4: EPUB Generator
**Goal**: Enable offline reading by compiling chapters into EPUBs.
- **Engine**: Implement `EpubGenerator` class.
- **Logic**:
    - Generate `mimetype` file.
    - Generate `container.xml`.
    - Generate `content.opf` (manifest).
    - Generate `toc.ncx` (navigation).
    - Build chapter XHTML files from stored HTML.
- **Packaging**: Use `JSZip` to bundle files.
- **Output**: Save `.epub` file to scoped storage or export via `Storage Access Framework` (Sharing Intent).

### Phase 5: Background Tasks
**Goal**: Ensure data safety and reliable long-running operations.
- **Background Tasks**:
    - Implement `expo-task-manager` / `expo-background-fetch` for periodic sync.
    - (Note: For long downloads while app is open, use `expo-keep-awake`).

### Phase 6: Optimization & Polish
**Goal**: Ensure a smooth, premium user experience.
- **Performance**: Implement batching for downloads (process 10 chapters, then yield).
- **UI Polish**: Add skeletons during loading, animations for progress, empty states.
- **Error Handling**: Graceful failures for network issues or parsing errors.

## Verification Plan

### Automated Tests
- **Unit Tests (`jest`)**:
    - Verify `cheerio` parsing logic against sample HTML snippets.
    - Verify `EpubGenerator` produces valid XML structures.

### Manual Verification
- **Emulator/Device Testing**:
    - **Scraping**: Test with a real RoyalRoad URL. Verify HTML is saved to file system.
    - **EPUB**: export a generated EPUB and open it in a reader app (e.g., Google Play Books).
    - **Backup**: Verify files appear in the connected Google Drive account.
