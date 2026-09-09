# Continue the app simplification implementation

Copy this document as the next coding agent's prompt, or ask it to read this file in the shared repository.

## Task and current status

Finish the simplification work already implemented in `/Users/Shared/Projects/Webnovel-Archiver-Android`. Preserve the existing changes and continue from them. The user stopped the prior agent because of credit limits, not because the implementation was rejected.

The original request was to simplify the app while preserving every feature. The investigation and detailed acceptance criteria are in `docs/architecture/simplification-implementation-handoff-2026-09-07.md`. Read it first, then this continuation record, the current diff, and relevant source. Do not restart the investigation or rewrite working changes.

Implementation is present for S1 through S4, plus a bounded S5 pilot and snapshot-isolation fixes. The final local quality gate passes. Remaining work is principally final emulator QA, a focused correctness review, any resulting fixes, and completing the documentation/execution record. Some exhaustive scenarios from the original handoff have not been performed; distinguish those from verified behavior.

## Repository and safety rules

- Work in the existing `main` branch. Do not create/switch branches, commit, push, or open a PR unless the user explicitly requests it in a new message.
- Read root `AGENTS.md` and `android/AGENTS.md`. Native Kotlin development is under `android/`; use programmatic Android Views, existing repository/container ownership, and route-based navigation.
- The working tree was clean at the beginning of this implementation. All current task changes are uncommitted, including several untracked new Kotlin files. Preserve them. Check `git status --short` and the diff rather than resetting anything.
- Never operate a physical phone. Use only the `webnovel_api36` emulator, currently `emulator-5554`; rediscover and verify before targeting it. Every device-targeting command needs `adb -s <verified-emulator-serial>`.
- Manual QA uses debug package `com.vinicius741.webnovelarchiver.nativeapp.debug`. Instrumentation uses the isolated `.instrumentation` package. Never clear or restore over the user's debug library.
- Do not build release, change persisted schemas, remove recovery safeguards, increase request concurrency, or merge AI coordinators into a generic framework.
- No paid AI requests were made during this implementation. Use synthetic/local fixtures for remaining tests. Avoid live source import/sync or mass downloads during routine QA.
- In this environment, Gradle and ADB required sandbox escalation for Gradle cache/socket/device access. Their approved commands ran successfully. Do not interpret a cache-lock permission failure as a source-code defect.

## Implemented changes

Production paths below are relative to `android/app/src/main/java/com/vinicius741/webnovelarchiver/`.

### S1: cover controls

Changed `feature/ai/AiCoverControls.kt`; added `feature/ai/AiCoverOptions.kt`.

- Replaced the Show AI cover checkbox and redundant Use source cover button with one Displayed cover radio group, Source/Generated.
- The selector appears only when both covers exist and the story is mutable. Choices call `repository.setShowAiCover`; generated images/versions are retained.
- Primary generation stays visible. Context chapters, saved one-step preference, and Write your own prompt are under Generation options.
- Expansion state is a per-story set in `AiControlsScreenState`, retained for the activity lifetime and across navigation back from Details. No new persisted fields.
- Prompt drafts, image previews, costs, and saved versions remain outside the collapsed options.
- Staged generation keeps its prompt-only action label. Existing confirmations and generation flows remain.
- Removed the obsolete toggle helper and `revertAiCover` action.

Existing focused cover unit tests and two initial device tests passed. The debug app was built/installed at this phase and its settled AI Controls hierarchy showed the primary action and collapsed Generation options. Later final code has been built but has not yet been installed for the final broad manual QA.

### S2: manual sync

Added `feature/story/ManualStorySync.kt`; simplified both overloads in `feature/story/StoryActions.kt`.

- Shared operation owns pre-fetch lookup, `fetchOrSync`, and `SyncDownloadPlanning.plan`, on `Dispatchers.IO`.
- It accepts narrow lookup/fetch functions for deterministic tests, with a production constructor using repository/engine.
- Existing-story lookup retains source-ID fallback; URL import resolves by URL.
- Pre-fetch snapshots remain essential to distinguish new chapters from an old cancelled/failed backlog.
- URL validation, archives, presentation callbacks, navigation, source-access dialog/retry closures, and completion order stay in the wrappers.
- URL `onDone` still precedes download handling. Existing-story sync still clears its operation, shows Details, then handles downloads.
- Errors and cancellation propagate. Lookup preserves its prior fallback behavior but rethrows cancellation.
- Bulk Updates orchestration is unchanged.

Added `ManualStorySyncTest`. Unit tests cover ordering, argument forwarding including Full mode, progress, old backlog/new chapters, missing prior story, failures, and cancellation. Device fixtures exercise failures through both UI entry points. No live import/full-sync/large-download scenario was run.

### S3: AI notification construction

Added `ai/AiJobNotifications.kt`; removed repeated builders from `ai/AiCoverForegroundService.kt` and `ai/AiChapterRewriteForegroundService.kt`.

- One helper constructs ongoing/result notifications with explicit title, text, request code, and ongoing state.
- IDs remain cover 1003/1004 and chapter polish 1005/1006. PendingIntent request codes remain cover result/ongoing 1/2, chapter result/ongoing 3/4.
- Icon, channel, intent flags, big text, indeterminate progress, queue text, and per-feature wording remain.
- Permission guards, start methods, idle-start handling, timeout policies, lifecycle, queue handoff, and coordinators remain in their original owners.
- Deliberately rejected further service/coordinator consolidation. Cover and chapter polish differ in queue ownership, timeout cancellation, start failure, and partial results. No coordinator behavior was changed.

`AiJobNotificationDeviceTest` passed its two tests for notification payload/flags and coexistence with distinct intents, plus idle foreground service startup/shutdown. Android lint passed. Denied-permission execution, synthetic active-job timeout/cancellation combinations, and real coordinator persistence races were not exhaustively tested in this task. Do not claim otherwise; their code paths were retained.

### S4: AI progress updates

Added `feature/ai/AiControlsBinding.kt` and moved `AiControlsScreenState` from `navigation/ScreenHost.kt` into `feature/ai/AiControlsScreenState.kt`.

Changed `feature/ai/AiControlsScreen.kt`, `app/AiCoverJobUiBridge.kt`, `app/AiChapterRewriteJobUiBridge.kt`, `app/MainActivity.kt`, `ui/Scaffold.kt`, and `feature/details/DetailsScreenDownload.kt`.

- The binding owns the attached AI Controls content root, story ID, busy kind, and progress-label references.
- `makeStoryOperationProgress` accepts an optional message-view callback so AI Controls can capture its label while reusing existing progress styling.
- Description progress and both background job bridges call `updateAiControlsProgress`.
- Message-only updates patch the live label. Changed busy kind, missing progress controls, or cover prompt arrival rebuilds the screen so controls reflect current state.
- Existing terminal events and queue-structure updates still rebuild their relevant screens.
- Updates require matching story/route and attached root. Missing/detached bindings are ignored.
- `screen()` clears the old binding before removing the outgoing tree; activity destruction also clears it. Activity-owned bridge collectors and application-owned jobs retain their lifetimes.
- Navigation keys, scroll storage, mini-player hooks, WebView disposal, and Library/Queue algorithms remain unchanged.

Device tests passed 100 synthetic description messages and 100 cover messages while asserting root/binding identity, visible progress, expansion retention, disabled generation, rebuild on operation change, ignored late updates after navigation, and activity recreation. This is not evidence of a live paid job surviving process death. There is no automatic replay of paid work added here.

### S5: bounded immutable value and snapshot isolation

Changed `domain/model/Models.kt`, `domain/story/StoryNormalization.kt`, and `data/repository/StoryMutations.kt`.

- Pilot converted the six fields of `SourceSyncState` from `var` to `val`. They contain only enums/numbers, so the value is fully immutable.
- The one normalization assignment now replaces the source state with `.copy(availability = ...)`. Existing sync mutation paths already used copies.
- Removed the now-unnecessary `sourceSyncState.copy()` from defensive snapshots.
- Also removed per-element `.copy()` for existing immutable `SourceMetric`; the owning collection still gets copied.
- Characterization tests found actual missing defensive copies for `aiContextChapterIndices`, `aiCoverContextChapterIndices`, and Patreon tier lists. The test failed before the fix. Snapshots now copy these collections.
- Story, Chapter, and SourceMetadata remain mutable. Their defensive copying, locks, generation checks, and persisted field names remain. No parallel model hierarchy or adapters were added.
- Keep the wider immutable Story/Chapter migration deferred. This pilot removes a bounded copying responsibility; it does not justify a repository-wide rewrite. The original handoff explicitly allows this conditional outcome.

Added `StorySnapshotIsolationTest` and extended `AppRepositoryTest`. Tests cover mutable nested collection isolation, input/output chapter selections, older published snapshots, legacy source-state wire names/defaults, and source-state sharing only after immutability. Repository, normalization, source failure, sync merge, archive, and backup suites passed.

The device fixture also passed JSON export/import and full ZIP export/restore through production repository APIs in the isolated instrumentation package. It checked source status, both AI chapter lists, chapter ID order, bookmark, and restored chapter text. This is a small fixture round trip, not a large user-library restore or minified/R8 validation.

## Validation actually completed

Final command completed even though its originating tool call was interrupted by the user. Its log was inspected afterward:

```sh
android/gradlew -p android :app:lintKotlin :app:ci -x copyDebugApkToProjectRoot --console=plain
```

Result: BUILD SUCCESSFUL in 43s. The log confirms `lintKotlin`, `checkKotlinFileSize`, `detekt`, `testInstrumentationUnitTest`, `assembleDebug`, `lintDebug`, and `ci`. JUnit XML reports **1,103 tests, zero failures, zero errors, zero skipped**.

Kotlin lint initially caught wildcard imports in new tests and one long JSON fixture line. These were corrected. The final format run and gate passed. Do not waste time diagnosing those already-fixed failures.

Device runs used:

```sh
ANDROID_SERIAL=emulator-5554 android/gradlew -p android :app:connectedInstrumentationAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.vinicius741.webnovelarchiver.app.AiSimplificationDeviceTest

ANDROID_SERIAL=emulator-5554 android/gradlew -p android :app:connectedInstrumentationAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.vinicius741.webnovelarchiver.app.AiJobNotificationDeviceTest
```

Latest simplification device XML: **5 tests, zero failures/errors/skips**. Notification device class separately passed **2 tests**. Existing full instrumentation suite has not been run after these changes.

Local evidence, if still present:

- `/tmp/simplification-full-gate.log`, `/tmp/simplification-format.log`
- `/tmp/simplification-s1-device.log`, `/tmp/simplification-s2.log`, `/tmp/simplification-s3.log`, `/tmp/simplification-s4.log`
- `/tmp/simplification-s5-before.log` contains the expected characterization failure; `/tmp/simplification-s5.log` is the passing suite.
- `/tmp/simplification-s5-device.log`
- `android/app/build/test-results/testInstrumentationUnitTest/`
- `android/app/build/outputs/androidTest-results/connected/instrumentation/`

## Remaining work, in order

1. Review the current diff for regressions and unnecessary abstraction. Focus on busy/idle transitions, draft visibility, selector persistence, late updates for another story, binding disposal, and snapshot coverage. Fix concrete issues, not unrelated architecture.
2. Read `.agents/skills/emulator-qa/SKILL.md`, `.agents/skills/build-and-install-apk/SKILL.md`, `.agents/skills/dev-launch-screen/SKILL.md`, and `.codex/skills/run-android-emulator/SKILL.md`. Use the debug-only build despite the generic build skill's release examples.
3. Install the final raw `android/app/build/outputs/apk/debug/app-debug.apk` with `adb -s <serial> install -r ...`. The currently installed debug app is the earlier S1 build. Preserve app data.
4. Finish the full manual emulator QA required after S4. Cold-start each target via `dev_start_screen`, wait for settled content, capture screenshot plus hierarchy, inspect images/logs, and exercise relevant controls. Include Library filters/tabs/selection/scroll, Details/overflow, AI Controls options and Back, Reader chapter navigation/TTS/voice settings, Updates/Follow Updates, Queue, Settings/Notifications/AI Settings, Add Story empty validation, and local EPUB generation where practical. Test compact/expanded width or rotation and restore emulator settings afterward. Avoid live source imports, bulk sync, or paid AI just for coverage.
5. Use isolated instrumentation fixtures for extra destructive or synthetic lifecycle checks. The original handoff lists more scenarios than have been tested. Complete the relevant gaps where useful, and explicitly record any remaining limitations rather than marking all original acceptance boxes complete automatically.
6. If production code changes, run relevant tests then the full gate again. If nothing changes, the completed gate is valid; don't repeat expensive checks without reason. Run additional device classes if the review calls for them.
7. Complete the per-phase execution record in `docs/architecture/simplification-implementation-handoff-2026-09-07.md`. It still says proposed/no implementation and has unchecked boxes. Record actual changes, verified outcomes, remaining limits, notification/coordinator consolidation decision, and limited S5 scope. Update `docs/README.md` if adding/moving docs. Root README, `android/AGENTS.md`, and cover documentation were already edited; proofread them for accuracy.
8. End with a concise report of what changed, validations performed, and anything intentionally deferred. Leave code uncommitted unless explicitly asked otherwise.

## Emulator and partial QA setup

The emulator was started in tmux session `webnovel-emulator` using the existing AVD and `-dns-server 8.8.8.8,1.1.1.1`. At handover it was the sole emulator, serial `emulator-5554`, AVD name verified `webnovel_api36`.

The debug library contained **169 novels** when read through the existing dev-library helper. Do not clear it. No paid generation, bulk downloads, phone operations, release build, or user-library restore occurred.

`/tmp/simplification-qa/` may contain:

- `s1.xml`: the settled S1 AI Controls hierarchy.
- `library.json`: read-only dev-library snapshot. Do not dump the entire file into conversation; it contains novel/chapter data.
- `target.json`: chosen local QA target, story `rr_173710`, chapter `3524171`, Chapter 1, already downloaded.
- `qa.py`: **created but not yet run or verified**. A helper for cold-starting screens, polling hierarchy, and capturing XML/PNG. Inspect before using; its readiness checks are basic and may need strengthening. It uses explicit emulator targeting and the debug package. Do not treat its output as proof without inspecting the resulting screen.

Example manual launch after verifying the emulator:

```sh
adb -s emulator-5554 shell am force-stop com.vinicius741.webnovelarchiver.nativeapp.debug
adb -s emulator-5554 shell am start \
  -n com.vinicius741.webnovelarchiver.nativeapp.debug/com.vinicius741.webnovelarchiver.app.MainActivity \
  --es dev_start_screen aicontrols --es dev_start_story rr_173710
```

Cold-start force-stops the process. Use it to reach the starting screen, not midway through a test meant to prove activity recreation or background continuity.
