# Documentation Index

Long-form documentation for the Webnovel Archiver Android app. Documents are grouped
by subject — one folder per topic. When adding a new document, place it in the folder
for its subject; create a new folder only if the subject does not already have one.
Keep this index current whenever the set of documents changes.

## Folders

### `architecture/`

Codebase-wide reviews, audits, and refactoring records covering the native Kotlin app
under `android/`.

| File | Description |
|------|-------------|
| `product-architecture-review-2026-07-09.html` | Current product-wide architecture review covering stability, maintainability, runtime validation, prioritized findings, and an implementation roadmap. |
| `project-improvement-audit.md` | Current static audit of speed, reliability, and maintainability gaps with prioritized recommendations (Markdown source, refreshed 2026-07-08). |
| `project-improvement-audit.html` | Legacy HTML rendering of the original audit for easier reading and navigation; use the Markdown source for the refreshed current version. |
| `refactoring-large-files.md` | Record of the large-file decomposition (Sources, AppStorage, DetailsScreen, TextCleanup), marked complete. |
| `metric-trends.md` | Per-novel metric Trends feature: snapshot capture on sync, retention/downsampling, JSON history-store layout, and the Trends screen. |

### `cloudflare/`

Strategy and implementation planning for making Scribble Hub sync and downloads work
when Cloudflare presents a browser challenge.

| File | Description |
|------|-------------|
| `scribblehub-cloudflare-options.html` | Compliance-focused investigation: shared WebView/OkHttp cookie jar, in-app Source Access screen, and source throttling. |
| `glm-Cloudflare-bypass.html` | GLM research — codebase audit, Cloudflare 2026 mechanics, Mihon deep-dive, bypass-method survey, and five candidate plans. |
| `scribblehub-cloudflare-master-strategy.html` | Master architectural plan synthesizing the research into the recommended Mihon-pattern implementation roadmap. |
| `source-reliability-implementation.md` | Current implemented architecture: source-wide pacing/circuit state, sticky Chromium transport, bulk preflight, retry semantics, diagnostics, and reset behavior. |

### `tts/`

Audits and evaluations of the Text-to-Speech subsystem.

| File | Description |
|------|-------------|
| `tts-audit.html` | Read-only audit of the current TTS implementation against modern Android best practices, with a prioritized recommendation list. |
| `tts-media3-migration-evaluation.md` | Short evaluation recommending against a Media3 migration in the same change as the audio-focus/robustness fixes. |

### `qa/`

Emulator-based functional QA reports for the native Android app.

| File | Description |
|------|-------------|
| `native-app-emulator-qa-2026-07-21.html` | Debug-APK emulator QA pass covering Library, Queue, Settings, Updates, Add Story, Details, Reader, TTS, EPUB, and regression verification (0 issues found, fully stable). |
| `native-app-emulator-qa-2026-07-09.html` | Debug-APK emulator QA covering local library, reader, TTS, settings, EPUB, and empty-state flows; records one P2 reader-settings navigation issue. |
