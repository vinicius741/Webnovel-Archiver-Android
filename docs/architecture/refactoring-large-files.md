# Large-File Refactor — Plan

Generated 2026-07-01. Audit of the largest files under `android/app/src/main` and the
recommended decomposition to keep the native Kotlin app maintainable as it grows. The
package layout is already healthy (`data/domain/feature/source/tts/ui` with well-extracted
pure `*Planning.kt` siblings for testing); the bloat is concentrated in a handful of files,
not spread across the tree.

> **Status: complete (2026-07-02).** All four priority refactors below have been executed and
> verified — each step built, passed `:app:ci` (unit tests + detekt + lint + assembleDebug),
> and the affected screens/flows were smoke-tested on the `webnovel_api36` emulator. The
> `[x]` items and per-section "Done" notes record what landed.

## Context

- Total main-source Kotlin: ~18,900 lines. Only **one** file is near the 1,000-line mark;
  the rest are in the 450–680 range.
- The soft ceiling this plan targets is **~600 lines** per file, matched against the existing
  proven splits (e.g. `LibraryScreen.kt` + `LibraryStoryViews.kt` + `LibraryFilters.kt`, and
  the `data/backup/*Planning.kt` family).
- Priorities below are ordered by leverage (size reduction + risk isolation + navigability),
  not strictly by line count.

## Largest files (by line count)

| #  | File                                        | Lines | Primary concern                                     |
|----|---------------------------------------------|------:|-----------------------------------------------------|
| 1  | `data/storage/AppStorage.kt`                |  982  | JSON CRUD + full backup/restore engine in one class |
| 2  | `feature/details/DetailsScreen.kt`          |  674  | One ~430-line composable + helpers                  |
| 3  | `source/Sources.kt`                         |  618  | 5 unrelated concepts in one file                    |
| 4  | `feature/downloads/QueueScreen.kt`          |  604  | UI screen + adapter in one file                     |
| 5  | `cleanup/TextCleanup.kt`                    |  510  | 10 unrelated cleanup jobs                           |
| 6  | `feature/story/StoryActions.kt`             |  488  | UI actions                                          |
| 7  | `feature/cleanup/CleanupScreen.kt`          |  482  | UI screen                                           |
| 8  | `feature/settings/SettingsScreen.kt`        |  481  | UI screen                                           |
| 9  | `feature/reader/ReaderScreen.kt`            |  469  | UI screen                                           |
| 10 | `tts/TtsForegroundService.kt`               |  459  | Service                                             |

## Top refactoring targets

### 1. `source/Sources.kt` (618) — easiest split, clearest win

**Problem.** Five distinct concepts share one file:

- `class NetworkClient` (lines 25–140) — rate-limited HTTP client with per-host gating.
- `object NetworkRequests` (142–187) — static request builders.
- `interface SourceProvider` + `object SourceRegistry` (189–225) — the provider contract.
- `object RoyalRoadProvider` (227–325) — ~100 lines of jsoup parsing.
- `object ScribbleHubProvider` (326–532) — ~200 lines of jsoup parsing.
- File-level helpers (533–618): `Element.blockText()`, `findPatreonUrl()`, `sanitizeTitle()`.

**Plan.** Split into sibling files, matching the pattern already present in
`source/PatreonStatsFetcher.kt` and `source/SourceUrlValidation.kt`:

- [x] `source/network/NetworkClient.kt` — `NetworkClient` + `NetworkRequests`.
- [x] `source/SourceProvider.kt` — interface + `SourceRegistry` + shared top-level helpers.
- [x] `source/RoyalRoadProvider.kt` — the object + its private parsing helpers.
- [x] `source/ScribbleHubProvider.kt` — the object + its private parsing helpers.

**Risk.** Mechanical move, package-private visibility unchanged. Each provider already
implements the interface and owns its parsing helpers. Lowest-risk item on the list.

**Done (2026-07-02).** `source/Sources.kt` (618) is gone; the four siblings above now own the
code. `NetworkClient`/`NetworkRequests` moved to the new `source.network` subpackage, so their
7 callers (AppContainer, DownloadEngine, StorySyncEngine, EpubEngine, PatreonStatsFetcher, plus
the source-package tests) now import `source.network.NetworkClient`. The shared parsing helpers
(`blockText`, `findPatreonUrl`, `sanitizeTitle`, `descriptionBlockTags`) widened from file-private
to package-`internal` in `SourceProvider.kt` so the per-provider files can use them; `sanitizeTitle`
stays `public` (its existing call surface). Resulting sizes: 176 / 152 / 104 / 204.

### 2. `data/storage/AppStorage.kt` (982) — highest leverage

**Problem.** Two very different responsibilities are conflated:

- Top ~360 lines: simple JSON CRUD (`getSettings`/`saveTabs`/`addOrUpdateStory`/etc.), each
  ~2 lines delegating to `read`/`write`.
- Lines 366–970 (~600 lines): an entire transactional backup/restore engine — `exportBackup`,
  `exportFullBackup`, `importBackupUri`, `importFullBackupUri`, `restoreFromZip`,
  `extractZipWithLimits`, `verifyStagedTree`, `swapRoots`, `rollbackRootFromSnapshot`,
  `cleanupRestoreArtifacts`, `writeStagedEnvelope`.

**Plan.** Extract the backup/restore cluster into a dedicated coordinator, keeping
`AppStorage` focused on CRUD + path resolution:

- [x] `data/storage/BackupRestoreCoordinator.kt` — owns the staging / atomic-swap / rollback
      transaction. Takes a reference to `AppStorage` (held as an `internal` collaborator in the
      same package, so it reaches the resolved `File` roots + path helpers).
- [x] `AppStorage.kt` drops to ~380 lines and keeps: library/story/chapter/epub CRUD,
      path resolution (`storyFile`, `chapterFile`, `relativize`), and the JSON envelope
      read/write primitives.

**Risk.** Medium — the restore logic (staging + verify + atomic swap + rollback) is
load-bearing and the most safety-sensitive code in the app. It is also the code with the
densest reliability comments (R1 / R1.3 / R7). Keep the existing `data/backup/*Planning.kt`
pure helpers in place and route the coordinator through them; this is the logic with the
best unit-test payoff, so the extraction doubles as a testability win.

**Done (2026-07-02).** `data/storage/BackupRestoreCoordinator.kt` (572) now owns all five
public backup methods (`exportBackup`, `exportCleanupRules`, `exportFullBackup`,
`importBackupUri`, `importFullBackupUri`) and every private restore helper (`restoreFromZip`,
`extractZipWithLimits`, `verifyStagedTree`, `swapRoots`, `rollbackRootFromSnapshot`,
`writeConfigTo`/`writeLibraryTo`/`writeStagedEnvelope`, `chapterFileIndex`,
`collectFullBackupChapterFiles`). `AppStorage.kt` (982 → 467) keeps its public backup method
signatures as one-line delegates to the lazily-constructed coordinator, so the 3 callers
(SettingsScreen, MainActivity, CleanupScreen) are unchanged. The members the coordinator
reaches (`root`, `storyDir`, `chapterRoot`, `epubRoot`, `backupRoot`, `restoreRoot`,
`preRestoreSnapshotDir`, `gson`, `appVersion`, `context`, `safeName`, `relativize`,
`resolveChapterPath`) widened from `private` to `internal`. All R1.2 / R7 / P3 / S5 / E3 / T1
reliability comments were preserved verbatim.

### 3. `feature/details/DetailsScreen.kt` (674) — finish an in-progress decomposition

**Problem.** The `feature/details/` package already shows the right instinct —
`DetailsHeader.kt`, `DetailsScreenDownload.kt`, `PatreonStatsCard.kt`, `ChapterListAdapter.kt`,
`ChapterSelectionScreen.kt` are extracted. But `DetailsScreen.kt` still holds a single
`ScreenHost.showDetails(storyId)` composable spanning roughly lines 62–494 (~430 lines of one
composable), plus `showDetailsOverflow`, `renderChapterList`, `renderFilterChips`.

**Plan.** Break `showDetails` into sub-composables matching the existing siblings:

- [x] `feature/details/DetailsChapterList.kt` — chapter list + filter chips + overflow menu
      (the `renderChapterList` / `renderFilterChips` / `showDetailsOverflow` content), plus the
      pure `filterDetailsChapters` helper and `toggleChapterBookmark`.
- [x] `DetailsScreen.kt` becomes the orchestrator: state wiring + layout selection
      (single-pane vs two-pane). Target ~200–250 lines.

**Pattern reference.** This mirrors the decomposition already proven by
`LibraryScreen.kt` (300) + `LibraryStoryViews.kt` (227) + `LibraryFilters.kt` (418).

**Done (2026-07-02).** The 430-line `showDetails` composable is gone. `DetailsScreen.kt`
(674 → 304) is now the orchestrator: state setup, the `screen(...)` shell, chapter-controls
wiring, single-pane vs two-pane layout selection, and the in-place download-refresh loop
(extracted to `observeDetailsDownload` + the compact-list header to `buildCompactListHeader`).
A new `feature/details/DetailsInfoPanel.kt` (315) owns the info-panel builder
(`buildDetailsInfoPanel` + `addDetailsDescription` / `addDetailsTags` / `buildStaleEpubNotice`),
returning the panel plus the two stable slots the refresh loop patches. `DetailsChapterList.kt`
(171) owns the chapter-list concerns. `renderDetailsDownloadAction` and the two-pane layout
constants stayed in `DetailsScreen.kt`. Resulting sizes: 304 / 315 / 171.

### 4. `cleanup/TextCleanup.kt` (510) — group by consumer

**Problem.** The `TextCleanup` object bundles 10 functions serving three different callers:

- Regex rule management (`sanitizeRegexRules`, `hasSimilarRegexRule`, `validateRegexRule`,
  `generateQuickPattern`, `previewRegexRule`) — consumed by the cleanup UI.
- Download/HTML transformation (`applyDownloadCleanup`, `htmlToPlainText`,
  `htmlToFormattedText`) — consumed by the download engine.
- TTS preparation (`prepareTtsChunks`, `prepareTtsAnnotatedHtml`) — consumed by the TTS engine.

**Plan.** Split by consumer domain (lower priority — pure functions, no shared mutable
state):

- [x] `cleanup/RegexRuleCleanup.kt` — the five regex-management functions.
- [x] `cleanup/HtmlCleanup.kt` — HTML-to-text transformation used by the download engine.
- [x] `cleanup/TtsTextPreparation.kt` — the two TTS prep functions (kept under `cleanup/`
      rather than `tts/` so it shares the package-private `TextCleanup.regexRunner` helper
      with the rest of the cleanup domain).

**Done (2026-07-02).** `TextCleanup.kt` (510 → 97) is now a thin facade that re-exposes every
public method + nested type (`QuickPattern`, `RegexValidationResult`, `TtsAnnotatedHtml`) and
owns the shared `internal` helpers (`regexOptions`, `regexRunner`) the siblings use. The
implementation moved to three consumer-domain objects — `RegexRuleCleanup` (199),
`HtmlCleanup` (151), `TtsTextPreparation` (161) — so the ~60 `TextCleanup.*` call sites (TtsEngine,
DownloadEngine, ReaderScreen, CleanupScreen, AppStorage, BackupRestoreCoordinator, CleanupEngine,
and the tests) are unchanged. `CleanupScreen`'s one nested-type reference was updated to
`RegexRuleCleanup.QuickPattern` (its natural consumer-domain owner).

## Files to leave alone (for now)

The 459–604-line UI screens (`QueueScreen`, `CleanupScreen`, `SettingsScreen`, `ReaderScreen`,
`StoryActions`) and `TtsForegroundService.kt` are large but internally cohesive — each is one
screen or one service. `QueueScreen` (screen + `GroupHolder`/adapter in one file) is the most
splittable of these, but the ROI is much lower than targets 1–3. Revisit only when next
touched, using the ~600-line soft ceiling as the trigger.

## Suggested execution order

1. **`Sources.kt` split** — mechanical, zero-risk, immediate navigability win. Do first. ✅ Done.
2. **`AppStorage.kt` backup extraction** — biggest size reduction + isolates the riskiest
   transactional code. Plan carefully; the restore transaction is load-bearing. ✅ Done.
3. **`DetailsScreen.kt` decomposition** — finish what its package already started. ✅ Done.
4. **`TextCleanup.kt` split** — optional cleanup; pure-function moves, low urgency. ✅ Done.

## Verification per step

After each extraction:

- [x] `android/gradlew -p android :app:assembleDebug` builds.
- [x] `android/gradlew -p android ci` (`testDebugUnitTest` + `detekt` + `lintDebug` +
      `assembleDebug`) passes.
- [x] Smoke run on `webnovel_api36` (debug variant, `emulator-5554` serial) — confirmed the
      Library, Details (info panel + chapter list + overflow menu), and Settings/backup flows
      still load with no crashes.

All four extractions were verified incrementally with `:app:ci` after each step, then a final
end-to-end smoke run on the emulator after the last extraction.

## Notes / gotchas

- **`AppStorage` has 13 callers** (`MainActivity`, `AppContainer`, `WebnovelArchiverApp`,
  `ScreenHost`, `DownloadEngine`, `DownloadForegroundService`, `TtsEngine`,
  `LegacyEpubsScreen`, `StorySyncEngine`, `AppRepository`, `DurableJson`, `EpubEngine`).
  Most access it through `AppRepository` or the engines; only the backup/restore methods are
  touched by the extraction, so the call surface for the coordinator stays small.
- **`AppRepository.kt` is the intended seam** between UI and storage — it already centralizes
  the transactional `synchronized(storage)` read-modify-write mutations. Keep the new
  `BackupRestoreCoordinator` outside that flow (backup/restore is user-initiated and does not
  go through the download/state-flow publish path) to avoid coupling the two.
- **Preserve the reliability comment headers** (R1 / R1.3 / R7 / R6 etc.) when moving code —
  they document cross-cutting invariants that span more than one file.
- **Match `detekt`'s file-length config** if one is set; if the quality gate enforces a
  per-file line cap, align these splits with it rather than the soft 600-line target above.
