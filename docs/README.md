# Documentation Index

Long-form documentation for the Webnovel Archiver Android app. Documents are grouped
by subject — one folder per topic. When adding a new document, place it in the folder
for its subject; create a new folder only if the subject does not already have one.
Keep this index current whenever the set of documents changes.

## Folders

### `ai/`

OpenRouter-backed AI features for the native app.

| File | Description |
|------|-------------|
| `ai-description-generation.md` | AI-generated novel descriptions: user flow, settings/key handling, cost controls, `ai/` package map, and how to extend the layer to more generators. |
| `ai-cover-generation.md` | AI-generated cover art: two-stage generation (text model writes the image prompt, image model paints it), cover storage/revert, EPUB embedding, and full-backup shipping of generated covers. |
| `ai-cost-tracking.md` | Exact OpenRouter receipt capture, device-local per-request history and aggregates, live current-key counters, preview cost labels, and privacy/backup behavior. |
| `ai-chapter-rewrite-plan.html` | Research-backed product and implementation plan for reversible, verified AI chapter polishing that preserves source chapters, story canon, formatting, Reader/TTS parity, cost visibility, and provider privacy controls. |
| `ai-chapter-rewrite-spike.md` | Phase 1 go/no-go spike results: prompt v1 vs v1.1 (merge semantics), three-model comparison incl. frontier, verifier holdout, cadence-report evidence, cost/privacy findings, and the blind-ballot verdict (harness: `scripts/chapter_polish_spike/`). |
| `ai-chapter-rewrite-handoff.md` | Implementation handoff for building the full Chapter polish feature in-app (plan phases 2–5): spike-proven decisions to port, scope, prompt-strength guidance from the ballot, and QA requirements. |
| `ai-chapter-rewrite-feature.md` | The shipped Chapter polish feature: user flow (Reader + AI Controls + comparison), storage layout, code map, enforced spike rules (merge contract, verifier pairing, routing tiers), and emulator QA notes. |

### `architecture/`

Codebase-wide reviews, audits, and refactoring records covering the native Kotlin app
under `android/`.

| File | Description |
|------|-------------|
| `performance-reliability-review-2026-09-04.md` | Static review of the native app with 30 prioritized reliability and performance recommendations, implementation evidence, effort estimates, and acceptance checks. Recommendations only; no implementation changes. |
| `code-simplification-audit-2026-07-31.html` | Current repository-wide simplification audit with 40 prioritized deletion, function-merging, state-consolidation, UI-structure, and tooling opportunities, including Kotlin examples and preservation guardrails. |
| `product-architecture-review-2026-07-09.html` | Current product-wide architecture review covering stability, maintainability, runtime validation, prioritized findings, and an implementation roadmap. |
| `project-improvement-audit.md` | Current static audit of speed, reliability, and maintainability gaps with prioritized recommendations (Markdown source, refreshed 2026-07-08). |
| `project-improvement-audit.html` | Legacy HTML rendering of the original audit for easier reading and navigation; use the Markdown source for the refreshed current version. |
| `refactoring-large-files.md` | Record of the large-file decomposition (Sources, AppStorage, DetailsScreen, TextCleanup), marked complete. |
| `metric-trends.md` | Per-novel metric Trends feature: snapshot capture on sync, retention/downsampling, JSON history-store layout, and the Trends screen. |
| `source-metadata-opportunity-audit-2026-07-31.html` | Source-by-source audit of missing public novel metadata across Royal Road, Scribble Hub, SpaceBattles, and FanFiction.net, with retrieval paths and prioritized implementation guidance. |

### `cloudflare/`

Strategy and implementation planning for making Scribble Hub sync and downloads work
when Cloudflare presents a browser challenge.

| File | Description |
|------|-------------|
| `scribblehub-cloudflare-options.html` | Compliance-focused investigation: shared WebView/OkHttp cookie jar, in-app Source Access screen, and source throttling. |
| `glm-Cloudflare-bypass.html` | GLM research — codebase audit, Cloudflare 2026 mechanics, Mihon deep-dive, bypass-method survey, and five candidate plans. |
| `scribblehub-cloudflare-master-strategy.html` | Master architectural plan synthesizing the research into the recommended Mihon-pattern implementation roadmap. |
| `source-reliability-implementation.md` | Current implemented architecture: source-wide pacing/circuit state, sticky Chromium transport, typed render-outcome escalation, circuit pausing (no queue drain), persisted reliability state, bulk preflight, retry semantics, the shareable bypass event log, and reset behavior. |

### `tts/`

Audits and evaluations of the Text-to-Speech subsystem.

| File | Description |
|------|-------------|
| `tts-podcast-player.md` | The podcast-style TTS player: per-story resume memory (`tts_positions.json`), stop-vs-finish semantics, mini-player + Now Playing screens, chapter-skip transport, and lifecycle rules. |
| `tts-audit.html` | Read-only audit of the current TTS implementation against modern Android best practices, with a prioritized recommendation list. |
| `tts-media3-migration-evaluation.md` | Short evaluation recommending against a Media3 migration in the same change as the audio-focus/robustness fixes. |

### `qa/`

Emulator-based functional QA reports for the native Android app.

| File | Description |
|------|-------------|
| `native-app-emulator-qa-2026-08-27.html` | Full-screen dogfood & UX pass with self-contained screenshot/video evidence: 4 P2 findings (cold-start spinner with transient 24–84s degradation, opaque full-backup feedback + .tmp leak, AI Controls complexity overload, free-text Voice & Speech fields) and 8 P3 polish items; clean crash sweep; all prior regressions still fixed. |
| `qa-2026-08-27-fix-plan.md` | Analysis of the 2026-08-27 QA findings with prioritized fix proposals per finding (code sites included), the shipped F2 full-backup fixes, and an execution order for F1/F3/F4 and the P3 batch. |
| `native-app-emulator-qa-2026-07-21.html` | Debug-APK emulator QA pass covering Library, Queue, Settings, Updates, Add Story, Details, Reader, TTS, EPUB, and regression verification (0 issues found, fully stable). |
| `native-app-emulator-qa-2026-07-31.html` | Broad debug-APK emulator QA with live followed-story sync, partial-story download, valid Add Story import, TTS, EPUB, backup, and four ranked findings with screenshot evidence. |
| `native-app-emulator-qa-2026-07-09.html` | Debug-APK emulator QA covering local library, reader, TTS, settings, EPUB, and empty-state flows; records one P2 reader-settings navigation issue. |

### `sources/`

Provider architecture and the workflow for extending supported novel sites.

| File | Description |
|------|-------------|
| `adding-a-source.md` | Stable source descriptors, URL matching, parsing/fetching hooks, registration, fixtures, compatibility rules, and validation checklist for a new provider. |
