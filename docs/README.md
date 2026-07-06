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
| `project-improvement-audit.md` | Static audit of speed, reliability, and maintainability gaps with prioritized recommendations (Markdown source). |
| `project-improvement-audit.html` | HTML rendering of the same audit for easier reading and navigation. |
| `refactoring-large-files.md` | Record of the large-file decomposition (Sources, AppStorage, DetailsScreen, TextCleanup), marked complete. |

### `cloudflare/`

Strategy and implementation planning for making Scribble Hub sync and downloads work
when Cloudflare presents a browser challenge.

| File | Description |
|------|-------------|
| `scribblehub-cloudflare-options.html` | Compliance-focused investigation: shared WebView/OkHttp cookie jar, in-app Source Access screen, and source throttling. |
| `glm-Cloudflare-bypass.html` | GLM research — codebase audit, Cloudflare 2026 mechanics, Mihon deep-dive, bypass-method survey, and five candidate plans. |
| `scribblehub-cloudflare-master-strategy.html` | Master architectural plan synthesizing the research into the recommended Mihon-pattern implementation roadmap. |

### `tts/`

Audits and evaluations of the Text-to-Speech subsystem.

| File | Description |
|------|-------------|
| `tts-audit.html` | Read-only audit of the current TTS implementation against modern Android best practices, with a prioritized recommendation list. |
| `tts-media3-migration-evaluation.md` | Short evaluation recommending against a Media3 migration in the same change as the audio-focus/robustness fixes. |
