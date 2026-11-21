# Native Android Instructions

These instructions apply to the Kotlin app under `android/`. Commands run from the repository root.
This file holds current rules only, never history. Git and `docs/` hold history. Detail about one
subsystem goes in that package's `AGENTS.md` (below), not here. `ArchitectureRulesTest` fails when
this file grows past its size budget.

## Package instructions

Before editing a package listed here, read its `AGENTS.md`. Paths are under
`app/src/main/java/com/vinicius741/webnovelarchiver/`.

| Package `AGENTS.md` | Covers |
|---|---|
| `source/` | Source providers, Cloudflare detection, solving and Chromium transport, `feature/browser/`, Patreon early access, Royal Road notices |
| `download/` | Download engine, scheduler and service, and the global queue screen (`feature/downloads/`) |
| `domain/` | Models, story and metric rules, download job transitions |
| `data/` | JSON storage, restore crash recovery, backups, activity log, removed-from-source chapters |
| `tts/` | Speech engines, built-in models (Kokoro, Phonon), playback, player UI, reader read-along |
| `narration/` | Pre-generated chapter audio, generation queue, computers (narration servers), the Listen screen |
| `ai/` | OpenRouter, evidence selection, covers, characters, descriptions, AI Controls |
| `appupdate/` | GitHub Releases update checks and the update UI |
| `feature/details/` | Story page tabs and Trends |
| `feature/updates/` | The Updates destination and the shared manual sync path |

## Project map

- `app/`: Android lifecycle and process-wide wiring. `WebnovelArchiverApp` builds `AppContainer`, the
  manual DI container, on its IO scope. `MainActivity` shows startup UI, awaits the container, and
  hosts the shell.
- `navigation/`: route-based navigation (`AppRoute` with persisted ids, `AppNavigator`, `ScreenBack`,
  `RouteViewMemory`, `ScreenHost`, `ScreenModelStore`). `ShellNavigationPlanning` maps routes to the
  five destinations (Library, Updates, Listen = `AppRoute.Narration`, Downloads = `AppRoute.Queue`,
  Settings) and decides which routes hide the navigation or the mini player.
- `operations/`: app-level work that several screens start and observe: `StoryOperations` (typed
  progress flows for sync, cleanup, EPUB and AI jobs), `StoryWork`, which runs sync, cleanup and EPUB, and
  `ListenStarts`, which every "listen" request goes through.
- `feature/<flow>/`: screens grouped by user flow. See "Screen architecture" below. The UI follows the
  v3 design (`docs/architecture/ui-v3-design.md`).
- `domain/`: models and pure rules. `data/`: repository, JSON storage, backup, diagnostics.
- `source/`, `sync/`, `download/`, `epub/`, `cleanup/`, `tts/`, `narration/`, `ai/`, `appupdate/`:
  engines and their planning logic.
- `ui/`: programmatic View DSL, the single Obsidian theme (`ThemeManager`), `AppShell` (bottom shelf
  with mini player and navigation, or a right-hand rail at ≥ 600dp), bottom sheets, steppers,
  `CoverImageView`, and pure layout planning under `ui/layout/`.
- `notification/`: notification channels and Android 13+ permission helpers.
- `perf/`: debug-gated performance recorder (`docs/architecture/performance-tracking.md`). It is
  active only when a launch carries `perf_session 1`, and every hook sits behind `BuildConfig.DEBUG`.
  Add new timings through `PerfInstrumentation`.
- "The download screen" is ambiguous. It can mean `feature/downloads/QueueScreen.kt` (the global queue)
  or `feature/details/DetailsScreenDownload.kt` (the per-story banner). Confirm which one a change targets.
- Unit tests mirror production packages under `app/src/test/java/`. Instrumentation tests live in
  `app/src/androidTest/` and use the separate `instrumentation` application id.

## Architecture rules

- **Layering** (enforced by `ArchitectureRulesTest`):
  - `domain/` imports nothing from Android or other app packages.
  - Engines and `data/` do not import `feature/` or `ui/`.
  - `feature/` does not import `data.storage`.
  - The test's allowlist records the remaining known seams, and its ratchets may only go down.
- **Screen architecture**: a screen is a `ScreenModel` plus a View renderer.
  - The model is a plain Kotlin class with constructor dependencies. It exposes a `StateFlow` of
    immutable UI state and action methods, and it never touches `View` or `Context`.
  - The model lives in `ScreenModelStore`, keyed by `AppRoute.stableKey`. It is disposed, and its
    scope cancelled, when its route leaves the history.
  - The renderer builds Views from state, rebuilds or rebinds on state changes, and forwards events
    to the model. It launches no business work and does not call repositories or engines.
  - Lists use `ListAdapter`/`DiffUtil` over immutable row models.
  - New screens must use this pattern. Changing an old `ScreenHost.showXxx()` screen's state means
    migrating that screen first.
  - Jetpack Compose is a separate, later decision. Do not add it ad hoc.
- **Cross-screen work** (sync, cleanup, EPUB, AI starts) goes through `operations/`, which publishes
  typed progress. Screens observe it and never store operation state on `ScreenHost`/`MainActivity`.
- Build UI programmatically with Android Views. Do not add XML layouts.
- Put non-trivial deterministic decisions in pure planning functions with JUnit tests. Keep I/O in
  engines and orchestration. Trivial decisions stay inline; do not create a `*Planning` file for them.
- Use `AppContainer` for process-wide dependencies and constructor injection elsewhere. Do not add a
  DI framework without an explicit architectural request.
- `AppRepository` is the single owner of library, queue and settings mutations, through its
  transactions and cached flows. Do not create additional `AppStorage` owners that race the same JSON
  files. The activity and foreground services share one repository and storage lock. Do not read
  repository settings on the main thread before `awaitRepositoryReady`.
- Navigation is route-based. Process recreation restores by route identity (persisted ids), not by
  titles. New full-screen destinations need a route, an `AppRoute.parent`, and a dispatch entry.
- **Back** (app-bar arrow and system gesture) always pops the history. Screens do not declare their
  own back target. Keep the history free of duplicates through `AppNavigator`:
  - Opening a route that is already in the stack returns to it.
  - `Working` is replaced by the next screen.
  - A finished form uses `navigator.replace(...)`.
  - Deep entry points use `navigator.resetTo(route)`.
  - State that Back should clear first, such as a search, goes through
    `screen(backIntercept = ...)`. Call `refreshBackCallback()` when it changes on a root screen.
- Scroll offsets, list positions (`rememberListPosition`), and search text live in `RouteViewMemory`,
  keyed by `AppRoute.stableKey`. Notification taps use `openAppPendingIntent(requestCode, route)`
  with a request code unique to that notification.
- Keep file-based JSON storage compatible with existing data. Migrations, backup formats, chapter
  paths, and archive ids are compatibility-sensitive.
- Record an `ActivityLog.record` event at each new library-, sync-, download- or backup-changing
  decision point (`docs/architecture/activity-log.md`). Never include chapter text, cookies or keys.

## UI spacing and copy

- Follow `docs/architecture/ui-v3-design.md`:
  - No floating buttons for navigation.
  - Menus and pickers are bottom sheets (`showBottomSheet`, `showStyledOptionsDialog`).
  - Numeric settings use steppers (`makeStepper`/`stepperRow`); only seek bars stay sliders.
  - Covers are shown whole, never `CENTER_CROP`.
  - The reading bookmark uses the ivory `bookmark` color.
  - Story-page tabs switch with `navigator.replace`.
- Spacing:
  - Use the `ui/Spacing` scale: 8dp from a label to its control, 16dp between related controls,
    24dp between groups.
  - Screen side padding (16dp, `SCREEN_SIDE_PADDING_DP`) comes from `screen()`; do not add it again.
    A body whose content must reach the display's sides passes `fullWidthBody = true`.
- Forms and buttons:
  - Build forms with `ui/FormLayout` and `addItem(control, label)`. Use `FormLayout(app, dialog = true)`
    in dialogs, then call `applyFormStyle()`. Do not stack manual spacers on top of its defaults.
  - Add buttons with the DSL builders (`button`, `fullButton`, `row`/`flow`). They own the 16dp gap
    above an action.
- Mind first-frame layout cost:
  - `screen()` runs `dropUnusedBaselineAlignment()`. A body rebuilt outside `screen()` calls it again.
  - Keep destination builds free of main-thread planning.
- Keep titles and labels concise. Add helper copy only for a necessary choice or consequence.
- For spacing changes, inspect settled screenshots on both displays, including a live fold
  (`docs/architecture/ui-spacing.md`).

## Build and validation

- **File size**: production Kotlin files are capped at 1,000 lines by `checkKotlinFileSize` (baseline:
  `config/kotlin-file-size-baseline.txt`; runs in `:app:releaseCheck`). Complexity is guarded by
  detekt, so keep cohesive code in one file rather than splitting it to dodge a budget.
- **Commands**:
  - Debug build: `android/gradlew -p android :app:assembleDebug`
  - Targeted test: `android/gradlew -p android :app:testInstrumentationUnitTest --tests "com.vinicius741.webnovelarchiver.cleanup.TextCleanupTest"` (append `.method` or use `"*.Class.method"`)
  - Formatting: `:app:formatKotlin`; check: `:app:lintKotlin`
  - Static analysis: `:app:detekt`. `app/detekt-baseline.xml` lists the existing findings, so anything
    printed is new and fails the build. Fix it rather than baselining it, and delete a baselined
    `<ID>` once fixed. The file-shape rules (`TooManyFunctions`, `LongParameterList`, `ThrowsCount`,
    `SpreadOperator`, `MatchingDeclarationName`) are off on purpose. Do not re-enable them or suppress them.
  - Android lint: `:app:lintDebug`
  - Per-change gate: `android/gradlew -p android :app:lintKotlin :app:ci` (formatting, unit tests
    including `ArchitectureRulesTest`, detekt, debug build).
  - Release gate: `android/gradlew -p android :app:lintKotlin :app:releaseCheck` (`ci` plus
    `lintDebug` and `checkKotlinFileSize`). The Quality Gate workflow runs it on every push to `main`
    and files a `quality-gate` issue on failure.
- **Choose validation by change**:
  - Planning logic: run the narrowest relevant tests.
  - Kotlin production changes: run relevant tests, `lintKotlin` and `detekt`.
  - Broad changes: run `:app:ci`.
  - UI, navigation, lifecycle, foldable or service changes: also exercise the flow on the emulator.
  - Docs only: no build.
- **Release artifacts**: run `:app:assembleRelease`/`:app:assembleBeta` only when the user asks for a
  release or beta artifact. Release, beta and publishing rules are in
  `docs/release/build-and-publish.md`. Follow the release preflight in the `build-and-install-apk`
  skill before publishing. Keep the literal `versionCode N` / `versionName '…'` lines in
  `defaultConfig`, because the workflows parse them.
- **Gson**: every class Gson reads or writes needs a keep rule (`<init>(...)` and `<fields>`) in
  `app/proguard-rules.pro`. R8 full mode otherwise renames fields and drops generic signatures, and
  only the minified release shows it.
- **sherpa-onnx**: it comes from GitHub release assets via the `exclusiveContent` Ivy repository in
  `settings.gradle`, and its JNI reads Kotlin fields by name (keep the `com.k2fsa.sherpa.onnx.**`
  rule). Native code ships for `arm64-v8a` and `armeabi-v7a` in every build type. `debug` and
  `instrumentation` add `x86_64`. Never drop a phone ABI. Recheck `packaging.jniLibs` after a
  sherpa-onnx upgrade.
- **Dependencies** are lockfile-pinned. A dependency change needs
  `android/gradlew -p android :app:<config> --update-locks` for the affected compile and runtime
  configurations.
- **Kotlinter** rejects a `//` comment directly above a declaration with no blank line between them,
  and treats a file-level `/** */` header as dangling KDoc. `formatKotlin` does not fix either.
- **Offline simulator**: default AI, source import, download and Cloudflare QA to it
  (`python3 scripts/simulate.py list`; `docs/qa/offline-simulation.md`). Live OpenRouter or site checks
  must be requested explicitly; keep paid checks to 1–2 cheap calls.

## Emulator workflow

- The emulator is `webnovel_api36`, a Galaxy Z Fold7 approximation. Profile, initialization and
  multi-display screenshot details are in `docs/qa/dev-launch.md`.
- Fold QA:
  - Check both `emu fold` (cover) and `emu unfold` (inner), plus a transition while the screen is open.
  - Leave the emulator unfolded on Library.
  - Screenshots must follow settled UI.
- Reuse a running emulator. Start it with `scripts/emulator/start_emulator.sh` and stop it with
  `adb -s emulator-5554 emu kill`. If it won't boot, use the `emulator-recovery` skill.
- `scripts/redeploy.sh [screen]` rebuilds, installs and cold-starts the debug app. Use
  `scripts/watch-redeploy.sh` for iterative UI work. Both target an `emulator-` serial and fail closed.
- Debug package: `com.vinicius741.webnovelarchiver.nativeapp.debug`. Activity:
  `com.vinicius741.webnovelarchiver.app.MainActivity`. Redeploy is a cold restart.
- **Reach a screen for QA by cold-starting onto it** (`dev-launch-screen` skill;
  `--es dev_start_screen <token>`, details in `docs/qa/dev-launch.md`), never by tapping through the UI.
  For remote-dependent flows, start a `scripts/simulate.py` session first.
- Performance: `scripts/perf/perf_runner.py` (`perf-suite` skill). Its numbers come from a debug build
  on the emulator and are advisory only.

## Completion expectations

- Report the validation commands actually run and any checks not run.
- Do not claim emulator or device verification based only on a successful build.
- Read Gradle files and the manifest for current dependency versions, SDK levels, permissions and
  artifact configuration.
