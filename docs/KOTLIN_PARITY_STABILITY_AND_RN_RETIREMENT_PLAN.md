# Kotlin Parity, Stability, and React Native Retirement Plan

**Created:** 2026-06-19  
**Scope:** Legacy React Native/Expo implementation in `app/`, `src/`, and `modules/` versus the native Android implementation in `android/`  
**Current decision:** **Do not delete the React Native implementation yet.** The native app has broad UI-level parity, but migration, behavioral, lifecycle, and cross-format evidence is not complete.

## Purpose

This document is the execution plan for proving that the Kotlin application can replace the React Native application without losing user data, supported product surfaces, features, edge-case behavior, or operational knowledge.

Existing documents such as `LEGACY_VS_NATIVE_FEATURE_COMPARISON.md` and `NATIVE_FUNCTIONALITY_MAP.md` are useful inventories, but their statement that the apps are already at parity is not sufficient for deletion. Those documents mainly establish that corresponding screens and code paths exist. Retirement requires evidence that the implementations produce equivalent outcomes under normal use, invalid input, partial failure, cancellation, process death, restore, and upgrade.

## Definition of done

React Native can be removed only when all of the following are true:

1. Every item in the feature and logic matrix below has an owner, tests, runtime evidence, and a final disposition of `Equivalent`, `Intentional native difference`, or `React Native behavior rejected as a bug`.
2. Every intentional difference has a written product decision and native acceptance criteria.
3. Existing Android users can upgrade or migrate without losing their library, tabs, downloaded chapters, bookmarks, cleanup rules, settings, and TTS state that the product promises to retain.
4. JSON and full-ZIP backups have been tested in both directions with real artifacts, not just compared by model names.
5. Native engine, service, process-death, and document-picker flows have automated or repeatable emulator coverage.
6. Release identity, signing, versioning, deep links, and distribution are resolved.
7. The supported-platform decision is explicit. Deleting React Native also deletes the current iOS and web implementations unless they are preserved elsewhere.
8. The React Native behavior oracle has been converted into durable fixtures, tests, and documentation that remain after its source is deleted.
9. A release candidate completes the soak and migration plan with no unresolved P0/P1 defect.

## Immediate evidence-backed findings

These are not hypothetical checklist items. They were found by comparing the current source and must be resolved or explicitly accepted.

| Priority | Finding | Evidence | Required closure |
|---|---|---|---|
| P0 | The legacy Android package is `com.vinicius741.webnovelarchiver`, while native release uses `com.vinicius741.webnovelarchiver.nativeapp`. The native app is therefore a separate install, not an in-place upgrade. | `app.json`; `android/app/build.gradle` | Decide the production package, signing key, version-code lineage, store listing, and whether migration is in-place or user-driven. Prove the chosen path on an emulator using a release-like legacy install and populated data. |
| P0 | Native direct migration from React Native AsyncStorage is not implemented. `migrateLegacyAsyncStorageIfPresent()` detects `RKStorage`, then writes an empty native library index. A different package cannot access the old private database anyway. | `android/.../data/storage/Storage.kt` | Implement a real migration path or require/export a full backup before upgrade. Never mark migration complete by creating an empty index. Add populated, corrupted, interrupted, and retry migration tests. |
| P0 | Deleting React Native removes iOS and web product surfaces. The native project is Android-only. | `app.json`; `package.json`; `android/` | Record whether iOS/web are officially discontinued, moved to another repository, or still supported. Obtain product sign-off before deletion. |
| P1 | React Native full backups store `foldLayoutMode` and `themeStorage`; native restore writes `displayPreferences` only and does not map those legacy fields when `displayPreferences` is absent. | `src/services/storage/BackupService.ts`; `android/.../data/storage/Storage.kt` | Add cross-version golden backups and map legacy configuration fields into `DisplayPreferences`. Verify native-to-RN only if backward compatibility remains a requirement. |
| P1 | Scribble Hub pagination differs. React Native fetches later pages when the page count is greater than one **or** the first page has at least 15 chapters. Kotlin only fetches when the first page has at least 15 chapters. | `src/services/source/providers/ScribbleHubProvider.ts`; `android/.../source/Sources.kt` | Port the missing pagination trigger and all RN pagination tests, including incomplete visible pagination, duplicate pages, short final pages, and trailing `0`. |
| P1 | The React Native downloader pauses remaining jobs for a story after repeated 403/429 responses. Native retries individual jobs but has no equivalent story-level circuit breaker. | `src/services/download/DownloadManager.ts`; `android/.../download/DownloadEngine.kt` | Decide whether the circuit breaker is required. If yes, implement and test it. If no, document why the native retry policy is safer and test server-throttling behavior. |
| P1 | Native cancel/pause changes queue state but does not cancel an in-flight OkHttp call. A job can still write chapter content and mutate the story after it was cancelled or paused; only the final queue transition checks cancellation. | `android/.../download/DownloadEngine.kt` | Add controllable-network integration tests for cancel/pause at each phase, then make queue state, file writes, and story mutation atomic with the chosen semantics. |
| P1 | Native sync overwrites `description` and `score` with null when parsing yields no value, while RN sync preserves existing values for those fields. | `src/services/story/storySyncOrchestrator.ts`; `android/.../sync/StorySyncEngine.kt` | Define metadata merge policy field-by-field and test transient missing selectors, source edits, and deliberate field removal. |
| P1 | The native manifest accepts the `webnovel-archiver` scheme, but `MainActivity` does not route `intent.data` in `onCreate` or `onNewIntent`. Expo Router previously owned scheme routing. | `android/app/src/main/AndroidManifest.xml`; `android/.../app/MainActivity.kt`; `app/_layout.tsx` | Define supported deep-link forms, implement routing or remove the unused filter, and test cold/warm launches with valid and invalid links. |
| P2 | RN rejects downloaded chapter content shorter than 50 characters. Native accepts any non-missing provider result. | `src/services/download/DownloadManager.ts`; `android/.../download/DownloadEngine.kt` | Determine whether short legitimate chapters exist. Replace the heuristic with a provider/content contract or document the intentional difference. |
| P2 | Global download settings are read once when the native process loop starts. Changes during an active run may not apply until a later service run; RN updates its active manager. | `android/.../download/DownloadEngine.kt`; `src/hooks/common/useSettings.ts` | Specify live-setting semantics and add a running-queue settings-change test. |

The table is a starting set, not a complete defect list. Completing the matrix is intended to uncover the remaining differences.

## Required audit method

Each matrix row must be investigated with the same protocol:

1. Identify the user-visible entry point and every RN implementation file, hook, service, native module, storage key, and test involved.
2. Write the behavior contract in implementation-neutral terms: inputs, output/state changes, persistence, errors, cancellation, retry, and lifecycle behavior.
3. Identify the Kotlin UI, planner, engine, repository/storage, service, manifest, and tests involved.
4. Build a table of RN cases versus Kotlin cases. Treat RN Jest test names as behavioral leads, not automatically correct product requirements.
5. Add shared fixtures or golden vectors that both implementations can consume where possible.
6. Add the smallest Kotlin unit test for pure logic, then engine/integration coverage for I/O, then emulator coverage for Android behavior.
7. Exercise at least: happy path, empty input, malformed input, duplicates, boundary values, partial failure, retry, cancellation, process death, restart, and concurrent actions.
8. Record one disposition: `Equivalent`, `Intentional native difference`, `RN bug not carried forward`, `Native gap`, or `Blocked by product decision`.
9. Link evidence: test class/method, fixture, emulator QA record, and defect/fix commit.

No row is complete based solely on source inspection or a successful debug build.

## Differential test assets to create before feature work

Create these durable assets while React Native remains available:

- A machine-readable parity ledger, preferably `docs/parity/parity-ledger.csv` or JSON, with IDs matching this document.
- Sanitized Royal Road and Scribble Hub fixture corpora for story pages, chapters, pagination, missing selectors, reordered chapters, removed chapters, duplicate chapters, empty responses, and bot/rate-limit responses.
- RN-generated JSON backup versions and full-ZIP backups containing:
  - empty and non-empty tabs;
  - downloaded and undownloaded chapters;
  - bookmarks and pending-new chapters;
  - split EPUB metadata;
  - archived stories;
  - all cleanup-rule target/flag variants;
  - non-default download, source, filter, fold, theme, and TTS settings;
  - a paused/resumable TTS session.
- Kotlin-generated equivalents for backward/forward compatibility tests.
- Golden cleanup inputs/outputs, chapter merge states, queue transition traces, TTS chunk lists, and EPUB manifests.
- A seeded legacy Android test build and data-generation script so upgrade/migration tests are reproducible after RN deletion.
- Screen recordings or screenshots only for interaction/visual references that cannot be expressed as assertions. They are supporting evidence, not the primary oracle.

Keep copyrighted fixture content short and synthetic where possible. Retain the structural HTML needed to exercise parsers.

## Feature and logic investigation matrix

### P-01 Product surface, release identity, and upgrade

- Decide Android-only versus Android+iOS+web support.
- Inventory current store listings, package/application IDs, schemes, signing certificate fingerprints, version codes, EAS profiles, and native release configuration without committing credentials.
- Prove whether the native binary can be signed as an update to the legacy binary.
- Define side-by-side install behavior if the native package remains different.
- Define migration UX, rollback behavior, and user messaging.
- Test cold update, update with populated data, update with active downloads, update with a saved TTS session, rollback attempt, uninstall/reinstall, and restore onto a clean install.
- Exit evidence: signed release-candidate lineage and a written platform/distribution decision.

### P-02 Models, serialization, defaults, and migrations

- Compare every field and default for `Story`, `Chapter`, `Tab`, `AppSettings`, `SourceDownloadSettings`, `ChapterFilterSettings`, `EpubConfig`, `RegexCleanupRule`, `TtsSettings`, `TtsSession`, and `DownloadJob`.
- Test absent, null, unknown, malformed, and future fields.
- Verify enum/wire strings, numeric coercion, timestamps, ID generation, tab-null semantics, archive metadata, and mutable-list behavior.
- Compare RN storage keys with Kotlin filenames/envelopes and establish a versioned migration table.
- Test legacy monolithic library to per-story RN migration as input to the final migration path.
- Test partial writes, missing index entries, orphan story records, corrupt individual records, corrupt envelopes, and interrupted migration.
- Define downgrade and forward-compatibility policy for durable JSON schema versions.

### P-03 Library and tabs

- Add/fetch story, duplicate add, canonical URL change, assignment during add, and unsupported/chapter URL rejection.
- All/Unassigned/custom tabs, empty-library tab visibility, persisted active tab, swipe/tap synchronization, tab deletion and story reassignment.
- Search title/author behavior, case/whitespace/Unicode handling, source/tag filter intersection, counts, and clearing filters.
- Every sort key, ascending/descending order, null values, numeric score parsing, stable tie-breaking, and default recency.
- Single and multi-selection, select all, move, delete, cancellation, rotation/fold changes, and stale selections after data updates.
- Story card metadata, progress, archive state, cover loading/failure/zoom, bookmark indicator, and accessibility labels.
- Large-library performance at 100, 1,000, and an agreed upper-bound story count.

### P-04 Source registry, URL validation, network, and browser import

- Provider matching and ordering, case variants, host spoofing, schemes, query/fragment forms, canonical URLs, series versus chapter URLs, and fallback IDs.
- Request headers, form encoding, redirect behavior, compression, character sets, timeouts, TLS failure, offline state, 403/429/5xx retry, cancellation, and response-size limits.
- Royal Road metadata fallbacks, chapter selectors, IDs, title cleanup, ordering, content removals, missing content, relative URLs, and live-fixture drift monitoring.
- Scribble Hub metadata fallbacks, stable fallback IDs, AJAX pagination triggers, page count hints, more pages than visible, repeated pages, short final page, empty page, trailing `0`, ordering, and content removals.
- Browser address/search resolution, history back/forward, refresh, loading state, redirects, external open, login/cookie behavior, unsupported pages, and selected-tab import.
- WebView Safe Browsing, JavaScript boundaries, mixed content, file/content access, downloads, popups, renderer death, and disposal.
- Establish a scheduled parser-fixture check against intentionally captured samples; do not rely on live-site tests as the only gate.

### P-05 Story sync, merge, and archive snapshots

- New story versus existing story paths, stable ID and URL alias matching, slug changes, duplicate IDs, missing IDs/URLs, reordering, insertions, and removals.
- Field-by-field metadata merge policy for title, author, cover, description, tags, score, and canonical URL.
- Preserve downloaded file paths/content, bookmark remapping, pending-new IDs, status, date added, tab, EPUB paths/config/stale state, and archive flags as specified.
- Confirm when a removal creates an archive, what happens if file copy partially fails, collision behavior for archive IDs, and whether multiple syncs create duplicate snapshots.
- Prove archived snapshots are read-only for sync/download but remain readable, exportable, movable, and deletable as intended.
- Failure injection: empty chapter list, parser failure, network failure after metadata, storage failure during archive, and process death during save.
- Add engine tests around `StorySyncEngine`; planner tests alone are insufficient.

### P-06 Download enqueue, scheduling, execution, and recovery

- Queue modes: all, remaining, individual, explicit range, count, bookmark-forward, multi-select, and duplicate enqueue.
- Exact job IDs, terminal-job reset, retry-count policy, invalid indexes, already-downloaded chapters, archive guards, and story status updates.
- Global/per-source concurrency and delay, fairness across stories/sources, dynamic setting changes, retry wake-up timing, and clock changes.
- Error classification and retry counts/delays for offline, DNS, timeout, cancellation, 403, 429, 408, 5xx, parser failure, missing story/provider/URL, and storage failure.
- Decide and test repeated-rate-limit circuit-breaker behavior.
- Pause/resume/cancel/remove at pending, selected, network-active, parsing, file-writing, and story-persisting phases. Confirm no cancelled job later mutates data.
- Process death with pending/downloading/paused/retry-waiting jobs; service restart; activity/service races; duplicate service starts; notification denial; app force-stop; device reboot policy.
- Queue/story/file atomicity, orphan job cleanup, orphan chapter files, stale story status recovery, and deletion while downloading.
- Foreground notification progress, content, actions, completed state, channel, permission behavior, and Android background-start restrictions.
- Performance/thermal behavior for large queues and slow sources.

### P-07 Text cleanup and rule management

- Default sentence list equality and ordering.
- Exact sentence behavior: case, flexible whitespace, HTML entities, nested text nodes, whole paragraphs, repeated occurrences, scripts/styles, and empty rules.
- Regex literal parsing, supported flags, escaped slashes/hyphens, unsafe patterns, replacement cap, malformed persisted rules, duplicates, enable/disable, ordering, and target (`download`, `tts`, `both`).
- Quick separator generate/parse/round-trip, min count, whole-line behavior, Unicode separator characters, live pattern preview, and sample-text preview.
- Confirm cleanup ordering: provider extraction, sentence removal, regex cleanup, HTML normalization, storage, reader, TTS, and EPUB.
- Add/edit/delete/export behavior, file MIME/share behavior, cancellation, and the explicit decision that neither app imports cleanup-rule exports.
- Run golden vectors through both engines and classify every output difference.

### P-08 Reader, chapter state, and navigation

- Downloaded/undownloaded/missing-file/corrupt-file states and recovery messages.
- HTML sanitization, preserved formatting, tables, headings, links, images, very large chapters, encodings, dangerous URLs/events/scripts, and injected TTS bridge isolation.
- Previous/next boundaries, chapter position, reader-to-details/back stack, process recreation, and story deletion while open.
- Bookmark toggle, mark-read semantics, `lastUpdated` policy, EPUB start-after-bookmark advancement, and indicator refresh.
- Font scaling limits, dark reader mode, copy plain/formatted text, persistence, theme changes, rotation, compact/expanded/folded layouts, and renderer termination.
- Accessibility: focus order, content descriptions, font scaling, contrast, touch targets, TalkBack, and hardware keyboard navigation.

### P-09 Text-to-Speech and media integration

- HTML-to-text formatting and cleanup, chunk boundary equality, empty chunks, punctuation, Unicode, tables, and changed chunk size.
- Start from beginning/paragraph/saved chunk, tap/double-tap contract, highlight synchronization, transport visibility, chunk counter, next/previous bounds, and next-chapter continuation.
- Pitch/rate/voice application, unavailable saved voice, engine initialization failure, missing language data, network-only voices, and runtime setting changes.
- Pause/resume/stop/restart, rapid commands, stale callbacks, utterance errors, silent-stop recovery, and concurrent reader/service commands.
- Persisted session eligibility, expiry if desired, missing story/chapter, app relaunch, process death, force-stop, and clear-session behavior.
- MediaSession state/metadata/actions, lock-screen and quick-settings controls, headset/Bluetooth buttons, notification actions, audio focus, phone calls, other media, noisy-audio events, route changes, and notification denial.
- Service lifecycle on Android 8 through target API, `START_STICKY` recreation, cold notification action, and teardown of `TextToSpeech`, MediaSession, wake locks, and listeners.
- Add an injectable TTS adapter so engine behavior can be tested without relying only on real audio.

### P-10 EPUB selection, generation, storage, open, and share

- Default/per-story max chapters, all range bounds, start-after-bookmark, downloaded-only inclusion, no-content behavior, original chapter numbering, splitting, and stale detection.
- Filename sanitization, Unicode, collision/overwrite behavior, volume labels, nested/fold layout modes, and old `epubPath` versus `epubPaths`.
- Cover fetch type/size/failure, metadata escaping, description/tags, identifier stability, OPF manifest/spine/guide, NCX navigation, CSS, XHTML sanitization, and `mimetype` first/uncompressed.
- Streaming failure cleanup, low storage, process death, regeneration, stale old volumes, story deletion, and large-book memory/time behavior.
- Validate generated files with an EPUB validator and at least two Android readers. Compare structural manifests with RN golden EPUBs rather than requiring byte equality.
- Verify FileProvider URIs, MIME types, URI grants, chooser behavior, missing target apps, and persisted access assumptions.

### P-11 JSON backup, full backup, restore, and data clearing

- JSON v1/v2 acceptance policy, empty library, size limit, malformed types, duplicates, unknown fields, tab merge/order collisions, metadata merge, and scrubbing of local download/EPUB state.
- Full-ZIP manifest version/format, safe paths, URL encoding, duplicate entries, missing/extra files, corrupt/truncated ZIP, entry/count/expanded-size limits, and hostile archives.
- RN-to-Kotlin and Kotlin-to-RN artifacts for every configuration and model field. Record directional compatibility explicitly.
- Verify chapter count/path relinking, file content hashes, bookmark/pending/archive state, settings, source overrides, filters, tabs, cleanup rules, TTS settings/session, theme, and fold/display preferences.
- Decide explicitly that EPUB binaries and download queue are or are not part of a full backup; both are currently omitted.
- Transactionality and rollback for failure during copy, parse, validate, stage verification, root swap, and post-restore repository refresh.
- Storage-full behavior, document provider cancellation/revocation, large backup performance, export sharing, and retained temporary files.
- Clear-local-storage confirmation, scope, active service shutdown, cache/files left behind, and post-clear default reconstruction.

### P-12 Settings, themes, foldables, and configuration changes

- Every default and validation boundary for download concurrency/delay, EPUB size, source overrides, TTS pitch/rate/chunk/voice, filters, theme, screen layout, fold layout, and reader preferences.
- Blank, non-numeric, negative, overflow, locale decimal, out-of-range, and corrupted persisted inputs.
- Save timing and whether changes apply immediately to active downloads/TTS/reader/UI.
- Four theme token parity, status/navigation bars, dialogs, WebViews, light/dark contrast, recreation, and saved legacy theme mapping.
- Compact/medium/expanded widths, cover/inner/auto overrides, physical fold posture, rotation, multi-window, font scale, display size, and activity recreation/process death.
- Verify all user-facing text is localized-ready and pluralization is correct.

### P-13 Navigation, intents, lifecycle, and error handling

- Map every Expo route and parameter to a native destination and back behavior.
- Cold/warm deep links, invalid/encoded story/chapter IDs, duplicate intents, saved TTS resume precedence, and external URL handling.
- Activity recreation, background/foreground, low-memory process death, task removal, configuration changes, and state restoration for each screen.
- Root/UI error boundary equivalent or explicit crash policy; user-visible retry paths for recoverable failures.
- Document picker, share/open intents, chooser absence, package visibility, URI grants, and return-to-app behavior.
- Notification permission rationale/denial/retry and foreground-service start failures.

### P-14 UI behavior, accessibility, and performance

- Convert `UI_AUDIT.md` and both emulator QA reports into tracked cases; re-test fixed defects and close remaining findings intentionally.
- Compare enabled/disabled/loading/error/empty/success states for every action, dialog, list, and selection screen.
- Prevent duplicate taps and conflicting async operations; preserve progress and selection across re-render.
- Test TalkBack labels/order, dynamic type, switch/checkbox semantics, keyboard, contrast, touch size, and reduced-motion expectations.
- Measure cold start, library render, details with 1,000+ chapters, selection, search/filter, cleanup preview, long reader chapter, queue refresh, backup, and EPUB generation.
- Add StrictMode/logcat checks for main-thread disk/network access, leaked activities/WebViews, ANRs, and uncaught exceptions.

### P-15 Security, privacy, and data durability

- Threat-model imported HTML, browser content, backup ZIPs/JSON, regex input, external URIs, and FileProvider exposure.
- Verify WebView settings, bridge exposure, sanitization, Safe Browsing, cleartext policy, redirect/host validation, and mixed content.
- Verify atomic writes, fsync/rename behavior, corruption recovery, schema envelopes, concurrent writers, orphan cleanup, and storage permissions.
- Confirm `allowBackup=false` is intentional and that manual backup is the supported recovery path.
- Scan release artifacts for legacy secrets, signing material, debug components, unnecessary permissions, exported components, and JavaScript bundles.

### P-16 Build, release, observability, and supportability

- Run unit, lint, detekt, debug build, and emulator smoke gates in CI.
- Add instrumented tests for primary flows and foreground services; current pure planning coverage is not enough.
- Establish crash/ANR observability appropriate for the product, with no novel content or sensitive local paths in telemetry.
- Test minSdk, representative intermediate Android versions, target API, 32/64-bit emulator/device classes as applicable, and Play pre-launch reports.
- Verify release minification/resource shrinking decision, signing, reproducibility, versioning, upgrade install, and artifact naming.
- Update README, `AGENTS.md`, development commands, architecture docs, issue templates, and support/migration instructions when workflows change.

## Test architecture to add

### Layer 1: Pure differential/golden tests

- Parser outputs from identical HTML fixtures.
- Story merge and metadata policy from identical serialized state.
- Cleanup outputs from identical text/HTML/rules.
- Queue state-machine transitions from identical event traces.
- TTS chunks and session transitions from identical input.
- EPUB selections, filenames, manifests, and XHTML from identical models.
- Backup merge/restore normalized state from identical artifacts.

### Layer 2: Native engine integration tests

Use temporary storage and injectable network/filesystem/clock/TTS dependencies for:

- `StorySyncEngine`
- `DownloadEngine`
- `EpubEngine`
- `TtsEngine`
- `AppStorage` and `AppRepository`

These tests must prove multi-document and file/state atomicity, not only planner output.

### Layer 3: Android component tests

- Activity navigation and state restoration.
- Download/TTS foreground service lifecycle and notification actions.
- MediaSession and media-button routing.
- WebView settings, JS bridge, and renderer cleanup.
- FileProvider, document picker, sharing, and external EPUB open.
- Deep links and intent routing.

### Layer 4: Emulator end-to-end suites

Run on `webnovel_api36` by default and add representative lower-API coverage in CI. Suites should include:

1. Legacy populated install to native migration/upgrade.
2. Royal Road add → sync → download → read → TTS → EPUB → backup.
3. Scribble Hub paginated add → rate-limit/failure recovery → completion.
4. Tabs, filters, sort, selection, archive, and deletion.
5. Full backup restore onto a clean native install and state/hash comparison.
6. Process kill/relaunch during sync, download, EPUB, TTS, and restore where safe and deterministic.
7. Compact, rotated, expanded, and foldable postures.

For all device-targeting ADB commands, follow `AGENTS.md`: discover with `adb devices -l`, select exactly one healthy `emulator-` serial, and pass it explicitly to every operation. Never fall back to a physical device.

## Execution phases

### Phase 0: Freeze the oracle and decide product scope

- Freeze feature additions on RN except migration/export fixes.
- Make the Android/iOS/web support decision.
- Resolve package/signing/store strategy.
- Create the parity ledger and golden artifacts.
- Reword existing parity docs so they do not claim retirement readiness before this plan is complete.

**Exit gate:** no unresolved P0 product decision; RN behavior oracle is reproducible.

### Phase 1: Data portability and upgrade safety

- Implement and test the chosen migration path.
- Close cross-version JSON/full-backup gaps.
- Test corruption, rollback, and storage-full conditions.
- Prove release-like upgrade with populated data.

**Exit gate:** zero data-loss defects; normalized pre/post state and chapter hashes match.

### Phase 2: Core behavior parity

- Complete source, sync/archive, cleanup, queue/download, EPUB, reader, and TTS differential tests.
- Fix or disposition every behavioral difference.
- Add engine integration tests.

**Exit gate:** all P-02 through P-11 ledger rows resolved with automated evidence.

### Phase 3: Android lifecycle and UI stability

- Add component/instrumented coverage.
- Exercise process death, services, notifications, intents, WebViews, document providers, foldables, accessibility, and performance.
- Resolve existing UI audit and emulator QA findings.

**Exit gate:** all P-12 through P-16 rows resolved; no open P0/P1 defect.

### Phase 4: Release candidate and soak

- Run the full local/CI gate and release signing checks.
- Perform a clean-install suite and at least two populated upgrade/migration repetitions.
- Run long queues, long TTS, large EPUB, and repeated backup/restore soak tests.
- Review crash/ANR/logcat evidence and storage integrity after the soak.

**Suggested minimum:** seven days of normal daily use plus overnight long-running cases, adjusted upward if the app has an external user base.

**Exit gate:** signed-off release report, no unresolved P0/P1, accepted P2 list, and rollback instructions tested.

### Phase 5: React Native deletion

Perform deletion in its own reviewable change after all earlier gates pass. Do not combine parity fixes with the deletion.

## React Native deletion runbook

### Before deletion

- Tag or otherwise preserve the last RN revision and document how to retrieve it.
- Archive the seeded legacy APK/build metadata needed for future migration tests without storing credentials.
- Ensure all required RN tests have Kotlin equivalents or retained golden vectors. Record exceptions.
- Ensure parser fixtures, backup artifacts, TTS/media behavior notes, theme tokens, and UI references no longer depend on executing RN.
- Search docs, scripts, CI, editor tasks, and repository instructions for RN commands and paths.
- Confirm the native release no longer reads RN source/assets at build or runtime.
- Create and restore a final RN full backup into the release-candidate native app.
- Verify rollback/support instructions for users who did not migrate before RN distribution ends.

### Candidate tracked paths to remove

Review with `git ls-files`; do not remove anything merely because it has a JavaScript extension if native tooling still uses it.

- `app/`
- `src/`
- `modules/tts-media-session/`
- `app.json`
- `eas.json`
- `package.json` and `package-lock.json`
- `metro.config.js`
- `react-native.config.js`
- `jest.config.js` and `jest-setup.ts`
- `eslint.config.cjs`
- `tsconfig.json`
- `patches/`
- `plugins/withAndroidForegroundService.js`
- RN-only code-quality scripts/baselines and generated coverage artifacts if tracked or documented
- RN-only root assets after confirming launcher/splash/source documentation needs

Do not remove `scripts/redeploy.sh`, `scripts/watch-redeploy.sh`, `.codex/skills/run-android-emulator/`, or Android assets merely because they live outside `android/`; they are part of the native workflow.

### Documentation and configuration changes in the deletion change

- Make the root README native-only.
- Remove legacy scope language from root `AGENTS.md` and keep native build/device safety rules current.
- Update all docs that refer to `app/`, `src/`, Expo, Jest, EAS, Metro, npm, or React Native as live code.
- Preserve this plan or replace it with a completed parity report containing links to final evidence.
- Remove obsolete `.gitignore`, editor, CI, and dependency entries.
- Confirm no tracked/generated RN build output remains.

### Verification after deletion

Run at minimum:

```text
rg -n "React Native|ReactNative|Expo|expo-|npm |npx |yarn |Jest|Metro|app/|src/|modules/tts-media-session" \
  README.md AGENTS.md android docs scripts .github 2>/dev/null

android/gradlew -p android :app:lintKotlin :app:ci
```

Then:

- Build and install the debug APK on the explicitly selected emulator.
- Run the native end-to-end smoke suite.
- Restore the preserved final RN full backup and compare normalized state/file hashes.
- Test deep links, download/TTS notifications, browser import, EPUB open/share, and both backup pickers.
- Inspect the final APK/AAB for JS bundles, RN/Expo classes, obsolete resources, and permissions.
- Confirm repository checkout/build works with no Node.js installation.
- Confirm CI, developer setup, and release creation work from a clean clone.

### Rollback plan

- Keep the pre-deletion tag and migration artifact accessible.
- Never require downgrading over a newer data schema without an explicit compatible path.
- Prefer restoring a user-created full backup into a known-good build.
- If a migration release is used, retain its ability to export a current full backup until the successor has completed the soak window.

## Final retirement sign-off checklist

- [ ] Android/iOS/web scope decision recorded.
- [ ] Production package, signing, version, store, and scheme strategy proven.
- [ ] Populated legacy-to-native migration proven; no empty-library false success.
- [ ] RN JSON and full backups restore completely in native, including display/theme/fold settings.
- [ ] Every parity-ledger row has a disposition and evidence.
- [ ] Source pagination/parser fixtures match or differences are approved.
- [ ] Sync metadata, chapter merge, bookmark, pending-new, EPUB, and archive policies are tested.
- [ ] Download cancellation, rate limits, retries, recovery, service lifecycle, and process death are tested.
- [ ] Cleanup golden vectors match.
- [ ] Reader sanitization, navigation, bookmark, settings, foldable, and accessibility flows pass.
- [ ] TTS engine, session, MediaSession, audio interruption, hardware controls, and process recovery pass.
- [ ] EPUB validator and multiple-reader checks pass.
- [ ] Backup hostile/corrupt/large/rollback tests pass.
- [ ] Deep links and every external intent pass cold and warm launch tests.
- [ ] Full CI, emulator end-to-end, release candidate, and soak gates pass.
- [ ] No unresolved P0/P1 defects; remaining P2 items are explicitly accepted.
- [ ] Last RN revision, fixtures, backups, and seeded legacy build are preserved.
- [ ] RN deletion succeeds from a clean clone without Node.js.
- [ ] Post-deletion migration restore and native smoke suite pass.

Only after every applicable item is checked should the React Native source be deleted.
