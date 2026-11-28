# Architecture plan (2026-11-21)

Status: **implemented through Phase 3 on branch `refactor/screen-architecture`** (the owner approved it
on 2026-11-21). "Before" numbers were measured on `main` at `fb6e4d8`. The outcome and the remaining
work are under "Result" at the end.
It replaces Phases 4–6 of `simplification-roadmap-2026-10-06.md`. Phases 1–3 of that roadmap are done.

Draft by Claude Opus 5.5. Reviewed independently by Gemini 4 Neon and Claude Fable 5.2, both of which
checked the claims against the code. This document includes their corrections.

## Verdict

**Worth doing at reduced scope. A rewrite is not worth doing.** All three reviews agree.

| Reviewer | Worth it? | Main point |
|---|---|---|
| Opus 5.5 (draft) | Yes, targeted | The screen layer is the missing piece; everything else works |
| Gemini 4 Neon | Yes, reduced scope | Do guardrails and screen models with View renderers; cut Compose, data-layer rework, Media3, modules |
| Fable 5.2 | Yes, reduced scope | Same cuts. Pilot on Details, add a stop decision after it, count the cost in screens that need owner QA |

The code below the screens is mostly layered:

- Engines (`sync/`, `download/`, `epub/`, `narration/`, `source/`, `ai/`) depend downward on `domain/`
  and `data/`.
- `domain/` has no Android imports.
- Rules live in pure, tested planning functions.
- One repository owns library and queue writes.
- Restore crash recovery has a test that interrupts it at every filesystem step.

Keep all of that.

What is missing is a **screen layer**:

- Every screen is `internal fun ScreenHost.showXxx()`, an extension on an interface that `MainActivity`
  implements. There are 448 of them, up from 248 on 2026-10-06.
- Screen state is stored as fields on the activity: `libraryScreenState`, `updatesSyncState`,
  `aiControlsScreenState`, `storyExpandOverride`, `storyOperation`, `aiOperation`,
  `detailsOperationSlot`, `rerender`, `screenObserver`, and others.
- Screens rebuild their View tree, then patch captured view "slots" in place, guarded by
  `root.parent !== frame` checks (18 sites).
- Orchestration runs in UI code. `feature/` contains 114 `scope.launch`, about 57 direct repository
  mutations, 48 engine calls, and about 90 `app.appContainer` lookups.
- The shared scaffold knows about one feature: `ui/Scaffold.kt:60` clears AI Controls state.

As a result, screen logic cannot be tested on the JVM. `feature/` has about 3k test lines for 29.5k
production lines, mostly on planning functions. Each new feature adds activity state and another patch path.

The second problem is the **instruction surface for agents**. `android/AGENTS.md` is 55 KB, loads into
every session, describes implementation history in detail, and is edited in roughly a third to half of
recent commits.

### Cost of not doing it

The reviewers disagree on how fast this gets worse:

- Gemini expects the growth to compound.
- Fable notes that ScreenHost extensions per thousand `feature/` lines went from 13.7 to 15.2, which is
  close to linear.

Either way the problems persist. Screen logic stays untestable, cross-screen state keeps landing on
`MainActivity`, and every agent session keeps loading 55 KB of narrative.

### Main risks

1. **Repeating the v3 polish phase.** The UI was rebuilt for v3 on 2026-11-12. Migrating screens could
   restart a long tail of layout fixes. Mitigation: view code moves, but layouts do not change.
2. **Owner QA is the bottleneck.** Each migrated screen needs fold QA on the cover and inner displays.
   For that reason, effort is counted in screens below, not weeks.
3. **Conflicts with feature work.** Threads share one checkout while features land at about 40 commits
   a week. Mitigation: one screen per change series, short-lived, in a worktree when a feature
   thread is active.
4. **Stalling half-done.** The October Phase 4 was decided and never started. Mitigation: ratchets
   stop the old pattern from growing, and a stop-or-continue gate follows the pilot.

## Decisions

| Question | Decision | Why |
|---|---|---|
| Rendering | **View renderers bound from screen state.** No Compose in this program | The bugs come from state ownership, not the toolkit. Adding Compose would run two design systems and two widget sets for months, nine days after v3. The Reader WebView, charts, drag-reorder, AppShell and `DestinationMotion` all need interop. The perf suite runs debug builds, which would misjudge Compose. Lists use `ListAdapter`/`DiffUtil` over immutable row models |
| Compose later? | Possible, as a separate decision measured on a release build | The screen-model layer keeps the option open |
| Immutable `Story`/`Chapter` | **Deferred** | `StoryNormalization.coerceDefaults` changes Gson-loaded stories in place to fill runtime nulls, and the R8/no-arg-constructor rules have caused release-only crashes before. Gain is low compared with that risk |
| Split `AppRepository` | **No.** Narrow only the `storage` escape hatch (~6 call sites) | It is the most stable, best-tested layer. 24 of its 35 extensions already live in `AppRepository.kt` |
| Room | No | Unchanged from 2026-10-06. Storage is not where the bugs are |
| DI framework | No | `AppContainer` plus constructor injection is fine |
| Media3 | Deferred until Android Auto or playback bugs justify it | Agrees with `../tts/tts-media3-migration-evaluation.md` |
| Gradle modules | No | Source-scan checks give most of the boundary value without Gradle, lockfile and R8 overhead |
| Package-by-feature mass move | No | Churn without behavior gain |

## Target shape

```
app/          MainActivity (lifecycle + shell only), AppContainer (manual DI), services
navigation/   AppRoute, AppNavigator, RouteViewMemory, ScreenModelStore (new)
operations/   StoryOperations: sync, cleanup, EPUB, AI starts, with typed StateFlow progress (new)
feature/<x>/  XxxScreenModel  plain Kotlin; constructor deps; StateFlow<XxxUiState>; action methods
              XxxScreen       renderer: state -> Views, events -> model; launches no business work
              XxxPlanning     pure rules (non-trivial only, unchanged convention)
engines       sync/ download/ epub/ narration/ tts/ source/ ai/ (unchanged)
data/ domain/ unchanged
```

Rules for screens:

- A screen model never touches `View`/`Context`.
- A renderer never calls repositories or engines directly.
- Cross-screen work goes through `operations/`.
- App-wide chrome (mini player, AI job chip, nav badges) gets its own app-level models instead of
  reading screen state.

## Plan

### Phase 0 — Guardrails (≈2–3 agent days, low risk, no UI change)

- [x] **0.1 Slim `android/AGENTS.md` to ≤ 15 KB.** (`8ea8f02`)
  - Keep rules and a one-line-per-package map.
  - Move subsystem detail into per-package `AGENTS.md` files (`tts/`, `narration/`, `source/`,
    `download/`) so it loads only when those files are touched, or into `docs/<subject>/`.
  - Add the rule: AGENTS.md states current rules, never history.
- [x] **0.2 Architecture tests as JUnit source scans** (`6e420c0`) (like `DownloadJobTransitionOwnershipTest`; no new dependency).
  - Import rules: `domain/` imports no Android and no other app packages; engines and `data/` do not
    import `feature/`/`ui/`; `feature/` does not import `data.storage`.
  - Allowlist today's real seams so the check passes on day one: services and notifications using
    `AppRoute`/`openAppPendingIntent`, `DownloadEngine` → `AppTime`/`AppContainer`, `PatreonSession` →
    `DevEnvironment`, `DownloadForegroundService` → `CloudflareSolveActivity`.
  - Ratchets that may only go down: count of `fun ScreenHost.`, `ScreenHost` properties,
    `appContainer` uses in `feature/`, and `android/AGENTS.md` size.
- [x] **0.3 Delete the dead description-playback branch in `TtsEngine` only.** (`3802d3d`)
  - Remove `speaksLive` (`TtsLoadedSession.kt:34`), the non-pack part of `loadOutputLocked`,
    `switchToLiveOutputLocked`, the render-ahead helpers, the `isDescriptionSession` checks in
    `TtsPlaybackPreparation.kt`, and the unused `availableVoices()`/`refreshEngine()` if confirmed.
  - Keep the resume guard in `TtsSessionPlanning`.
  - **Keep `RenderedTtsOutput`, `LiveTtsOutput`, `TtsAudioRenderer`, `TtsAudioCache` and
    `TtsWatchdog`.** The Settings › Listening "Try it" sample uses them (`TtsSamplePreview.kt:54,235`).
  - Expected size: about 150–400 lines.

### Phase 1 — Foundation (≈1 week, medium risk)

- [x] **1.1 `operations/StoryOperations`.** (`7142fc1`, work moved in `94a17d7`)
  - Move `storyOperation`, `aiOperation`, `detailsOperationSlot` and `detailsAiOperationSlot` off
    `ScreenHost` into typed `StateFlow`s.
  - Move in the orchestration from `feature/story/ManualStorySync.kt`, `StoryActions.kt`,
    `SyncDownloadActions.kt` and `StoryContentActions.kt`.
  - Make `app/AiJobUiBridge.kt` publish to it instead of changing screen state.
  - Engines are unchanged.
- [x] **1.2 `ScreenModel` base and `ScreenModelStore`.** (`7142fc1`, `a88d9f8`, `80cf41f`) Write the lifecycle design first:
  - A model is created on route entry and disposed (scope cancelled) when its route leaves the history,
    the same lifetime as `RouteViewMemory`.
  - On fold, `MainActivity` is not recreated (`configChanges`), so the renderer rebuilds from the
    model's current state. The `rerender` and `screenObserver` fields retire as screens migrate.
  - On process death, only fields that already persist (`LibraryScreenStatePersistence`, route ids)
    are saved, through `RouteViewMemory`/`onSaveInstanceState`.
  - Dialog and picker callbacks call model actions and never capture views.
- [x] **1.3 Narrow fakeable ports** (one data interface per model, added with each migration) for what screen models need, following `RepositoryStoryStore`, so
  models get JVM tests with in-memory fakes.

### Phase 2 — Pilot: Details and the Chapters tab (≈1 week including owner QA)

- [x] Migrate the story page (Overview, Chapters tab, download banner, operation progress) to
  `DetailsScreenModel` plus a View renderer.
  - Delete `DetailsBindings`, the observer's patch paths, and the slot fields.
  - The chapter list uses `ListAdapter`/`DiffUtil`.
  - Done as `StoryPageScreenModel` + `StoryPageRenderer`; sync / cleanup / EPUB work moved to
    `operations/StoryWork`. Not done: the chapter adapter keeps its existing hand-rolled DiffUtil with
    stable ids (no `ListAdapter` conversion), and tab memory stays in `StoryTabMemory`.
- [x] Add JVM tests of the model: download events, operation progress, and tab state.
  - Model and `StoryWork` tests; no tab-state tests, since tab state did not move into the model.
- [x] Do emulator QA on the cover and inner displays, including a live fold, an active download, and a
  sync while on the page.
- [x] **Gate: measure and decide.** Decided: continue. The page had one source of state and one render path, +200 production and +545 test lines, and one stale-state bug fixed. The pilot recommended a shared binding helper and a create-free model lookup (`a88d9f8`).
  - Measure lines and files before and after, model test lines, state/stale-view bugs found, and perf
    suite navigation/scroll numbers (advisory).
  - Then continue, adjust, or stop. Stopping here still keeps Phases 0–1.

### Phase 3 — Remaining state-heavy screens (≈1 screen group per change series, paced by owner QA)

- [x] AI Controls (Cover, Characters, Description tabs; removes the `Scaffold` coupling)
  - Done as `AiControlsScreenModel` + `AiControlsRenderer`, with the app-level `AiAttention` holder for
    drafts, unseen markers and chip failures, and description drafts on a process-wide
    `AiDescriptionJobCoordinator`. The story page's `refreshPage` hook is gone (it observes
    `coverToReview`). Not done: the Characters list and portraits could not be exercised on the
    emulator (the simulator's chapters yield no character names).
- [x] Listen / Narration
  - Done as `ListenScreenModel` + `ListenRenderer` and `PlayerScreenModel` + `PlayerRenderer`, with the
    shared `PlaybackControls` port; `playIfGenerated` / `playChapter` / `continueListening` run in
    `operations/ListenStarts`, and the mini player is an activity-owned `TtsMiniPlayer`. Not done: a
    story-level "Move to front" with two stories queued was not exercised on the emulator (unit-tested).
- [x] Reader chrome (the WebView and read-along controller stay as they are; only screen state moves)
  - Done as `ReaderScreenModel` + `ReaderRenderer`, with `ReaderData` / `ReaderPlayback` ports; a fold re-renders from
    the prepared chapter. Not done: the page still opens at the top after a rebuild (a fold, Back to it); there was no
    reader scroll memory before either, so keeping the place across a fold is a separate change.
- [x] Library
  - Done as `LibraryScreenModel` + `LibraryRenderer` (rebuilds on a `LibraryBuildKey`, patches filters, selection and
    download progress from state; `refreshStoryCards` is gone), with `AddStoryScreenModel` and `AuthorNovelsScreenModel`.
    Process-death memory goes through `ScreenModelStore.restore`. Not done: the story adapters keep their hand-rolled
    filtering and rebinding (no `ListAdapter` conversion, which would rebind cards during a gesture on download
    progress); the perf suite was not run (no before build in the worktree).
- [x] Downloads queue
  - Done as `QueueScreenModel` + the `QueueScreen` renderer, with queue changes as `QueueControl`s through
    `HostQueueData`; expand choices moved off the activity. Not done: drag reorder within Up next was exercised only with
    two novels on different sites (the simulator has three novels on two sites), so a longer Up next is unit-tested only.
- [x] Updates
  - Done as `UpdatesScreenModel` + the `UpdatesScreen` renderer; the syncs (row and "Sync all") moved to the
    activity-level `UpdatesSyncs` and publish through `StoryWork.syncFromUpdates` / `StoryOperations.updatesSyncs`.
    Not done: "Sync all" and a non-zero May be out could not be run on the emulator (the simulator's fixture chapters
    have no dates, so nothing is forecast; the real library is not synced in QA), so the batch is unit-tested only.
- [x] Settings and Cleanup
  - Done as one screen model per page (`SettingsScreenModel`, `DownloadSettingsScreenModel`, `TtsSettingsScreenModel`,
    `AiSettingsScreenModel`, `TabsScreenModel`, `DataBackupScreenModel`, `AppUpdateScreenModel`,
    `PatreonAccountScreenModel`, `CleanupScreenModel`) with the `SettingsXxx` renderers; autosave through `Autosave`
    and `UnsavedSettings`; the full-backup export in the activity-level `BackupExports`; Cleanup's process-death memory
    through `ScreenModelStore.restore`. `backupExportState`, `cleanupScreenState` and `screenObserver` are gone from
    `ScreenHost`. Not done: `ScreenHost.rerender` stays (Trends, Add story and other screens still set it; screens that
    do not fall back to re-dispatching their route), and `activeStory` was out of scope. The debug speed test and
    Patreon sign-in/paste were not exercised on the emulator (unit-tested only).
- Low-state screens (Settings forms, Trends, Add Story, Cleanup, App update) migrate only when a
  change touches their state.
- After the pilot, **new screens must use a screen model**, and changing an old screen's state means
  migrating that screen first.
- Exit: `ScreenHost` holds the navigator, shell, launchers and activity services only. No screen state
  remains on `MainActivity`.

### Separate later decisions (not part of this program)

- Compose, measured on a release build once screen models exist.
- Immutable models, which first needs a replacement for in-place null coercion in `StoryNormalization`.
- Media3, triggered by Android Auto.

## Working rules for the migration

- One screen per change series. Do not mix structural moves with behavior or layout changes.
- Keep layouts pixel-identical. The v3 design is not reopened.
- Run `:app:ci` per change. Do emulator fold QA per migrated screen before calling it done.
- When feature threads are active, do migration work in a worktree, merge quickly, and keep each
  series short.

## Success metrics

- Fixes that are state or stale-view bugs specifically (about 8–12 of 48 UI fix commits since
  2026-10-06), not total fix share, which v3 polish distorts.
- `fun ScreenHost.`: 448 → < 100 after Phase 3. `appContainer` in `feature/`: ~90 → 0.
- Every migrated screen has model tests. The `feature/` test-to-production ratio rises from ~10%.
- `android/AGENTS.md` ≤ 15 KB and no longer edited in most commits.
- No perf regression in the existing suite. Old backups still restore.

## Corrections from the reviews

The draft got these wrong. They are fixed above and recorded here so future audits do not repeat them:

- **The rendered/live TTS outputs are not dead.** The Settings sample uses them. Only the
  `TtsEngine` description branch is dead.
- **"~740 slot/patch/rerender mentions" was a grep artifact.** `patch` matches `dispatch`, and
  `rerender` matches `CloudflareRender`. The real count is ~300 in UI code, and the October "~400"
  likely has the same flaw.
- **"91 `var`s on Story/Chapter"** is the total for all of `Models.kt`. Story and Chapter have 52.
- **`AppRepository` extensions:** 24 of 35 are already in `AppRepository.kt`.
- **Fix concentration holds by count, not by density.** `download/` has the highest fix touches per
  thousand lines. About half of the UI fixes were v3 layout polish, not architecture.
- **Fold already keeps state** through `configChanges`. A screen-model store adds scoping and
  testability, not survival.
- **Line savings will be small.** The October phases predicted 1,500+ lines and delivered −340. The
  payoff here is structure and testability, not size.

## Progress log

| Date | Item | Commits | Main Kotlin lines after | `fun ScreenHost.` | Notes |
|---|---|---|---|---|---|
| 2026-11-21 | Phase 2 pilot: story page | `d749494`..`94a17d7` | 84,404 | 438 | Story page on `StoryPageScreenModel`; `detailsOperationSlot`/`detailsAiOperationSlot` and `DetailsBindings` removed; `appContainer` in `feature/` 93 → 84, ScreenHost properties 30 → 28. Pilot lines: details + story + operations 7,917 → 8,117 production, 876 → 1,421 test. Fixed the app-bar ⋮ menu acting on a story read at render time (cleanup counted chapters from before a download). Gate decision pending. |
| 2026-11-21 | Phase 3: AI Controls | `7b257e9` (+ docs) | 85,067 | 408 | AI tab on `AiControlsScreenModel`; `AiControlsScreenState`, `AiControlsBinding`, the bridge's redraws, `refreshStoryHero` and the `Scaffold` coupling removed; `appContainer` in `feature/` 84 → 69, ScreenHost properties 28 → 27. Group lines (`feature/ai` + bridge + description jobs): 4,244 → 4,875 production, 169 → 722 test. Fixed: a description draft now survives activity recreation (process-wide job and holder). Changed lifetime: the open sub-tab and character page reset when the story leaves the history. |
| 2026-11-21 | Phase 3: Listen and the player | `6c79492`..`a4b8414` (+ docs) | 85,916 | 394 | Listen on `ListenScreenModel` (rebuilds only on `ListenBuildKey` changes, patches banner, running row, top card and compression in place), Now Playing on `PlayerScreenModel`, listen starts in `operations/ListenStarts`, mini player as an activity-owned `TtsMiniPlayer`. Removed the Listen and player `screenObserver` loops, `NarrationScreenPlanning.changesSince`, the process-wide `pendingPlay` and `lastContinue` globals and `NarrationLiveStatus.observe`. ScreenHost extensions 408 → 394; `appContainer` in `feature/` 69 and ScreenHost properties 27 unchanged. Group lines (`feature/narration` + `feature/player` + `ListenStarts`): 3,668 → 4,510 production, 150 → 1,110 test. No state or stale-view bugs found; queue changes now run in the model's scope as `NonCancellable`, so one confirmed just before leaving Listen still finishes, as it did in the activity scope. |
| 2026-11-21 | Phase 3: Reader chrome | `f817809` (+ docs) | 86,285 | 386 | Reader on `ReaderScreenModel` (page, bookmark, this chapter's playback slice for the dock, display settings; read-aloud starts through `ListenStarts` via `ReaderPlayback`), `ReaderRenderer` bound while attached; the WebView, page scripts and `ReaderReadAlong` are unchanged apart from Next at the page end using `readableChapter`. Removed the reader's `screenObserver` use, `navigateChapter`, the dead `readerTtsTransport`, `readerOfStory` and the `ScreenHost` settings panel (now `feature/reader/ReaderSettingsSheet`). ScreenHost extensions 394 → 386; `appContainer` in `feature/` 69 and ScreenHost properties 27 unchanged. Group lines (`feature/reader` + the old `ui/ReaderPanels` + `navigateChapter`): 1,984 → 2,353 production, 602 → 1,071 test. Fixed: Previous/Next used the story captured when the page was built, so a chapter downloaded while reading was skipped; they now read the story as it is now. Changed: a fold re-renders from the prepared chapter without the "Preparing chapter…" spinner or a disk read (a text-size change refreshes the prepared HTML so the rebuild keeps it). Verified the page still resets to the top on a fold, as before. |
| 2026-11-21 | Phase 3: Library | `80cf41f`..`fedecd3` (+ docs) | 86,741 | 365 | Library on `LibraryScreenModel` (published library shared by identity, never deep-copied; preferences apply at once and save in the background), `LibraryRenderer` bound while attached; Add story on `AddStoryScreenModel`, the author page on `AuthorNovelsScreenModel`. Removed `LibraryScreenState`, `AddStoryScreenState`, `LibraryUiExtras` (activity-wide search flag, Add story tab pick, sheet handle), `refreshStoryCards`, the library `screenObserver` use and the dead `filtersExpanded`. ScreenHost extensions 386 → 365, ScreenHost properties 27 → 25, `appContainer` in `feature/` 69 unchanged. Group lines (`feature/library`): 3,279 → 3,748 production, 510 → 1,159 test. Fixed: a picked Unassigned tab jumped to All on a fold (the preference stores All); a source-blocked fetch left Add story stuck on "Fetching..." until restart (the status lived on the activity). Changed: leaving Add story mid-fetch no longer pulls you back on the next status (the fetch still opens the story when it finishes), and reopening it then starts a fresh form. |
| 2026-11-21 | Phase 3: Downloads | `0a351a2` | 87,001 | 347 | Downloads on `QueueScreenModel` (published queue + pacer + site checks + ticker + expand choices, planned off the main thread; skips idle ticks; holds the dragged order until the save lands), renderer bound while attached; every queue change is a `QueueControl` through `HostQueueData`, and the menus are a `QueueMenus` class reading the model at tap time. Removed `ScreenHost.storyExpandOverride`, the queue's `screenObserver` loop and `root.parent !== frame` guard, `launchQueueControl`, `queuePlan` and nine other `ScreenHost` extensions. ScreenHost extensions 365 → 347, `appContainer` in `feature/` 69 → 61, ScreenHost properties 25 → 24. Group lines (`feature/downloads`): 1,886 → 2,155 production, 56 → 424 test. No state or stale-view bugs found. Changed lifetime: expand choices now reset when Downloads leaves the history (they lived on the activity before); queue controls now run as `NonCancellable` in the model's scope, so one tapped just before leaving still saves. |
| 2026-11-21 | Phase 3: Updates | `51ce74c` | 87,383 | 330 | Updates on `UpdatesScreenModel` (overview planned off the main thread from the badge's cache, replanned 400 ms after library/queue changes; filter; live sync lines and batch), renderer bound while attached. Syncs moved from `UpdatesSyncState` on the activity to the activity-level `UpdatesSyncs` (batch, summary, held reviews, forecast cache); each novel syncs through the new `StoryWork.syncFromUpdates`, listed in `StoryOperations.updatesSyncs`, which the story page's Sync, cleanup and EPUB now check instead of `ScreenHost.isSyncingFromUpdates`. Removed `UpdatesSyncState` and its `onChange`/`onStatus` view callbacks, the Updates `screenObserver` loop, and 17 `ScreenHost` extensions (the forecast card and row binding now take a `Context`). ScreenHost extensions 347 → 330; `appContainer` in `feature/` 61 and ScreenHost properties 24 unchanged (`updatesSyncState` → `updatesSyncs`). Group lines (`feature/updates` + operations changes): 1,246 → 1,630 production, 59 → 548 test (+ 3 `StoryWorkTest` cases). No state or stale-view bugs found. Changed: the filter resets to All when Updates leaves the history; "Sync all" takes its novels from the screen's last plan rather than replanning on the tap (on the main thread); a finished row sync shows its new "Synced just now" line after the coalesced replan (≤ 0.4 s) instead of at once. |
| 2026-11-21 | Phase 3: Settings and Cleanup | `d707410`..`449eb74` (+ docs) | 88,812 | 316 | Every Settings page and Cleanup rules on screen models with data ports (`Host…Data`/`Repository…Data` in the renderer files). Removed `ScreenHost.backupExportState` (+ `BackupExportState`/`Kind`), `cleanupScreenState` (+ `CleanupScreenState`) and `screenObserver` (+ its Scaffold/MainActivity handling), the module-level drafts and caches (`unsavedDownloadSettings`, `downloadAdvancedOpen`, `unsavedAiKey`, `unsavedTts`, `sampleTextDraft`, `sampleComputerIds`, `lastHubSummaries`, `lastPhoneSummary`, `PatreonAccountScreenState`) and `AiModelFieldsModel`. ScreenHost extensions 330 → 316, `appContainer` in `feature/` 61 → 54, ScreenHost properties 24 → 22. Group lines (`feature/settings` + `feature/cleanup`): 4,767 → 6,246 production, 418 → 1,613 test. Fixed: choosing an engine or voice within 0.4 s of a stepper change could let the older pending save overwrite it (the choice now flushes the draft); the new tab name and the Listening sample text being typed now survive a fold. Changed lifetime: Download settings' Advanced toggle, the sample computers, Patreon's checked status and the sample text reset when their page leaves the history (they were process-wide). |
| 2026-11-21 | Phases 0–1: guardrails and foundation | `8ea8f02`..`7142fc1`, `a88d9f8` | 84,229 | 448 | `android/AGENTS.md` 55 KB → 14 KB with per-package `AGENTS.md` files; `ArchitectureRulesTest`; `TtsEngine` plays packs only (−264 lines, the live-only media-button claim removed); `StoryOperations`, `ScreenModel`/`ScreenModelStore`, `bindWhileAttached`, `ScreenModelStore.find`. |

## Result

Every destination and every state-heavy screen now runs on a screen model and a renderer. These
screens are on models: story page, AI Controls, Listen, Now Playing, Reader, Library, Add story,
author page, Downloads, Updates, every Settings page, and Cleanup.

**`ScreenHost` and `MainActivity` hold no per-screen state any more.** What remains is shared
dependencies, navigation, the shell, launchers, and two activity-level work holders: `UpdatesSyncs`
and `BackupExports`. Their runs must outlive a screen, and each one's reasoning is written in its
package `AGENTS.md`.

| Measure | Before (`fb6e4d8`) | After |
|---|---:|---:|
| Main Kotlin lines / files | 84,311 / 524 | 88,812 / 553 |
| JVM test lines | 32,970 | 38,557 |
| `feature/` test lines (share of `feature/` production) | 2,955 (10%) | 7,594 (23%) |
| `fun ScreenHost.` extensions | 448 | 316 |
| ScreenHost properties | 30 | 22 |
| `appContainer` lookups in `feature/` | 93 | 54 |
| `android/AGENTS.md` | 55 KB | 14 KB |

Production code grew by about 4.5k lines (+5%). As predicted, the payoff is structure and test
coverage, not size. Several state bugs were found and fixed on the way:

- the story page's ⋮ menu read a stale story;
- Reader Previous/Next skipped a chapter downloaded while reading;
- the Unassigned tab jumped to All on a fold;
- Add story stuck on "Fetching…" after a blocked fetch;
- a TTS voice choice could be overwritten by an older pending save.

Each migration's progress-log row records its lifetime changes. Most of them are drafts or toggles
that now reset when their screen leaves the history instead of living for the whole activity.

**The `fun ScreenHost.` < 100 target was not met, and it was the wrong measure.** The remaining
extensions are mostly renderer entry points (`showXxx`, which route dispatch calls) and view-building
helpers that use the host only to reach the activity. They hold no state. The ratchet keeps the count
from growing. The real exit criterion, no screen state on the host, is met.

### Remaining work (not in this program)

- Trends, Chapter selection, the cover viewer and the browser import screens still use the old shape.
  They hold little live state; migrate each when a change touches its state.
- `ScreenHost.rerender` and `activeStory` are still set by those screens. Remove them once the last
  user migrates.
- Convert hand-written list diffing to `ListAdapter` where it does not fight gesture handling. The
  Library and Chapters lists deliberately keep theirs. The Library's adapter now dispatches a
  `DiffUtil` result (`LibraryCardDiff`) whose content check ignores download progress, so it stays
  off `ListAdapter` but no longer rebinds every card on a reorder.
- The Reader opens at the top after a fold, as it always has. Remembering its scroll position is a
  separate feature.
- Separate later decisions, unchanged: Compose (measured on a release build), immutable models,
  Media3.

