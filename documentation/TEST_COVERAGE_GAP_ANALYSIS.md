# Test Coverage Gap Analysis

## Executive Summary
This document outlines the current state of test coverage in the Webnovel Archiver project. **Phase 1 of the action plan has been completed**, with critical services and the core download hook now tested. Remaining gaps exist in UI components, remaining custom hooks, and TTS-related functionality.

## 1. Current Test Coverage Status

### A. Custom Hooks (`src/hooks`) - **~10% Coverage** (IN PROGRESS)
The application relies heavily on custom hooks for logic separation. Progress has been made on the most critical hook.

**Completed:**
- ✓ **`useStoryDownload.test.ts`**: Tests the core download feature including download loop, error handling, and queue management.

**Remaining Critical Hooks:**
- **`useLibrary.ts`**: Handles the user's collection, updates, and persistence states.
- **`useTTS.ts`**: Complex logic for Text-to-Speech state and control.
- **`useStoryEPUB.ts`**: Logic for generating EPUBs; errors here fail the export feature.
- **`useSettings.ts`**: Manages app-wide configurations.
- **`useStoryDetails.ts`**: Fetches and manages story metadata.
- **`useStoryActions.ts`**: Handles story actions (delete, update, etc.).

### B. Core Services (`src/services`) - **~60% Coverage**
Most critical services now have test coverage.

**Completed:**
- ✓ **`BackupService.test.ts`**: **CRITICAL**. Tests for data import/export integrity.
- ✓ **`BackgroundService.test.ts`**: Tests for background task management.
- ✓ **`DownloadService.test.ts`**: Tests for download queue and progress tracking.
- ✓ **`StorageService.test.ts`**: Tests for file system operations.
- ✓ **`EpubGenerator.test.ts`**: Tests for EPUB generation.
- ✓ **`RoyalRoadProvider.test.ts`**: Tests for RoyalRoad novel source parsing.
- ✓ **`ScribbleHubProvider.test.ts`**: Tests for ScribbleHub novel source parsing.

**Remaining Services:**
- **`NotificationService.ts`**: User feedback mechanisms.
- **`TTSNotificationService.ts`**: TTS-specific notifications.
- **`TTSStateManager.ts`**: Manages the complex state of the TTS player.
- **`TTSQueue.ts`**: TTS playback queue management.
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
