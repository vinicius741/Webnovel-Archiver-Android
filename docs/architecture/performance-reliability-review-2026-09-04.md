# Performance and reliability review

Review date: 2026-09-04. Baseline: `94c03e7` plus the existing working-tree changes.

> **Implementation status (2026-09-04, branch `perf-reliability-review-2026-09-04`)**: all 30
> recommendations R01–R30 have been implemented with unit-test coverage, and the repository gate
> (`:app:lintKotlin :app:detekt :app:ci`) passes. Notes on scope per item:
>
> - R01/R02: queue controls are suspend repository transactions on the I/O dispatcher with
>   story-scoped single-transaction batch operations; UI reads serve a lock-free published snapshot.
> - R05: a library generation invalidates in-flight sync/download/AI commits; AI existence checks
>   ride the draft-save transactions; removed/cancelled downloads no longer publish.
> - R07: full restore keeps a durable phase journal (`prepared`/`old-root-moved`/`committed`) beside
>   the live root and its snapshot moved out of cacheDir; `RestoreStartupRecovery` runs before
>   `AppStorage` construction and also recovers pre-journal legacy states.
> - R10: the export manifest records `missingContent`; legacy inline chapter content is
>   materialized; a progress warning names the omissions.
> - R13: `Call.executeCancellable()` binds coroutine cancellation to `Call.cancel()`; every source
>   request has a default 180s total call budget.
> - R14: accepted `Retry-After` values are no longer clamped to the ordinary backoff cap, reach the
>   shared host coordinator on arrival, and deadlines beyond the operation budget defer the work.
> - R22: the report's first step shipped (200ms search debounce, equivalent-filter skip,
>   off-main regex preview); the full RecyclerView card-recycling conversion remains the staged
>   follow-up. R23 shipped countdown label patching on time-only ticks; adapters' background diff
>   migration remains staged follow-up work.
> - R27: `AiCoverDraftMeta` lives in the kept `domain.model` package with `@SerializedName` wire
>   names; minified-artifact round-trip verification still needs the release workflow.
> - R30: startup-phase timings (Timber), `LocalDiagnostics.recordOperation` for
>   maintenance/queue-save/reader-preparation durations, and the per-recommendation failure tests
>   added throughout this change.

This report recommends changes only. No app implementation, tests, build configuration, or device data were changed for this review.

The best first steps are to make queue controls safe for the main thread, reject incomplete source chapter lists, protect concurrent local edits during sync, and stop treating unreadable rewrite metadata as an empty document. These address hangs and lost or misleading state without replacing the app's architecture. Batch queue operations and remove redundant file work next.

## Scope and evidence

The inventory contains 318 production Kotlin files, 46,534 lines, and 34 package directories under the native app. The review covered the main execution paths across these packages, searched the production tree for blocking I/O, mutation, cancellation, lifecycle, and rendering patterns, and inspected the corresponding test coverage. There are 149 Kotlin files under unit tests, including test helpers, and four device-test files.

This is a static implementation review, not a line-by-line correctness proof or an emulator QA run. The report distinguishes code-visible behavior from runtime hypotheses. It makes no measured speedup, crash-frequency, or battery-life claims. Older architecture and QA reports were checked for context; recommendations below were checked against current code rather than copied from those reports.

Existing edits in AI prompt/planning files, their tests, and `BypassLogExporter.kt` were left intact. Source references give the file and line at review time, together with the relevant method in the explanation. Line numbers will move as implementation changes.

| Area reviewed | Main paths inspected | Result |
|---|---|---|
| Application and navigation | Container construction, startup migrations, readiness, Activity callbacks, route restoration, screen lifecycle | Remaining lock, error, and startup work in R01, R12, R20 |
| Repository and persistence | Snapshot caches, story/queue/settings transactions, durable JSON, chapter files, AI stores | R04–R09, R21, R26–R28 |
| Backup and restore | JSON import, full ZIP export/extraction, validation, staging, root swap, rollback | R07, R10, R11; existing validation and rollback should be retained |
| Downloads | Enqueue, scheduler, process loop, pacing, controls, retries, service timeout | R01, R02, R05, R13, R14 |
| Source providers and sync | Royal Road, Scribble Hub, SpaceBattles, FanFiction.net, metadata/Patreon enrichment, latest/full merge | R03–R05, R13, R14, R29 |
| Reader and TTS | Content resolution, document preparation, chunk preparation, session persistence, audio focus, watchdog, player/mini-player | R12, R16, R18, R19, R26 |
| AI | OpenRouter requests, description/cover/rewrite generation, verification, queued jobs, foreground services, drafts, usage | R08, R09, R15, R24, R26, R27 |
| EPUB and cleanup | Selection, streamed generation, covers, retention, cleanup application and regex rules | R06, R17–R19, R25 |
| User interface | Library, details, queue, updates, settings, AI controls, adapters, layout and fold handling | R01, R12, R22–R24, R26, R28 |
| Domain rules and supporting infrastructure | Archive/bookmark/status planning, metrics retention/chart inputs, notifications, browser safety, diagnostics, build/test gates | No replacement proposed; retain existing planning seams and add targeted checks in R30 |

## Priorities and effort

“High” means a code path can lose state, misreport content, or block/crash an important operation. It does not mean the failure was reproduced on a device. “Medium” covers visible slowness, incomplete recovery, and resource waste. Small means a localized change; medium means several callers or a new coordination/test seam; large means compatibility-sensitive recovery work. These are relative estimates, not delivery promises.

| ID | Recommendation | Priority | Effort |
|---|---|---|---|
| R01 | Make queue controls and cached story reads safe for the main thread | High | Small–medium |
| R02 | Persist each grouped queue action once | High | Small |
| R03 | Reject partial chapter lists during full sync | High | Small–medium |
| R04 | Preserve all current local edits when sync commits | High | Small–medium |
| R05 | Reject stale results after deletion, cancellation, or restore | High | Medium |
| R06 | Fail safely when an atomic rename or file sync fails | High | Small |
| R07 | Recover interrupted restores before normal startup | High | Medium–large |
| R08 | Fence corrupt or unsupported rewrite manifests against writes | High | Small–medium |
| R09 | Give each AI content generation its own file identity | High | Medium |
| R10 | Report missing content in a full backup | High | Small |
| R11 | Include per-story TTS positions in full backups | Medium | Small |
| R12 | Handle recoverable UI-operation failures explicitly | High | Small–medium |
| R13 | Tie source requests to cancellation and total deadlines | Medium | Medium |
| R14 | Preserve the server's retry deadline | Medium | Small |
| R15 | Coordinate AI service timeout, cancellation, and queued work | High | Medium |
| R16 | Distinguish TTS failure from normal completion | Medium | Small–medium |
| R17 | Mark EPUBs stale before cleanup changes chapter content | High | Small |
| R18 | Stop clearing shared WebView cache on normal teardown | Medium | Small |
| R19 | Check the regex breaker on every use and bound preview work | Medium | Small–medium |
| R20 | Consolidate startup library passes before adding another index | Medium | Medium |
| R21 | Remove redundant metadata reads, writes, and copies | Medium | Small–medium |
| R22 | Recycle library cards and debounce search | Medium | Small first step; medium full fix |
| R23 | Move list diffs off main and narrow countdown updates | Medium | Small–medium |
| R24 | Bound response/image memory and decode previews off main | High | Small–medium |
| R25 | Reuse EPUB covers and avoid duplicate chapter reads | Medium | Small–medium |
| R26 | Make rewrite reads asynchronous and reuse one manifest snapshot | Medium | Small–medium |
| R27 | Stabilize the cover-draft JSON fields in minified builds | High, validation needed | Small |
| R28 | Update preferences transactionally by field | Medium | Small–medium |
| R29 | Bound source caches and report failed reliability persistence | Medium | Small |
| R30 | Add failure-focused regression checks and useful timing evidence | Medium | Incremental |

## Reliability recommendations

### R01. Keep queue controls and cached story reads off the storage monitor on main

Evidence: [DownloadEngine.kt:131](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/download/DownloadEngine.kt#L131), [QueueScreen.kt:271](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/feature/downloads/QueueScreen.kt#L271), [AppRepository.kt:40](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/data/repository/AppRepository.kt#L40), [MaintenanceCoordinator.kt:32](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/data/storage/MaintenanceCoordinator.kt#L32).

Queue pause/resume/cancel/retry/remove controls call non-suspending engine methods directly from click handlers. `mutateQueue()` reads and rewrites JSON and republishes from disk. A non-suspending method can still block; its current comment claims the opposite. Separately, `repository.story()` acquires the same monitor used for disk transactions, even though it returns cached data. Full backup, restore, and streamed EPUB generation can hold that monitor for a long time.

Move queue commands behind suspend repository operations on the I/O dispatcher and handle failures at the button boundary. Publish a separately protected or immutable story lookup snapshot so UI reads do not wait for file work. Preserve coherent transactions. Simply removing synchronization from a mutable map would introduce races. The initial enqueue flow in `StoryActions.queueDownload()` already uses the process I/O scope; keep that improvement.

Validate with a deliberately blocked maintenance operation: Library/Details navigation, queue buttons, and service callbacks must remain responsive while writes wait. Android's [ANR guidance](https://developer.android.com/topic/performance/anrs/diagnose-and-fix-anrs) identifies both main-thread I/O and lock contention as causes, so dispatcher changes alone do not solve the cached-read lock.

### R02. Batch grouped queue actions into one transaction

Evidence: [QueueScreen.kt:400](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/feature/downloads/QueueScreen.kt#L400), [QueueScreen.kt:430](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/feature/downloads/QueueScreen.kt#L430)}, [AppStorage.kt:419](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/data/storage/AppStorage.kt#L419).

Pausing, resuming, cancelling, or removing a story loops over jobs and calls a durable transaction for each one. For K selected jobs in a queue of Q entries, this does roughly K whole-queue read/write cycles plus repeated publications. It also leaves a partially changed group if a later write fails.

Add one operation that transforms all matching job IDs or one story ID, saves once, publishes once, and wakes the process loop once. Resolve eligibility against the current queue inside the transaction. Keep single-job controls for individual rows.

Validate a 1,000-job group with an instrumented store. Each action should make one queue save and preserve every unrelated job. Inject a write failure and confirm the UI reports it without presenting the group as fully changed.

### R03. Never use an interrupted full chapter list as authoritative

Evidence: [ScribbleHubProvider.kt:195](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/source/ScribbleHubProvider.kt#L195), [StorySyncPlanning.kt:20](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/sync/StorySyncPlanning.kt#L20), [StorySyncEngine.kt:68](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/sync/StorySyncEngine.kt#L68), [SpaceBattlesProvider.kt:183](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/source/SpaceBattlesProvider.kt#L183).

Scribble Hub catches `SourceAccessBlockedException` while fetching TOC pages. Page one becomes an empty result; later pages break the loop. `getChapterList()` then returns the chapters collected so far. During full sync, `mergeChapters()` treats absent existing chapters as removed. The archive fallback preserves a snapshot, but the active novel can still be shortened and its bookmark reset because a request was blocked, not because the author removed chapters. A new import can also look complete when it is partial.

The small fix is to propagate pagination failures from the full-list path. A stronger provider result explicitly distinguishes complete lists, latest-only lists, and incomplete retrieval. Only a complete list may remove existing chapters. Treat hitting page limits without an observed end as incomplete too. SpaceBattles also caps pagination, so apply the same completion contract there. Keep successful latest-only merging separate.

Validate page two returning a challenge, an unexpectedly empty/repeated page, and pagination reaching its cap. Existing active chapter IDs and bookmarks must remain unchanged on incomplete full sync. A genuine complete list with removals should still create the intended archive.

### R04. Merge every local field against the current story, including explicit resets

Evidence: [StorySyncMergePlanning.kt:81](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/sync/StorySyncMergePlanning.kt#L81), [StorySyncEngine.kt:43](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/sync/StorySyncEngine.kt#L43), [AppRepository.kt:495](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/data/repository/AppRepository.kt#L495).

The final sync transaction protects downloaded content, bookmarks, and several AI fields. It does not preserve all concurrent local fields. For example, a tab move or EPUB generation during the network request can be overwritten by the old `tabId` or EPUB pointers in the sync snapshot. Expressions such as `onDisk.aiCoverPath ?: synced.aiCoverPath` and `onDisk.chapterRewriteStrength ?: synced.chapterRewriteStrength` also undo a user's explicit reset to null.

Define source-owned and user-owned fields. Take user-owned fields from the current record, including null values, and merge source metadata deliberately. Retain the existing chapter matching and downloaded-content preservation. For fields updated by both sync and local operations, compare versions or merge the specific operation rather than choosing an entire stale object.

Extend `StorySyncMergePlanningTest` with tab changes, EPUB completion, cover deletion, context-selection reset, and strength reset while sync is blocked. Verify the latest local choice survives.

### R05. Reject work that belongs to an obsolete library or cancelled job

Evidence: [AppRepository.kt:503](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/data/repository/AppRepository.kt#L503), [StorySyncMergePlanning.kt:38](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/sync/StorySyncMergePlanning.kt#L38), [DownloadEngine.kt:249](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/download/DownloadEngine.kt#L249), [AiChapterRewriteJobCoordinator.kt:131](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/app/AiChapterRewriteJobCoordinator.kt#L131), [AppRepositoryRewrites.kt:18](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/data/repository/AppRepositoryRewrites.kt#L18).

A sync that started on an existing story can recreate it after deletion because a missing current record falls back to the fetched story. Downloads save chapter files before the final cancellation check, and that check recognizes only an explicit cancelled queue entry. Removed jobs do not count as cancelled. AI jobs check for story existence before entering their save transaction, leaving a check/write race. Restore's storage lock prevents simultaneous file writes, but it does not invalidate network work started against the previous library.

Capture a library generation and the relevant story/job identity when work starts. At commit, under the same transaction, require that generation, story, chapter, and job eligibility still match. Distinguish intentional new imports from updates of existing stories. Increment the generation after clear/restore and stop or reject older work. Put AI existence checks inside draft-save transactions. For pause, explicitly decide whether an already-fetched chapter may finish; cancellation/removal should not silently publish it.

Validate with barriers: delete a syncing story, remove an active download, and restore while a fetch or AI generation is in flight. Release the response afterward. No stale story, chapter file, draft, or queue completion should appear in the replacement library.

### R06. Preserve the old file when atomic replacement cannot complete

Evidence: [AtomicFileWrites.kt:63](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/data/storage/AtomicFileWrites.kt#L63), [AtomicFileWrites.kt:83](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/data/storage/AtomicFileWrites.kt#L83), [AppStorage.kt:455](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/data/storage/AppStorage.kt#L455).

`AtomicFileWrites` creates a sibling temporary file, but if rename fails it copies over the destination with `overwrite = true`. That fallback can truncate the known-good destination and then fail halfway through. The file `fsync()` failure is also swallowed, so the helper can report success without confirming its stated durability guarantee. Archive chapter copies bypass the atomic helper entirely.

For sibling files, fail the operation if atomic replacement cannot succeed, leaving the old destination intact. Propagate or explicitly classify file-sync failures. Keep directory-sync portability handling separate from file-data sync. Route archive copies through a streamed temporary file and the same checked commit. Do not substitute a delete-then-rename sequence.

Add a file-operation seam and inject rename, copy, disk-full, and sync failures. The destination must always contain either the complete old file or the complete new file. An unsuccessful commit must not return success.

### R07. Recover restore transactions after process death

Evidence: [RestoreRootSwap.kt:45](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/data/storage/RestoreRootSwap.kt#L45), [FullBackupRestorer.kt:73](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/data/storage/FullBackupRestorer.kt#L73), [AppStorage.kt:77](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/data/storage/AppStorage.kt#L77), [AppContainer.kt:103](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/app/AppContainer.kt#L103), [JsonBackupImporter.kt:71](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/data/storage/JsonBackupImporter.kt#L71).

Full restore moves the live root to a snapshot, then installs the staged root. In-process failures invoke rollback, but no startup path checks the snapshot or resumes an interrupted transaction. A process death between the root moves bypasses `catch` and `finally`; the next `AppStorage` construction creates directories before recovery is considered. The snapshot also lives under `cacheDir`. JSON import has exception rollback but no restart recovery for a kill after some files changed.

Add a durable transaction record beside live storage, with explicit prepared, old-root-moved, and committed phases. Run recovery before creating or hydrating the normal root. Store the only rollback copy in durable app files, not cache. A committed marker is essential: a leftover, partially deleted old snapshot after successful commit must never be blindly restored over good new data. Fail closed and show recovery state when the phase is ambiguous. Apply the same principle to JSON import or stage its complete result before commit.

This is important but not a quick patch. Validate by killing a disposable test process at every phase and restarting through production startup. Existing `RestoreRootSwapTest` fault injection is useful, but caught exceptions do not simulate a process disappearing.

### R08. Give rewrite manifests the same read/write health checks as library JSON

Evidence: [AiChapterRewriteStore.kt:43](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/data/storage/AiChapterRewriteStore.kt#L43), [AiChapterRewriteStore.kt:218](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/data/storage/AiChapterRewriteStore.kt#L218), [AiChapterRewriteModels.kt:109](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/domain/model/AiChapterRewriteModels.kt#L109), [DurableJson.kt:161](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/data/storage/DurableJson.kt#L161).

Rewrite manifest reads collapse missing files, I/O failures, malformed JSON, and a wrong format into an empty manifest. The model has a version, but this read path does not validate it. A subsequent save can replace unreadable existing state with a manifest containing only the new record, losing references to other applied rewrites. Successfully parsed JSON can also contain null collection fields despite Kotlin declarations.

Reuse a typed durable read result, validate version and required collections, quarantine confirmed corruption, and block writes on unsupported schema or unresolved I/O failure. Show a recoverable state in AI Controls and fall back to source text for reading without erasing metadata. Preserve the original document until recovery is deliberate.

Validate malformed JSON, a future version, null `applied`/`drafts`, and injected read failure. Saving another draft must not overwrite a fenced document or lose existing applied records.

### R09. Commit AI content and metadata using generation-specific files

Evidence: [AiChapterRewriteStore.kt:91](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/data/storage/AiChapterRewriteStore.kt#L91), [AiChapterRewriteStore.kt:108](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/data/storage/AiChapterRewriteStore.kt#L108), [AiCoverDraftStore.kt:54](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/data/storage/AiCoverDraftStore.kt#L54).

Writing content before its manifest is safe for a brand-new record, but these stores reuse filenames. Rewriting an already-applied chapter replaces `applied.html` before its new verification metadata is committed. If the app dies between those writes, the old manifest describes new content. Replacing a draft can similarly pair new text or image bytes with old prompt, model, cost, or verification data.

Include the operation ID or a generation identifier in the content filename. Write and verify that file, atomically switch the manifest reference, then remove unreferenced generations later. Retain the previous generation until the metadata commit succeeds. Use exact metadata references when loading images instead of choosing the first matching filename.

Validate a second generation, not just the first save. Interrupt after each content/metadata write. The restored draft/applied content must always match its prompt and verification record.

### R10. Make missing downloaded files visible in full-backup results

Evidence: [BackupExporter.kt:195](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/data/storage/BackupExporter.kt#L195), [BackupExporter.kt:188](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/data/storage/BackupExporter.kt#L188), [AiChapterRewriteStore.kt:194](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/data/storage/AiChapterRewriteStore.kt#L194).

A chapter marked downloaded is silently omitted from the ZIP when its file is missing. Inline `chapter.content` is cleared from the manifest and is not materialized by `collectChapterFiles()`. Missing applied rewrite files are also filtered out. A successful export therefore does not necessarily contain every item the library reports as available. Restore can reconcile the missing content, but that does not make the backup complete.

Count expected and included content before export. Either fail with an actionable list or return an explicit partial-backup result with missing story/chapter IDs. Materialize valid inline chapter content into the archive when supporting that legacy representation. Validate the generated manifest and entry set before presenting success; preserve the last good backup.

Test a missing source HTML file, an inline-only downloaded chapter, and a missing applied rewrite file. A normal success message must imply that all expected supported content was included.

### R11. Back up every story's TTS resume position

Evidence: [BackupExporter.kt:158](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/data/storage/BackupExporter.kt#L158), [AppStorage.kt:99](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/data/storage/AppStorage.kt#L99), [TtsSessionStore.kt:68](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/tts/TtsSessionStore.kt#L68).

Full backup includes `ttsSession` but not `tts_positions.json`. Explicit stop intentionally clears the active session while preserving the per-story position. Those stopped stories lose their resume locations after a full restore, even though their chapter content returns.

Add an optional version-compatible TTS-position field to export, validation, and staging. On restore, retain positions only for valid stories/chapters and bound chunk indices when preparing playback. Older backups without positions should restore with an empty position map.

Round-trip two partially listened-to stories with playback stopped before export. Both should resume at their saved locations. Keep an older-backup compatibility case.

### R12. Turn recoverable operation failures into visible states

Evidence: [MainActivity.kt:125](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/app/MainActivity.kt#L125), [ReaderScreen.kt:95](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/feature/reader/ReaderScreen.kt#L95), [ReaderDocumentPreparer.kt:78](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/feature/reader/ReaderDocumentPreparer.kt#L78), [LibraryScreen.kt:65](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/feature/library/LibraryScreen.kt#L65).

Several `scope.launch` paths call fallible repository operations without a local error boundary. Backup parsing returns friendly messages for many failures, but repository refresh and other storage calls can still throw. Reader preparation returns null for a missing story/chapter and leaves the loading screen unchanged; a file-read exception is not handled at that screen boundary. Settings writes can also fail without a recoverable UI response.

Use a small shared operation wrapper for expected storage/network failures that rethrows cancellation, clears busy state in `finally`, logs the operation, and presents retry/back actions. Give Reader explicit ready, missing, and failed results. Guard delayed UI delivery by route or rendered-root identity so an operation finishing after navigation does not replace an unrelated screen. Do not blanket-catch VM errors or rely on a global handler to pretend a save succeeded.

Validate missing Reader IDs, an unreadable chapter, disk-full settings save, and refresh failure after import. No permanent spinner or unhandled recoverable exception should remain.

### R13. Cancel the actual source request and set a total request deadline

Evidence: [NetworkClient.kt:264](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/source/network/NetworkClient.kt#L264), [NetworkClient.kt:160](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/source/network/NetworkClient.kt#L160), [NetworkClient.kt:363](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/source/network/NetworkClient.kt#L363), [OpenRouterHttp.kt:20](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/ai/OpenRouterHttp.kt#L20)}.

Source requests run blocking `Call.execute()` inside `withContext(IO)`. Cancelling the coroutine does not by itself call `Call.cancel()`. The production client sets connect/read timeouts but no overall call timeout by default. A request can occupy a worker after its UI/service operation has been cancelled, and a response that keeps delivering data can outlive the intended operation budget.

Use a cancellation-aware OkHttp bridge, following the existing OpenRouter cancellation pattern, or another implementation that explicitly cancels the call. Apply deliberate total deadlines per request class while preserving the longer browser-challenge budget where needed. Check cancellation before cleanup and before durable download commit, in addition to R05's transaction guard. Avoid automatic replay of billable AI POSTs.

Use MockWebServer to stall headers, stream a slow body, and cancel mid-read. Verify the call closes, the worker slot becomes reusable, and no cancelled result is committed.

### R14. Do not cap a server retry deadline to the ordinary backoff delay

Evidence: [RetryBackoff.kt:16](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/source/network/RetryBackoff.kt#L16), [RetryBackoff.kt:27](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/source/network/RetryBackoff.kt#L27), [NetworkClient.kt:256](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/source/network/NetworkClient.kt#L256), [NetworkClient.kt:247](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/source/network/NetworkClient.kt#L247).

`retryAfterMillis()` has a separate maximum for server deadlines, but `delayFor()` subsequently clamps the result to `maximumRetryDelayMillis`. When the accepted `Retry-After` exceeds the ordinary retry cap, an intermediate retry can occur early. Shared source cooldown is recorded on the terminal HTTP failure rather than on each retryable response, so another operation can continue during the first operation's server-directed wait.

Separate server deadlines from client-generated backoff. Honor the accepted server deadline across the shared host coordinator as soon as the response arrives. If a deadline is beyond the operation's budget, defer the work rather than sleep for a shorter time and retry. Preserve the policy's explicit sanity limit for unreasonable headers.

Test numeric and HTTP-date headers longer than normal backoff and run two callers against the same host. Neither should request before the accepted deadline.

### R15. Make AI timeout and cancellation update the coordinator too

Evidence: [AiChapterRewriteJobCoordinator.kt:127](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/app/AiChapterRewriteJobCoordinator.kt#L127), [AiChapterRewriteJobCoordinator.kt:142](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/app/AiChapterRewriteJobCoordinator.kt#L142), [AiCoverJobCoordinator.kt:159](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/ai/AiCoverJobCoordinator.kt#L159), [AiChapterRewriteForegroundService.kt:95](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/ai/AiChapterRewriteForegroundService.kt#L95), [AiChapterPolishActions.kt:69](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/feature/ai/AiChapterPolishActions.kt#L69).

Both coordinators rethrow cancellation without releasing their registered job state in `finally`. Rewrite timeout stops the foreground service while the application-scope job and batch queue remain active. Service startup errors are logged, but enqueue has already started work. Queued/running state is memory-only, so process death loses accepted queue entries and leaves no record explaining an interrupted operation.

Retain Job handles; release slots in `finally` and distinguish user cancellation, timeout, failure, and completion. Timeout should block batch drain and cancel or checkpoint owned work before service shutdown. Make service-start failure observable to the enqueue flow. Persist a minimal queued/interrupted record if batch recovery is desired, but never blindly replay an AI request with an unknown billing outcome after process death.

Android's [foreground-service timeout documentation](https://developer.android.com/develop/background-work/services/fgs/timeout) describes a shared `dataSync` background time allowance. Individual rewrites being short does not guarantee that a long batch, or work after downloads, has time remaining.

Test cancellation during request and persistence, forced service-start failure, timeout with another chapter queued, and restart after an interrupted response. No stuck busy slot or silent continued batch should remain.

### R16. Give TTS initialization and chapter-transition failures a recovery path

Evidence: [TtsEngine.kt:481](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/tts/TtsEngine.kt#L481), [TtsEngine.kt:597](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/tts/TtsEngine.kt#L597), [TtsEngine.kt:625](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/tts/TtsEngine.kt#L625), [TtsPlaybackPreparation.kt:100](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/tts/TtsPlaybackPreparation.kt#L100), [TtsWatchdog.kt:18](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/tts/TtsWatchdog.kt#L18).

The playback watchdog starts after `speak()`, but initialization can remain pending before that point. At chapter end, a failed `nextChapter()` preparation is logged and converted to null, then treated as natural completion. Natural completion clears the saved position. An I/O/preparation failure should not have the same persistence outcome as reaching the end of the book.

Add a bounded initialization watchdog with retry and an explicit paused/error state. Represent chapter transition as next chapter, end of story, or failure. On failure, retain the resumable session and position; only true completion should clear them. Preserve the existing utterance-identity guards and audio-focus behavior.

Use a fake TTS adapter whose init callback never arrives, plus a next-chapter storage failure. The user should receive an actionable error and keep a valid resume position. Actual end-of-story completion should still clear it.

### R17. Mark generated output stale before cleanup can partially succeed

Evidence: [StoryContentActions.kt:60](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/feature/story/StoryContentActions.kt#L60), [StoryContentActions.kt:82](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/feature/story/StoryContentActions.kt#L82), [StoryMutations.kt:24](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/data/repository/StoryMutations.kt#L24).

Cleanup overwrites chapters individually but marks the EPUB stale only after the whole loop. Cancellation, process death, or a later failure can leave modified chapter files with an EPUB still marked current. The inner `runCatching` also catches cancellation, unlike the outer handler.

Persist the stale marker before the first possible chapter modification. Rethrow cancellation inside the per-chapter boundary and retain progress/error counts for partial completion. When practical, skip writes whose cleaned HTML equals the original. Do not clear the stale marker merely because a later step fails.

Interrupt after the first successful overwrite. On restart, the EPUB must be marked stale and the original source/cleanup behavior must otherwise match the chosen operation.

### R18. Separate WebView teardown from explicit cache clearing

Evidence: [WebViewSafety.kt:41](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/platform/WebViewSafety.kt#L41), [MainActivity.kt:282](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/app/MainActivity.kt#L282), [CloudflareWebViewSolver.kt:179](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/source/network/CloudflareWebViewSolver.kt#L179).

Normal Reader teardown calls `clearCache(true)`. WebView's resource cache is shared across the app, so leaving/rebuilding Reader also clears resources useful to source-access WebViews. The [WebView API reference](https://developer.android.com/reference/android/webkit/WebView#clearCache(boolean)) documents that shared behavior. Cookie state is separate; this finding does not claim that the call deletes clearance cookies.

Keep stop, detach, and destroy on normal teardown. Reserve cache clearing for the explicit source/session reset action. In the source renderer's `onRenderProcessGone`, explicitly dispose the unusable WebView instead of only setting the session reference to null. Keep cleanup steps independent enough that one cleanup exception cannot skip all remaining disposal.

Validate repeated Reader/settings rebuilds while source access is available. Verify normal teardown does not invoke global cache clearing, and a renderer-process failure permits creation of a clean replacement view.

### R19. Make regex disabling effective inside the already-created runner

Evidence: [RegexRuleCleanup.kt:28](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/cleanup/RegexRuleCleanup.kt#L28), [RegexRuleCleanup.kt:39](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/cleanup/RegexRuleCleanup.kt#L39), [RegexRuleCleanup.kt:165](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/cleanup/RegexRuleCleanup.kt#L165), [RegexCircuitBreaker.kt:17](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/cleanup/RegexCircuitBreaker.kt#L17), [CleanupEngine.kt:123](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/cleanup/CleanupEngine.kt#L123).

The download cleanup path rechecks the breaker before applying each cached rule. The Reader/TTS runner filters disabled rules only when constructing its closure, then keeps applying them to subsequent input even after that closure trips the breaker. Regex preview directly applies the pattern without that protection. The breaker can react only after matching returns, so it is not a hard timeout.

Add an `isDisabled` check inside the runner's per-rule application, reuse validated compiled rules, and bound editor preview input with computation off main. Keep heuristic validation and make skipped rules visible. If hard execution limits become necessary, evaluate a restricted regex syntax/engine or isolated execution separately; wrapping JVM regex in a coroutine timeout is not a dependable interruption mechanism.

Validate a runner created before a rule is disabled: later invocations must skip it. Test the editor with long input and a deliberately slow supported pattern using a bounded test process, never an unbounded main-thread test.

## Performance and remaining hardening

### R20. Remove repeated cold-start library parsing before adding a second persistent index

Evidence: [AppContainer.kt:107](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/app/AppContainer.kt#L107), [AppStorage.kt:302](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/data/storage/AppStorage.kt#L302), [SourceIdentityStorageMigration.kt:10](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/data/storage/SourceIdentityStorageMigration.kt#L10), [AppRepository.kt:161](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/data/repository/AppRepository.kt#L161).

Startup separately reads story JSON for path migration, reads the library for source-identity migration, and reads it again for hydration. These passes still scan on every startup even when no migration writes are needed. The branded loading screen already improves feedback, but it does not remove that work. Container construction also creates directories and sweeps backup temps before the asynchronous startup transaction.

Hydrate once on I/O, normalize/migrate the loaded records, persist only changed records, then publish that same normalized snapshot. Measure directory setup and move safe nonessential maintenance after readiness. If using a migration marker, handle old-format backup imports explicitly rather than assuming the marker covers all future files.

Record per-phase time and number of parsed story files for a large library. Only after this change is measured should a compact summary index be considered. A second persistent index brings invalidation and recovery work and should not be the first optimization.

### R21. Cut redundant work from normal story and queue mutations

Evidence: [AppStorage.kt:122](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/data/storage/AppStorage.kt#L122), [AppRepository.kt:409](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/data/repository/AppRepository.kt#L409), [AppRepository.kt:237](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/data/repository/AppRepository.kt#L237), [DownloadEngine.kt:318](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/download/DownloadEngine.kt#L318), [DownloadRequestGateFactory.kt:16](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/download/DownloadRequestGateFactory.kt#L16).

An existing-story update rereads the library index, rereads the story to preserve `dateAdded`, saves the whole story, and rewrites the unchanged index. Some callers have already read the same story under the lock. Queue progress/gating still read durable queue data, while repository state already contains snapshots. `library()` deep-copies every story and chapter through `StoryMutations.snapshot()`, even for screens that need summaries.

Do not rewrite the index when membership is unchanged. Pass the current record through internal transaction helpers to avoid rereading it. Use coherent cached queue/settings snapshots for observation and retain durable transactions for mutation. Add immutable story-summary snapshots for list screens rather than copying complete chapter arrays. Do not expose mutable cached models directly to callers as an optimization.

Count file reads, writes, and allocations for one bookmark, one completed chapter, and a queue-status refresh. Preserve cache/disk coherence and concurrent update tests. Start with the index-write elimination; it is the smallest part.

### R22. Recycle library cards and avoid rebuilding them on every keystroke

Evidence: [LibraryPagesAdapter.kt:59](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/feature/library/LibraryPagesAdapter.kt#L59), [LibraryPagesAdapter.kt:110](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/feature/library/LibraryPagesAdapter.kt#L110), [LibraryStoryViews.kt:58](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/feature/library/LibraryStoryViews.kt#L58), [LibraryScreen.kt:162](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/feature/library/LibraryScreen.kt#L162), [LibraryPagesAdapter.kt:115](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/feature/library/LibraryPagesAdapter.kt#L115).

The library uses a RecyclerView adapter for pager pages, but each page contains a ScrollView and a grid of all matching cards. The story cards themselves are not recycled. Search invalidates bound pages and rebuilds their grids immediately on each keystroke. Current observer updates patch progress only, so title/cover changes can also remain stale in already-bound cards until a later rebuild.

The small first step is to debounce search, skip equivalent filter states, and compute filtering away from main using a stable input snapshot. Then use a RecyclerView/GridLayoutManager for cards within each page, with immutable rows and content-aware updates. Include title, cover, membership, and order changes, not just progress. Preserve scroll and selected tabs.

Compare first-content time, allocations, and search responsiveness with hundreds of novels. Update a visible cover/title from a background completion and verify it changes without losing scroll position.

### R23. Move diff calculation off main and keep countdown work local

Evidence: [ChapterListAdapter.kt:106](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/feature/details/ChapterListAdapter.kt#L106), [QueueGroupAdapter.kt:105](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/feature/downloads/QueueGroupAdapter.kt#L105), [UpdatedItemsAdapter.kt:78](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/feature/updates/UpdatedItemsAdapter.kt#L78), [FollowStoryAdapter.kt:146](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/feature/updates/FollowStoryAdapter.kt#L146), [QueueScreen.kt:147](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/feature/downloads/QueueScreen.kt#L147), [QueueScreen.kt:117](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/feature/downloads/QueueScreen.kt#L117).

Adapters calculate diffs synchronously from UI update paths. Queue countdowns can rebuild grouped row data and summaries every second while timed status is present. These costs scale with list size even when only one visible countdown label changes. Screen observers use the Activity scope, so some work can continue while the Activity is stopped.

Use AsyncListDiffer/ListAdapter or a background diff with immutable inputs and a generation guard before dispatch. Keep ordinary progress/countdown changes as payload updates. Run the ticker only while the screen is started and timed rows exist, and patch visible countdown labels instead of reconstructing the full queue presentation.

Validate a large queue with one paced job and rapid updates. Backgrounding the screen should suspend UI tick work; returning should show current state. Verify no old diff is dispatched after a newer list replaces it.

### R24. Bound response memory and downsample generated-cover previews

Evidence: [NetworkClient.kt:85](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/source/network/NetworkClient.kt#L85), [OpenRouterHttp.kt:36](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/ai/OpenRouterHttp.kt#L36), [OpenRouterClient.kt:266](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/ai/OpenRouterClient.kt#L266), [AiCoverControls.kt:185](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/feature/ai/AiCoverControls.kt#L185).

Source HTML and OpenRouter JSON bodies are read fully without application-level byte caps. Image generation then holds the JSON/base64 string and decoded bytes; the UI decodes the complete bitmap synchronously. Existing source-cover downloads already have an 8 MB cap, which is useful, but that protection does not cover generated image JSON or bitmap dimensions.

Bound actual bytes read for each response type, including chunked responses and error bodies. Give image JSON a deliberately larger budget than text/catalog responses. Validate decoded byte count and image dimensions before allocation. Load the persisted preview through the existing Coil path or decode a sampled bitmap off main, sized for the preview. Retain full bytes only where applying/exporting needs them.

Validate oversized content-length, oversized chunked JSON, invalid base64, and an image with small compressed bytes but huge dimensions. Failure should be visible and should not allocate an unbounded bitmap on main. Do not infer speed or memory savings from file size alone.

### R25. Reuse the EPUB cover and read each chapter once per output pass

Evidence: [EpubEngine.kt:52](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/epub/EpubEngine.kt#L52), [EpubEngine.kt:67](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/epub/EpubEngine.kt#L67), [EpubEngine.kt:160](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/epub/EpubEngine.kt#L160), [EpubEngine.kt:142](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/epub/EpubEngine.kt#L142).

EPUB generation reads chapters to test availability, then reads them again while writing. It fetches or loads the same cover inside every volume iteration. Cover media type is inferred from the URL, so extensionless or transformed URLs can be mislabeled. If a previously available chapter becomes missing before writing, the writer substitutes an empty string.

Resolve the cover once per generation and reuse the bytes across volumes. Carry validated content type or detected image format with binary downloads. Plan availability from file metadata or a bounded per-volume input preparation, then stream each chapter without a full duplicate read. Keep memory bounded; do not cache an entire long novel's HTML. Treat a missing write-time chapter as an explicit failure or partial-output decision, not blank content. Retain generation-specific outputs until the full result is committed if old EPUBs must survive a partial regeneration.

Validate several volumes with one cover request, extensionless PNG cover data, and a chapter removed between planning and writing. Inspect the resulting ZIP/OPF and ensure no unannounced blank chapter appears.

### R26. Make rewrite reads asynchronous and reuse one manifest per operation

Evidence: [AiChapterPolishActions.kt:36](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/feature/ai/AiChapterPolishActions.kt#L36), [AppRepositoryRewrites.kt:74](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/data/repository/AppRepositoryRewrites.kt#L74), [AiChapterRewriteStore.kt:62](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/data/storage/AiChapterRewriteStore.kt#L62), [ChapterContentResolver.kt:68](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/feature/reader/ChapterContentResolver.kt#L68), [AppRepositoryRewrites.kt:107](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/data/repository/AppRepositoryRewrites.kt#L107).

The polish status index reads a manifest synchronously during UI construction. Reader resolution loads an applied record and then `appliedHtml()` reads the manifest again. Status changes can republish by performing an identity `updateStory`, rewriting unrelated story JSON just to notify observers. Rewrite reads also use their own store monitor rather than participating in the outer maintenance boundary.

Expose an asynchronous manifest snapshot or a small repository cache invalidated on write/restore. Pass the selected record to the HTML reader instead of reloading metadata. Publish a rewrite/content version without a no-op story write. Keep the read snapshot and selected content consistent with maintenance and R09's generation scheme.

Validate AI Controls with a large manifest under StrictMode and count parses for one Reader open. One operation should use one coherent manifest snapshot, and toggling a rewrite should not rewrite the complete story document.

### R27. Stabilize cover-draft JSON in minified builds

Evidence: [AiCoverDraftStore.kt:38](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/data/storage/AiCoverDraftStore.kt#L38), [AiCoverDraftStore.kt:91](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/data/storage/AiCoverDraftStore.kt#L91), [release keep rules](../../android/app/proguard-rules.pro), [release build configuration](../../android/app/build.gradle).

`DraftMeta` is outside the kept `domain.model` package, has no serialized field annotations, and is not named in the app's explicit keep rules. It is persisted through reflection. This is a release compatibility risk, not a reproduced release bug: dependency-supplied rules and the generated R8 mapping must be checked before concluding how this particular build transforms it.

Use explicit stable wire names and appropriate constructor/field retention, or serialize this tiny document explicitly. Verify old literal `prompt`/`mediaType` JSON remains readable across minified builds. The [Gson troubleshooting guide](https://google.github.io/gson/Troubleshooting.html) specifically covers release-only obfuscated-field and cross-version JSON problems.

Validate with an isolated minified test artifact and inspect its mapping; round-trip existing cover drafts across versions. The ordinary unminified debug gate cannot prove this. No release build or phone installation was performed for this review.

### R28. Save preference changes as transactions on the latest value

Evidence: [LibraryScreen.kt:107](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/feature/library/LibraryScreen.kt#L107), [LibraryScreen.kt:61](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/feature/library/LibraryScreen.kt#L61), [AppRepository.kt:346](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/data/repository/AppRepository.kt#L346), [AppRepository.kt:378](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/data/repository/AppRepository.kt#L378).

Callers commonly read a whole preference object, change one field, then asynchronously save the copy. The write is serialized, but the read/modify step occurs outside that transaction. Two rapid independent changes can both start from the same old object; the later save restores the earlier field's old value. Library tab and sort persistence provide a concrete pair of callers.

Add `updateDisplayPreferences { latest -> ... }` and equivalent narrow operations for settings changed independently. Read, normalize, persist, and update cache inside one repository transaction. For sliders, coalesce intermediate values and persist the final user selection. Keep deliberate whole-document replacement for import/default restoration.

Test two concurrent updates to different fields and two ordered updates to the same field. Independent changes must both survive; the latest same-field choice must win.

### R29. Bound the source caches and expose reliability-save failure

Evidence: [NetworkClient.kt:68](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/source/network/NetworkClient.kt#L68), [NetworkClient.kt:70](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/source/network/NetworkClient.kt#L70), [NetworkClient.kt:319](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/source/network/NetworkClient.kt#L319), [SourceReliabilityStore.kt:41](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/source/network/SourceReliabilityStore.kt#L41).

Reusable HTML entries have a count limit, but their per-key mutex map has no normal eviction. Prepared pages have an expiry timestamp but no capacity limit or periodic expiry cleanup; entries disappear only when consumed or when the network changes. A long session that prepares unused pages or visits many cache keys can retain unused HTML and locks. Reliability-state saves swallow all failures, leaving no diagnostic signal that a circuit will not survive restart.

Use explicit bounded cache ownership for HTML and per-key coalescing state. Remove expired prepared pages on insertion and cap retained bytes as well as count. Do not evict an in-use mutex and accidentally allow duplicate concurrent fetches for the same key. Return/log a failed reliability save with bounded retry or the next conflated persistence signal; this advisory document should not crash the app.

Validate thousands of unique keys and abandoned preflights with a fake clock. Retained entries/bytes should converge to limits. Inject a write error and verify one useful diagnostic signal plus eventual recovery when storage becomes writable.

### R30. Validate failure boundaries and measure the actual bottlenecks

Evidence: [unit tests](../../android/app/src/test/java/com/vinicius741/webnovelarchiver), [device tests](../../android/app/src/androidTest/java/com/vinicius741/webnovelarchiver), [LocalDiagnostics.kt:9](../../android/app/src/main/java/com/vinicius741/webnovelarchiver/data/diagnostics/LocalDiagnostics.kt#L9), [local quality gate](../../android/app/build.gradle).

The repository already has substantial pure-planning, provider-fixture, storage-failure, and TTS persistence coverage. The remaining risk is often between those units: a process dies between file commits, a user deletes an item while a network call runs, or a main-thread callback waits for a maintenance lock. Existing in-memory diagnostics store only time, priority, and throwable type, which makes different failures hard to distinguish after the fact.

Add a small set of integration tests around the boundaries named above, using injected dispatchers, clocks, file operations, and network barriers. Capture privacy-safe operation identifiers, failure categories, and durations for startup phases, maintenance, queue saves, and Reader preparation. Keep logs bounded and exclude chapter text, prompts, keys, and full authenticated URLs. Add persistent failure summaries only if needed for post-restart diagnosis.

Measure first real Library content, queue-action latency, Reader preparation, file reads/writes per chapter, allocation peaks, and repeated source requests on representative libraries. Report median and tail behavior separately; do not promise a target speedup before measurement. The current Detekt threshold is 45, and `:app:ci` already includes formatting, file-size checks, unit tests, analysis, lint, and debug assembly. Do not repeat the older recommendation about a 1,000-issue threshold as if it were current.

## Suggested implementation order

1. Start with R01–R04, R06, R08, R12, and R17. These are the most direct protections against UI blocking, partial-source commits, overwritten local choices, and misleading saved state. Pair R01 and R02 so queue controls become both asynchronous and efficient.
2. Add R05 and R09 before changing maintenance locking or allowing more background concurrency. Add R15 before promising recoverable AI batches. Treat R07 as a separate recovery change with process-death tests.
3. Take the small performance wins in R18, R19, R21, R25, R26, R28, and R29. Check R27's minified serialization risk early. R10 and R11 belong in the next backup-focused change.
4. Measure startup/list rendering, then implement R20, R22, and R23. Apply R13, R14, R16, and R24 with their failure tests rather than as an unverified global tuning pass. Use R30 throughout.

Do not start with a Room migration, a new UI framework, a Media3 migration, or higher request concurrency. The present architecture has useful boundaries and many protections already in place. Removing repeated work and closing failure gaps should come before those larger decisions.

## Existing safeguards to preserve

- One process container, repository-owned transactions, coherent download snapshots, and a single service-owned download loop.
- Durable library JSON with typed read outcomes, corrupt-file quarantine, and write fences.
- ZIP entry/path/size validation, staged restore, and tested rollback on caught file-operation failures.
- Reader and TTS preparation already split between I/O/computation and main-thread rendering/playback.
- Debounced TTS persistence, audio-focus handling, noisy-audio handling, and utterance identity guards.
- Shared source pacing, persisted circuit state, typed network errors, latest-only merge support, and provider fixture tests.
- Streamed EPUB ZIP output, backup progress/results, orphan backup-temp cleanup, Coil for ordinary covers, and recycled chapter/queue/update rows.
- AI source chapters remain separate from polished variants, and completed drafts are persisted before success is announced.

These protections narrow the remaining work. For example, restore refresh already exists in repository import methods, ordinary enqueue already runs off main, and the Following Review list already uses RecyclerView.

## Validation for future implementation

Each recommendation includes a specific acceptance scenario. For a later implementation, run the narrow relevant tests first, then the required repository gates:

```sh
android/gradlew -p android :app:testInstrumentationUnitTest --tests 'com.vinicius741.webnovelarchiver.data.repository.AppRepositoryTest'
android/gradlew -p android :app:lintKotlin :app:detekt
# For broad or cross-cutting implementation changes:
android/gradlew -p android :app:lintKotlin :app:ci
```

Adjust the targeted class to the changed subsystem. UI/service/lifecycle changes also require affected-flow verification on `webnovel_api36` using the repository's emulator skills. Use the isolated instrumentation variant for destructive fixtures; preserve the user's debug library. Release/minification validation needs an explicitly requested artifact workflow and must not use the owner's phone by default. AI tests should use fakes/MockWebServer unless a paid call is separately intended.

For this documentation-only review, validation consisted of source/caller inspection, test inventory inspection, official API documentation checks, source-link validation, and documentation diff checks. No Gradle build, unit-test execution, benchmark, live source request, AI generation, emulator run, or phone operation was performed. Recommendations describe remaining work, not completed fixes.
