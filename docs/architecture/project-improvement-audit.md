# Project Improvement Audit

Generated 2026-07-02; refreshed 2026-07-08. Static audit of the native Kotlin app under
`android/`, focused on speed, reliability, and maintainability. This review covered the production
Kotlin tree, unit-test tree, Gradle/tooling configuration, manifest, and existing documentation. It
did not include runtime profiling or emulator QA.

## Validation pass (2026-07-08)

A fresh pass re-checked every claim below against the current code and added items found during
verification. File paths are relative to the package root
(`android/app/src/main/java/com/vinicius741/webnovelarchiver/`) unless noted.

### Verification outcome

The major risks from the original audit still stand, but several observations needed narrowing:

- `AppRepository` now exposes `DownloadUiSnapshot`, cached `library()` / `queue()` accessors, and
  `story(id)`, and queue/details surfaces have started using them. Direct `AppStorage` reads remain
  in library, details, updates, reader, settings, sync, EPUB, and TTS paths.
- `TtsEngine` public controls now route through `scope.launch { stateMutex.withLock { ... } }`.
  The remaining TTS issue is synchronous storage/parsing work on the default
  `Dispatchers.Main.immediate` engine scope, especially chunk preparation and `tts_session.json`
  durable writes.
- Update sync now uses bounded concurrency (`UPDATE_SYNC_CONCURRENCY = 3`). That improves speed but
  creates a stronger need for transactional story updates and source-aware rate limiting.
- Network tests now cover Cloudflare challenge detection, including a false-positive regression for
  prose that resembles a challenge. Typed errors, `Retry-After`, and the Scribble Hub 403/429 retry
  branch remain unresolved.
- The tooling/file-size snapshot changed: `TtsEngine.kt` and `DownloadEngine.kt` are now over 400
  lines, and `DownloadForegroundService.kt` remains moderate-sized.

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
- The source now uses the shared `storage` monitor for repository/download transactions, not a
  coroutine transaction mutex. One `AppStorage.mutateQueueInPlace` comment still mentions a
  repository `txMutex`; that comment is stale and should be removed or rewritten.

### Gaps and additions found during validation

These extend or sharpen the recommendations and were not in the original audit:

1. **`txMutex` is still mentioned in a comment but is not part of the implementation.**
   `AppStorage.kt` still references a repository `txMutex` around the queue mutation comments, but
   the real serialization point is the shared `storage` monitor used by `AppRepository`,
   `DownloadEngine`, `mutateQueueInPlace`, and `saveEnqueue`. This is no longer a missing field bug;
   it is stale concurrency documentation that should be corrected so future changes do not chase a
   nonexistent lock.

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

4. **Extra `storage.getQueue()` hotspots remain in the download loop.** Recommendation 4 enumerates the
   main re-reads; verification found two more that compound the cost:
   - `isCancelled(id)` (`download/DownloadEngine.kt:421`) is called after a successful chapter write
     and in the error path, so each job can trigger fresh queue parses just for cancellation checks.
   - `cleanupUnsupportedSourceJobs()` reads the queue at `DownloadEngine.kt:292` and still runs at
     the top of every loop iteration. `buildProgress()` also reads the queue at `DownloadEngine.kt:430`.
     These should reuse the in-memory queue snapshot recommendation 4 introduces.

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
   `NetworkClientTest.kt` (MockWebServer) already covers: happy path, non-retryable 404,
   Cloudflare challenge detection on 403 and 200, a Cloudflare false-positive regression, per-call
   timeout, 429 no-retry on a non-ScribbleHub host, image content type, non-image rejection, 500
   → null, and socket failure. Not yet covered (so still worth adding): a plain non-challenge
   **403**, the **ScribbleHub 403/429 retry branch itself** (currently hard to exercise against
   MockWebServer because the host is hard-coded to `www.scribblehub.com`), **`Retry-After`** (not
   read anywhere), and **oversize image** (`MAX_IMAGE_BYTES`). Moving host policy out of
   `NetworkClient` would make the Scribble Hub branch directly testable.

7. **`kotlinx-coroutines-test` is already a dependency** (`app/build.gradle:122`). Recommendation 2's
   "add tests for TTS state transitions using `kotlinx-coroutines-test`" needs no new dependency —
   only the tests, which do not yet exist (the `tts` test package covers only pure helpers; `TtsEngine`
   itself, with its `TextToSpeech` dependency and `Dispatchers.Main.immediate` scope, is untested).

8. **Untested EPUB generation path.** The `epub` test package covers the helper classes
   (`EpubContent`, `EpubMetadata`, `EpubFilename`, `EpubSelection`) but **never instantiates
   `EpubEngine` or exercises `generate` / the multi-volume `chunked` path**. The secondary EPUB
   suggestion to add a multi-volume regression test is confirmed necessary.

9. **Foreground services have no service-level tests, and `DownloadForegroundService` is only
   moderate-sized.**
   Neither `Service` subclass has unit tests. `tts/TtsForegroundService.kt` is 548 lines (large,
   stateful — the audit's "large" label fits), but `download/DownloadForegroundService.kt` is 223
   lines (moderate). If a file-size gate is added under recommendation 8, size the TTS service for
   splitting but leave the download service's inline notification/command handling alone unless it
   grows.

10. **Concurrent update sync uses stale story snapshots.** `feature/updates/UpdatesScreen.kt` now
    syncs followed stories in batches of three, but `StorySyncEngine.fetchOrSync()` still reads an
    existing story before network work and writes the merged story afterward through
    `storage.addOrUpdateStory()`. That is safe for different story IDs most of the time, but it can
    overwrite newer fields if another writer mutates the same story while a sync is in flight
    (bookmark changes, details edits, enqueue/download updates, archive creation). Route the final
    merge through a repository transaction or make the merge re-read-and-apply under the storage
    monitor.

11. **Detekt currently reports 53 findings but passes because `maxIssues` is 1000.** A 2026-07-08
    run of `android/gradlew -p android :app:detekt` succeeded and reported 53 findings: mostly
    `TooManyFunctions`, enum naming, long parameter lists, and complexity. This gives recommendation
    8 a concrete baseline.

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
  flows plus a typed `DownloadUiSnapshot` for library/queue snapshots, which is the right direction
  for a local-first app.

## Highest-impact recommendations

### 1. Make repository state the single read path for UI and engines

**Impact:** speed, reliability, maintainability  
**Priority:** high

The repository now exposes cached flows, a typed `DownloadUiSnapshot`, and convenience helpers in
`data/repository/AppRepository.kt`, but many screens and engines still read `AppStorage` directly:

- `feature/updates/UpdatesScreen.kt` reads the whole library on entry and before syncing.
- `feature/library/LibraryScreen.kt` seeds from `storage.getLibrary()` and then observes repository
  state.
- `feature/details/DetailsScreen.kt` still seeds from `storage.getStory()` and `storage.getQueue()`;
  `DetailsScreenDownload.kt` has moved to repository snapshots/helpers.
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
- `tts/TtsEngine.kt` still uses a main-dispatcher scope by default and writes `tts_session.json` in
  `speakCurrent()` for every chunk. Public methods now route through the documented scope and
  `stateMutex`; the remaining problem is that storage reads/writes and TTS chunk preparation still
  run inside that locked main-dispatcher path.
- `storage.saveTtsSession()` uses durable JSON writes, so every chunk can trigger synchronous JSON
  serialization plus `AtomicFile` writes on the dispatcher that called it.

Recommended work:

- Introduce a reader preparation step that runs on `Dispatchers.IO` or `Default`, returning a
  `ReaderDocument` containing sanitized HTML, annotated chunks, formatted text, and display metadata.
- Render a lightweight loading state for very large chapters, then load the prepared document into
  the WebView on the main thread.
- Keep TTS public controls on the engine scope and `stateMutex`, but move storage and chunk
  preparation work off the main dispatcher or split the engine into a main-thread TTS bridge plus an
  IO/default preparation layer.
- Persist TTS sessions from an IO dispatcher. Consider debouncing chunk-position writes, while still
  writing immediately on pause, stop, chapter transition, and service destruction.
- Add tests for TTS state transitions using `kotlinx-coroutines-test`, especially concurrent
  `pause`/`next`/utterance-complete cases.

Expected result: fewer UI stalls while opening large chapters or speaking long chapters, and a
TTS state model that matches its documented thread-safety contract.

### 3. Guard backup and restore against concurrent writers

**Impact:** reliability  
**Priority:** high

`BackupRestoreCoordinator` has strong internal staging and rollback for full ZIP restore, but its
public import/export methods are not a global app transaction. Full restore can swap the app data
root while the download service, TTS engine, update sync, EPUB generation, or screen actions are
writing story, queue, or session files. JSON import also performs a multi-step read/merge/save
sequence without staging, rollback, repository refresh, or a broader app-level operation lock.

Recommended work:

- Add an app-level "maintenance transaction" around import and full restore. It should pause/stop
  downloads, prevent new enqueue/sync/EPUB/TTS writes, run restore, refresh repository flows, then
  resume only if appropriate.
- At minimum, synchronize import/restore public methods on the storage monitor and ensure callers
  stop the foreground download loop before restore.
- Publish a `MaintenanceState` to disable destructive or write-heavy UI actions while restore is in
  progress.
- Add integration-style tests for failed restore rollback, JSON import failure, stale repository
  refresh, and "restore while queue has active jobs" behavior. If Android `Context` makes direct
  tests hard, extract a filesystem-backed storage core with a temp-root constructor.

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
- Use that same snapshot for unsupported-source cleanup and cancellation checks, with an explicit
  refresh after network I/O where user cancellation must be observed.
- Collapse status writes when a batch completes close together. For example, update all completed
  jobs from one storage transaction where possible.
- Preserve the current per-loop settings refresh so running downloads continue to see updated
  concurrency and delay without a service restart.
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
- Update sync already uses a bounded worker (`UPDATE_SYNC_CONCURRENCY = 3`). Keep it, but add
  per-source limits once the network layer exposes typed rate-limit errors, and make the final story
  merge transactional so concurrent sync does not overwrite newer same-story changes.

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
- Use the same retry and timeout policy for binary fetches, and return content type alongside bytes
  so EPUB generation does not infer cover media type from URL alone. Keep cover failures non-fatal
  at the EPUB layer.
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
- Revisit the documented split between cached `CleanupEngine` for downloads and stateless
  `TextCleanup.regexRunner` for TTS/reader/preview. If behavior stays split, add equivalent
  observability/slow-rule handling to both paths; otherwise converge TTS and reader preparation onto
  the cached engine.
- Add tests for known pathological patterns and imported malformed rules.

Expected result: cleanup rules remain powerful without allowing a single rule to hang a download or
reader open.

### 8. Tighten tooling gates now that the codebase is cleaner

**Impact:** maintainability, reliability  
**Priority:** medium

`android/app/detekt.yml` still uses `build.maxIssues: 1000`, explicitly marked as permissive during
adoption. That made sense while refactoring, but it means complexity regressions can accumulate. A
2026-07-08 run of `android/gradlew -p android :app:detekt` succeeded and reported 53 findings.

Recommended work:

- Fix or baseline the current 53 Detekt findings, then lower `maxIssues` toward 0.
- Add a file-length or class-size rule that reflects the repo's soft ceiling. Current files above
  400 lines are concentrated in a short list: `UpdatesScreen`, `QueueScreen`, `TtsEngine`,
  `SettingsScreen`, `BackupRestoreCoordinator`, `TtsForegroundService`, `StoryActions`,
  `CleanupScreen`, `AppStorage`, `ReaderScreen`, `DownloadEngine`, `ChapterListAdapter`, and
  `LibraryFilters`.
- Introduce CI that runs `:app:lintKotlin :app:ci` rather than relying on contributors to remember
  both.

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
- Medium term: make domain mutation copy-on-write where practical. `DownloadJobStatus` already has
  lowercase wire serialization; adopt it on `DownloadJob.status` instead of keeping that field as a
  raw `String`.
- Long term: if libraries grow into thousands of stories and tens of thousands of chapters, consider
  moving metadata to SQLite/Room while keeping chapter HTML and EPUBs as files.

### Source-provider maintainability

Royal Road and Scribble Hub parsing is now cleanly split, and both have fixture tests. Provider
additions will still require manual registry edits and fixture discipline.

Suggested direction:

- Keep the existing provider fixture pattern as the template: metadata fixture, chapter-list fixture,
  chapter-content fixture, URL validation, and story/chapter ID extraction.
- Add a `SourceRegistry` test that every registered provider has at least one importable story URL
  and one chapter URL fixture.
- Move source-specific constants such as page-size cookies and rate-limit gaps into source policies.

### EPUB generation

EPUB generation streams ZIP output, which is good. Remaining speed work is mostly around repeated
chapter reads and cover metadata.

Suggested direction:

- Cache chapter HTML for the selected EPUB chunk before writing entries, so availability checks and
  `writeEpub()` do not read the same chapter twice.
- Fetch the cover once per `generate()` call instead of once per output volume.
- Return content type from `fetchBytes()` and use it when available instead of inferring cover media
  type from URL.
- Add a regression test for multi-volume generation with missing cover, oversized cover, and mixed
  filePath/content chapters.

### Foreground services

The foreground services are intentionally stateful and large. They are reasonable to leave intact
until touched, but future changes should isolate notification building and command handling. The
larger maintenance pressure is currently on `TtsForegroundService` and `TtsEngine`; the download
service is still moderate-sized.

Suggested direction:

- Split `TtsForegroundService.kt` into service lifecycle, notification/media-session builder, and
  command dispatch if it grows further.
- Consider a similar split for `TtsEngine.kt`: Android `TextToSpeech` bridge, session persistence,
  chunk preparation, and playback state machine.
- Add service-level tests around action-to-engine-command planning where Android framework calls can
  be kept out of the pure layer.

## Suggested execution order

1. Refresh repository state after backup imports and full restore; add a small regression test around
   stale repository flows if feasible.
2. Add an app-level maintenance lock for restore/import and correct the stale `txMutex` comment.
3. Move reader preparation and TTS session persistence off the main thread.
4. Make update-sync story merges transactional now that followed sync runs concurrently.
5. Change download progress, unsupported-source cleanup, and cancellation paths to use queue
   snapshots instead of repeated JSON reads.
6. Convert the Updates follow-selection surface to `RecyclerView` and split the file.
7. Introduce typed network errors, `Retry-After`, source network policies, and richer binary fetch
   metadata.
8. Harden regex execution and converge or equally instrument the cleanup paths.
9. Tighten Detekt and file-size gates after baselining the current 53 findings.

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
