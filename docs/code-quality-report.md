# Code Quality Report

Generated: 2026-06-11
Scope: **Native Kotlin app** (`android/`)
Status: Active development

---

## Executive Summary

The native Kotlin implementation is a fresh rewrite with no inherited technical debt from the React Native codebase. This report tracks code quality metrics for the active `android/` source tree.

---

## Source Metrics

| Metric | Value |
|--------|-------|
| Kotlin source files | ~39 (`android/app/src/main/java/`) |
| Kotlin test files | ~36 (`android/app/src/test/java/`) |
| Largest source file | `MainActivity.kt` (~1400 lines) |
| Second largest | `Engines.kt` (~1086 lines) |
| Architecture pattern | Planning + Engine |
| Test framework | JUnit 4 |
| Dependencies | Minimal (OkHttp, Jsoup, Gson, coroutines, AndroidX) |

---

## Priority 1: Large Files

### Production Code

| File | Lines | Recommendation |
|------|-------|----------------|
| `MainActivity.kt` | ~1400 | **Highest priority.** Extract screens into separate Fragment/Activity classes. See `documentation/REFACTORING_OPPORTUNITIES.md`. |
| `Engines.kt` | ~1086 | Split each engine class into its own file. Contains 4 engine classes + 2 utility classes. |
| `Sources.kt` | ~236 | Moderate size. Contains interface + registry + 2 providers + network client. Consider splitting providers. |

### Test Code
All test files follow a 1:1 pattern with planning modules and are reasonably sized.

---

## Priority 2: Architecture Concerns

### No Dependency Injection
Engines are manually instantiated in `MainActivity` and independently in each foreground service. This leads to:
- Duplicated engine construction in `DownloadForegroundService` and `TtsForegroundService`.
- No shared singleton lifecycle for `AppStorage` or `NetworkClient`.

**Recommendation:** Use `Application`-level singletons or a lightweight DI framework (Hilt/Koin).

### No Room/SQLite
All persistence is file-based JSON via Gson. Adequate for current scale but may need migration for complex queries or large libraries.

**Recommendation:** Monitor performance. Migrate to Room if library query performance becomes an issue.

### No Robolectric / Instrumented Tests
All tests are pure JUnit unit tests. No Android framework component testing (Activity, Service, WebView).

**Recommendation:** Add `androidTest/` instrumented tests or Robolectric for Activity and Service coverage.

---

## Priority 3: Legacy Artifacts

The following items are remnants of the React Native era and should be cleaned up:

| Item | Location | Recommendation |
|------|----------|----------------|
| `gradle.properties` RN properties | `android/gradle.properties` | Remove `hermesEnabled`, `reactNativeArchitectures`, `edgeToEdgeEnabled`, `newArchEnabled` entries. |
| `proguard-rules.pro` RN rules | `android/app/proguard-rules.pro` | Remove Reanimated and React Native ProGuard rules. |
| `app/`, `src/`, `modules/` directories | Project root | Consider moving to an `archive/` or `legacy/` directory, or removing entirely. |
| Root config files | `package.json`, `tsconfig.json`, `metro.config.js`, etc. | Consider removing or moving with the legacy RN code. |

---

## Build Verification

```bash
cd android
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

All three tasks should pass cleanly for a green build.

---

## Legacy React Native Report

The previous version of this report tracked React Native code quality metrics (203 source files, 36,857 lines, 5.59% duplication, 6 oversized files, 2 circular dependencies, 16 unused code issues). Those metrics apply only to the legacy `app/` and `src/` directories and are preserved in version control history.
