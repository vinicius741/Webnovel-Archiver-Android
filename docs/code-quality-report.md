# Code Quality Report

Generated: 2026-05-05  
Script: `npm run quality:report` (`scripts/code-quality.js`)  
Status: **At baseline** (no regressions)

---

## Executive Summary

| Metric | Current | Baseline | Status |
|---|---|---|---|
| Source files | 203 | 203 | = |
| Total lines | 36,857 | 36,857 | = |
| Duplicated lines | 2,304 | 2,304 | = |
| Duplication % | 5.59% | 5.59% | = |
| Duplicate clones | 182 | 182 | = |
| Oversized files (>500 lines) | 6 | 6 | = |
| Circular dependencies | 2 | 2 | = |
| Unused code issues (knip) | 16 | 16 | = |

---

## Priority 1: Code Duplication (2,304 duplicated lines, 5.59%, 182 clones)

### Non-Test Duplication (highest impact)

These are the most actionable since they affect production code, not just test files.

| Clone | Lines | Files |
|---|---|---|
| `RegexRuleList.tsx` ↔ `SentenceList.tsx` | 48 | Nearly identical list components in `src/components/sentence-removal/` |
| `DownloadRangeDialog.tsx` ↔ `EpubConfigDialog.tsx` | 30 | Shared dialog patterns in `src/components/details/` |
| `useTabManagement.ts` ↔ `useTabs.ts` | 22 | Duplicated hook logic |

**Recommendation:** Extract a shared `ListEditor` component for `RegexRuleList`/`SentenceList`. Create a shared dialog utility for the dialog duplication. Consolidate `useTabManagement.ts` and `useTabs.ts`.

### Test-to-Test Duplication (largest volume)

| File | Self-duplication (lines) | Clones |
|---|---|---|
| `useStoryDownload.test.ts` | 223 | 15 |
| `useDownloadProgress.test.ts` | 214 | 14 |
| `DownloadManager.concurrent.test.ts` | 190 | 11 |
| `useReaderContent.test.ts` | 170 | 15 |
| `useLibrary.test.ts` | 154 | 17 |
| `useTabManagement.test.ts` | 134 | 14 |
| `DownloadManager.test.ts` | 127 | 7 |
| `storySyncOrchestrator.test.ts` | 121 | 7 |

**Recommendation:** Extract shared test setup/teardown into factory functions or `describe` blocks. Use `beforeEach` patterns and test data builders to reduce repeated mock setup.

### Cross-File Test Duplication

| Clone | Lines | Files |
|---|---|---|
| `ForegroundServiceCoordinator.concurrent` ↔ `.test` | 90 | 3 clones |
| `TTSStateManager.concurrent` ↔ `.test` | 37 | 1 clone |
| `useTTS.concurrent` ↔ `TTSStateManager.test` | 32 | 2 clones |
| `BackgroundService.test` ↔ `ForegroundServiceCoordinator.test` | 18 | 1 clone |

**Recommendation:** The `.concurrent.test.ts` files share large blocks with their non-concurrent counterparts. Extract shared mock factories into `__tests__/helpers/` or `__tests__/fixtures/`.

---

## Priority 2: Oversized Files (6 files exceed 500 lines)

### Production Code

| File | Lines | Recommendation |
|---|---|---|
| `app/index.tsx` | 673 | Split into smaller components and hooks. Extract tab navigation logic, screen layout, and state management into separate modules. |
| `src/services/download/DownloadManager.ts` | 476 (near threshold) | Monitor; approaching 500-line threshold. Consider splitting download coordination from queue management. |
| `src/services/TTSStateManager.ts` | 443 (near threshold) | Monitor; approaching threshold. The circular dependencies (see below) suggest this module has grown too broad. |

### Test Files

| File | Lines | Recommendation |
|---|---|---|
| `useStoryDownload.test.ts` | 865 | Largest file. Extract mock factories and test helpers. |
| `DownloadManager.concurrent.test.ts` | 706 | Heavy duplication with non-concurrent variant. |
| `storySyncOrchestrator.test.ts` | 630 | Split by test category. |
| `DownloadManager.test.ts` | 595 | Split by test category. |
| `ForegroundServiceCoordinator.concurrent.test.ts` | 570 | Shares setup with non-concurrent variant. |

---

## Priority 3: Circular Dependencies (2 cycles)

Both cycles involve `TTSStateManager.ts`, which at 443 lines is already one of the largest production files:

| Cycle | Files |
|---|---|
| #1 | `src/services/TTSStateManager.ts` ↔ `src/services/tts/TTSSessionPersistence.ts` |
| #2 | `src/services/TTSStateManager.ts` ↔ `src/services/tts/TTSStateEmitter.ts` |

**Root cause:** `TTSStateManager` imports from both sub-modules, and they import back from it.

**Recommendation:** Introduce an interface or event-based decoupling layer:
- Extract shared types into `src/services/tts/types.ts`
- Have `TTSSessionPersistence` and `TTSStateEmitter` depend on the types, not on `TTSStateManager` directly
- Use dependency injection or a simple event emitter to break the reverse dependency

---

## Priority 4: Unused Code (16 issues across 5 files)

### Unused Dependencies in `package.json` (7 production deps)

| Package | Line | Notes |
|---|---|---|
| `buffer` | 26 | Likely used via bundler polyfill; verify if still needed |
| `events` | 28 | Likely used via bundler polyfill; verify if still needed |
| `expo-background-task` | 30 | Not referenced in source |
| `expo-status-bar` | 43 | Not referenced in source |
| `expo-task-manager` | 44 | Not referenced in source |
| `string_decoder` | 57 | Likely used via bundler polyfill; verify if still needed |
| `url` | 60 | Likely used via bundler polyfill; verify if still needed |

### Unused Dev Dependencies (3 packages)

| Package | Notes |
|---|---|
| `@babel/preset-typescript` | May be unused if Expo handles TS compilation |
| `eslint-config-prettier` | Check if ESLint config references it |
| `typescript` (devDep) | Duplicated in both `dependencies` and `devDependencies` |

### Unlisted Dependencies (runtime imports without package.json entry)

| File | Missing Package |
|---|---|
| `app.json` | `expo-updates`, `expo-system-ui` |
| `eslint.config.cjs` | `@eslint/js` |
| `src/utils/textCleanup.ts` | `domelementtype`, `domhandler` |
| `modules/tts-media-session/src/index.ts` | `expo-modules-core` |

**Note:** Some unlisted packages may be transitive dependencies that are safe to use (e.g., `expo-modules-core` in a native module). The Node polyfills (`buffer`, `events`, `url`, `string_decoder`) may be required by the Metro bundler config.

**Recommendation:** Audit each unused dependency. Remove confirmed unused packages. For Node polyfills, check `metro.config.js` or bundler config to confirm they're still needed. Move `typescript` from `dependencies` to `devDependencies` only (currently listed in both).

---

## Summary of Recommended Actions (by impact)

1. **Break circular dependencies in TTSStateManager** — Architectural improvement; prevents future coupling issues
2. **Refactor `app/index.tsx`** (673 lines) — Split into smaller components/hooks for maintainability
3. **Extract shared test utilities** — Addresses the largest source of duplication (test self-duplication accounts for ~70% of all clones)
4. **Consolidate `RegexRuleList`/`SentenceList`** — 48 lines of identical production code; easy win
5. **Deduplicate dialog components** — `DownloadRangeDialog`/`EpubConfigDialog` share 30 lines
6. **Consolidate `useTabManagement`/`useTabs`** — 22 lines of duplicated hook logic
7. **Audit unused dependencies** — Remove dead weight from `package.json`
8. **Clean up concurrent test files** — Share setup with their non-concurrent counterparts to reduce 159+ lines of cross-file duplication
