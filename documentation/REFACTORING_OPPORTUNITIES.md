# Refactoring Opportunities

This document identifies files in the native Kotlin codebase that are good candidates for refactoring due to their size, complexity, or multiple responsibilities.

> **Note:** The original version of this document tracked refactoring of the React Native codebase. Those refactorings were completed as part of the RN implementation. This document now reflects the current native Kotlin codebase.

## Priority 1: High Impact Refactoring Candidates

### 1. `MainActivity.kt` (~1400 lines)
**Issues:**
- Single file contains the entire UI: library, story details, add story, browser, reader, queue, and settings screens.
- All screen navigation handled by inline methods.
- Engine initialization, coroutine management, and UI rendering mixed together.
- Large `when` expressions for screen switching.

**Suggested Refactoring:**
- Extract each screen into a separate `Fragment` or dedicated `Activity`.
- Create a `Screen` sealed class for type-safe navigation.
- Extract engine initialization into a `ViewModel` or `Application`-level singleton.
- Move WebView management into a dedicated `BrowserController` class.

**Potential Modules:**
- `ui/LibraryScreen.kt` — Library list view
- `ui/StoryDetailsScreen.kt` — Story details and actions
- `ui/BrowserScreen.kt` — WebView browser
- `ui/ReaderScreen.kt` — Chapter reader
- `ui/QueueScreen.kt` — Download queue management
- `ui/SettingsScreen.kt` — Settings UI
- `ui/NavigationController.kt` — Screen navigation

---

### 2. `Engines.kt` (~1086 lines)
**Issues:**
- Contains 4 major engine classes (`StorySyncEngine`, `DownloadEngine`, `EpubEngine`, `TtsEngine`) plus `DownloadScheduler` and `DownloadErrorClassifier`.
- Mixes network I/O, file I/O, and business logic.
- Each engine is tightly coupled with `AppStorage`.

**Suggested Refactoring:**
- Split each engine into its own file.
- Extract shared interfaces for storage access.
- Separate engine coordination from engine implementation.
- Consider a `EngineFactory` or dependency injection.

**Potential Modules:**
- `engine/StorySyncEngine.kt` — Isolated sync engine
- `engine/DownloadEngine.kt` — Isolated download engine
- `engine/EpubEngine.kt` — Isolated EPUB engine
- `engine/TtsEngine.kt` — Isolated TTS engine
- `engine/DownloadScheduler.kt` — Job scheduling
- `engine/DownloadErrorClassifier.kt` — Error classification

---

## Priority 2: Medium Impact Refactoring Candidates

### 3. `Storage.kt`
**Issues:**
- Single `AppStorage` class manages all storage domains (library, settings, queue, TTS session, tabs, cleanup rules, backups).
- Gson serialization concerns mixed with business logic.
- No separation between storage domains.

**Suggested Refactoring:**
- Create domain-specific storage classes: `LibraryStorage`, `SettingsStorage`, `QueueStorage`, `TtsSessionStorage`.
- Extract common serialization into a `JsonStorage` base class.
- Keep `AppStorage` as a facade composing the domain storages.

---

### 4. `Sources.kt` (~236 lines)
**Issues:**
- Contains `SourceProvider` interface, `SourceRegistry`, `RoyalRoadProvider`, `ScribbleHubProvider`, `NetworkClient`, and `NetworkRequests` all in one file.
- Network request building mixed with HTML parsing logic.

**Suggested Refactoring:**
- Move each provider to its own file.
- Extract `NetworkClient` and `NetworkRequests` into a separate `network/` package.
- Keep `Sources.kt` for the interface and registry only.

---

### 5. `Foreground Service Duplication`
**Issues:**
- `DownloadForegroundService.kt` and `TtsForegroundService.kt` both independently instantiate their own `AppStorage`, `NetworkClient`, and engines.
- Similar notification management patterns duplicated between services.

**Suggested Refactoring:**
- Extract shared notification builder utility.
- Use `Application`-level singletons for `AppStorage` and `NetworkClient` instead of per-service instantiation.
- Create a base `BaseForegroundService` class with common notification/channel logic.

---

## General Refactoring Principles

1. **Planning + Engine Pattern**: Maintain the separation between pure planning functions and stateful engines. New logic should go into planning functions when possible.
2. **One Class Per File**: Kotlin convention — split large multi-class files into individual files.
3. **Single Responsibility**: Each class/file should have one well-defined purpose.
4. **Testability**: Planning functions remain pure and trivially testable. Engines depend on injectable storage/network interfaces.
5. **Minimal Dependencies**: The project intentionally has few dependencies. Avoid adding libraries unless they provide significant value.

## Refactoring Priority Matrix

| File | Lines | Complexity | Impact | Priority |
|------|-------|------------|--------|----------|
| `MainActivity.kt` | ~1400 | High | High | 1 |
| `Engines.kt` | ~1086 | High | High | 1 |
| `Storage.kt` | — | Medium | Medium | 2 |
| `Sources.kt` | ~236 | Low | Medium | 2 |
| Foreground services | — | Low | Low | 2 |

## Legacy React Native Refactoring (Completed)

The following React Native refactorings were completed during the RN era and are documented here for historical reference:

| File | Lines | Status |
|------|-------|--------|
| `app/reader/[storyId]/[chapterId].tsx` | 201 | ✅ Completed (RN) |
| `src/hooks/useStoryDetails.ts` | 102 | ✅ Completed (RN) |
| `src/services/TTSStateManager.ts` | 147 | ✅ Completed (RN) |
| `src/services/EpubGenerator.ts` | 48 | ✅ Completed (RN) |
