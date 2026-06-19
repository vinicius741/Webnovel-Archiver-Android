# Native Android Instructions

These instructions apply to the active Kotlin application under `android/`. Commands below run from the repository root.

## Project Map

All packages below are rooted at `app/src/main/java/com/vinicius741/webnovelarchiver/`.

- `app/` owns Android lifecycle and process-wide dependency wiring; `navigation/` defines the `ScreenHost` contract.
- `feature/` groups browser, cleanup, details, downloads, library, reader, settings, and shared story-action UI by user-facing flow.
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
- New novel sites implement `SourceProvider` and must be registered in `SourceRegistry`.
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

## Completion Expectations

- Report the validation commands actually run and any checks not run.
- Do not claim emulator or device verification based only on a successful build.
- Keep this file focused on stable, non-obvious rules. Read Gradle files and the manifest for current dependency versions, SDK levels, permissions, and artifact configuration.
