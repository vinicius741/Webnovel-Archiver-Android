# App simplification implementation handoff

Date: 2026-09-07. Status: proposed; no implementation performed for this document.

The objective is to preserve the app's functionality while reducing duplicated code, state-management work, and the number of controls a user must consider at once. This handoff expands the five recommendations from the read-only simplification investigation. It is not a request to remove features or rewrite the app.

The evidence comes from the current working tree, including existing uncommitted changes. No emulator walkthrough, build, or tests were performed for this investigation. Re-read the named functions before editing; line numbers and behavior may have changed by the time implementation starts. New filenames and test names below are proposals, not claims that those files exist.

## Start here

1. Read root `AGENTS.md` and `android/AGENTS.md`, then any closer instructions. Work in the current branch. Do not create branches, commit, push, or open PRs without a new explicit request.
2. Run `git status --short` and inspect existing diffs for every file you intend to touch. The investigation found ongoing edits in AI cover generation, library state, models, sync, and navigation. Preserve them. Do not restore files to HEAD to obtain a clean starting point.
3. Read [the reliability review](performance-reliability-review-2026-09-04.md), particularly its implementation and merge-review notes. Several seemingly verbose guards protect against previously found data loss and lifecycle bugs.
4. Implement one phase at a time in the order below. Finish its checks before proceeding. If a candidate adds more concepts than it removes, document that result and retain the existing design.
5. Keep a per-phase record in this document: files changed, old code removed, behavior preserved, exact commands and results, emulator evidence, remaining limitations. Do not mark a phase done based on compilation alone.

All Kotlin paths in this document are relative to `android/app/src/main/java/com/vinicius741/webnovelarchiver/`. Unit tests mirror those packages under `android/app/src/test/java/com/vinicius741/webnovelarchiver/`.

| Order | Phase | Intended result | Risk |
|---|---|---|---|
| 1 | S1: cover controls | One cover display selector and fewer always-visible advanced controls | Low to medium |
| 2 | S2: manual sync | One shared fetch-and-plan sequence with separate presentation callbacks | Medium |
| 3 | S3: AI background plumbing | Shared notification construction; further sharing only where semantics match | Medium |
| 4 | S4: screen state and updates | Routine AI progress updates no longer rebuild the screen | Medium to high |
| 5 | S5: immutable snapshots | Fewer manual defensive-copy responsibilities, if a bounded pilot proves worthwhile | High; conditional |

## Rules that apply to every phase

- Keep native programmatic Android Views, `AppContainer`, `AppRepository`, existing route identities, and file-based JSON storage. No Compose, DI framework, database migration, generic job framework, or new dependency is required by this plan.
- Preserve source chapters, downloaded files, archive IDs, JSON field names, backups, generated-cover versions, AI usage receipts, and read/TTS positions.
- Keep repository transaction locks, library-generation invalidation, existence checks during result persistence, and recovery/rollback behavior. A deleted story must not reappear when an old operation finishes.
- Preserve explicit preview/apply behavior. Generating a cover or polished chapter must not automatically replace the active content.
- Never automatically retry an AI request whose billing outcome is unknown.
- Keep source pacing, Cloudflare transport, provider capabilities, download persistence, TTS media-session behavior, and `feature/reader/ChapterContentResolver.kt` intact.
- Do not equate splitting files with simplifying behavior. The 400-line budget still applies, but a wrapper that merely moves every existing branch elsewhere is not a meaningful reduction.
- Do not remove compatibility-only fields such as `foldLayoutMode` as incidental cleanup. Its presence in exports and normalization needs a separate compatibility decision.

## S1: simplify cover controls

### Evidence and files

Read `feature/ai/AiCoverControls.kt`, especially `addAiCoverCard`, `addAiCoverDisplayToggleRow`, `addAiCoverModeRow`, and `revertAiCover`. Also read `AiCoverGeneration.kt`, the context picker, saved-version UI, `AiControlsScreen.kt`, and [the cover feature documentation](../ai/ai-cover-generation.md).

Today, disabling “Show AI cover” and choosing “Use source cover” both save `showAiCover = false`. The card also exposes generation mode, chapter selection, custom prompts, previews, and saved versions together. These are separate capabilities, but they do not all need equal visual emphasis.

### Exact changes

1. Replace the checkbox and redundant source-cover button with one clearly labeled “Displayed cover” selector offering Source and Generated. Use existing themed components. Both choices must call `repository.setShowAiCover`; do not delete or rewrite images.
2. Preserve current availability rules. Offer the selector when both source and generated covers exist and the story is mutable. With one cover, show its current state without offering an unavailable choice. Archived snapshots remain read-only. Re-read current guards before changing them.
3. Keep the primary generation action visible. Put context-chapter selection, the existing one-step preference, and “Write your own prompt” inside an expandable “Generation options” section.
4. Preserve `AiSettings.coverOneStep` and its current persistence scope. Moving its control must not reset it. In staged mode, keep the primary label “Generate Prompt with AI” or its existing regeneration equivalent; do not label a prompt-only call “Generate cover.”
5. Keep generated prompt drafts, image previews, Apply/Use actions, costs, and errors visible when present. They must not disappear behind a collapsed options section after a job completes.
6. Keep saved versions reachable with a labeled count or expandable list. Selecting a version, comparing it, reusing its prompt, and applying it must remain possible. Closing a preview must retain saved covers exactly as today.
7. Store expansion state as lightweight UI state, scoped to the story. It must survive a progress refresh and a trip to Details and back during the same activity lifetime. Do not add fields to persisted `Story` or `AiSettings` merely to remember disclosure state.
8. Preserve existing billable-call confirmations and disabled/busy rules. Do not change model selection, automatic context sampling, or independent cover/description chapter choices.
9. Remove the unused old display-row helper and redundant button after all callers are migrated. Update cover documentation and any changed user-facing overview wording.

### Verification

Use fixtures or existing emulator data for source-only, generated-only, both-cover, archived, no-downloaded-chapter, prompt-draft, image-draft, and running-job states. Check that the selector persists after reopening, all generation options remain reachable, and collapsing options never loses a draft or an edited prompt.

Exercise one-step and staged entry paths without requiring successful paid generation for every case. Reuse saved drafts for preview/apply checks. Verify “Close preview” preserves the version and choosing Source preserves the generated image. Check compact and expanded widths, scrolling, and accessibility labels/selected state.

Run existing `AiCoverPlanningTest`, `AiControlsScreenStateTest`, `AiCoverDraftStoreTest`, and `AiCoverVersionStoreTest`. Add unit tests only for new state/decision logic; do not test a button-label function solely by repeating its implementation. Add a targeted device test if needed to verify actual selection persistence and disclosure behavior.

Acceptance: every old capability is reachable, exactly one cover-source selection control remains, and opening generation options is never necessary to see or apply a newly produced result.

## S2: consolidate manual sync orchestration

### Evidence and files

`feature/story/StoryActions.kt` contains two `syncStory` overloads, one accepting a URL and one accepting a `Story`. Both perform a pre-sync story lookup, call `StorySyncEngine.fetchOrSync`, calculate `SyncDownloadPlanning.plan`, and handle cancellation/errors. Read `SyncDownloadActions.kt`, `SyncDownloadPlanning.kt`, and the callers before extracting code.

### Exact changes

1. Add a small internal operation in `feature/story/ManualStorySync.kt`, or a similarly focused file. It should accept URL, tab ID, sync mode, and progress callback, and return the synced story plus `SyncDownloadPlan`.
2. Move only the common pre-sync lookup, `fetchOrSync`, and download-plan calculation into it. Keep the lookup before the fetch, since the plan distinguishes newly discovered chapters from an older cancelled or failed backlog.
3. Keep I/O on the I/O dispatcher. The shared operation must not reference Views, `frame`, `activeStory`, dialogs, or navigation. Progress callbacks must be documented as potentially arriving off the main thread; the UI wrappers retain main-thread dispatch.
4. Keep both current `syncStory` entry points as thin presentation wrappers. URL validation, archive guards, showing Details, full-screen Working, inline progress, and the existing callback defaults remain in these wrappers.
5. Preserve the caller-supplied `onStatus`, `onDone`, and `onError` callbacks for URL-based import. Do not replace them with hard-coded navigation.
6. Preserve completion order. URL import calls `onDone` before handling the download plan. Existing-story sync clears its operation, shows the synced Details, then handles downloads. Each path handles the plan exactly once.
7. Let the shared operation propagate errors and `CancellationException`. Keep source-access-blocked dialogs and retry closures in each UI wrapper. A retry must retain URL/tab/mode/callbacks and require the existing user interaction; do not add silent retry loops.
8. Do not merge bulk Updates syncing into this operation. Its batching and metadata-refresh policy differ. Do not change `StorySyncEngine` merge rules in this phase.
9. For tests, inject narrow suspend functions or an existing suitable dependency boundary. Avoid introducing interfaces around every repository and engine method simply to test this extraction.

### Verification

Create `ManualStorySyncTest` with a fake fetch and recorded calls. Check pre-sync lookup precedes mutation, mode/tab/URL are passed unchanged, the returned plan uses the pre-fetch snapshot, progress arrives, ordinary errors propagate, and cancellation prevents completion/download handling.

Cover new import, existing novel with new chapters, unchanged novel, prior failed/cancelled backlog, full sync, and missing provider. Reuse `SyncDownloadPlanningTest` for AUTO_QUEUE/REVIEW/NONE thresholds rather than duplicating its entire algorithm. Keep `StorySyncMergePlanningTest` passing.

On the emulator, cold-start Add Story and Details separately. Verify URL import progress and error presentation, existing-story inline sync, Full Sync, archive restrictions, and the large-update review dialog. A failed sync must not queue chapters. Do not trigger large live downloads to test threshold logic; use isolated fixtures for that case.

Acceptance: one shared fetch-and-plan sequence, unchanged UI behavior for both entry points, and no change to automatic follow-update policy.

## S3: share AI background plumbing conservatively

### Evidence and files

Read `ai/AiCoverForegroundService.kt`, `ai/AiChapterRewriteForegroundService.kt`, `ai/AiCoverJobCoordinator.kt`, `app/AiChapterRewriteJobCoordinator.kt`, both `app/*JobUiBridge.kt` files, and their startup call sites. The chapter coordinator is under `app/`, not `ai/`.

The two services repeat notification building and lifecycle scaffolding. Their coordinators share a broad shape but have important differences:

| Contract | Cover | Chapter polish |
|---|---|---|
| Accepted work | One active cover job, no batch queue | One active chapter plus sequential queue |
| Partial result | One-step prompt can persist before image failure | Draft carries ready/blocked/verify_failed status |
| Timeout | Stops service; existing application-scope call can continue | Cancels active work and clears queue before service shutdown |
| Start failure | Current start method logs; generation may continue | Returns Boolean so enqueue UI can surface failure |
| UI completion | Rehydrates cover/prompt preview | May navigate to comparison from matching Reader or AI Controls |

These describe current code, not endorsements of every policy. Preserve them during simplification. Any policy change requires separate justification and tests.

### Exact changes

1. Start with a composed helper such as `ai/AiJobNotifications.kt`. Share construction of ongoing and terminal notifications using explicit title, body, channel, notification ID, request code, and PendingIntent inputs.
2. Preserve distinct notification IDs: cover 1003/1004 and chapter polish 1005/1006 at this baseline. Preserve request codes, channel, intent flags, progress style, queued-count text, and completion wording. Do not accidentally make one feature replace the other's notification.
3. Leave Android permission checks adjacent to `notify` calls unless Android lint proves a shared boundary is understood. Existing comments explicitly explain these guards are inlined for lint.
4. Keep separate concrete Service classes, manifest entries, start methods, timeout handlers, and queue-idle decisions. Both must still call `startForeground` even when start arrives after the job already finished, then stop correctly.
5. After notification sharing, measure the remaining duplication. Extract a small lifecycle helper only if the same operations occur in the same order. Prefer composition; do not create a base Service with a long list of overridable hooks.
6. Do not combine the coordinators merely because both expose jobs/events. Preserve persistence before success events, deleted-story checks, cancellation slot release, and the chapter queue's atomic handoff. Cover prompt recovery must not be treated as a complete image result.
7. If a genuinely common coordinator helper remains useful, cover both callers with deterministic tests before extracting it. A shared helper must remove duplicated branches and have fewer policies than the old code. Otherwise finish this phase with notification sharing and record why coordinator merging was rejected.
8. Do not move AI description generation into a foreground coordinator here. Its current scope and transient drafts differ; changing them expands behavior and deserves a separate feature decision.

### Verification

Use fake engines/persistence and a test coroutine scheduler. Add coordinator tests where missing. Cover success, failure, cancellation, duplicate enqueue, deletion during generation, persistence failure, one-step prompt success followed by image failure, and chapter queue transitions. A queued chapter must not disappear during handoff; cancelling all must not launch the next one. A terminal event must never announce a result before persistence finishes.

Test service behavior for immediate idle start, progress, completion, denied notification permission, start failure, and timeout. Use instrumentation or an existing Android test mechanism for actual service/notification behavior; pure unit tests cannot prove Android lifecycle behavior. Simulate timeout through a test seam rather than waiting hours.

Check notification coexistence using synthetic jobs, then background/foreground and activity recreation. Force-stop is a separate test: application-scope jobs do not survive process death. After restart, only already persisted results are expected to reappear; no paid request should replay automatically.

Acceptance: shared notification construction, retained per-feature policies, no new universal job framework, and explicit evidence for all lifecycle code that changed.

## S4: reduce rebuilding and clarify screen-state ownership

### Evidence and files

Read `navigation/ScreenHost.kt`, `app/MainActivity.kt`, `ui/Scaffold.kt`, `feature/ai/AiControlsScreen.kt`, both AI job UI bridges, and `feature/downloads/QueueScreen.kt`.

`patchAiDraftProgress` rebuilds AI Controls on progress messages. Both AI bridges can also rebuild it for message changes. `ScreenHost` holds activity-lifetime data and direct references to views. The Queue already patches much of its content and deliberately rebuilds synchronously for action changes; do not replace that tested behavior with deferred posts.

### Exact changes

1. Start with AI Controls only. Inventory its state in three categories: persisted repository data, activity-lifetime drafts/choices, and view references that are valid only for the currently attached screen.
2. Move feature-owned state definitions out of the navigation file into the relevant feature package, keeping ownership and lifetime explicit. Merely moving declarations is preparatory; it is not the substantive simplification.
3. Add an AI Controls view binding that holds progress labels/bars and controls requiring busy-state updates. Keep it on the UI owner only, with a route/story identity and an attached-root check. Do not put Views in `AppContainer`, a repository, or a background coordinator.
4. Build the screen for navigation and structural changes. Patch text/progress for message-only changes. Switching idle/running state must update buttons as well as the message; do not leave a stale enabled action.
5. Let prompt arrival, draft arrival, queue structure changes, and terminal outcomes rebuild the relevant content or screen initially. Do not try to implement a general diff engine in this phase.
6. Make `patchAiDraftProgress` and both bridges call the same targeted update entry point. If the binding is absent or belongs to another route, update state without touching an old view or navigating the user.
7. Explicitly dispose/null the binding when `screen()` tears down the outgoing tree and on activity destruction. Cancel only view-owned collectors there. AI bridge collectors remain activity-owned, and background jobs remain application-owned.
8. Preserve `AppRoute.stableKey`, scroll restoration, back-stack behavior, `onScreenBuilt`, mini-player attachment, fold re-rendering, and WebView disposal. Keep description drafts transient and cover drafts persisted as today.
9. Once AI Controls passes, inspect other screens individually for the same narrow pattern. Do not create new controllers for screens that do not need them, and do not rewrite Library state that has recently been fixed just for consistency.
10. Retain the existing shared operation-slot gating initially. Multiple AI jobs have feature-specific state while `storyOperation` is a presentation/busy slot; replacing it with a global scheduler would change concurrency semantics.

### Verification

With synthetic progress, emit many messages while the user scrolls or edits an available field. Assert the attached root remains the same for message-only updates, progress changes, typed content stays intact, and no extra observers accumulate. This is an appropriate device test because the requirement concerns actual Views.

Navigate to another story and send a late update. It must not modify the new screen or navigate away. Recreate the activity during a cover job; reconnect to coordinator state and rehydrate persisted results. Test prompt arrival then image failure, queue drain to empty, and terminal-event/idle-state emissions in either order. No “Generating” or “Polishing” state may remain stuck.

Run `AiControlsScreenStateTest`, `AppRouteTest`, and relevant library state tests if shared saved-state code changes. Exercise app-bar and system Back, compact/expanded widths, rotation, theme recreation, mini-player visibility, Reader/TTS, and Queue pause/resume if scaffold behavior changes.

Acceptance: routine AI progress no longer calls `showAiControls`, detached Views receive no updates, existing navigation behavior remains, and state survives exactly the lifetimes it did before this phase.

## S5: immutable snapshots, conditional pilot

### Evidence and scope

`domain/model/Models.kt` uses mutable Story, Chapter, and nested collections. `data/repository/StoryMutations.snapshot` manually copies chapters, EPUB paths, tags, source state, and nested metadata. `AppRepository.library()` and `story()` return defensive snapshots.

This is a possible larger simplification, not a ready mechanical conversion. Kotlin `val` does not make a nested mutable collection immutable, and `List` alone does not guarantee ownership of its backing collection. Removing defensive copies prematurely can expose repository state to callers.

### Exact investigation and pilot steps

1. Inventory all assignments to Story/Chapter fields, collection mutations, `.copy()` calls, Gson serialization, normalization, archive creation, sync merges, repository transactions, and backup import/export. Use `rg` and compiler errors together; text searches alone cannot prove all mutation paths are covered.
2. Add or strengthen characterization tests around repository snapshot isolation and serialization before touching model declarations. Include the recently added independent cover-context selection fields. Do not assume the existing copy helper covers every new nested field.
3. Pilot one bounded nested value, such as chapter metadata, through its actual consumers. Prefer conversion of existing types where compatible. Do not introduce a second complete Story hierarchy with a permanent mapping layer unless that demonstrably reduces total complexity.
4. Use explicit copying at mutation boundaries and owned collections. Preserve all serialized names, defaults, null handling, and old-input normalization. Do not rename persisted fields or change file formats.
5. Reuse representative legacy JSON fixtures. Check missing optional fields, populated nested metadata, archives, cover state, context selections, and EPUB paths. Test semantic round trips and preserved field names, not byte equality of JSON key order.
6. Remove a defensive-copy path only after proving all nested values reachable through that path cannot mutate the repository snapshot. Keep transaction locks and generation checks; immutability does not replace write ordering or stale-result protection.
7. Compare the pilot's production code, mapping helpers, and changed call sites. Continue only if it removes copying/mutation responsibilities without creating parallel models or broad adapters. Otherwise revert only the pilot's own edits and document the finding. Do not revert unrelated work.
8. If proceeding, migrate one complete dependency slice at a time, then run the full gate. Do not leave half-mutable nested data advertised as immutable.

### Verification

Extend `StoryMutationsTest` and `AppRepositoryTest`: mutate every mutable nested value in a returned snapshot and assert repository state is unchanged. For migrated immutable types, verify incoming mutable collections cannot later change stored values, and updating one story does not change an older published snapshot.

Run repository, normalization, sync-merge, archive, and backup suites. Verify concurrent bookmark/AI-setting edits are preserved while sync completes, deleted stories stay deleted, and restore invalidates in-flight work.

Perform JSON import/export and full ZIP restore round trips only in the isolated instrumentation sandbox with fixture data. Compare IDs, chapter ordering/counts, chapter file hashes, AI versions, preferences, and resume positions. Never clear or restore over the user's debug library for this test. Preserve recovery failure tests and quarantine behavior.

Acceptance: unchanged persisted schema and behavior, complete snapshot isolation, and a demonstrated reduction in copying responsibilities. If the pilot fails that test, leave S5 deferred with evidence. That is an acceptable outcome, not unfinished mandatory migration.

## Validation commands and device procedure

Run commands from the repository root. Test filters below use the existing unit-test task for the isolated instrumentation variant; they are local JVM tests, not device tests.

Example focused checks, selecting the classes appropriate to the phase:

```sh
android/gradlew -p android :app:testInstrumentationUnitTest --tests '*.AiCoverPlanningTest' --tests '*.AiControlsScreenStateTest' --tests '*.AiCoverDraftStoreTest' --tests '*.AiCoverVersionStoreTest'
android/gradlew -p android :app:testInstrumentationUnitTest --tests '*.SyncDownloadPlanningTest' --tests '*.StorySyncMergePlanningTest'
android/gradlew -p android :app:testInstrumentationUnitTest --tests '*.StoryMutationsTest' --tests '*.AppRepositoryTest'
android/gradlew -p android :app:lintKotlin :app:detekt
```

Run newly added tests explicitly as well. For broad changes and final acceptance:

```sh
android/gradlew -p android :app:lintKotlin :app:ci
```

Read `.agents/skills/build-and-install-apk/SKILL.md`, `.agents/skills/dev-launch-screen/SKILL.md`, and `.agents/skills/emulator-qa/SKILL.md` when doing the relevant device work. Use the emulator skill's current setup and target-resolution steps.

- Use `webnovel_api36` and the debug app `com.vinicius741.webnovelarchiver.nativeapp.debug` for manual checks. Install the raw `android/app/build/outputs/apk/debug/app-debug.apk`, never a possibly stale mirror.
- Use unqualified `adb devices -l` only for discovery. Resolve a healthy `emulator-` serial and verify its AVD name with qualified ADB. If none is running, follow the emulator-start workflow; if multiple are running without a selected target, stop instead of guessing. Never use a phone.
- For every device-targeting ADB operation, use `adb -s "$EMULATOR_SERIAL" ...`. Cold-start through `dev_start_screen` for `aicontrols`, `addstory`, `details`, `library`, `queue`, and `reader`, supplying explicit story/chapter IDs when relevant. Discover those IDs with the existing dev-library helper.
- Cold-starting force-stops the app. Use it to land on the target before a lifecycle test, not midway through a test intended to prove activity recreation or background continuity. These are different lifecycle events.
- Wait for real content to settle. Capture screenshots and UI hierarchy for relevant before/after states, and inspect crash logs. A loading screen is not evidence of a working feature.
- Device tests must run as `com.vinicius741.webnovelarchiver.nativeapp.instrumentation`. A typical connected run is `ANDROID_SERIAL="$EMULATOR_SERIAL" android/gradlew -p android :app:connectedInstrumentationAndroidTest` after validating the serial and current task availability. Test code must not reference the debug package's data directory.
- Use fakes/local fixtures for AI failure, cancellation, timeout, queue, and race tests. Real AI QA costs money: at most 1–2 real calls per session using the cheapest suitable configured model. Never spend credits to exhaustively test lifecycle combinations; never expose API keys in logs or fixtures.
- Do not build a release artifact or use a physical phone without a current explicit request. If a model change requires minified serialization validation, report it as outstanding when release validation is not authorized; do not claim debug round trips prove R8 behavior.

After S4 or a completed S5 migration, run the full emulator QA workflow because their effects cross screens. Include Library search/tabs/scroll restoration, Details, Reader/TTS, Updates, Queue, Settings, AI Controls, and EPUB generation. Keep destructive restore tests isolated as described above.

## Completion checklist for the implementing agent

- [ ] S1: all cover capabilities preserved; redundant selector removed; disclosure tested.
- [ ] S2: one manual fetch-and-plan operation; both presentation paths tested.
- [ ] S3: notification sharing complete; feature-specific service/queue semantics retained; any rejected further consolidation explained.
- [ ] S4: AI message updates patch live controls; lifecycle/navigation regression checks pass.
- [ ] S5: pilot completed and either safely extended or explicitly deferred with evidence.
- [ ] Relevant tests, lint, Detekt, and final full gate pass, or exact blockers are recorded.
- [ ] Required emulator checks have screenshots and observed outcomes, not only build logs.
- [ ] Documentation matches resulting behavior; `docs/README.md` and root README updated where applicable.
- [ ] Existing unrelated worktree changes remain intact.

The final implementation report should identify the duplicated code or state rules actually removed, the behavior checks performed, and anything intentionally retained. Do not claim a percentage reduction from file counts, or describe the entire plan as implemented when the conditional model migration was deferred.
