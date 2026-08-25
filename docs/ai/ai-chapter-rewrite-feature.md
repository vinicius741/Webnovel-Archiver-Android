# Chapter polish — shipped feature (plan phases 2–5)

Date: 2026-08-25. Status: **built and shipped in-app.** The product/architecture plan is
`ai-chapter-rewrite-plan.html`, the Phase-1 spike record is `ai-chapter-rewrite-spike.md`, and
the implementation handoff this build followed is `ai-chapter-rewrite-handoff.md`. This document
describes what now exists in the app.

## What the feature does

Chapter polish rewrites one downloaded chapter's prose to remove repetitive habits (staccato
fragment clusters, automatic triplets, over-explained punchlines) while preserving every event,
fact, number, speaker, and System panel. The downloaded source chapter file is **never
modified**: a rewrite is a separate local variant that Reader, formatted copy, and TTS all
resolve through one seam, and it can be switched off or deleted at any time.

Every rewrite is a **Verified** flow: one rewrite call, deterministic structural validation,
one bounded repair on validation failure, then an independent cross-model preservation verify.
Only a draft with zero verifier blockers can be applied. `finish_reason == "length"` is a hard
reject — truncated replies are discarded, never partially applied.

Two prompt strengths ship, per the blind ballot's preference for least-intervention rewrites:

- **Light** (`v1.2-light`, the per-novel default): merge sparingly, keep isolated punchlines,
  never convert paragraph fragments into in-sentence triplet rhythms.
- **Balanced** (`v1.1`, the spike's proven prompt): rebuild rhythm wherever it drones.

## User flow

- **From Reader:** overflow (Reader settings) → "Polish this chapter…" → preflight confirm
  (models, strength, cost ceiling, what leaves the device, rights reminder) → generation runs
  on a foreground-service-backed job → the comparison screen opens when the draft is ready.
- **From AI Controls:** a Chapter Polish section lists every downloaded chapter with its status
  (Polishing…, Draft ready, Flagged, Polished, Polished (off)); tapping a chapter opens its
  comparison or starts the preflight.
- **Comparison screen:** status/provenance card (models, prompt version, strength, merges, cost,
  routing tier), the deterministic cadence report (fragment share, clusters, triplets, sentence
  CV, template-swap warning), verifier findings, and Source/Polished tabs rendered through the
  reader sanitizer. Footer: Apply Polished Version (enabled only when verified), Discard,
  Regenerate.
- **In Reader after apply:** the app-bar subtitle gains a `· Polished` badge (or `· Polished
  (out of date)` when the source hash changed). Tapping the subtitle — or the panel's "Switch
  to source/polished version" — flips the variant without leaving the reader. If TTS is
  narrating that chapter, playback restarts from the top of the new text, because chunk indices
  no longer refer to the same prose.

## Storage layout

```
files/webnovel_archiver/
  novels/<story>/...source chapter html...       # untouched
  chapter_rewrites/<safeStoryId>/
    manifest.json                                # atomic index (completeness marker)
    <safeChapterId>-<hash>/
      draft.html                                 # preview draft; never resolved by Reader/TTS
      applied.html                               # the applied polished variant
```

- Applied files and manifests are included in full backups; pending drafts are not.
- Deleting a story removes its whole `chapter_rewrites/<story>` tree (hooked into
  `AppStorage.deleteStory`).
- Staleness: the manifest records the sanitized-source SHA-256 each rewrite was generated
  from. On resolve, a mismatch keeps serving the polished variant (never switch text under the
  reader) but flags it Out of date in the reader badge and comparison screen.

## Code map

| File | Responsibility |
|------|----------------|
| `ai/ChapterBlockParsing.kt` | Sanitize → block split (`b0001`…) → protected classification wiring; text/hashes. |
| `ai/ChapterBlockClassification.kt` | Conservative protected-block rules (dividers, tables, headings, spacers, stat-like text). |
| `ai/ChapterCadenceReport.kt` | Pure before/after cadence metrics + template-swap detector. |
| `ai/ChapterRewriteValidation.kt` | The merge-semantics contract: id order, protected byte-equality, `""` merges, slot-shift safety net, verifier parsing. |
| `ai/AiChapterRewritePrompts.kt` | Versioned prompts: Balanced v1.1, Light v1.2-light, verifier v1. |
| `ai/AiChapterRewritePlanning.kt` | SOURCE_DATA user messages, token budgets (×2.2 + 1000 + 6k reasoning), cost estimates. |
| `ai/AiChapterRewriteSchemas.kt` | Structured-output JSON schemas + strict/relaxed provider routing blocks. |
| `ai/AiChapterRewriteEngine.kt` | One-chapter Verified flow: routed rewrite, validation, bounded repair, cross-model verify. |
| `ai/AiChapterRewriteEngineSupport.kt` | Call spec, usage recorder, routing-failure rules, verifier-model resolution. |
| `ai/AiChapterRewriteForegroundService.kt` | Keep-alive service + result notifications (ids 1005/1006, AI channel). |
| `app/AiChapterRewriteJobCoordinator.kt` | Process-lifetime job state; persists the draft before announcing it. |
| `app/AiChapterRewriteJobUiBridge.kt` | Mirrors job progress into the story-operation slot; opens the comparison on success. |
| `domain/model/AiChapterRewriteModels.kt` | Strength, verification, cadence summary, draft/applied records, manifest. |
| `data/storage/AiChapterRewriteStore.kt` | Atomic manifest + draft/applied files, toggling, deletion, backup enumeration. |
| `data/repository/AppRepositoryRewrites.kt` | Storage-transaction mutations + republish. |
| `data/repository/ChapterRewriteDraftMapping.kt` | Draft output → persisted record mapping. |
| `feature/ai/AiChapterRewriteControls.kt` | AI Controls section: models, strength, chapter statuses. |
| `feature/ai/AiChapterRewritePreview.kt` | Comparison screen: cadence, findings, tabs, Apply/Discard/Regenerate. |
| `feature/ai/AiChapterPolishActions.kt` | Shared preflight (estimate + confirm) and job start. |
| `feature/reader/ChapterContentResolver.kt` | Single Source-vs-Polished seam for Reader, copy, and TTS. |

Settings: `AiSettings.chapterRewriteModel` (default `openai/gpt-5.6-terra`) and
`chapterVerifierModel` (default `x-ai/grok-4.6`); the verifier must differ from the rewriter —
an equal pick is swapped to a spike-verified alternate (`openai/gpt-5.6-sol`) and recorded.
Per-novel strength lives on `Story.chapterRewriteStrength` (null = Light) and is carried
forward by sync like the other local-only AI fields.

Usage receipts use features `chapter_rewrite`, `chapter_repair`, and `chapter_verify` under one
operation id, so the ledger's grouped cost matches the record's cost line exactly.

## Spike-proven rules this build enforces

- `""` merge semantics with a required addressable carrier above; merging across protected
  blocks is rejected; >12 consecutive empties is rejected (`merge_slot_shift`).
- Token budget `≈ serialized user tokens × 2.2 + 1000 + 6000` clamped to the catalog's
  completion cap; `finish_reason=length` hard-rejects.
- Verifier pairing is rebuilt from the validated reply (ids + `""` markers), never from
  re-parsed polished HTML; an unparseable verifier reply is `verify_failed`, never a pass; one
  bounded retry.
- Provider routing starts `zdr + data_collection:deny + require_parameters` and steps down
  (relaxed → none) only on routing 404s; the tier that served is recorded.
- Protected blocks compare byte-identical after whitespace normalization; model output is
  re-sanitized through the prose-only allowlist.

## QA notes

- AI-call discipline on the emulator: prove plumbing with `deepseek-v4-flash-0731` (cents),
  then 2–3 real `gpt-5.6-terra`/`sol` rewrites max per session. The rr_165465 problem novel is
  the reference chapter set.
- Cold-start onto the flow with `dev_start_screen aicontrols --es dev_start_story rr_165465`;
  the reader flow via `dev_start_screen reader --es dev_start_story rr_165465`.
