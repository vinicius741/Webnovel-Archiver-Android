# Project Improvement Audit

Generated 2026-07-02. Static audit of the native Kotlin app under `android/`, focused on speed,
reliability, and maintainability. This review covered the production Kotlin tree, unit-test tree,
Gradle/tooling configuration, manifest, and existing documentation. It did not include runtime
profiling or emulator QA.

## Validation pass (2026-07-02)

A second pass re-checked every claim below against the code and added items found during
verification. File paths are relative to the package root
(`android/app/src/main/java/com/vinicius741/webnovelarchiver/`) unless noted.

### Verification outcome

All strengths, problem statements, and current-state assertions were checked against the source.
**Every claim is accurate** — no problem description was found to be wrong. The line counts in
recommendation 8 are exact (all 11 listed files are genuinely over 400 lines; the next-largest,
`download/DownloadEngine.kt`, is 380). The only items below are terminology refinements and gaps the
original audit did not capture.

### Terminology refinements

- Recommendation 2 parenthetical "during screen construction (composable invocation)" is
  mislabeled. `feature/reader/ReaderScreen.kt` contains **no `@Composable` functions** — it is
  imperative Android View code (`WebView`, `LinearLayout`, `TextView`). The substance of the claim
  (synchronous main-thread work during the screen-building function) is fully correct; only the
  "composable" label is wrong.
- Recommendation 8 says "Make CI run `:app:lintKotlin :app:ci`." There is currently **no CI of any
  kind** in the repo (no `.github/workflows/`, no other CI config). The `:app:ci` task exists
  (`app/build.gradle:86-89`, aggregates `testDebugUnitTest`, `detekt`, `lintDebug`,
  `assembleDebug`) but does not include `lintKotlin`, and nothing automates running it. So the work
  is "introduce CI," not "adjust existing CI."

### Gaps and additions found during validation

These extend or sharpen the recommendations and were not in the original audit:

1. **`txMutex` is documented as the multi-document transaction lock but is never implemented.**
   `AppStorage.kt` (around the enqueue path) and `app/AppContainer.kt` reference a "repository's
   transactional `txMutex`" as the owner for multi-document read-modify-write atomicity, but the
   field is never defined anywhere in `app/src/main`. This is directly relevant to recommendation 3:
   the code comments assert a lock exists that does not. Either implement it or remove the stale
   references so the concurrency contract is honest.

2. **JSON backup import (`importBackupUri`) has neither staging nor rollback.** Recommendation 3
   focuses on full restore racing with writers, but the JSON import path
   (`data/storage/BackupRestoreCoordinator.kt:177`) is worse: it is a plain `getLibrary()` →
   merge-in-memory → `saveLibrary()` sequence with each per-document `@Synchronized` block entered
   and exited independently, and no snapshot to roll back to on failure. A malformed or partially
   merged import can leave the library index in a mixed state. Treat JSON import with at least the
   same atomicity guarantees being added for full restore.

3. **`DownloadJobStatus` already exists — recommendation wording should be "adopt," not "build."**
   The "Storage model evolution" section suggests starting `DownloadJobStatus` serialization so
   callers can use the enum. The enum **already exists** (`domain/model/Models.kt:25-51`) with a
   `wire: String` property and a `parse` companion that round-trips the lowercase wire strings. The
   actual gap is narrower: `DownloadJob.status` is still typed `String` (`Models.kt:198`), so every
   planner and UI surface reparses it. The work is to make the field carry the enum (with the
   existing wire serialization), not to invent the type.

4. **Two extra `storage.getQueue()` hotspots in the download loop.** Recommendation 4 enumerates the
   main re-reads; verification found two more that compound the cost:
   - `isCancelled(id)` (`download/DownloadEngine.kt:359`) is called **twice per `processJob`**
     (lines 291 and 300), so each job triggers two fresh queue parses just for cancellation checks.
   - `cleanupUnsupportedSourceJobs()` reads the queue at `DownloadEngine.kt:237` and runs at the
   **top of every loop iteration** (`:175`). Both should reuse the in-memory queue snapshot
   recommendation 4 introduces.

5. **Regex cleanup split is a documented, intentional design — not an oversight.** Recommendation 7
   says "apply regex cleanup through `CleanupEngine` consistently." The split is real
   (`CleanupEngine` caches compiled rules for the download path; `TextCleanup.regexRunner`
   recompiles per call for TTS/reader/preview), but it is explicitly called out as a design
   trade-off in `cleanup/CleanupEngine.kt` and `cleanup/TextCleanup.kt`. Frame the work as
   "revisit the documented trade-off and converge on one path" rather than fixing an accidental
   inconsistency. Separately, the validator heuristic (`cleanup/RegexRuleCleanup.kt`) only inspects
   the literal pattern string and is bypassable; rules restored from backup are re-sanitized, but
   patterns the heuristics miss still match with no timeout.

6. **Existing network test coverage — avoid duplicate work when adding recommendation 6's tests.**
   `NetworkClientTest.kt` (MockWebServer) already covers: happy path, non-retryable 404, per-call
   timeout, 429 no-retry on a non-ScribbleHub host, image content type, non-image rejection, 500
   → null, and socket failure. Not yet covered (so still worth adding): **403**, the **ScribbleHub
   403/429 retry branch itself** (currently impossible to exercise against MockWebServer because the
   host is hard-coded to `www.scribblehub.com`), **`Retry-After`** (not read anywhere), and
   **oversize image** (`MAX_IMAGE_BYTES`). Note the ScribbleHub branch is untestable until the
   host check is moved behind a source policy — fixing recommendation 6 unblocks the test.

7. **`kotlinx-coroutines-test` is already a dependency** (`app/build.gradle:122`). Recommendation 2's
   "add tests for TTS state transitions using `kotlinx-coroutines-test`" needs no new dependency —
   only the tests, which do not yet exist (the `tts` test package covers only pure helpers; `TtsEngine`
   itself, with its `TextToSpeech` dependency and `Dispatchers.Main.immediate` scope, is untested).

8. **Untested EPUB generation path.** The `epub` test package covers the helper classes
   (`EpubContent`, `EpubMetadata`, `EpubFilename`, `EpubSelection`) but **never instantiates
   `EpubEngine` or exercises `generate` / the multi-volume `chunked` path**. The secondary EPUB
   suggestion to add a multi-volume regression test is confirmed necessary.

9. **Foreground services have no tests, and `DownloadForegroundService` is only moderate-sized.**
   Neither `Service` subclass has unit tests. `tts/TtsForegroundService.kt` is 459 lines (large,
   stateful — the audit's "large" label fits), but `download/DownloadForegroundService.kt` is 220
   lines (moderate). If a file-size gate is added under recommendation 8, size the TTS service for
   splitting but leave the download service's inline notification/command handling alone unless it
   grows.

## Current strengths

- The package structure is healthy: Android lifecycle/wiring lives in `app/`, pure rules live in
  `domain/` and `*Planning.kt` files, persistence lives in `data/`, source-specific scraping lives
  in `source/`, and screens are grouped under `feature/`.
- There is meaningful unit coverage for planning and parsing logic: backup planning, download
  scheduling, EPUB helpers, source fixtures, cleanup, TTS session planning, layout planning, and
  settings normalization all have focused tests.
- Storage durability has already received serious attention. JSON goes through `DurableJson`,
  chapter/EPUB writes go through temp-file plus rename helpers, and full restore uses staging plus
  rollback.
- Hot UI paths have started moving to `RecyclerView`: details chapter lists, chapter selection,
  legacy EPUBs, library pages, and the download queue already have adapter-based surfaces.
- The app uses one process-wide `AppContainer`, one shared `NetworkClient`, and repository state
  flows for library/queue snapshots, which is the right direction for a local-first app.

## Highest-impact recommendations

### 1. Make repository state the single read path for UI and engines

**Impact:** speed, reliability, maintainability  
**Priority:** high

The repository already exposes cached flows in `data/repository/AppRepository.kt`, but many screens
and engines still read `AppStorage` directly:

- `feature/updates/UpdatesScreen.kt` reads the whole library on entry and before syncing.
- `feature/library/LibraryScreen.kt` seeds from `storage.getLibrary()`.
- `feature/details/DetailsScreen.kt` and `DetailsScreenDownload.kt` mix direct queue reads with
  repository snapshots.
- `download/DownloadEngine.kt` re-reads `download_queue.json` for progress, cancellation checks,
  process-loop selection, and state publication.
- `reader/ReaderScreen.kt` reads chapter HTML and settings while rendering on the main thread.

This works for small libraries, but the file-backed model makes `getLibrary()` an O(number of
stories) parse of the library index plus every story JSON file. As the user's library grows, direct
reads during render and progress updates become the main scalability limit.

Recommended work:

- Treat `AppRepository.downloadState`, `libraryFlow`, and `queueFlow` as the default read path for
  screens.
- Add fast repository helpers backed by in-memory maps, for example `storyById(id)` and
  `queueForStory(id)`, instead of repeated list scans and JSON reads.
- Keep `AppStorage` as the persistence boundary only. UI should not need to know whether state is
  file-backed.
- After backup import or full restore, call `repository.refresh()` before navigating or showing
  toast-driven success. Current import paths call `storage.importBackupUri()` and
  `storage.importFullBackupUri()` from `MainActivity`, then navigate without refreshing cached flows.
- Add a regression test around repository refresh after import/restore once storage is testable
  without Android framework dependencies.

Expected result: fewer disk reads on render, fewer stale-flow edge cases after imports, and a
clearer ownership boundary between storage and presentation.

### 2. Move disk-heavy reader and TTS work off the main thread

**Impact:** speed, reliability  
**Priority:** high

Several user-visible paths can perform file I/O or expensive parsing on the main thread:

- `feature/reader/ReaderScreen.kt` reads chapter HTML, sanitizes it, prepares annotated TTS HTML,
  formats copy text, and builds the WebView document during screen construction.
- `tts/TtsEngine.kt` uses a main-dispatcher scope and writes `tts_session.json` in `speakCurrent()`
  for every chunk. Public methods such as `play`, `pause`, `next`, and `stop` also mutate state
  directly rather than consistently going through the documented mutex path.
- `storage.saveTtsSession()` uses durable JSON writes, so every chunk can trigger synchronous JSON
  serialization plus `AtomicFile` writes on the dispatcher that called it.

Recommended work:

- Introduce a reader preparation step that runs on `Dispatchers.IO` or `Default`, returning a
  `ReaderDocument` containing sanitized HTML, annotated chunks, formatted text, and display metadata.
- Render a lightweight loading state for very large chapters, then load the prepared document into
  the WebView on the main thread.
- Route all TTS public controls through the engine scope and `stateMutex`, matching the class
  comment and the listener callback behavior.
- Persist TTS sessions from an IO dispatcher. Consider debouncing chunk-position writes, while still
  writing immediately on pause, stop, chapter transition, and service destruction.
- Add tests for TTS state transitions using `kotlinx-coroutines-test`, especially concurrent
  `pause`/`next`/utterance-complete cases.

Expected result: fewer UI stalls while opening large chapters or speaking long chapters, and a
TTS state model that matches its documented thread-safety contract.

### 3. Guard backup and restore against concurrent writers

**Impact:** reliability  
**Priority:** high

`BackupRestoreCoordinator` has strong internal staging and rollback, but its public import/export
methods are not a global app transaction. Full restore can swap the app data root while the download
service, TTS engine, update sync, or screen actions are writing story, queue, or session files.
JSON import also performs a multi-step read/merge/save sequence without owning a broader app-level
operation lock.

Recommended work:

- Add an app-level "maintenance transaction" around import and full restore. It should pause/stop
  downloads, prevent new enqueue/sync/EPUB/TTS writes, run restore, refresh repository flows, then
  resume only if appropriate.
- At minimum, synchronize import/restore public methods on the storage monitor and ensure callers
  stop the foreground download loop before restore.
- Publish a `MaintenanceState` to disable destructive or write-heavy UI actions while restore is in
  progress.
- Add integration-style tests for failed restore rollback, stale repository refresh, and "restore
  while queue has active jobs" behavior. If Android `Context` makes direct tests hard, extract a
  filesystem-backed storage core with a temp-root constructor.

Expected result: restore remains safe even when background services are active, not only when the
app is idle.

### 4. Reduce repeated queue parsing and progress recomputation in downloads

**Impact:** speed, battery, maintainability  
**Priority:** high

`download/DownloadEngine.kt` repeatedly calls `storage.getQueue()` during the process loop,
progress emission, cancellation checks, and after each job update. Each call parses the queue JSON.
For large batches, progress notifications and status changes can turn into a steady stream of disk
reads and writes.

Recommended work:

- Keep an in-memory queue snapshot inside `DownloadEngine` for the current process loop iteration.
- Change `emitProgress()` and `buildProgress()` to accept a queue snapshot instead of reading
  storage every time.
- Collapse status writes when a batch completes close together. For example, update all completed
  jobs from one storage transaction where possible.
- Refresh settings inside the loop or subscribe to settings changes if running downloads should
  respect updated concurrency and delay without a service restart.
- Add a fake-storage/fake-network unit test for the engine that asserts queue read/write counts for
  a multi-chapter batch.

Expected result: lower disk churn during large downloads and clearer separation between scheduling,
progress, and persistence.

### 5. Virtualize the Updates screens

**Impact:** speed, maintainability  
**Priority:** medium-high

`feature/updates/UpdatesScreen.kt` renders followed stories and follow-selection rows into
scrollable `LinearLayout`s. The follow-selection screen rebuilds the entire visible list on every
search text change, and selecting covers can add image views for every row at once.

Recommended work:

- Replace follow selection with a `RecyclerView` adapter using stable IDs and `DiffUtil`.
- Split `UpdatesScreen.kt` into smaller files:
  - `UpdatesScreen.kt` for route-level wiring.
  - `UpdateFollowSelectionScreen.kt` for selecting followed novels.
  - `UpdatedStoryViews.kt` for updated story/chapter rows.
  - `UpdateSyncActions.kt` for batch sync orchestration.
- Keep `UpdateTrackerPlanning.kt` as the pure decision layer and add adapter-facing tests for row
  models if a presenter is introduced.
- For update sync, consider a bounded-concurrency worker with per-source limits once the network
  layer exposes typed rate-limit errors. Sequential sync is safer today, but slow for many followed
  stories.

Expected result: smooth search and selection in large libraries, plus a screen file that is easier
to reason about.

### 6. Introduce typed network errors and richer retry policy

**Impact:** reliability, maintainability  
**Priority:** medium-high

`NetworkClient` throws generic `IllegalStateException("HTTP ...")`, and
`DownloadErrorClassifier` re-parses the message to determine retry behavior. `fetchBytes()` swallows
all failures with `runCatching { ... }.getOrNull()`, which makes cover failures non-fatal but also
hard to diagnose. Only Scribble Hub 403/429 responses are retried in the low-level client.

Recommended work:

- Add sealed network errors such as `HttpError(code, url)`, `RateLimited(retryAfter)`,
  `Timeout(url)`, `NetworkUnavailable`, and `ParseRejected`.
- Parse `Retry-After` for 429/503 and pass it into download retry scheduling.
- Use the same retry and timeout policy for binary fetches, but keep cover failures non-fatal at the
  EPUB layer.
- Add per-source network policy in `SourceProvider` or a `SourceNetworkPolicy` registry instead of
  hard-coding Scribble Hub behavior inside `NetworkClient`.
- Add tests around HTTP 403, 429 with `Retry-After`, 5xx, timeout, invalid content type, and oversize
  image responses.

Expected result: less stringly-typed error handling, better user-facing retry behavior, and easier
support for new sources.

### 7. Harden user regex execution

**Impact:** reliability  
**Priority:** medium

Regex rule validation rejects some risky nested patterns, but Kotlin/JVM regex matching has no
execution timeout. A crafted pattern can still cause catastrophic backtracking during download
cleanup, TTS preparation, preview, or reader preparation.

Recommended work:

- Consider RE2/J for user-provided cleanup regexes if feature parity is acceptable.
- If staying with JVM regex, keep the current validation but add stricter pattern limits and a
  "disable rule after repeated slow/failing application" mechanism.
- Apply regex cleanup through `CleanupEngine` consistently so rules compile once and failures can be
  observed in one place.
- Add tests for known pathological patterns and imported malformed rules.

Expected result: cleanup rules remain powerful without allowing a single rule to hang a download or
reader open.

### 8. Tighten tooling gates now that the codebase is cleaner

**Impact:** maintainability, reliability  
**Priority:** medium

`android/app/detekt.yml` still uses `build.maxIssues: 1000`, explicitly marked as permissive during
adoption. That made sense while refactoring, but it means complexity regressions can accumulate.

Recommended work:

- Run `android/gradlew -p android :app:detekt` and record the current issue count.
- Fix or baseline remaining issues, then lower `maxIssues` toward 0.
- Add a file-length or class-size rule that reflects the repo's soft ceiling. Current files above
  400 lines are concentrated in a short list: `QueueScreen`, `UpdatesScreen`,
  `BackupRestoreCoordinator`, `StoryActions`, `CleanupScreen`, `SettingsScreen`, `ReaderScreen`,
  `AppStorage`, `TtsForegroundService`, `ChapterListAdapter`, and `LibraryFilters`.
- Make CI run `:app:lintKotlin :app:ci` rather than relying on contributors to remember both.

Expected result: future large-file and complexity issues are caught before they require another
large audit.

## Secondary recommendations

### Storage model evolution

The current file-per-story design is simple and durable, but it has scaling costs:

- `library_index.json` plus one story JSON per story makes whole-library reads expensive.
- Story models contain mutable lists and mutable fields, which forces defensive version counters in
  flows because equality is unreliable after in-place mutation.
- `DownloadJob.status` remains a `String`, so every planner and UI surface reparses status values.

Suggested direction:

- Short term: keep files, but add a lightweight summary index for library list rows and a repository
  map cache for details lookup.
- Medium term: make domain mutation copy-on-write where practical. Start with `DownloadJobStatus`
  serialization so callers can use the enum while preserving lowercase wire strings.
- Long term: if libraries grow into thousands of stories and tens of thousands of chapters, consider
  moving metadata to SQLite/Room while keeping chapter HTML and EPUBs as files.

### Source-provider maintainability

Royal Road and Scribble Hub parsing is now cleanly split, but provider additions will still require
manual registry edits and fixture discipline.

Suggested direction:

- Add a provider test template: metadata fixture, chapter-list fixture, chapter-content fixture,
  URL validation, and story/chapter ID extraction.
- Make `SourceRegistry` test that every provider has at least one importable story URL and one
  chapter URL fixture.
- Move source-specific constants such as page-size cookies and rate-limit gaps into source policies.

### EPUB generation

EPUB generation streams ZIP output, which is good. Remaining speed work is mostly around repeated
chapter reads and cover metadata.

Suggested direction:

- Cache chapter HTML for the selected EPUB chunk before writing entries, so availability checks and
  `writeEpub()` do not read the same chapter twice.
- Use content type from `fetchBytes()` when available instead of inferring cover media type from URL.
- Add a regression test for multi-volume generation with missing cover, oversized cover, and mixed
  filePath/content chapters.

### Foreground services

The foreground services are intentionally stateful and large. They are reasonable to leave intact
until touched, but future changes should isolate notification building and command handling.

Suggested direction:

- Split `TtsForegroundService.kt` into service lifecycle, notification/media-session builder, and
  command dispatch if it grows further.
- Add service-level tests around action-to-engine-command planning where Android framework calls can
  be kept out of the pure layer.

## Suggested execution order

1. Refresh repository state after backup imports and full restore; add a small regression test around
   stale repository flows if feasible.
2. Move reader preparation and TTS session persistence off the main thread.
3. Change download progress and cancellation paths to use queue snapshots instead of repeated JSON
   reads.
4. Add an app-level maintenance lock for restore and import.
5. Convert the Updates follow-selection surface to `RecyclerView` and split the file.
6. Introduce typed network errors and source network policies.
7. Harden regex execution and centralize rule application through `CleanupEngine`.
8. Tighten Detekt and file-size gates.

## Validation strategy for future changes

- Documentation-only changes: no Gradle build required.
- Storage/repository changes: run targeted storage/repository tests first, then
  `android/gradlew -p android :app:ci`.
- Reader/TTS changes: run TTS and reader unit tests, `:app:lintKotlin`, `:app:detekt`, and emulator
  smoke tests for opening a long chapter, changing reader settings, starting TTS, pausing, skipping,
  and leaving the reader.
- Download changes: run download unit tests, add fake-engine tests where needed, then build and
  smoke test the debug variant on `webnovel_api36`.
- Source/network changes: run source fixture tests and MockWebServer tests before any emulator work.

