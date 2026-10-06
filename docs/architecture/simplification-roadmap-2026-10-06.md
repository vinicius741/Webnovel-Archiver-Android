# Simplification Roadmap (2026-10-06)

Structural plan for shrinking the native app without losing functionality. Each section ends with a
checklist; tick items as they land and record the commit next to the item.

Baseline: commit `9131e1f8` on `main`. All numbers below were measured on that commit.

## Verdict

The code is careful (consistent style, ~22k lines of tests, deliberate locking and crash recovery)
but heavily **accreted**: problems were solved locally and carefully, rarely by deleting code or
reusing an existing mechanism. Two drivers produce most of the weight:

1. **A minimal-dependency stance.** No Compose, Fragments, Room, DataStore, or Media3, so the app
   re-implements a reactive UI layer, a transactional store, and a media session by hand.
2. **Rules applied mechanically.** The 400-line file cap and the "every decision becomes a tested
   pure `*Planning` function" rule lead agents to split and wrap code instead of simplifying it.

Main-source Kotlin grew from ~18.9k lines (`refactoring-large-files.md`, 2026-07-01) to 52.7k lines
in about three months.

## Where the code lives today

| Area | Lines | Share | Notes |
|------|------:|------:|-------|
| `feature/` (screens) | 18,097 | 34% | Imperative View screens built as `ScreenHost` extensions |
| AI (all layers) | ~9,841 | 19% | `ai/`, `feature/ai/`, AI stores, AI repository extensions |
| `data/` | 6,521 | 12% | Hand-built JSON persistence, repository, backup/restore |
| `source/` | 6,085 | 12% | Providers + Cloudflare (mostly justified complexity) |
| `ui/` | 4,022 | 8% | Programmatic View DSL and widgets |
| `tts/` | 2,998 | 6% | Hand-rolled media session, focus, notification |
| Everything else | ~5,100 | 9% | download, app, domain, cleanup, epub, sync, perf, ... |
| **Main total** | **52,700** | | 379 files; tests: 22,335 lines in 172 files |

Other baseline signals:

- 248 `fun ScreenHost.` extension functions; 92 files reference `ScreenHost`.
- ~400 mentions of slot / rerender / in-place patch logic.
- 20 files state in comments that they exist to keep another file under its size budget.
- 57 `*Planning` files; 63 files under 40 lines.
- 48 files carry review IDs in comments (`(R05)`, `(R09)`, ...).
- 34 `get*/save*` functions in `AppStorage.kt`, each mirrored by a cached field in `AppRepository`.

Re-measure with:

```bash
find android/app/src/main/java -name '*.kt' | xargs wc -l | tail -1
```

## Guardrails (apply to every phase)

- Keep same functionality. Every phase is a refactor, not a feature change.
- File-based JSON data, backup formats (JSON and full ZIP), chapter paths, and archive IDs are
  compatibility-sensitive. Existing user libraries and old backups must keep loading.
- Validate each phase per `android/AGENTS.md`: targeted tests, `:app:lintKotlin`, `:app:detekt`, and
  for UI/service/lifecycle work, emulator QA on cover and inner displays including a live fold.
- Update `android/AGENTS.md` in the same change whenever a rule it states changes (file cap, Views-only
  UI, planning-function rule, dependency policy).
- One phase item per commit series; do not mix structural moves with behavior changes.

## Relationship to earlier documents

- `code-simplification-audit-2026-07-31.html` lists 40 function-level opportunities. This roadmap is
  structural; where an audit item overlaps, finish it as part of the matching phase below.
- `refactoring-large-files.md` introduced many of the file splits that Phase 1 reverses. It was
  correct for its time (single ~1,000-line files); the follow-on 400-line cap overshot.
- `../tts/tts-media3-migration-evaluation.md` deferred Media3 to a dedicated change. Phase 6 is that
  change.

---

## Master checklist

- [x] Phase 1: Quick wins (file cap, planning ceremony, comment noise) — commits `822f4ccb`..`d3c66a8c`
- [ ] Phase 2: One AI job pipeline, fewer AI stores
- [ ] Phase 3: Generic cached settings documents
- [ ] Phase 4: Screen architecture (biggest payoff)
- [ ] Phase 5: Real storage engine for library and queue
- [ ] Phase 6: TTS on Media3 (optional)

Phases 1-3 are low risk and independent. Phase 4 is the largest win and benefits from Phase 1
landing first. Phase 5 is the riskiest because it touches persisted user data.

---

## Phase 1: Quick wins

Effort: small. Risk: low. Expected effect: fewer files, easier navigation; small line savings.

### 1.1 Replace the 400-line file cap

**Problem.** `checkKotlinFileSize` (in `android/gradle/quality.gradle`, baseline in
`android/config/kotlin-file-size-baseline.txt`) fails any file over 400 lines. Agents respond by
splitting files along arbitrary lines. Details is now 25 files and AI Controls 21; the pieces talk
through binding holders (`DetailsBindings`, `AiControlsBinding`) and `ScreenHost` extensions.

Files that say they exist only for the size budget:

```
ai/OpenRouterCatalogParsing.kt            source/ScribbleHubTocPagination.kt
app/MainActivityPerfHooks.kt              source/SpaceBattlesCoverImage.kt
data/repository/AppRepositoryCovers.kt    source/network/NetworkClientBinary.kt
data/repository/AppRepositoryRewrites.kt  source/network/NetworkClientPages.kt
data/repository/AppRepositoryTts.kt       source/network/NetworkRequests.kt
data/storage/AppStorageIndexRecovery.kt   source/network/RetryBackoff.kt
data/storage/CoverFileStore.kt            source/network/SourceRequestEvents.kt
download/DownloadRequestGateFactory.kt    tts/TtsEngineLogging.kt
feature/details/ChapterStatusSlots.kt     feature/details/TrendMetricCards.kt
feature/details/DetailsDownloadObserver.kt  feature/reader/ReaderPerfHooks.kt
```

**Target.** Raise the cap substantially (e.g. 1,000 lines) or remove it in favor of detekt's
complexity rules (`LongMethod`, `LargeClass`, `ComplexMethod`). Then merge pieces back where the split
was only for size.

- [x] Decide: raise cap to ___ lines / remove cap and rely on detekt → **1,000 lines** (`822f4ccb`)
- [x] Update `quality.gradle`, the baseline file, and `android/AGENTS.md` (`822f4ccb`)
- [x] Merge the size-only splits listed above back into their owners (tick per area)
  - [x] `source/network/` (NetworkClient pieces) (`fb9c1bf4`)
  - [x] `data/repository/` (AppRepository extensions) (`b7003e5b`)
  - [x] `feature/details/` (`3add53d9`)
  - [x] Remaining files from the list (`c35deffa`)
- [x] Remove "kept here so X stays within its size budget" comments (gone with the merges; verified by grep)

### 1.2 Inline ceremonial `*Planning` functions

**Problem.** The rule "deterministic decisions go in pure planning functions with a JUnit test" is
applied even to one-line decisions. Example: `feature/story/EpubOpenPlan.kt` is an enum plus a
function wrapping `if (hasEpubReader)`, with its own test file.

Candidates (under 45 lines; review each, keep the ones with real logic):

```
feature/story/EpubOpenPlan.kt (13)              epub/EpubConfigPlanning.kt (24)
feature/downloads/QueueGroupExpansionPlanning.kt (15)  domain/story/SourceMetadataPlanning.kt (26)
feature/browser/BrowserImportPlanning.kt (17)   feature/browser/BrowserUrlPlanning.kt (26)
data/backup/BackupProgressPlanning.kt (22)      feature/browser/SourcePickerPlanning.kt (26)
feature/details/ChapterSelectionPlanning.kt (22) tts/TtsHeadsetTapPlanning.kt (27)
ai/AiReasoningPlanning.kt (24)                  app/StartupPlanning.kt (29)
data/backup/BackupExportPlanning.kt (24)        feature/browser/CloudflareSolvePlanning.kt (29)
```

**Target.** Keep planning objects where they hold real rules (metric snapshots, trend axes, Cloudflare
render planning, backup validation, chapter parsing). Inline trivial ones at their single call site
and delete their tests. Reword the `android/AGENTS.md` rule to "non-trivial decisions".

- [x] Reword the planning rule in `android/AGENTS.md` (`de4e7a0f`)
- [x] Review each candidate above; inline and delete trivial ones with their tests (`de4e7a0f`)
- [x] Record kept vs. inlined here:
  - Inlined: `EpubOpenPlan`, `QueueGroupExpansionPlanning`, `BrowserImportPlanning`, `BackupProgressPlanning`
  - Kept (real rules): `EpubConfigPlanning` (compat-sensitive defaults), `SourceMetadataPlanning`
    (UI metric curation), `BrowserUrlPlanning` (URL/search resolution), `SourcePickerPlanning`
    (host extraction), `ChapterSelectionPlanning` (range clamping), `TtsHeadsetTapPlanning`
    (tap codes), `AiReasoningPlanning` (catalog filtering), `StartupPlanning` (splash grace),
    `BackupExportPlanning` (backup validation), `CloudflareSolvePlanning` (page state machine)

### 1.3 Remove history from comments

**Problem.** 48 files cite review IDs (`(R05)`, `(R26)`, ...) and many comments narrate why an earlier
design changed. That history belongs in git and in the review documents.

- [x] Strip `(Rnn)` tags, keeping the sentence only when it explains a current invariant (`61b49660`, 103 tags in 41 files)
- [x] Remove comments that describe past designs ("previously", "used to", "moved from") (`61b49660`, six reworded)

### 1.4 Small duplicate helpers

- [x] One shared `Gson` instance (10 separate `Gson()`/`GsonBuilder()` today) (`d3c66a8c`)
- [x] Route `CoverEvidenceSelectionStore` and `CoverEvidenceCache` through `AtomicFileWrites` instead
      of hand-written temp-file + rename (`d3c66a8c`)
- [x] One SHA-256 hex helper (`AiCoverVersionStore`, `DevLibraryReportPlanning`, 6 `%02x` encoders) (`d3c66a8c`)

---

## Phase 2: One AI job pipeline, fewer AI stores

Effort: medium. Risk: medium (foreground services, notifications). Expected savings: ~1,000+ lines.

### 2.1 Merge the cover and chapter-rewrite job pipelines

**Problem.** Cover generation and chapter rewrite each have a foreground service, a job coordinator,
and a UI bridge:

| Cover | Chapter rewrite |
|-------|-----------------|
| `ai/AiCoverForegroundService.kt` (183) | `ai/AiChapterRewriteForegroundService.kt` (188) |
| `ai/AiCoverJobCoordinator.kt` (188) | `app/AiChapterRewriteJobCoordinator.kt` (220) |
| `app/AiCoverJobUiBridge.kt` (116) | `app/AiChapterRewriteJobUiBridge.kt` (94) |

After renaming, each pair differs by only ~120 lines; the rewrite service's KDoc says it is
"mirroring `AiCoverForegroundService`".

**Target.** One generic `AiJobCoordinator<Request, Result>`, one `AiJobForegroundService` that
renders whichever jobs are active, and one UI bridge. Feature-specific parts become small strategy
objects (run the job, persist the result, describe progress and outcome notifications).

- [ ] Design the shared job contract (queue, active jobs, events, cancellation)
- [ ] Implement the generic coordinator + service + bridge
- [ ] Port cover generation; emulator QA (progress, outcome notification, cancel, process death)
- [ ] Port chapter rewrite; same QA
- [ ] Delete the old six files; update manifest and `docs/ai/`

### 2.2 Consolidate cover storage

**Problem.** Covers use five stores: `AiCoverDraftStore`, `AiCoverVersionStore`, `CoverFileStore`,
`CoverEvidenceCache`, `CoverEvidenceSelectionStore`, plus `AppRepositoryCovers.kt` and
`AppRepositoryCoverVersions.kt`.

**Target.** One `CoverStore` owning applied cover, drafts, and version history (drafts are versions
that are not yet applied), and one disposable evidence cache that also holds the last selection.

- [ ] Map current on-disk layout and backup behavior (generated covers ship in full backups)
- [ ] Merge draft + version + file stores, keeping existing directories readable
- [ ] Fold the selection store into the evidence cache
- [ ] Merge the two repository extension files

### 2.3 Simplify the model picker

**Problem.** Model selection spans ~9 files and ~1,200 lines (`AiModelControls`, `AiModelPicker`,
`AiModelPickerRow`, `AiModelReasoningPicker`, `AiImageModelPicker`, `AiModelSelectionDraft`,
`AiModelPresentation`, `AiReasoningCatalog`, `AiReasoningPlanning`).
`feature/ai/AiModelControls.kt` repeats the same save handler for description, rewrite, and verifier.

- [ ] Replace the three handlers with one table of `(label, getter, setter, excluded)` rows
- [ ] Merge the image and text picker dialogs where they only differ by filter
- [ ] Merge the small reasoning helpers into one file

---

## Phase 3: Generic cached settings documents

Effort: small-medium. Risk: low (file names and JSON shapes stay the same). Expected savings: a few
hundred lines, and adding a setting becomes one line.

**Problem.** Each settings file is written four times: a `File` field, `get*` and `save*` in
`AppStorage`, a `@Volatile` cached field in `AppRepository`, and a repository `save*` that writes and
re-caches (34 `get*/save*` functions in `AppStorage` alone).

**Target.** A `JsonDocument<T>(file, default, normalize)` that owns read, normalize, atomic write, and
an in-memory `StateFlow<T>`. `AppStorage`/`AppRepository` declare one property per document.

- [ ] Implement `JsonDocument<T>` on top of `DurableJson`/`AtomicFileWrites`, with tests
- [ ] Migrate settings, display preferences, tabs, cleanup rules, TTS, AI, follow settings
- [ ] Delete the per-setting getters, savers, and cached fields
- [ ] Confirm JSON output is byte-compatible (or at least read-compatible) with old files and backups

---

## Phase 4: Screen architecture (biggest payoff)

Effort: large. Risk: medium-high (every screen). Expected effect: the largest reduction in size and
in stale-view bugs (~80 commits so far mention stale views, re-rendering, or fold rebuilds).

**Problem.** Every screen is an extension function on `navigation/ScreenHost.kt` (248 of them). Screen
state lives on the host (`AddStoryScreenState`, `LibraryScreenState`, `BackupExportState`,
`storyExpandOverride`, `detailsOperationSlot`, ...). Screens rebuild their whole View tree, so to
avoid that cost the code captures view "slots" and patches them in place, with
`root.parent !== frame` guards against patching detached views. Example: `feature/details/DetailsScreen.kt`
holds five nullable view refs so `DetailsDownloadObserver.kt` can patch them.

**Options.**

- **A. Jetpack Compose (recommended end state).** State-driven rendering removes the slot/patch layer,
  the per-screen state classes on `ScreenHost`, and most of the `ui/` DSL. Requires reversing the
  "programmatic Android Views" rule and adding dependencies (refresh lockfiles). Can be adopted one
  screen at a time with `ComposeView` inside the existing frame.
- **B. Per-screen controller classes (intermediate).** Each screen becomes a class owning its state and
  a single `render(state)` function; `ScreenHost` shrinks to shared dependencies and navigation. Smaller
  change, no new dependencies, but keeps manual View diffing.

Checklist:

- [ ] Decide A or B (or B now, A later) and update `android/AGENTS.md`
- [ ] Pilot on one high-churn screen (Details recommended), with emulator fold QA
- [ ] Measure pilot: lines before/after, files before/after, bugs found
- [ ] Move screen state off `ScreenHost` as each screen migrates
  - [ ] Library
  - [ ] Details (+ chapter selection, trends, legacy EPUBs)
  - [ ] Reader
  - [ ] Player / mini-player
  - [ ] Download queue
  - [ ] Updates / follow selection
  - [ ] Settings (all sub-screens)
  - [ ] Cleanup
  - [ ] AI Controls
  - [ ] Add Story / browser
- [ ] Remove now-unused slot/patch helpers and `ui/` DSL pieces
- [ ] Shrink `ScreenHost` to dependencies + navigation only

---

## Phase 5: Real storage engine for library and queue

Effort: large. Risk: high (persisted user data). Expected effect: most of `data/storage` recovery and
locking code goes away; repository state collapses to one source of truth.

**Problem.** Library and queue are stored as ~15 JSON files managed by hand: per-story documents, a
library index with recovery, a maintenance coordinator, generation counters, a transaction monitor,
and three in-memory copies of the library in `AppRepository` (working map, published snapshot,
`DownloadUiSnapshot`). Backup/restore alone is ~2,200 lines across ~20 files.

**Target.** Room for stories, chapters, and the download queue (transactions, queries, and `Flow`
observation built in); DataStore or Phase 3 documents for settings. Backup formats stay JSON/ZIP and
are produced from and restored into the database. Chapter text files stay on disk.

- [ ] Decide: go / no-go (Phase 3 + Phase 4 may make this less urgent)
- [ ] Write a migration design: first-launch import from JSON, rollback plan, backup compatibility
- [ ] Add Room (KSP, lockfiles) and schema for stories, chapters, queue
- [ ] One-time JSON → Room import with verification via the dev library report
- [ ] Rewrite `AppRepository` on Room `Flow`s; delete published-state copies
- [ ] Port JSON and full-backup export/import; restore old backups on the emulator
- [ ] Delete superseded index recovery, maintenance, and locking code
- [ ] Restore a real user-size backup (`restore-library-backup` skill) and compare `storyIdsSha256`

---

## Phase 6: TTS on Media3 (optional)

Effort: medium. Risk: medium (playback, media buttons). Expected effect: removes much of the hand-built
media layer in `tts/` (~3,000 lines today).

**Problem.** `tts/` re-implements session, notification, audio focus, and media-button handling
(`TtsMediaSessionManager`, `TtsNotificationManager`, `TtsAudioFocusManager`, `TtsMediaButtonClaim`,
plus `TtsForegroundService`) on `MediaSessionCompat`.

**Target.** `MediaSessionService` with a custom `Player` wrapping `TextToSpeech`, following the scope
in `../tts/tts-media3-migration-evaluation.md`. Best done after Phase 4 if the player UI moves to
Compose.

- [ ] Prototype a TTS-backed Media3 `Player`
- [ ] Migrate service, session, notification, focus, and media buttons
- [ ] QA: start, pause, resume, prev/next, stop, lock screen, Bluetooth/headset, resume after process death
- [ ] Delete superseded managers

---

## Not a target

These areas are complex because the problem is, and are out of scope for this roadmap:

- Cloudflare detection, solver, and Chromium transport (`source/network/Cloudflare*`,
  `feature/browser/CloudflareSolveActivity.kt`)
- Source providers and their parsing
- Domain rules with real logic and tests (`domain/metrics/`, `domain/archive/`, chapter block parsing)
- The debug-only `perf/` recorder

## Progress log

| Date | Phase item | Commit | Main lines after | Notes |
|------|-----------|--------|-----------------:|-------|
| 2026-10-06 | Baseline | `9131e1f8` | 52,700 | Roadmap written |
