# Test Coverage Gap Analysis

## Executive Summary
This document outlines the current state of test coverage in the Webnovel Archiver project. While core services like `DownloadService`, `StorageService`, and `EpubGenerator` have existing tests, there are significant gaps in critical areas, particularly in UI components, custom hooks, and backup/restore functionality.

## 1. Critical High-Priority Gaps

### A. Custom Hooks (`src/hooks`) - **0% Coverage**
The application relies heavily on custom hooks for logic separation, but **none** of them are currently tested. The directory `src/hooks/__tests__` exists but is empty.

**Critical Hooks to Test:**
- **`useStoryDownload.ts`**: Manages the core feature of the app. Bugs here directly impact the user's ability to archive novels.
- **`useLibrary.ts`**: Handles the user's collection, updates, and persistence states.
- **`useTTS.ts`**: Complex logic for Text-to-Speech state and control.
- **`useStoryEPUB.ts`**: Logic for generating EPUBs; errors here fail the export feature.
- **`useSettings.ts`**: Manages app-wide configurations.

### B. Core Services (`src/services`)
While some services are tested, several critical background and integrity services are missing coverage:

- **`BackupService.ts`**: **CRITICAL**. Handles data import/export. Failure here leads to data loss.
- **`BackgroundService.ts`**: manages background tasks. These are notoriously hard to debug and should be well-tested.
- **`NotificationService.ts` / `TTSNotificationService.ts`**: User feedback mechanisms.
- **`TTSStateManager.ts`**: Manages the complex state of the TTS player.

### C. UI Components (`src/components`) - **0% Coverage**
No component tests exist. Logic embedded in components (e.g., `TTSController.tsx`, `ReaderContent.tsx`) is currently unverified by automated tests.

## 2. Existing Coverage (Maintain & Expand)

The following areas have baseline tests but should be expanded:
- **`src/services/DownloadService.ts`**: Has tests. Ensure edge cases (retries, partial failures) are covered.
- **`src/services/StorageService.ts`**: Has tests. Verify migration paths and large data handling.
- **`src/services/EpubGenerator.ts`**: Has tests.
- **`src/utils/htmlUtils.ts`**: Has tests.

## 3. Recommended Action Plan

### Phase 1: Core Logic Stability (Immediate)
Focus on unit testing the pure logic (hooks and services) that drives the app features.
1.  **Implement tests for `BackupService`**. This is the highest risk area for data integrity.
2.  **Mock and test `useStoryDownload`**. Ensure the download loop, error handling, and queue management work correctly.
3.  **Test `BackgroundService`**.

### Phase 2: Feature Stability (Short-term)
1.  Add tests for `useLibrary` and `useSettings` to ensure state consistency.
2.  Add tests for `TTSStateManager` and `useTTS`.

### Phase 3: UI & Integration (Medium-term)
1.  Add component testing (using React Native Testing Library) for `ReaderContent` and `StoryCard`.
2.  Create integration tests for the main flows (Add Novel -> Download -> Read).
