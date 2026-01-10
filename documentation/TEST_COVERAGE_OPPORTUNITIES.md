# Test Coverage Opportunities and Priorities

This document identifies areas of the codebase that require additional automated testing, prioritized by criticality, complexity, and risk.

---

## 1. Executive Summary

**Current Test Coverage:** < 10% (5 test files out of ~70 total files)

**High-Priority Test Opportunities:**
- Download orchestration and queue management (P0)
- TTS playback state machine (P0)
- Library filtering and sorting logic (P0)
- Backup/restore functionality (P1)
- Storage and file system operations (P1)

**Key Risk Areas Without Tests:**
1. Data loss during backup/restore operations
2. Download queue corruption and recovery
3. TTS state synchronization failures
4. Storage access permission errors
5. EPUB generation with invalid content

---

## 2. Current Test Coverage

### Already Tested Modules

| Module | Test File | Lines | Coverage Focus |
|--------|-----------|-------|----------------|
| EpubGenerator | `EpubGenerator.test.ts` | 139 | EPUB generation, sentence removal, error handling |
| StorageService | `StorageService.test.ts` | 146 | CRUD operations, settings, clear all |
| htmlUtils | `htmlUtils.test.ts` | 58 | Text extraction, sentence removal, title cleaning |
| ScribbleHubProvider | `ScribbleHubProvider.test.ts` | 143 | URL detection, metadata, chapters, pagination |
| RoyalRoadProvider | `RoyalRoadProvider.test.ts` | 84 | Metadata parsing, chapter list |

---

## 3. Priority 0 (P0) - Critical Path Testing

### 3.1 DownloadManager.test.ts

**Location:** `src/services/download/DownloadManager.ts` (218 lines)

**Why Critical:**
- Most complex service in the codebase
- Manages download queue, worker pool, and concurrency
- Integration point for notifications, storage, and network
- High risk of race conditions and memory leaks

**Test Scenarios Required:**

#### Queue Management
- Queue initialization with empty state
- Adding jobs to queue (single, multiple)
- Job priority handling
- Queue persistence to AsyncStorage
- Queue recovery from corrupted data
- Queue stats calculation (pending, active, completed, failed)

#### Worker Pool Management
- Worker pool initialization
- Worker assignment to jobs
- Worker lifecycle (start, complete, fail, cleanup)
- Concurrency control (max 3 concurrent workers)
- Worker timeout handling
- Worker error recovery

#### Story Locking
- Acquiring lock for story download
- Lock contention handling (multiple requests for same story)
- Lock release after completion
- Lock timeout and cleanup

#### Job Lifecycle
- Job status transitions (pending → active → completed/failed)
- Job retry logic on failure
- Job cancellation
- Job progress tracking
- Batch job processing

#### Error Handling
- Network failures during download
- File system errors (write failures, disk full)
- AsyncStorage failures
- Notification service unavailability
- Worker pool exhaustion
- Recovery from partial failures

---

### 3.2 DownloadQueue.test.ts

**Location:** `src/services/download/DownloadQueue.ts` (104 lines)

**Why Critical:**
- Persistent storage of download state
- Recovery mechanism for app crashes
- Foundation for resume functionality

**Test Scenarios Required:**

#### Persistence
- Save queue to AsyncStorage
- Load queue from AsyncStorage
- Handle missing/corrupted queue data
- Queue versioning and migration

#### Job Management
- Add job to queue
- Update job status
- Remove completed jobs
- Reset stuck jobs (jobs in active state on app start)
- Clear all jobs

#### Statistics
- Calculate pending jobs count
- Calculate active jobs count
- Calculate completed jobs count
- Calculate failed jobs count

---

### 3.3 DownloadService.test.ts

**Location:** `src/services/DownloadService.ts` (130 lines)

**Why Critical:**
- Orchestrates chapter downloads
- Applies sentence removal rules
- Coordinates with DownloadManager
- Direct user-facing download functionality

**Test Scenarios Required:**

#### Single Chapter Download
- Download one chapter successfully
- Download chapter with sentence removal
- Download chapter with invalid content
- Download chapter with network error
- Download chapter with storage error

#### Range Download
- Download range of chapters
- Download range with invalid indices
- Download range with sentence removal
- Handle partial range completion
- Resume interrupted range download

#### Progress Tracking
- Emit progress events for each chapter
- Calculate overall progress percentage
- Emit completion event

#### Error Handling
- Handle missing chapter content
- Handle invalid HTML content
- Handle fetch errors (404, 500, timeout)
- Handle storage write failures
- Handle sentence removal errors

---

### 3.4 TTSPlaybackController.test.ts

**Location:** `src/services/tts/TTSPlaybackController.ts` (214 lines)

**Why Critical:**
- Complex state machine for playback control
- Manages TTS queue and buffer
- Coordinates with expo-speech native module
- High user-facing feature with frequent interactions

**Test Scenarios Required:**

#### Initialization
- Initialize with empty queue
- Initialize with content queue
- Initialize with position (resume)

#### Playback Controls
- Start playback from beginning
- Start playback from specific position
- Pause playback
- Resume playback
- Stop playback
- Skip to next chunk
- Skip to previous chunk
- Seek to position

#### Queue Management
- Load content queue
- Clear queue
- Update queue position
- Handle empty queue during playback

#### State Management
- State transitions (idle → playing → paused → idle)
- Position tracking
- Buffer management (next chunk preloading)
- End of queue detection

#### Event Handling
- onStart callback execution
- onDone callback execution
- onProgress callback execution
- onError callback execution

#### Error Handling
- Handle speech synthesis errors
- Handle empty content chunks
- Handle queue position out of bounds
- Handle multiple rapid state changes
- Handle native module unavailability

---

### 3.5 useStoryDownload.test.ts

**Location:** `src/hooks/useStoryDownload.ts` (256 lines)

**Why Critical:**
- Orchestrates update checking and downloads
- Complex async flows with multiple services
- User-facing feature with alert dialogs
- High risk of data corruption during updates

**Test Scenarios Required:**

#### Update Checking
- Detect new chapters available
- Detect no updates available
- Handle metadata changes (title, author)
- Handle deleted chapters on source
- Handle update detection errors

#### Download Orchestration
- Trigger download of all chapters
- Trigger download of specific range
- Apply sentence removal during download
- Update existing story metadata
- Merge downloaded chapters with existing

#### Download Range Validation
- Validate valid chapter range
- Reject invalid start index
- Reject invalid end index
- Reject end < start
- Reject range beyond available chapters

#### Error Handling
- Handle network errors during update check
- Handle download failures
- Handle storage errors
- Handle sentence removal errors
- Show appropriate error alerts

---

### 3.6 useLibrary.test.ts

**Location:** `src/hooks/useLibrary.ts` (197 lines)

**Why Critical:**
- Complex filtering and sorting logic
- Multiple filter combinations
- Performance critical (runs on every render)
- User-facing data display

**Test Scenarios Required:**

#### Filtering
- Filter by source (RoyalRoad, ScribbleHub)
- Filter by tag
- Filter by search query (title, author)
- Combine multiple filters (source + tag)
- Empty filter results
- Filter with no matching items

#### Sorting
- Sort by title (ascending/descending)
- Sort by author (ascending/descending)
- Sort by download date (ascending/descending)
- Sort by chapter count (ascending/descending)
- Sort by progress (ascending/descending)
- Sort by last update (ascending/descending)
- Handle sorting with empty library
- Handle sorting with duplicate values

#### Display
- 2-column vs 3-column layout
- Empty library state
- Large library performance (100+ stories)

---

## 4. Priority 1 (P1) - High-Priority Testing

### 4.1 TTSStateManager.test.ts

**Location:** `src/services/TTSStateManager.ts` (149 lines)

**Test Scenarios:**
- Singleton initialization
- Delegation to TTSPlaybackController
- State synchronization
- Notification service integration
- Foreground service lifecycle
- Handle controller initialization failures

### 4.2 useTTS.test.ts

**Location:** `src/hooks/useTTS.ts` (165 lines)

**Test Scenarios:**
- TTS state initialization
- Playback state synchronization via EventEmitter
- Notifee foreground event handling
- Settings management (voice, speed, pitch)
- Background/foreground transitions
- Handle notification service errors

### 4.3 BackupService.test.ts

**Location:** `src/services/BackupService.ts` (160 lines)

**Test Scenarios:**
- Export library to JSON
- Import library from JSON
- Handle large files (>50MB limit)
- Data merging (import into existing library)
- Handle invalid JSON
- Handle corrupted data
- Handle version mismatches
- Document picker integration

### 4.4 storyValidation.test.ts

**Location:** `src/utils/storyValidation.ts` (27 lines)

**Test Scenarios:**
- Validate story object (required fields)
- Validate chapter object
- Validate download range (indices)
- Check if story has updates
- Edge cases (null, undefined, empty strings)

### 4.5 NotificationService.test.ts

**Location:** `src/services/NotificationService.ts` (157 lines)

**Test Scenarios:**
- Create notification channel
- Show progress notification
- Update progress notification
- Show completion notification
- Handle permission denied
- Handle channel creation failure
- Platform-specific behavior (Android vs web)

### 4.6 useAddStory.test.ts

**Location:** `src/hooks/useAddStory.ts` (103 lines)

**Test Scenarios:**
- Parse URL to extract story ID
- Select correct provider
- Fetch metadata from source
- Parse chapter list
- Create story in library
- Handle invalid URLs
- Handle unsupported sources
- Handle network errors

---

## 5. Priority 2 (P2) - Medium-Priority Testing

### 5.1 stringUtils.test.ts

**Location:** `src/utils/stringUtils.ts` (11 lines)

**Test Scenarios:**
- Remove trailing ellipsis
- Remove trailing dots
- Handle empty string
- Handle string with no dots
- Handle string with only dots
- Handle special characters

### 5.2 useDownloadProgress.test.ts

**Location:** `src/hooks/useDownloadProgress.ts` (79 lines)

**Test Scenarios:**
- Calculate progress percentage
- Handle active download
- Handle paused download
- Handle completed download
- Handle failed download
- Event listener cleanup

### 5.3 useReaderContent.test.ts

**Location:** `src/hooks/useReaderContent.ts` (70 lines)

**Test Scenarios:**
- Load chapter content from file
- Handle file read errors
- Handle missing chapter file
- Mark chapter as read
- Handle invalid chapter ID

### 5.4 TTSQueue.test.ts

**Location:** `src/services/tts/TTSQueue.ts` (73 lines)

**Test Scenarios:**
- Pre-buffer content chunks
- Speak chunks sequentially
- Handle speech callbacks
- Handle buffer underrun
- Clear queue

### 5.5 fileSystem.test.ts

**Location:** `src/services/storage/fileSystem.ts` (126 lines)

**Test Scenarios:**
- Create directory structure
- Write chapter file
- Read chapter file
- Delete story directory
- Check file existence
- SAF integration (Android 11+)
- Handle permission denied
- Handle disk full

### 5.6 fetcher.test.ts

**Location:** `src/services/network/fetcher.ts` (25 lines)

**Test Scenarios:**
- Successful HTTP GET request
- Handle 404 response
- Handle 500 response
- Handle network timeout
- Handle invalid URL
- User-Agent header verification

### 5.7 EpubMetadataGenerator.test.ts

**Location:** `src/services/epub/EpubMetadataGenerator.ts` (87 lines)

**Test Scenarios:**
- Generate OPF XML
- Generate NCX XML
- XML special character escaping
- Sanitize filenames
- Handle missing metadata
- Handle invalid characters in filenames

### 5.8 EpubContentProcessor.test.ts

**Location:** `src/services/epub/EpubContentProcessor.ts` (78 lines)

**Test Scenarios:**
- Generate HTML template
- Sanitize HTML content
- Handle missing chapter content
- Handle invalid HTML
- Read file content

---

## 6. Priority 3 (P3) - Low-Priority Testing

### 6.1 Component Tests

UI components using React Native Testing Library:

**ReaderNavigation** - Navigation buttons and state management
**TTSController** - Playback controls and settings
**StoryCard** - Display story information and actions
**ProgressBar** - Progress bar rendering and animations
**DownloadRangeDialog** - Range selection and validation
**ChapterListItem** - Chapter list item display and interactions

### 6.2 AlertContext.test.ts

**Location:** `src/context/AlertContext.tsx`

**Test Scenarios:**
- Show alert with single button
- Show alert with multiple buttons
- Execute button callback
- Handle dismiss action

### 6.3 useSettings.test.ts

**Location:** `src/hooks/useSettings.ts` (110 lines)

**Test Scenarios:**
- Save settings to storage
- Load settings from storage
- Export backup
- Import backup
- Clear all data
- Handle invalid settings

---

## 7. Integration Testing Opportunities

### 7.1 Download Flow Integration

**Full Flow Test:**
1. Add story with URL
2. Provider extracts metadata
3. DownloadManager queues jobs
4. Worker downloads chapters
5. StorageService saves files
6. Progress notifications displayed
7. Download completes
8. Story appears in library

**Test Scenarios:**
- Successful download flow
- Partial download (network failure mid-flow)
- Resume after app restart
- Concurrent downloads from multiple stories

---

### 7.2 TTS Flow Integration

**Full Flow Test:**
1. Load story content
2. Initialize TTS
3. Start playback
4. Navigate to next chapter
5. Pause and resume
6. Stop playback
7. Foreground service lifecycle

**Test Scenarios:**
- Playback across chapters
- Background playback with notification
- Handling interruptions (calls, other apps)
- Settings changes during playback

---

### 7.3 EPUB Generation Integration

**Full Flow Test:**
1. Download all chapters
2. Generate EPUB metadata
3. Process chapter content
4. Create EPUB structure
5. Zip files together
6. Save to storage

**Test Scenarios:**
- Generate EPUB for complete story
- Generate EPUB for chapter range
- Generate EPUB with missing chapters
- Handle large stories (500+ chapters)

---

### 7.4 Backup/Restore Integration

**Full Flow Test:**
1. Library with multiple stories
2. Export to JSON file
3. Clear library
4. Import from JSON file
5. Verify all stories restored

**Test Scenarios:**
- Backup empty library
- Backup library with 100+ stories
- Import into existing library (merge)
- Import invalid/corrupted backup

---

## 8. Error Injection Testing

Critical error scenarios that need explicit testing:

### 8.1 Network Errors
- Timeout during chapter download
- 403 Forbidden (Cloudflare challenge)
- 500 Server Error
- Network disconnection mid-download
- Slow network responses

### 8.2 Storage Errors
- AsyncStorage quota exceeded
- Disk full during file write
- Permission denied (Android 11+ SAF)
- Corrupted file data on read
- File lock contention

### 8.3 Data Corruption
- Invalid JSON in AsyncStorage
- Corrupted queue data
- Invalid chapter content (HTML, encoding issues)
- Metadata inconsistencies

### 8.4 Native Module Failures
- expo-speech unavailable
- Notifee service crashes
- File system API failures
- Background service termination

---

## 9. Testing Infrastructure Recommendations

### 9.1 Mock Setup

Create comprehensive mocks in `jest-setup.ts`:

```typescript
// Native modules to mock
- expo-file-system
- expo-speech
- @notifee/react-native
- @react-native-async-storage/async-storage
- expo-clipboard
- expo-document-picker
- expo-sharing
```

### 9.2 Test Utilities

Create reusable test utilities:
- Mock providers (RoyalRoad, ScribbleHub)
- Mock story data factory
- Mock chapter data factory
- Mock HTML responses
- Error simulation helpers

### 9.3 Test Organization

Structure:
```
src/
  components/
    __tests__/
      StoryCard.test.tsx
  hooks/
    __tests__/
      useLibrary.test.ts
  services/
    __tests__/
      download/
        DownloadManager.test.ts
      tts/
        TTSPlaybackController.test.ts
  utils/
    __tests__/
      stringUtils.test.ts
```

### 9.4 Property-Based Testing

For pure utility functions:
- Use `fast-check` library for stringUtils
- Test edge cases with generated inputs
- Verify invariant properties

---

## 10. Risk Matrix

| Area | Risk Level | Impact | Mitigation |
|------|------------|--------|------------|
| Download queue corruption | High | Data loss | P0: DownloadQueue.test.ts |
| Backup/restore failures | High | Data loss | P1: BackupService.test.ts |
| TTS state desync | Medium | UX issues | P0: TTSPlaybackController.test.ts |
| Storage permission errors | High | App unusable | P2: fileSystem.test.ts |
| EPUB generation errors | Medium | Feature unusable | P2: EpubMetadataGenerator.test.ts |
| Update detection bugs | Medium | Missed chapters | P0: useStoryDownload.test.ts |
| Sorting/filtering bugs | Low | UX issues | P0: useLibrary.test.ts |
| Native module crashes | High | App crashes | Integration tests |

---

## 11. Implementation Roadmap

### Phase 1: Critical Services (Week 1-2)
1. DownloadManager.test.ts
2. DownloadQueue.test.ts
3. DownloadService.test.ts
4. TTSPlaybackController.test.ts

### Phase 2: Critical Hooks (Week 3)
5. useStoryDownload.test.ts
6. useLibrary.test.ts

### Phase 3: High-Priority Services (Week 4)
7. TTSStateManager.test.ts
8. useTTS.test.ts
9. BackupService.test.ts
10. storyValidation.test.ts
11. NotificationService.test.ts
12. useAddStory.test.ts

### Phase 4: Supporting Logic (Week 5)
13. stringUtils.test.ts
14. useDownloadProgress.test.ts
15. useReaderContent.test.ts
16. TTSQueue.test.ts
17. fileSystem.test.ts
18. fetcher.test.ts
19. EpubMetadataGenerator.test.ts
20. EpubContentProcessor.test.ts

### Phase 5: Integration & Error Injection (Week 6)
21. Download flow integration tests
22. TTS flow integration tests
23. EPUB generation integration tests
24. Backup/restore integration tests
25. Error injection tests

---

## 12. Summary Statistics

| Category | Files | Tested | Coverage |
|----------|-------|--------|----------|
| Components | 18 | 0 | 0% |
| Hooks | 13 | 0 | 0% |
| Services | 24 | 4 | 17% |
| Utils | 3 | 1 | 33% |
| Context | 1 | 0 | 0% |
| **Total** | **~70** | **5** | **<10%** |

**Estimated Test Files to Add:** 25-30
**Estimated Total Test Lines:** 2,500-3,500
**Target Coverage:** 80%+ for critical paths
