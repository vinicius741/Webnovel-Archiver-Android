# Architecture plan (2026-11-21)

Status: **proposal, waiting for the owner's decision.** Measured on `main` at `fb6e4d8`.
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

- [ ] **0.1 Slim `android/AGENTS.md` to ≤ 15 KB.**
  - Keep rules and a one-line-per-package map.
  - Move subsystem detail into per-package `AGENTS.md` files (`tts/`, `narration/`, `source/`,
    `download/`) so it loads only when those files are touched, or into `docs/<subject>/`.
  - Add the rule: AGENTS.md states current rules, never history.
- [ ] **0.2 Architecture tests as JUnit source scans** (like `DownloadJobTransitionOwnershipTest`; no new dependency).
  - Import rules: `domain/` imports no Android and no other app packages; engines and `data/` do not
    import `feature/`/`ui/`; `feature/` does not import `data.storage`.
  - Allowlist today's real seams so the check passes on day one: services and notifications using
    `AppRoute`/`openAppPendingIntent`, `DownloadEngine` → `AppTime`/`AppContainer`, `PatreonSession` →
    `DevEnvironment`, `DownloadForegroundService` → `CloudflareSolveActivity`.
  - Ratchets that may only go down: count of `fun ScreenHost.`, `ScreenHost` properties,
    `appContainer` uses in `feature/`, and `android/AGENTS.md` size.
- [ ] **0.3 Delete the dead description-playback branch in `TtsEngine` only.**
  - Remove `speaksLive` (`TtsLoadedSession.kt:34`), the non-pack part of `loadOutputLocked`,
    `switchToLiveOutputLocked`, the render-ahead helpers, the `isDescriptionSession` checks in
    `TtsPlaybackPreparation.kt`, and the unused `availableVoices()`/`refreshEngine()` if confirmed.
  - Keep the resume guard in `TtsSessionPlanning`.
  - **Keep `RenderedTtsOutput`, `LiveTtsOutput`, `TtsAudioRenderer`, `TtsAudioCache` and
    `TtsWatchdog`.** The Settings › Listening "Try it" sample uses them (`TtsSamplePreview.kt:54,235`).
  - Expected size: about 150–400 lines.

### Phase 1 — Foundation (≈1 week, medium risk)

- [ ] **1.1 `operations/StoryOperations`.**
  - Move `storyOperation`, `aiOperation`, `detailsOperationSlot` and `detailsAiOperationSlot` off
    `ScreenHost` into typed `StateFlow`s.
  - Move in the orchestration from `feature/story/ManualStorySync.kt`, `StoryActions.kt`,
    `SyncDownloadActions.kt` and `StoryContentActions.kt`.
  - Make `app/AiJobUiBridge.kt` publish to it instead of changing screen state.
  - Engines are unchanged.
- [ ] **1.2 `ScreenModel` base and `ScreenModelStore`.** Write the lifecycle design first:
  - A model is created on route entry and disposed (scope cancelled) when its route leaves the history,
    the same lifetime as `RouteViewMemory`.
  - On fold, `MainActivity` is not recreated (`configChanges`), so the renderer rebuilds from the
    model's current state. The `rerender` and `screenObserver` fields retire as screens migrate.
  - On process death, only fields that already persist (`LibraryScreenStatePersistence`, route ids)
    are saved, through `RouteViewMemory`/`onSaveInstanceState`.
  - Dialog and picker callbacks call model actions and never capture views.
- [ ] **1.3 Narrow fakeable ports** for what screen models need, following `RepositoryStoryStore`, so
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
- [ ] **Gate: measure and decide.**
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
- [ ] Listen / Narration
- [ ] Reader chrome (the WebView and read-along controller stay as they are; only screen state moves)
- [ ] Library
- [ ] Downloads queue
- [ ] Updates
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
