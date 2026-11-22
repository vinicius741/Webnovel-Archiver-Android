# Trending

Applies to `feature/trending/` and `domain/metrics/TrendingPlanning.kt`. These rules add to `android/AGENTS.md`.

- The ranking is pure and lives in `TrendingPlanning` (signals, decayed growth, baseline discount, per-signal scaling, caught-up filter, one Patreon credit per creator). Change its rules there with `TrendingPlanningTest`, and keep `docs/architecture/trending.md` and the "How Trending works" sheet (`TrendingViews.kt`) in step with it.
- `TrendingScreenModel` (kept under `AppRoute.Trending`) reads every novel's metric history through `TrendingData` (`HostTrendingData` over the repository), caches each history until that novel's sync fingerprint changes, and plans on `Dispatchers.Default`. It replans 1.5 s after a library change, and only while a renderer collects `state`. The filter is screen state.
- `TrendingScreen.kt` is the renderer: a fixed chip row over a `ListAdapter` of hero, rows and footer (two columns at ≥ 600dp). Rows rebuild their small view tree on bind; they read the entry and open the novel, and never call the repository.
- The screen stores nothing. Rotation comes from the decay in the ranking, not from saved exposure history.
