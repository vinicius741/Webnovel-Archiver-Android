# Test Coverage Gap Analysis

## Executive Summary
This document outlines the current state of test coverage in the Webnovel Archiver project. **Phase 1, Phase 2, and Phase 3 (partial) of the action plan have been completed**, with critical services, most custom hooks, core functionality, and initial UI component testing now in place. Remaining gaps exist in notification services, useStoryEPUB hook, some reader hooks, and comprehensive UI component testing.

## 1. Current Test Coverage Status

### A. Custom Hooks (`src/hooks`) - **~80% Coverage**
The application relies heavily on custom hooks for logic separation. Most critical hooks now have comprehensive tests.

**Completed:**
- ✓ **`useStoryDownload.test.ts`**: Tests the core download feature including download loop, error handling, and queue management.
- ✓ **`useLibrary.test.ts`**: Tests for library collection management, updates, and persistence states.
- ✓ **`useTTS.test.ts`**: Tests for Text-to-Speech state and control logic.
- ✓ **`useSettings.test.ts`**: Tests for app-wide configuration management.
- ✓ **`useStoryDetails.test.ts`**: Tests for story metadata fetching and management.
- ✓ **`useStoryActions.test.ts`**: Tests for story actions (delete, update, etc.).

**Remaining Hooks:**
- **`useStoryEPUB.ts`**: Logic for generating EPUBs; errors here fail the export feature.
- **`useDownloadProgress.ts`**: Download progress tracking.
- **`useReaderContent.ts`**: Reader content loading and management.
- **`useReaderNavigation.ts`**: Reader navigation controls.
- **`useScreenLayout.ts`**: Screen layout calculations.
- **`useAddStory.ts`**: Story addition flow.
- **`useWebViewHighlight.ts`**: WebView highlighting functionality.

### B. Core Services (`src/services`) - **~90% Coverage**
Most critical services now have test coverage.

**Completed:**
- ✓ **`BackupService.test.ts`**: **CRITICAL**. Tests for data import/export integrity.
- ✓ **`BackgroundService.test.ts`**: Tests for background task management.
- ✓ **`DownloadService.test.ts`**: Tests for download queue and progress tracking.
- ✓ **`StorageService.test.ts`**: Tests for file system operations.
- ✓ **`EpubGenerator.test.ts`**: Tests for EPUB generation.
- ✓ **`RoyalRoadProvider.test.ts`**: Tests for RoyalRoad novel source parsing.
- ✓ **`TTSStateManager.test.ts`**: Tests for TTS state management.
- ✓ **`tts/TTSQueue.test.ts`**: Tests for TTS playback queue management.
- ✓ **`tts/TTSPlaybackController.test.ts`**: Tests for TTS playback control logic.
- ✓ **`DownloadManager.test.ts`**: Tests for download management logic.
- ✓ **`DownloadQueue.test.ts`**: Tests for download queue implementation.
- ✓ **`EpubContentProcessor.test.ts`**: Tests for EPUB content processing.
- ✓ **`EpubFileSystem.test.ts`**: Tests for EPUB file system operations.
- ✓ **`EpubMetadataGenerator.test.ts`**: Tests for EPUB metadata generation.
- ✓ **`network/fetcher.test.ts`**: Tests for HTTP client fetching content.

**Remaining Services:**
- **`NotificationService.ts`**: User feedback mechanisms.
- **`TTSNotificationService.ts`**: TTS-specific notifications.

### C. UI Components (`src/components`) - **~15% Coverage**
Initial component testing infrastructure has been established.

**Completed:**
- ✓ **`StoryCard.test.tsx`**: Tests for story card rendering, optional props (cover image, source, score, progress), and user interactions.
- ✓ **`ReaderContent.test.tsx`**: Tests for WebView HTML generation with CSS styling, content processing, empty content handling, and large content support.

**Remaining Components:**
- **`TTSController.tsx`**: Complex TTS playback UI with controls.
- **`TTSSettingsModal.tsx`**: TTS settings configuration.
- **`ProgressBar.tsx`**: Progress bar component.
- **`ScreenContainer.tsx`**: Screen layout container.
- **`details/`**: All detail page components (ChapterListItem, DownloadRangeDialog, StoryActions, StoryDescription, StoryHeader, StoryMenu, StoryTags).
- **`ImageViewer.tsx`**: Image viewing component.
- **`HeadlessWebView.tsx`**: WebView component without UI.
- **`SortButton.tsx`**: Sorting button component.

### D. Utils (`src/utils`) - **~100% Coverage**
All utility functions now have comprehensive tests.

**Completed:**
- ✓ **`htmlUtils.test.ts`**: Tests for HTML parsing utilities.
- ✓ **`storyValidation.test.ts`**: Tests for story/chapter validation, download range validation, and update checking.
- ✓ **`stringUtils.test.ts`**: Tests for title sanitization including ellipsis removal and whitespace handling.

## 2. Existing Coverage (Maintain & Expand)

The following areas have baseline tests but should be expanded:
- **`src/services/DownloadService.test.ts`**: Add edge cases (retries, partial failures, network timeouts).
- **`src/services/StorageService.test.ts`**: Verify migration paths and large data handling.
- **`src/services/EpubGenerator.test.ts`**: Test with different content types and edge cases.
- **`src/hooks/__tests__/useStoryDownload.test.ts`**: Expand error scenarios and concurrent downloads.
- **`src/services/source/providers/__tests__/`**: Add more provider tests and edge cases (malformed HTML, missing content).
- **`src/components/__tests__/`**: Expand UI component tests for more scenarios and edge cases.

## 3. Recommended Action Plan

### ✅ Phase 1: Core Logic Stability (COMPLETED)
- ✓ **Implemented tests for `BackupService`**
- ✓ **Mock and test `useStoryDownload`**
- ✓ **Test `BackgroundService`**
- ✓ **Added tests for novel source providers**

### ✅ Phase 2: Feature Stability (COMPLETED)
- ✓ **Added tests for `useLibrary` and `useSettings`** to ensure state consistency
- ✓ **Added tests for `TTSStateManager` and `useTTS`**
- ✓ **Tested remaining TTS services** (`TTSQueue`, `TTSPlaybackController`)
- ✓ **Added tests for `useStoryActions` and `useStoryDetails`**
- ✓ **Added tests for EPUB services** (`EpubContentProcessor`, `EpubFileSystem`, `EpubMetadataGenerator`)
- ✓ **Added tests for download services** (`DownloadManager`, `DownloadQueue`)
- ✓ **Added tests for network utilities** (`network/fetcher`)
- ✓ **Added tests for utility functions** (`storyValidation`, `stringUtils`)

### Phase 3: UI & Integration (IN PROGRESS)
1. ✅ **Added component testing infrastructure** for `ReaderContent` and `StoryCard`
2. **Add component tests for TTS-related components** (`TTSController`, `TTSSettingsModal`)
3. **Add component tests for details page components** (`StoryActions`, `StoryMenu`, `ChapterListItem`, etc.)
4. **Add component tests for remaining UI components** (`ProgressBar`, `ScreenContainer`, `SortButton`, `ImageViewer`)
5. **Create integration tests for the main flows** (Add Novel -> Download -> Read)

### Phase 4: Coverage Completion (Future)
1. **Add tests for `useStoryEPUB` hook** - EPUB generation logic
2. **Add tests for remaining reader hooks** (`useReaderContent`, `useReaderNavigation`, `useScreenLayout`, `useDownloadProgress`)
3. **Add tests for `useAddStory` hook** - Story addition flow
4. **Add tests for `useWebViewHighlight` hook** - WebView highlighting
5. **Add tests for `NotificationService.ts`** - User feedback mechanisms
6. **Add tests for `TTSNotificationService.ts`** - TTS-specific notifications
