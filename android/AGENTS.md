# Native Android Instructions

These instructions apply to the active Kotlin application under `android/`. Commands below run from the repository root.

## Project Map

All packages below are rooted at `app/src/main/java/com/vinicius741/webnovelarchiver/`.

- `app/` owns Android lifecycle and process-wide dependency wiring; `navigation/` defines the `ScreenHost` contract.
- `feature/browser/` launches third-party sites in a browser-powered Custom Tab and receives import URLs through `MainActivity`; do not move third-party OAuth back into a WebView.
- `feature/` groups browser, cleanup, details, downloads, library, reader, settings, and shared story-action UI by user-facing flow.
- "The download screen" is ambiguous: `feature/downloads/QueueScreen.kt` (`showQueue`) is the global queue across all stories; `feature/details/DetailsScreenDownload.kt` is the per-story download banner on the details page. The top-level `download/` package is the engine/planning layer (`DownloadEngine`, `DownloadForegroundService`, scheduler) and contains no screen — confirm which surface a change targets before editing.
- `domain/model/` contains persisted/application models; `domain/archive/` and `domain/story/` contain pure domain rules.
- `data/repository/`, `data/storage/`, and `data/backup/` own state access and persistence concerns.
- `source/` owns network/source providers, while `sync/`, `download/`, `epub/`, `cleanup/`, and `tts/` own their respective engines and planning logic.
- `ui/` contains the programmatic View DSL, themes, responsive/foldable behavior, and reusable widgets; `ui/layout/` contains pure layout planning.
- Unit tests mirror production package paths under `app/src/test/java/`.

## Architecture Rules

- Build UI programmatically with Android Views. Do not add XML layout files.
- Put deterministic decisions and transformations in pure planning functions; keep Android, network, storage, and other I/O in engines or orchestration code.
- Add or update the matching JUnit test when changing planning logic.
- Use the existing `AppContainer` for process-wide dependencies and explicit constructor dependencies elsewhere. Do not add a dependency-injection framework without an explicit architectural request.
- Keep file-based JSON storage compatible with existing data. Treat migrations, backup formats, chapter paths, and archive IDs as compatibility-sensitive.
- New novel sites implement `SourceProvider` and must be registered in `SourceRegistry`. Source
  metadata parsing also discovers Patreon links; `StorySyncEngine` refreshes and persists public or
  tier-estimated Patreon statistics by default without creator-specific mappings. Bulk update
  tracking can skip the Patreon refresh to keep followed-novel checks fast while preserving existing
  saved stats.
- Archived snapshots remain read-only for sync and download.

## Build and Validation

- Debug build: `android/gradlew -p android :app:assembleDebug`
- Targeted test class: `android/gradlew -p android :app:testDebugUnitTest --tests "com.vinicius741.webnovelarchiver.cleanup.TextCleanupTest"`
- Targeted test method: `android/gradlew -p android :app:testDebugUnitTest --tests "*.TextCleanupTest.cleanupRemovesScripts"`
- Kotlin formatting: `android/gradlew -p android :app:formatKotlin`
- Kotlin format check: `android/gradlew -p android :app:lintKotlin`
- Static analysis: `android/gradlew -p android :app:detekt`
- Android lint: `android/gradlew -p android :app:lintDebug`
- Full local gate: `android/gradlew -p android :app:lintKotlin :app:ci`

Choose validation based on the change:

- Planning/core logic: run the narrowest relevant unit tests first.
- Kotlin production changes: run relevant tests, `:app:lintKotlin`, and `:app:detekt`.
- Broad or cross-cutting changes: run the full local gate.
- UI, navigation, lifecycle, foldable, or service changes: also build and exercise the affected flow on the emulator.
- Documentation-only changes do not require a Gradle build.

Run `:app:assembleRelease` only when the user explicitly requests a release artifact. Release signing requires local credentials and is not a routine validation step.

## Emulator Workflow

- Reuse a running `webnovel_api36` emulator; do not start another unnecessarily.
- Use `scripts/redeploy.sh` for a one-shot debug rebuild/install/relaunch and `scripts/watch-redeploy.sh` for iterative UI work.
- Both scripts must target an `emulator-` serial explicitly and fail closed rather than selecting a physical device.
- Debug package: `com.vinicius741.webnovelarchiver.nativeapp.debug`
- Main activity: `com.vinicius741.webnovelarchiver.app.MainActivity`
- Redeploy is a cold restart. Persisted `AppStorage` state survives; in-memory state does not.

### Dev launch screen (agent testing)

The debug variant cold-starts directly into a chosen screen via an `am start --es dev_start_screen <token>` intent extra, so you can test a feature in place (e.g. TTS in the reader, the download manager) without navigating there by hand. The feature is gated on `BuildConfig.DEBUG` and is a complete no-op in the release variant — it never affects phone releases.

**This is the required way to reach a screen for agent QA.** When you have built/installed the debug app and need to verify a change that lives on a screen (reader transport, settings, queue, etc.), cold-start onto that screen via the `dev-launch-screen` skill or the commands below — do NOT launch the app and tap through the UI by hand to get there. Tap/swipe only for interactions *after* you've landed on the target screen. Manual navigation wastes a session and is error-prone (you can end up on the wrong chapter, the wrong story, or fumbling the overflow menu). Skill triggering is heuristic; if the skill isn't auto-loaded for a post-build verification step, invoke it explicitly or run the cold-start command here directly.

Tokens: `library`, `queue` (download manager), `settings`, `updates`, `reader`, `details`, `addstory`.

- No-arg screens (`library`, `queue`, `settings`, `updates`, `addstory`) need nothing else.
- `reader` and `details` auto-pick the first story in the persisted library (and the first chapter for `reader`); supply `--es dev_start_story <id>` and (reader only) `--es dev_start_chapter <id>` to target a specific one. If the library is empty or the ids don't resolve, the app falls back to the normal library start rather than rendering a blank screen.
- The dev target takes precedence over browser-import and TTS-resume, so it reliably lands where asked.

Shorthand — `scripts/redeploy.sh <token>` rebuilds and relaunches straight into the screen:

```bash
scripts/redeploy.sh reader   # rebuild + cold-start in the reader
scripts/redeploy.sh queue    # rebuild + cold-start in the download manager
```

Cold-start without rebuild (app already installed):

```bash
adb -s <emulator-serial> shell am force-stop com.vinicius741.webnovelarchiver.nativeapp.debug
adb -s <emulator-serial> shell am start -n com.vinicius741.webnovelarchiver.nativeapp.debug/com.vinicius741.webnovelarchiver.app.MainActivity --es dev_start_screen reader
```

For the full launch command set (reader with explicit story/chapter, library-id discovery), use the `dev-launch-screen` skill. Planning logic lives in `app/DevLaunchPlanning.kt` with unit tests under `app/src/test/.../app/`.

## Completion Expectations

- Report the validation commands actually run and any checks not run.
- Do not claim emulator or device verification based only on a successful build.
- Keep this file focused on stable, non-obvious rules. Read Gradle files and the manifest for current dependency versions, SDK levels, permissions, and artifact configuration.
