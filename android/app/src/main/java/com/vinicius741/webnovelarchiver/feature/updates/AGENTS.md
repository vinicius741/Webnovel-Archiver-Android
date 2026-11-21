# Updates and manual sync

Applies to `feature/updates/` and the shared manual sync path. These rules add to `android/AGENTS.md`.

- `feature/story/ManualStorySync.kt` owns the common manual lookup/fetch/download-plan sequence. URL import and existing-story actions retain their own UI callbacks. The Updates destination (`feature/updates/`, `AppRoute.Updates`) replaced the removed threshold-based bulk Updates / Follow Updates screens: `domain/story/UpdatesPlanning` groups every novel from `ReleaseCadencePlanning` forecasts (May be out, To download, Expected soon, Running late, Recently synced, a 7-day calendar, and the nav badge count), and its per-novel sync and "Sync all that may be out" run one novel at a time through the same `ManualStorySync` path as Details.
