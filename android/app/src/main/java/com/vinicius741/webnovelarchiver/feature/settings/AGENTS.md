# Settings

Applies to `feature/settings/` (the Settings hub and its pages). These rules add to `android/AGENTS.md`.

- Every page is a `XxxScreenModel` (kept in `ScreenModelStore` under its route's `stableKey`) plus its `SettingsXxx.kt`
  renderer: hub `SettingsScreenModel`, `DownloadSettingsScreenModel`, `TtsSettingsScreenModel` (Listening),
  `AiSettingsScreenModel`, `TabsScreenModel`, `DataBackupScreenModel`, `AppUpdateScreenModel`,
  `PatreonAccountScreenModel`. Each model reads and writes through a small data interface whose app-side
  implementation (`Host…Data`, `Repository…Data`) lives in the renderer file; that is the only place that touches
  the repository, `appContainer`, Android or `Context`.
- Renderers build from the model's state and bind with `bindWhileAttached`. A state field named `buildKey` says what
  rebuilds the page; everything else (stepper values, progress lines, download progress, online checks, spending) is
  patched in place. Typed drafts (sample text, new tab name, API key) are read at build time from the model, not
  emitted, so typing never rebuilds. A fold re-dispatches the route and rebuilds from the same model.
- Pages that save as they change use `Autosave` (`SettingsAutosave.kt`): the wait runs in the model's scope, the save
  in the process scope (`applicationScope`), and the renderer flushes on detach (the model also flushes when
  cleared). `UnsavedSettings` holds the newest unsaved value process-wide so the hub and a quick return show it. A
  choice that must save at once (engine, voice, model removal) updates the draft and flushes, so an older pending
  change is never saved over it.
- The full-backup export outlives Data & backup, so it runs in the activity-level `BackupExports`
  (`ScreenHost.backupExports`, like `UpdatesSyncs`); the page follows it, and its outcome dialog shows on whatever
  screen is open. Restore stays on `importFullBackupLauncher`.
- Listening: `SettingsTts.kt` hosts the narration setup section (`feature/narration` `setupSection`/`ServerRows`) and
  the `TtsSamplePreview` sample (renderer-owned, released on detach). The computers are checked only while the page is
  collected (`TtsSettingsScreenModel.serverChecks`). The debug speed test runs in the model; its dialog follows
  `speedTest` on its own so it survives a fold.
- AI settings' model rows (`feature/ai/AiModelControls.kt`) save through `AiSettingsScreenModel.saveModelChoice`;
  pickers that outlive a fold call `refreshAiModelFields`, which bumps the model's `modelsRevision`.
- Process-wide caches, not screen state: `SettingsHubCache` (last hub values shown while fresh ones load) and
  `UnsavedSettings`. Everything else resets when its route leaves the history.
