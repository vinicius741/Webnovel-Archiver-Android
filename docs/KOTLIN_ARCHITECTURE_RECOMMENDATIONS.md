# Kotlin Architecture Recommendations

This audit covers the active native Kotlin app under `android/app/src/main/java/com/vinicius741/webnovelarchiver`. The legacy React Native folders were treated as reference only.

The current implementation has several good foundations: source-specific parsing is isolated behind `SourceProvider`, a lot of business logic has already been extracted into pure planning modules, and there are many JVM tests around queue planning, EPUB content, backup planning, URL handling, and text cleanup. The biggest remaining risks are not "Kotlin syntax" problems. They are Android lifecycle, storage durability, concurrent state mutation, long-list performance, and maintainability of large programmatic View screens.

> **Implementation status:** Recommendations are being implemented on branch
> `refactor/architecture-recommendations`. Each item below is prefixed with a status tag:
> `[DONE]`, `[IN PROGRESS]`, or `[TODO]`. Status is updated as work lands.


| Priority | Area | Recommendation | Why it matters |
| --- | --- | --- | --- |
| P0 | Storage durability | Replace direct JSON writes with atomic writes and transactional restore. | Prevents corrupted library/settings/queue files after crashes, power loss, or failed restore. |
| P0 | Download ownership | Make one component own the download queue and engine at runtime. | Avoids the activity and foreground service racing against the same JSON queue. |
| P0 | Lifecycle cleanup | Tie coroutines, WebViews, TTS, and workers to lifecycle-aware scopes and explicit shutdown. | Prevents leaked work, duplicate callbacks, and stale UI updates after navigation/destroy. |
| P1 | Long-list UI | Move Library, Details chapters, Queue, and Cleanup lists to `RecyclerView`. | Avoids inflating hundreds/thousands of views and enables partial updates. |
| P1 | Main-thread I/O | Put storage behind a repository with cached `StateFlow` and background disk access. | Keeps screens responsive and avoids repeated full-library JSON reads on render. |
| P1 | Typed state | Replace string statuses and mutable model internals with enums/sealed classes and immutable updates. | Removes a large class of invalid state bugs. |
| P2 | Test depth | Add storage, network, lifecycle, and emulator smoke tests beyond pure planning tests. | Catches the failures most likely to lose data or break downloads. |
| P2 | Tooling | Add ktlint/detekt, CI, MockWebServer, Robolectric or instrumented test coverage. | Keeps quality consistent as the app grows. |

## What Is Already Working Well

1. `core/*Planning.kt` is a good direction. Pure planning functions are easier to test than Android-bound code.
2. `SourceProvider` and `SourceRegistry` are the right shape for adding Royal Road, Scribble Hub, and future sources.
3. Download work runs through a foreground service, which is the correct Android shape for user-visible long-running downloads.
4. ZIP restore already uses a safe extraction target helper, which avoids obvious path traversal bugs.
5. There are 40 JVM test files. That is a much better base than most early native rewrites.

## Reliability

### 1. Make Storage Crash-Safe — `[DONE]`

Current state:

- `AppStorage.write()` writes directly to the final file with `file.writeText(...)`.
- Chapter HTML and EPUB files are also written directly to final paths.
- `importFullBackupUri()` extracts the backup, validates the manifest, then calls `clearAll()` before all restore work is complete.
- Several storage methods are `@Synchronized`, but many getters/setters are not. More importantly, synchronization only protects one `AppStorage` instance. The activity and foreground service each create their own `AppStorage`.

Recommended changes:

1. Use `android.util.AtomicFile` for all JSON files.
2. Write chapters and EPUBs to temporary files, `fsync` where practical, then rename into place.
3. Store a small schema version and app version in every durable JSON document.
4. Make full restore transactional:
   - Extract into cache.
   - Validate manifest, entry count, total uncompressed size, required files, and JSON parseability.
   - Build a complete replacement data directory under a temporary root.
   - Swap roots only after all files are written.
   - Keep the previous root until the new root is verified.
5. Persist chapter paths as relative paths under the app data root instead of absolute filesystem paths.

Why:

The app is local-first. Losing or corrupting local archive data is the worst possible failure mode. Direct JSON writes are vulnerable to partial files if the process dies mid-write. Clearing all data before restore completion creates a one-way failure point.

### 2. Put Storage Behind a Single Repository — `[DONE]`

Current state:

- Screens call `storage.getLibrary()`, `storage.getStory()`, `storage.getQueue()`, and settings reads directly, often during render.
- Engines mutate `Story`, `Chapter`, and `DownloadJob` objects in place.
- Queue updates are read-modify-write operations across JSON files.

Recommended changes:

1. Add an `AppRepository` that owns one `AppStorage` instance per process.
2. Use a `Mutex` or single-threaded dispatcher for all read-modify-write transactions.
3. Expose state as `StateFlow<LibraryState>`, `StateFlow<QueueState>`, and `StateFlow<SettingsState>`.
4. Give storage transactional APIs, for example:
   - `updateStory(storyId) { story -> ... }`
   - `updateQueue { jobs -> ... }`
   - `replaceAllFromBackup(restoredState)`
5. Keep `AppStorage` as low-level disk I/O only.

Why:

The current code has many small operations that are individually simple but collectively race-prone. A repository gives Android screens and services one consistent state surface.

### 3. Make DownloadEngine Single-Owner — `[DONE]`

Current state:

- `MainActivity` creates a `DownloadEngine`.
- `DownloadForegroundService` creates another `DownloadEngine`.
- Both engines can read and write `download_queue.json`.
- `DownloadEngine` has a private `CoroutineScope(SupervisorJob() + Dispatchers.IO)` but no visible `shutdown()`/`cancel()` path.

Recommended changes:

1. The foreground service should be the only active download runner.
2. The activity should enqueue commands and observe queue state, not run its own engine.
3. Add explicit lifecycle methods:
   - `start()`
   - `pause()`
   - `stopAndCancel()`
   - `close()`
4. Cancel worker jobs in `DownloadForegroundService.onDestroy()`.
5. Keep all queue transitions in one reducer/planner, then persist the resulting queue transactionally.

Why:

Two engines writing the same queue file can lose updates. Example: one engine marks a job completed while another engine saves an older queue with that job paused or pending.

### 4. Replace String Queue States With Typed States — `[DONE]`

Current state:

- `DownloadJob.status` is a free-form `String`.
- Valid states are implied by string comparisons: `"pending"`, `"downloading"`, `"completed"`, `"failed"`, `"cancelled"`, `"paused"`.

Recommended changes:

1. Introduce:

```kotlin
enum class DownloadJobStatus {
    Pending,
    Downloading,
    Paused,
    Completed,
    Failed,
    Cancelled,
}
```

2. Use a custom Gson adapter if backward-compatible lowercase JSON names are needed.
3. Centralize transitions so illegal moves are rejected or normalized.

Why:

String states are easy to mistype and hard to exhaustively check. Enums make bad states impossible at compile time.

### 5. Make Models Less Mutable

Current state:

- Most model fields are `var`.
- Lists are usually `MutableList`.
- Engines and screens mutate shared model instances in place.

Recommended changes:

1. Prefer immutable data classes:

```kotlin
data class Story(
    val id: String,
    val title: String,
    val chapters: List<Chapter>,
    ...
)
```

2. Use `copy(...)` and repository update functions for changes.
3. Keep mutable builders only inside narrow planning or storage boundaries.

Why:

Mutable models are convenient early on, but they make it difficult to know who changed state, whether UI is stale, and whether two references point to the same mutable object.

### 6. Fix Network Concurrency and Timeouts — `[DONE]`

Current state:

- `NetworkClient.nextAllowedByHost` is a mutable map with no synchronization.
- Download jobs can run concurrently.
- EPUB cover fetching uses raw `URL.openConnection()` instead of the shared OkHttp client.
- Cover downloads call `readBytes()` without a clear size cap.

Recommended changes:

1. Use a per-host `Mutex` or synchronized rate limiter.
2. Reuse OkHttp for all HTTP, including covers and images.
3. Add per-request cancellation support.
4. Add response size limits for cover images and backup imports.
5. Include source-specific retry policy in the provider or network layer instead of scattered host checks.

Why:

Concurrent downloads can bypass rate limits. Raw URL fetching has weaker defaults and less observability than the app's OkHttp path.

### 7. Harden Backup Import and Export — `[DONE]`

Current state:

- ZIP entries are path-safe, which is good.
- Restore still needs stronger limits and transaction boundaries.
- JSON backups are parsed into generic maps, then converted through Gson.

Recommended changes:

1. Define versioned backup DTOs instead of `Map<String, Any>`.
2. Validate:
   - backup format string
   - version
   - entry count
   - total uncompressed bytes
   - max stories
   - max chapters per story
   - required fields
3. Keep an automatic pre-restore snapshot.
4. Add restore dry-run summary before destructive replacement.
5. Clean temporary restore files in `finally`.

Why:

Backups are safety equipment. They should be stricter than normal app input.

### 8. Make TTS Thread-Safe — `[DONE]`

Current state:

- `UtteranceProgressListener.onDone()` calls engine methods that read/write storage and continue playback.
- Those callbacks are not guaranteed to run on the main thread.
- `TtsEngine` mutable fields are not guarded.

Recommended changes:

1. Give `TtsEngine` a `CoroutineScope`.
2. Route TTS callbacks into that scope:

```kotlin
override fun onDone(utteranceId: String?) {
    scope.launch { handleChunkDone() }
}
```

3. Keep all `TtsEngine` state mutations on one dispatcher.
4. Add `close()` and call it from the service and activity as appropriate.

Why:

TTS bugs are hard to reproduce because callbacks depend on the Android TTS engine. A single serialized state path is much safer.

### 9. Clean Up WebView Lifecycle and Security Settings — `[DONE]`

Current state:

- Reader WebView disables JavaScript and DOM storage, which is good.
- Browser WebView enables JavaScript and DOM storage.
- WebViews are created in screen functions but not explicitly destroyed when navigating away.

Recommended changes:

1. Add a screen disposal mechanism so outgoing WebViews call:
   - `stopLoading()`
   - `clearHistory()` where appropriate
   - `removeAllViews()`
   - `destroy()`
2. Enable Safe Browsing where available.
3. Explicitly set browser WebView file/content access policy.
4. Consider clearing browser data/cookies for the in-app import browser unless login persistence is a deliberate feature.
5. Keep source import validation outside WebView state.

Why:

WebView is a heavy component. Leaked WebViews can retain activity references, network work, JavaScript state, and memory.

### 10. Revisit Manifest Backup and FileProvider Scope — `[DONE]`

Current state:

- `android:allowBackup="true"`.
- `FileProvider` paths include broad private file/cache roots.

Recommended changes:

1. Decide whether Android Auto Backup should include downloaded novels and generated EPUBs. If not, set `allowBackup=false` or add backup rules.
2. Narrow FileProvider paths to specific export/share directories instead of all files/cache.
3. Keep all exported files under a dedicated export directory.

Why:

Broad backup and broad sharing roots make privacy and accidental exposure harder to reason about.

## Speed and Responsiveness

### 1. Use RecyclerView for Large Lists

Current state:

- Library uses a custom `GridLayout` and creates all visible story cards on render.
- Details creates one `LinearLayout` row per chapter.
- Queue creates every story group and job row.
- Cleanup rules are all rendered as full cards.

Recommended changes:

1. Use `RecyclerView` with `ListAdapter`/`DiffUtil` for:
   - Library stories
   - Details chapter list
   - Queue jobs grouped by story
   - Cleanup sentence/regex rules
   - Selection screens
2. Keep header/filter controls outside the `RecyclerView`.
3. Use stable item IDs for story/chapter/job rows.

Why:

Programmatic `LinearLayout` lists are fine for 10 rows. They become slow and memory-heavy for hundreds or thousands of chapters.

### 2. Stop Rebuilding Full Screens for Small Updates

Current state:

- `screen(...)` removes the whole root view and rebuilds it.
- Queue refresh rebuilds every 30 seconds.
- Download progress can call `showLibrary()` or `showDetails(...)`.
- Operation progress updates re-render full details.

Recommended changes:

1. Keep screen-level state in a ViewModel or presenter.
2. Update only changed rows/progress widgets.
3. Use observable state (`StateFlow`) and render functions that diff list contents.
4. Avoid repeated image reloads during re-render.

Why:

Full rebuilds are simple but expensive. They also lose transient UI state unless every piece is manually preserved.

### 3. Move Disk Reads Off Render Paths

Current state:

- Screens read storage directly during render.
- `getLibrary()` reads the index and then each story JSON file.
- `loadImage()` and storage reads happen from screen helpers.

Recommended changes:

1. Cache library summaries in memory.
2. Load full story details lazily when opening Details/Reader.
3. Have repository flows emit changes after disk writes.
4. Avoid direct storage calls inside UI row builders.

Why:

Rendering should be cheap. Disk I/O during render causes jank and makes UI performance depend on library size.

### 4. Improve Image Loading

Current state:

- Cover loading uses `URL(url).openStream()` and `BitmapFactory.decodeStream(...)`.
- There is no memory cache, disk cache, timeout policy, downsampling, or cancellation tied to the target view.

Recommended changes:

1. Add Coil or Glide.
2. Use placeholder/error images.
3. Cache covers by URL.
4. Downsample to target size.

Why:

Image loading is solved infrastructure. Hand-rolled image loading is almost always slower and more fragile.

### 5. Stream EPUB Generation

Current state:

- EPUB generation builds a full `ByteArrayOutputStream`.
- `saveEpub()` writes that byte array after the entire EPUB is built.

Recommended changes:

1. Stream ZIP output directly to a temporary file.
2. Rename the completed file into the EPUB directory.
3. Keep only one chapter's XHTML in memory at a time.
4. Add cancellation checks between chapters.

Why:

Large books can be memory-heavy. Streaming generation is more stable and easier to cancel.

### 6. Cache Compiled Cleanup Rules

Current state:

- Cleanup regexes are compiled repeatedly during download cleanup, TTS preparation, and manual cleanup.

Recommended changes:

1. Compile cleanup rules once after settings load/change.
2. Store compiled rules in a small `CleanupEngine`.
3. Apply all cleanup off the main thread.
4. Keep regex safety checks, but add performance tests with large chapter samples.

Why:

Regex compilation and repeated parsing add up across hundreds of chapters.

## Maintainability

### 1. Split `Engines.kt`

Current state:

- `Engines.kt` contains story sync, download engine, scheduler, error classifier, text cleanup, EPUB generation, and TTS.

Recommended structure:

```text
core/sync/StorySyncEngine.kt
core/download/DownloadEngine.kt
core/download/DownloadScheduler.kt
core/download/DownloadErrorClassifier.kt
core/cleanup/TextCleanup.kt
core/epub/EpubEngine.kt
core/tts/TtsEngine.kt
```

Why:

The current file is over 1,000 lines and mixes unrelated responsibilities. Smaller files make ownership and review easier.

### 2. Introduce an App Container — `[DONE]`

Current state:

- Activity and services manually instantiate storage, network, and engines.
- This duplicates runtime ownership.

Recommended changes:

1. Add a lightweight `AppContainer` on `WebnovelArchiverApp`.
2. Put singleton process-wide dependencies there:
   - repository
   - network client
   - source registry
   - image loader
3. Keep Android components thin.

Why:

This is not full dependency injection. It is a simple native Android pattern that prevents accidental duplicate engines.

### 3. Use Lifecycle-Aware Scopes

Current state:

- `MainActivity` owns `CoroutineScope(SupervisorJob() + Dispatchers.Main)` but does not cancel it in `onDestroy()`.
- `DownloadEngine` owns its own scope.
- `FoldTracker` collection runs inside the activity scope.

Recommended changes:

1. Use `lifecycleScope` for activity UI work.
2. Use `repeatOnLifecycle` for state collection.
3. Make engines accept a scope from their owner or expose `close()`.
4. Cancel custom scopes explicitly.

Why:

Native Android best practice is to let lifecycle-aware scopes stop work when the owner is destroyed.

### 4. Add ViewModels or Presenters Before a UI Rewrite

Current state:

- Screens are extension functions on `ScreenHost`.
- They contain UI creation, state lookup, event handling, and sometimes business decisions.

Recommended incremental pattern:

1. For each major screen, add a state class:

```kotlin
data class LibraryUiState(
    val stories: List<StorySummary>,
    val selectedTabId: String?,
    val query: String,
    val sort: LibrarySort,
)
```

2. Add a presenter/ViewModel that maps repository state to UI state.
3. Keep the current programmatic Views initially.
4. Later, consider Compose only if you want a larger UI migration.

Why:

You do not need to rewrite the UI to get native best practices. Separating screen state from view construction gives most of the benefit.

### 5. Reduce `configChanges` Overreach

Current state:

- `MainActivity` handles many configuration changes itself.
- Screens rely on `rerender` lambdas.

Recommended changes:

1. Prefer normal activity recreation where possible.
2. Preserve UI state in ViewModels/repository.
3. Keep explicit foldable handling only where needed.

Why:

Opting out of recreation shifts responsibility for theme, locale, font scale, orientation, and resource changes onto app code. That is easy to get wrong.

### 6. Move User-Facing Strings to Resources

Current state:

- Most UI strings are hardcoded in Kotlin.

Recommended changes:

1. Move visible strings to `res/values/strings.xml`.
2. Keep internal status/error codes as enums.
3. Centralize user-facing error messages.

Why:

String resources improve consistency, localization readiness, and UI test stability.

## Test Recommendations

### Keep the Existing Pure Tests

The current planning tests are valuable. Keep adding to them when business logic changes.

### Add Storage Tests

Add Robolectric or instrumented tests for:

- atomic write recovery
- queue transactions
- full backup restore success
- failed restore preserving old data
- relative chapter path reconstruction
- migration from older JSON schemas

### Add Network Tests

Use MockWebServer for:

- retry behavior
- 403/429 handling
- timeout behavior
- source-specific rate limits under concurrency
- cover download failures

### Add Parser Fixture Tests

Keep sanitized HTML fixture files for supported sources:

```text
src/test/resources/fixtures/royalroad/story.html
src/test/resources/fixtures/royalroad/chapter.html
src/test/resources/fixtures/scribblehub/story.html
src/test/resources/fixtures/scribblehub/chapter.html
```

Why:

Novel sites change markup. Fixture tests make parser drift obvious.

### Add Emulator Smoke Tests

At minimum:

1. Launch app.
2. Import a fixture-backed story or local test source.
3. Queue a chapter download.
4. Open reader.
5. Generate/share EPUB.
6. Export and restore backup.

These can run less often than JVM tests, but they catch real Android breakage.

## Tooling Recommendations

1. Add ktlint or Spotless for formatting.
2. Add detekt for Kotlin maintainability checks.
3. Add CI that runs:

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

4. Add MockWebServer and kotlinx-coroutines-test dependencies.
5. Consider dependency update monitoring.
6. Add a release checklist that validates backup/restore, download service, EPUB generation, and TTS on the emulator.

## Suggested Implementation Order

### Phase 1: Data Safety

1. Add atomic JSON writes.
2. Add storage transaction APIs.
3. Make full backup restore non-destructive until validation and staging are complete.
4. Add storage tests for crash/restore behavior.

### Phase 2: Download Reliability

1. Make foreground service the sole download runner.
2. Add typed queue statuses.
3. Serialize queue updates through the repository.
4. Add MockWebServer tests for retries/rate limits.

### Phase 3: UI Performance

1. Convert Details chapter list to `RecyclerView`.
2. Convert Queue to `RecyclerView`.
3. Convert Library grid to `RecyclerView` with grid layout manager.
4. Add image loading library.

### Phase 4: Lifecycle and State

1. Add AppContainer.
2. Replace custom activity scope with lifecycle-aware collection.
3. Add ViewModel/presenter state for Library and Details.
4. Add WebView disposal hooks.

### Phase 5: Maintainability Cleanup

1. Split `Engines.kt`.
2. Move strings to resources.
3. Add detekt/ktlint.
4. Document source-provider contracts for future site support.

## Practical Native Kotlin Guidance

For this app, "best practice" does not mean adopting every Jetpack library at once. A pragmatic target architecture would be:

```text
Activity / Service
    -> ViewModel or Presenter
        -> AppRepository
            -> AppStorage / NetworkClient / Engines
```

Use:

- `StateFlow` for observable app state.
- `RecyclerView` for any potentially long list.
- `AtomicFile` for important JSON files.
- OkHttp for every network request.
- A single repository or app container for shared dependencies.
- Lifecycle-aware coroutine scopes.
- Pure planning functions for rules and state transitions.

Avoid:

- Multiple engine instances mutating the same files.
- Direct disk I/O from screen render functions.
- Direct writes to important final files.
- Stringly typed statuses.
- Large mutable models passed through UI and engine layers.
- Full screen rebuilds for small progress changes.

