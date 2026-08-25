# Chapter polish — implementation handoff (Phases 2–5, in-app)

Date: 2026-08-25. Status: **the user has explicitly re-opened this feature after
the Phase-1 spike ballot.** The memory note `ai-chapter-rewrite-plan-assessment`
currently says "CLOSED no-go … re-open only on explicit request" — that
condition is now met; update that memory at the start of the work (and again
when you ship). The ballot result stands as evidence, not as a blocker: the
user wants to judge the rewrites inside the actual Reader experience, which the
blind markdown/web ballot could not reproduce.

## Read first (in this order)

1. `docs/ai/ai-chapter-rewrite-plan.html` — the authoritative product and
   architecture plan: storage layout, record models, code map (section 06),
   user flow (07), safeguards (08), roadmap (09), test gates (10). Build what
   it specifies; this handoff only adds spike-proven details and scope cuts.
2. `docs/ai/ai-chapter-rewrite-spike.md` — everything learned in the two spike
   rounds, including the corrected verification story.
3. `scripts/chapter_polish_spike/` — the reference implementation to port:
   - `blocks.py` — sanitize → block parse → protected classification →
     output sanitization. Port to `feature/reader`-adjacent planning code in
     Kotlin (Jsoup is already a dependency; mirror the allowlists).
   - `cadence.py` — deterministic cadence metrics + template-swap detection
     (becomes `ai/ChapterCadenceReport.kt` + tests, pure functions).
   - `prompts/rewrite_system_v1_1.txt`, `verifier_system_v1.txt` — the proven
     prompt assets (version headers mandatory; see `spike.py:load_prompt`).
   - `spike.py` — `validate_rewrite` (the full contract), `load_id_aligned_blocks`
     (id pairing), `chat` (request shape), `verify` (retry semantics),
     `build_rewrite_user_message` / `build_verifier_user_message` (SOURCE_DATA
     framing).

## Decisions locked by the spike (do not re-derive)

- **Merge semantics are the core rewrite contract.** Addressable blocks may
  return the exact empty string `""` meaning "absorbed into the previous
  addressable block". Validation must enforce: every input block id exactly
  once in order; protected blocks byte-identical after whitespace
  normalization; `""` only for addressable blocks with a non-empty addressable
  carrier above (no merging across protected blocks/dividers); more than 12
  consecutive empty merges = reject (`merge_slot_shift` safety net — dense
  fragment clusters legitimately produce runs of 4–8, so do not lower this).
- **Token budgeting:** `max_tokens ≈ serialized_user_tokens × 2.2 + 1000 +
  ~6000 reasoning allowance`, clamped to the model's max completion tokens.
  `finish_reason == "length"` is a hard reject — never partially apply. All
  re-run attempts must be single-bounded-repair only.
- **Verification pairing:** rebuild id-aligned pairs from the validated model
  reply (ids + `""` markers). Never re-parse rendered/polished HTML for ids —
  that silently mis-pairs once merges shrink the chapter (the spike's worst
  bug). Verifier model must differ from the rewriter; an unparseable verifier
  reply is a failure (`verify_failed`), never a pass; one bounded retry.
- **Provider routing:** `provider: { zdr: true, data_collection: "deny",
  require_parameters: true }` as the default request shape, but the user has
  de-prioritized privacy: on routing 404s, step down to `{zdr, deny}` then no
  provider block, record which tier was used, and continue. Do not block the
  feature on it.
- **Model reality (August 2026, from the spike):** verified-clean rewriters on
  the problem chapter: `gpt-5.6-terra`, `gpt-5.6-sol` (ballot: matched source;
  lowest triplet drift), `grok-4.6`, `glm-5.3`, `deepseek-v4-pro-0813`,
  `kimi-k2-0905` (nondeterministically drops trailing blocks — validation
  catches it), plus round-one `claude-opus-5-fast` (needs no-privacy tier) and
  `gemini-3.1-pro`. `deepseek-v4-flash-0731` truncates — fine for cheap
  plumbing QA, never a serious rewriter. `stealth/ox-alpha` (free) dropped a
  beat. Do not restrict the model picker; surface a "verified in spike" note
  where the plan suggests labels.

## Scope for this build (single-chapter Verified flow, plan Phases 2–5)

Everything in the plan's roadmap phases 2–5; phase 6 (hardening) and phase 7
(queue/batch/EPUB) stay out. Concretely, follow the plan's code map (section
06): planning/`ChapterCadenceReport`/engine/prompts/`AiChapterRewriteModels`,
`AiChapterRewriteStore` (atomic manifest under
`files/webnovel_archiver/chapter_rewrites/<story>/…`), repository mutations,
job coordinator + foreground service reusing the cover-job pattern,
`feature/ai` controls + preview screens, and `ChapterContentResolver` feeding
Reader, formatted copy, and TTS with the version badge/toggle and safe TTS
restart on version switch.

One deliberate change from the plan's defaults, from ballot evidence: ship
**two rewrite prompt strengths**, "Light" (new prompt `v1.2-light`: merge
sparingly, keep isolated punchlines, never convert paragraph fragments into
in-sentence triplet rhythms, minimal intervention) and "Balanced" (the proven
`v1.1`). Default per-novel profile = **Light** — in the blind ballot the
user rated the untouched source top-tier and only the least-intervention
rewrites (terra/sol) matched it. The strength switch is the product's answer to
that; writing `v1.2-light` is part of this build (derive it from v1.1 by
weakening the merge mandate and the fragment-reduction targets).

## QA and validation requirements

- Unit tests at the plan's required locations (section 10), ported selftest
  cases from `spike.py:cmd_selftest` (merge semantics, protected mutation,
  slot-shift safety net, cadence numbers on the foxkin reference: the parser
  should reproduce ~186 paragraphs / ~94 fragments / ~10 clusters).
- Full local gate: `android/gradlew -p android :app:lintKotlin :app:ci`
  (instrumentation variant for unit tests — see android/AGENTS.md).
- Emulator QA on `webnovel_api36` (never the phone): the debug library already
  contains the spike's problem novel (`rr_165465`, chapters downloaded) — use
  `dev_start_screen` (`reader`, `aicontrols` with `dev_start_story rr_165465`)
  to exercise the flow end to end. The OpenRouter key is already configured in
  the app's AI Settings.
- AI-call discipline: prove plumbing with `deepseek-v4-flash-0731` (cents),
  then at most 2–3 real `gpt-5.6-terra`/`sol` rewrites for the true end-to-end
  check. Grouped receipts must match the previewed combined cost.
- Update in the same change: `docs/README.md`, a feature doc under `docs/ai/`,
  the root `README.md` package map, and `android/AGENTS.md` if workflows shift.
