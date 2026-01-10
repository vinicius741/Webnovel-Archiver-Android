# Refactoring Opportunities

This document identifies files in the codebase that are good candidates for refactoring due to their size, complexity, or multiple responsibilities. Files are prioritized by their potential impact on maintainability and code quality.

## Priority 1: High Impact Refactoring Candidates

### 1. Reader Screen (`app/reader/[storyId]/[chapterId].tsx`) - 332 lines
**Issues:**
- Multiple responsibilities: content loading, TTS management, navigation, highlighting, and UI rendering
- Complex state management with 8+ state variables
- WebView interaction logic mixed with component logic
- TTS integration tightly coupled with the component
- Inline JavaScript for WebView manipulation

**Suggested Refactoring:**
- Extract WebView highlighting logic into a custom hook (`useWebViewHighlight`)
- Extract TTS-specific logic into a separate component or use existing `useTTS` hook more effectively
- Create `ReaderContent` component for the WebView portion
- Extract navigation logic into `useReaderNavigation` hook
- Consider creating a `ReaderContext` for shared state

**Potential Components:**
- `WebViewReader` - Handles content display and highlighting
- `ReaderControls` - Manages TTS controls and navigation
- `useReaderContent` - Manages content loading and processing
- `useTTSIntegration` - Bridges TTS with reader-specific behavior

---

### 2. Story Details Hook (`src/hooks/useStoryDetails.ts`) - 322 lines
**Issues:**
- Handles 6+ different operations: delete, mark read, download, update, range download, EPUB generation
- Complex download/update logic mixed with state management
- Duplicate code between `downloadOrUpdate` and `downloadRange`
- Multiple alert dialogs embedded in business logic
- Tight coupling with multiple services (Storage, Download, EPUB, Network)

**Suggested Refactoring:**
- Extract individual operations into separate hooks:
  - `useStoryActions` - delete, mark as read
  - `useStoryDownload` - download, range download, update
  - `useStoryEPUB` - EPUB generation and reading
- Create a `StoryOperations` class or service to coordinate operations
- Extract validation logic into separate utilities
- Consider using a state machine for download/update status transitions

**Potential Modules:**
- `hooks/useStoryActions.ts` - Basic story operations
- `hooks/useStoryDownload.ts` - Download and update logic
- `services/StoryOperations.ts` - Coordinated operations
- `utils/storyValidation.ts` - Input validation

---

### 3. TTS State Manager (`src/services/TTSStateManager.ts`) - 273 lines
**Issues:**
- Monolithic singleton class managing multiple concerns
- Complex queue processing logic embedded in the manager
- Notification service coupling (lazy-loaded but still present)
- Session management and chunk buffering mixed together
- No separation between playback control and queue management

**Suggested Refactoring:**
- Extract queue processing into `TTSQueue` class
- Create `TTSPlaybackController` for playback control
- Extract notification logic into `TTSNotificationAdapter`
- Use composition instead of a large singleton
- Consider using a state machine for TTS states (idle, playing, paused, stopped)

**Potential Modules:**
- `services/tts/TTSQueue.ts` - Queue management
- `services/tts/TTSPlaybackController.ts` - Playback control
- `services/tts/TTSNotificationAdapter.ts` - Notification integration
- `services/tts/TTSStateMachine.ts` - State management

---

### 4. EPUB Generator (`src/services/EpubGenerator.ts`) - 231 lines
**Issues:**
- Multiple XML generation methods in a single class
- File I/O, XML generation, and content processing mixed together
- Hard-coded XML templates as template literals
- No separation between EPUB structure and content generation
- Content sanitization logic embedded in the generator

**Suggested Refactoring:**
- Extract XML generators into separate modules:
  - `EpubXmlBuilder` - Builds XML structures
  - `EpubMetadataGenerator` - Generates metadata
  - `EpubContentGenerator` - Generates chapter content
- Create `EpubFileSystem` for file operations
- Extract content processing into `EpubContentProcessor`
- Use a template engine or dedicated XML builder library

**Potential Modules:**
- `services/epub/EpubXmlBuilder.ts` - XML structure building
- `services/epub/EpubMetadataGenerator.ts` - Metadata generation
- `services/epub/EpubContentProcessor.ts` - Content processing
- `services/epub/EpubFileSystem.ts` - File operations

---

## Priority 2: Medium Impact Refactoring Candidates

### 5. Sentence Removal Screen (`app/sentence-removal.tsx`) - 227 lines
**Issues:**
- CRUD operations mixed with UI logic
- File export/import logic embedded in component
- Duplicate handling logic
- Alert dialogs for confirmations

**Suggested Refactoring:**
- Extract CRUD operations into `useSentenceRemoval` hook
- Create `SentenceRemovalList` component for the list display
- Extract export/import logic into `SentenceRemovalIO` utility
- Create a `SentenceRemovalContext` for shared state

**Potential Components:**
- `components/SentenceRemovalList.tsx` - List display
- `components/SentenceRemovalDialog.tsx` - Add/edit dialog
- `hooks/useSentenceRemoval.ts` - CRUD operations
- `utils/sentenceRemovalIO.ts` - Import/export

---

### 6. Home Screen (`app/index.tsx`) - 219 lines
**Issues:**
- Complex layout calculation logic mixed with UI
- Search, filter, and sort logic tightly coupled
- Grid layout calculation embedded in component
- Tag and source filtering logic mixed together

**Suggested Refactoring:**
- Extract layout calculation into `useGridLayout` hook
- Create `StoryGrid` component for the grid display
- Extract search/filter/sort into `useStoryFilter` hook (already exists as `useLibrary`)
- Separate tag filtering component

**Potential Components:**
- `components/StoryGrid.tsx` - Grid display
- `components/StorySearchBar.tsx` - Search and sort
- `components/StoryTagsFilter.tsx` - Tag filtering
- `hooks/useGridLayout.ts` - Layout calculations

---

### 7. Download Manager (`src/services/download/DownloadManager.ts`) - 217 lines
**Issues:**
- Complex concurrency management
- Notification logic mixed with download logic
- Story locking mechanism embedded in manager
- Main process loop handles multiple concerns

**Suggested Refactoring:**
- Extract concurrency management into `ConcurrencyManager` class
- Extract notification handling into `DownloadNotificationHandler`
- Create `StoryLockManager` for story-level locking
- Separate job processing into `JobProcessor`

**Potential Modules:**
- `services/download/ConcurrencyManager.ts` - Concurrency control
- `services/download/DownloadNotificationHandler.ts` - Notification logic
- `services/download/StoryLockManager.ts` - Story locking
- `services/download/JobProcessor.ts` - Job execution

---

### 8. ScribbleHub Provider (`src/services/source/providers/ScribbleHubProvider.ts`) - 204 lines
**Issues:**
- Complex pagination logic embedded in chapter list fetching
- URL construction logic duplicated
- Loop detection mixed with pagination
- Progress callbacks scattered throughout

**Suggested Refactoring:**
- Extract pagination logic into `PaginationHandler` utility
- Create `ScribbleHubUrlBuilder` for URL construction
- Extract page parsing into separate methods
- Use a strategy pattern for different pagination strategies

**Potential Modules:**
- `services/source/providers/ScribbleHubPagination.ts` - Pagination logic
- `services/source/providers/ScribbleHubUrlBuilder.ts` - URL construction
- `services/source/providers/BaseProvider.ts` - Common provider logic

---

### 9. Library Hook (`src/hooks/useLibrary.ts`) - 197 lines
**Issues:**
- Complex filtering logic with multiple conditions
- Sorting logic embedded in the hook
- Tag and source filtering mixed together
- Memoization dependencies complex and error-prone

**Suggested Refactoring:**
- Extract filtering logic into `StoryFilter` utility
- Extract sorting logic into `StorySorter` utility
- Separate tag and source filtering into their own utilities
- Use composition to combine filter and sort operations

**Potential Modules:**
- `utils/StoryFilter.ts` - Filtering logic
- `utils/StorySorter.ts` - Sorting logic
- `utils/TagFilter.ts` - Tag filtering
- `utils/SourceFilter.ts` - Source filtering

---

### 10. TTS Settings Modal (`src/components/TTSSettingsModal.tsx`) - 191 lines
**Issues:**
- Voice loading and filtering logic mixed with UI
- Multiple sliders with similar code
- Voice list rendering logic embedded in modal

**Suggested Refactoring:**
- Extract voice management into `useVoiceList` hook
- Create reusable `SettingSlider` component
- Extract voice list into `VoiceList` component
- Separate voice filtering logic

**Potential Components:**
- `components/SettingSlider.tsx` - Reusable slider
- `components/VoiceList.tsx` - Voice list display
- `components/VoiceListItem.tsx` - Individual voice item
- `hooks/useVoiceList.ts` - Voice management

---

## Additional Refactoring Opportunities

### 11. Storage Service (`src/services/StorageService.ts`) - 186 lines
**Issues:**
- Multiple storage domains (library, settings, sentence removal, TTS)
- No clear separation between domains
- Similar patterns repeated for different data types

**Suggested Refactoring:**
- Create domain-specific repositories:
  - `LibraryRepository`
  - `SettingsRepository`
  - `TTSSettingsRepository`
  - `SentenceRemovalRepository`
- Create a base `Repository` class with common CRUD operations

---

### 12. Notification Service (`src/services/NotificationService.ts`) - 157 lines
**Issues:**
- Platform-specific code mixed with service logic
- Multiple notification types handled in one service
- Lazy loading logic for native modules

**Suggested Refactoring:**
- Create platform-specific adapters
- Separate download notification logic into `DownloadNotificationService`
- Create `NotificationChannelManager` for channel management
- Use dependency injection for native modules

---

## General Refactoring Principles

1. **Separation of Concerns**: Each module should have a single, well-defined responsibility
2. **Composition over Inheritance**: Use composition to combine smaller, focused modules
3. **Single Responsibility Principle**: Functions and classes should do one thing well
4. **Dependency Injection**: Avoid tight coupling by injecting dependencies
5. **Testability**: Smaller, focused modules are easier to test
6. **Reusability**: Extract common patterns into reusable components and utilities

## Refactoring Priority Matrix

| File | Lines | Complexity | Impact | Priority |
|------|-------|------------|--------|----------|
| `app/reader/[storyId]/[chapterId].tsx` | 332 | High | High | 1 |
| `src/hooks/useStoryDetails.ts` | 322 | High | High | 1 |
| `src/services/TTSStateManager.ts` | 273 | High | Medium | 1 |
| `src/services/EpubGenerator.ts` | 231 | Medium | High | 1 |
| `app/sentence-removal.tsx` | 227 | Medium | Medium | 2 |
| `app/index.tsx` | 219 | Medium | Medium | 2 |
| `src/services/download/DownloadManager.ts` | 217 | High | Medium | 2 |
| `src/services/source/providers/ScribbleHubProvider.ts` | 204 | Medium | Low | 2 |
| `src/hooks/useLibrary.ts` | 197 | Medium | Medium | 2 |
| `src/components/TTSSettingsModal.tsx` | 191 | Low | Low | 3 |

## Next Steps

1. Start with Priority 1 files as they have the highest impact
2. Create new files for extracted components/services
3. Update imports gradually
4. Run tests after each refactoring step
5. Update this document as refactoring progresses

## Notes

- All refactoring should maintain existing functionality
- Consider backward compatibility when extracting services
- Use TypeScript strict mode to catch type errors during refactoring
- Keep tests passing throughout the refactoring process
- Document any breaking changes in the code comments
