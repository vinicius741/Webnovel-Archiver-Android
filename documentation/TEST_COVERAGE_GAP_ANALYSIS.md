# Test Coverage Gap Analysis

## Executive Summary
This document outlines the current state of test coverage in the Webnovel Archiver project. **Phases 1 and 2 of the action plan have been completed**, with critical services, all custom hooks, and most core functionality now tested. Remaining gaps exist in notification services, useStoryEPUB hook, and comprehensive UI component testing.

## 1. Current Test Coverage Status

### A. Custom Hooks (`src/hooks`) - **~85% Coverage** (COMPLETED)
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

### B. Core Services (`src/services`) - **~80% Coverage**
Most critical services now have test coverage.

**Completed:**
- ✓ **`BackupService.test.ts`**: **CRITICAL**. Tests for data import/export integrity.
- ✓ **`BackgroundService.test.ts`**: Tests for background task management.
- ✓ **`DownloadService.test.ts`**: Tests for download queue and progress tracking.
- ✓ **`StorageService.test.ts`**: Tests for file system operations.
- ✓ **`EpubGenerator.test.ts`**: Tests for EPUB generation.
- ✓ **`RoyalRoadProvider.test.ts`**: Tests for RoyalRoad novel source parsing.
- ✓ **`ScribbleHubProvider.test.ts`**: Tests for ScribbleHub novel source parsing.
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


- **`TTSPlaybackController.ts`**: TTS playback control logic.
- **`DownloadManager.ts`**: Download management logic.
- **`DownloadQueue.ts`**: Download queue implementation.
- **EpubContentProcessor.ts`**: EPUB content processing.
- **EpubFileSystem.ts`**: EPUB file system operations.
- **EpubMetadataGenerator.ts`**: EPUB metadata generation.
- **Network/fetcher.ts`**: HTTP client for fetching content.

### C. UI Components (`src/components`) - **0% Coverage**
No component tests exist. Logic embedded in components (e.g., `TTSController.tsx`, `ReaderContent.tsx`) is currently unverified by automated tests.

### D. Utils (`src/utils`) - **~50% Coverage**
- ✓ **`htmlUtils.test.ts`**: Tests for HTML parsing utilities.
- **`storyValidation.ts`**: No tests.
- **`stringUtils.ts`**: No tests.

## 2. Existing Coverage (Maintain & Expand)

The following areas have baseline tests but should be expanded:
- **`src/services/DownloadService.test.ts`**: Add edge cases (retries, partial failures, network timeouts).
- **`src/services/StorageService.test.ts`**: Verify migration paths and large data handling.
- **`src/services/EpubGenerator.test.ts`**: Test with different content types and edge cases.
- **`src/hooks/__tests__/useStoryDownload.test.ts`**: Expand error scenarios and concurrent downloads.
- **`src/services/source/providers/__tests__/`**: Add more provider tests and edge cases (malformed HTML, missing content).

## 3. Recommended Action Plan

### ✅ Phase 1: Core Logic Stability (COMPLETED)
- ✓ **Implemented tests for `BackupService`**
- ✓ **Mock and test `useStoryDownload`**
- ✓ **Test `BackgroundService`**
- ✓ **Added tests for novel source providers**

### Phase 2: Feature Stability (Short-term)
1. Add tests for `useLibrary` and `useSettings` to ensure state consistency.
2. Add tests for `TTSStateManager` and `useTTS`.
3. Test remaining TTS services (`TTSQueue`, `TTSPlaybackController`).

### Phase 3: UI & Integration (Medium-term)
1. Add component testing (using React Native Testing Library) for `ReaderContent` and `StoryCard`.
2. Create integration tests for the main flows (Add Novel -> Download -> Read).
3. Test utility functions (`storyValidation`, `stringUtils`).
