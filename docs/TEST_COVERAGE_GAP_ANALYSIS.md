# Test Coverage Gap Analysis

## Executive Summary

The native Kotlin implementation has **36 unit test files** with 1:1 coverage of all planning modules and core utilities. Tests use JUnit 4 and run via `./gradlew :app:testDebugUnitTest`. This document tracks the current test status and remaining gaps.

## 1. Current Test Coverage Status

### A. Core Planning Modules — **100% Coverage**
Every planning module has a dedicated test file:

| Test File | Module Tested | Status |
|-----------|--------------|--------|
| `TextCleanupTest` | Text cleanup, regex cleanup, script removal, TTS cleanup, chunk splitting, reader text extraction | ✅ |
| `SentenceRemovalPlanningTest` | Sentence trimming, blank/duplicate rejection, insertion order, editing, deletion | ✅ |
| `DefaultCleanupTest` | Default sentence removal list validation | ✅ |
| `ReaderContentRendererTest` | Reader HTML wrapping, viewport, font sizing, image styling, fallback messages | ✅ |
| `SourceProviderTest` | Royal Road + Scribble Hub metadata, chapter list, chapter ID, chapter body parsing, author fallbacks, chapter title cleanup | ✅ |
| `SourceUrlValidationTest` | Story URL validation, chapter/non-story URL rejection | ✅ |
| `BrowserUrlPlanningTest` | URL resolution, Google search fallback, Google auth external routing | ✅ |
| `TabPlanningTest` | Tab creation, rename validation, delete reordering, move ordering | ✅ |
| `NetworkRequestsTest` | Fetch/form request headers, Scribble Hub AJAX form body | ✅ |
| `ArchiveUtilsTest` | ZIP traversal protection, stored EPUB mimetype entry | ✅ |
| `ArchiveSnapshotPlanningTest` | Archive snapshots, file path copying, EPUB/pending-new cleanup | ✅ |
| `EpubSelectionTest` | EPUB range selection, downloaded-only inclusion, start-after-bookmark, selector labels | ✅ |
| `EpubFilenameTest` | Safe bounded filenames, chapter range suffix generation | ✅ |
| `DownloadSchedulerTest` | Global/per-source concurrency, per-source delay scheduling | ✅ |
| `DownloadQueuePlanningTest` | Chapter queueing, terminal duplicate reset, active duplicate suppression | ✅ |
| `DownloadQueueControlPlanningTest` | Pause/resume/cancel/retry status transitions | ✅ |
| `DownloadQueueMaintenanceTest` | Unsupported-source cleanup, stuck story status recovery | ✅ |
| `DownloadErrorClassifierTest` | Error classification, retry-delay policy | ✅ |
| `ChapterSelectionPlanningTest` | Inclusive forward/reverse range selection and drag-to-clear behavior | ✅ |
| `StorySyncPlanningTest` | Chapter merge, slug changes, bookmark remapping, pending-new tracking, EPUB stale detection | ✅ |
| `StoryActionGuardsTest` | Archived snapshot action guards | ✅ |
| `BackupMergePlanningTest` | JSON backup import merge, local download preservation, stale state scrubbing | ✅ |
| `BackupExportPlanningTest` | Empty-library guards, JSON 50 MB size limit | ✅ |
| `JsonBackupValidationTest` | JSON backup version, library, and story-ID validation | ✅ |
| `FullBackupPathsTest` | Full-backup chapter path encoding, filename generation | ✅ |
| `FullBackupManifestValidationTest` | Manifest validation errors, missing-manifest messaging | ✅ |
| `FullBackupRestorePlanningTest` | Metadata scrubbing, chapter file re-linking, downloaded count recomputation | ✅ |
| `EpubMetadataTest` | OPF description/subject/guide, cover manifest, NCX doctype, dtb metadata, navigation | ✅ |
| `EpubContentTest` | Cover/details XHTML, escaped tags/descriptions, CSS, chapter content sanitization | ✅ |
| `StoryBookmarkPlanningTest` | Bookmark toggling, EPUB range advancement | ✅ |
| `LibraryQueryTest` | Source-name filtering, source+tag intersections, sorting | ✅ |
| `TtsSessionPlanningTest` | TTS resume eligibility, chunk size restore, chunk bounds, next-chapter continuation | ✅ |
| `TtsNotificationActionsTest` | Foreground notification pause/play actions | ✅ |
| `SettingsValidationTest` | Download concurrency/delay, EPUB size, TTS pitch/rate/chunk size bounds | ✅ |
| `PreferenceNormalizationTest` | Invalid/out-of-range/legacy preference normalization | ✅ |
| `FileMimeTypesTest` | MIME types for JSON, ZIP, EPUB, fallback | ✅ |
| `AndroidManifestIntegrationTest` | Package-visibility queries, FileProvider coverage | ✅ |

### B. Engine Classes — **Partial Coverage**
The stateful engine classes (`StorySyncEngine`, `DownloadEngine`, `EpubEngine`, `TtsEngine`) in `Engines.kt` are partially covered through planning function tests, but lack dedicated engine-level integration tests.

### C. UI / Activity — **No Coverage**
`MainActivity.kt` (~1400 lines) has no tests. All UI is programmatic and would require instrumented tests or Robolectric.

### D. Foreground Services — **No Coverage**
`DownloadForegroundService.kt` and `TtsForegroundService.kt` have no tests. These depend on Android framework components.

---

## 2. Remaining Gaps

### High Priority
1. **Engine integration tests** — Test `DownloadEngine`, `EpubEngine`, `TtsEngine`, and `StorySyncEngine` as integrated units with mock storage/network.
2. **Storage round-trip tests** — Test `AppStorage` read/write cycles, backup export/import, and data migration.

### Medium Priority
3. **Android instrumented tests** — Add `androidTest/` tests for `MainActivity` screen flows (library, download, reader, settings).
4. **Service lifecycle tests** — Test foreground service startup, notification updates, and job recovery.

### Low Priority
5. **End-to-end tests** — Full flow: add story → download chapters → generate EPUB → share EPUB.
6. **Performance tests** — Large library (100+ stories), long chapter lists (1000+ chapters), large EPUB generation.

---

## 3. Recommended Action Plan

### Phase 1: Engine Coverage (Recommended Next)
- Create `DownloadEngineTest.kt` with mock storage and network.
- Create `EpubEngineTest.kt` verifying end-to-end EPUB generation.
- Create `TtsEngineTest.kt` with mock Android TTS.
- Create `StorySyncEngineTest.kt` with mock sources.
- Create `AppStorageTest.kt` for storage round-trips.

### Phase 2: Instrumented Tests
- Add Robolectric or `androidTest/` for `MainActivity` screen flows.
- Test foreground service lifecycle with `androidTest/`.

### Phase 3: Integration Tests
- End-to-end flow tests covering the full user journey.
- Performance benchmarks for large libraries and long downloads.

---

## Legacy React Native Test Status

The React Native codebase had extensive Jest test coverage (~80% hooks, ~90% services, ~15% UI components). Those tests remain in the repository but apply only to the legacy `app/` and `src/` directories. See version control history for the previous version of this document.
