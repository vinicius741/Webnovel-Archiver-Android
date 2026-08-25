# Chapter polish — Phase 1 spike results (go/no-go gate)

Date: 2026-08-24. Harness: `scripts/chapter_polish_spike/` (see its README).
Plan: `ai-chapter-rewrite-plan.html`, roadmap phase 1 — prompt and evaluation
spike, no Android code. This document is the spike's output; the reader's blind
preference vote (below) is the gate's final input.

## What ran

- **Corpus**: the plan's problem chapter (Foxkin of the Night Sky ch. 1, pulled
  from the emulator's downloaded library), an authored synthetic LitRPG chapter
  (System notices, stat table, dividers, spacers, class-selection lines), and a
  public-domain control (Pride and Prejudice ch. 1).
- **Models**: `anthropic/claude-opus-5-fast` (frontier, $10/$50 per M tokens),
  `google/gemini-3.1-pro-preview` (value frontier, $2/$12), and
  `deepseek/deepseek-v4-flash` (flash class, $0.07/$0.13) — all with
  OpenRouter structured outputs.
- **Pipelines**: one-pass rewrite; rewrite + independent cross-model verifier;
  rewrite → critic → rewrite (critique-repair). Plus a planted-error holdout
  against the verifier.
- **Spend**: ≈ $2.99 of the key's $3.00 limit across 19 runs (receipts in each
  run's `receipts.jsonl`). Nothing was spent beyond the pre-existing key limit.

Full table: `scripts/chapter_polish_spike/results/report.md`. Blind ballot:
`results/ballot.md` (+ `ballot_key.json`, to open only after voting).

## Findings

1. **Prompt v1 was too timid; merge semantics (v1.1) fixed it.** Under v1 every
   model left the problem chapter essentially untouched (fragment share 51% →
   50–51%, clusters 10 → 10; Claude edited 16 of 186 addressable blocks). Root
   cause was structural: the return-every-block-id-once contract forbids
   paragraph merging, the rubric's main tool. v1.1 adds explicit merge
   semantics (absorb into the previous addressable block, return `""`), a
   fragment-reduction mandate, and a braver self-audit line. Under v1.1:
   Claude 10→6 clusters / 51%→41% fragments / 39 merges; Gemini 10→4 / 51%→37%
   / 73 merges; flash 10→5 / 51%→39%. Word count is conserved (Claude:
   1802→1802). Deterministic validation proved merges: legal target required,
   merging across protected blocks rejected, id set and order enforced.

2. **The deterministic cadence report works and clears its own bar.** The
   parser reproduces the plan's hand-measured reference numbers on the source
   chapter (186/94/10 vs the plan's 187/91/11 — spacer handling accounts for
   the difference). No rewrite traded one dominant sentence shape for another
   (template-swap flag never fired); the control chapter stayed light-touch on
   every model (21% → 18–19% fragment share, structure intact).

3. **The verifier catches subtle planted errors.** Holdout (planted number
   change, deleted block, invented sensory detail in a validated rewrite):
   Gemini-as-verifier produced blockers covering all three plants (the number
   bump was typed `changed_system_text` because it hit a "Night N Event"
   label — adjacent typing, still a blocker). Cross-model verification of real
   rewrites: Claude's rewrite ← Gemini verifier: 0 findings; Gemini's ← Claude:
   1 warning (a merge that changed beat timing — "I stare at the window." →
   "until it vanishes" — correctly a warning, not a blocker); flash's ← Gemini:
   0 blockers.

4. **Flash-class is not viable as the rewrite model.** Beyond the plan's
   template-swap worry, deepseek-v4-flash burned ~9.5k hidden reasoning tokens
   inside the completion limit on the dialogue-heavy control chapter and
   truncated deterministically (2/2 attempts, `finish=length`); on v1 it was a
   near-no-op (5/186 blocks edited). Cheap ($0.006/chapter) but unusable for
   the core job. Fine as a verifier for drafts, not as the rewriter.

5. **Reasoning-token budgeting is a product requirement.** Hybrid-reasoning
   models spend hidden reasoning tokens inside `max_tokens`; a budget computed
   from visible output size truncates the JSON (three smoke failures before
   the allowance was added). The app's chunk planner must budget `visible +
   reasoning allowance` and treat `finish_reason=length` as a hard reject.

6. **Strict provider routing is unavailability on Anthropic via OpenRouter.**
   `zdr + data_collection:deny` and even `require_parameters`-relaxed ZDR both
   404 for Claude endpoints; the frontier runs required dropping provider
   routing (`--no-privacy`, recorded in run meta and this report). Gemini and
   DeepSeek satisfied strict routing. Product decision needed: whether Claude
   (no ZDR declaration) is ever offered for chapter text, or whether the
   strict-routing default silently excludes it.

7. **Cost reality matches the plan's guidance.** Frontier Claude ≈ $0.55–0.60
   per chapter rewrite (verify adds ~$0.05–0.20); Gemini ≈ $0.15–0.20; flash
   <$0.01. Critique-repair on Gemini: $0.42 total. The plan's $0.10–0.50
   frontier band is right; Claude slightly exceeds it.

8. **The critic earns its call.** On the Gemini rewrite it correctly named a
   residual habit (uniform snark untouched), a new habit (over-connected
   sentences losing comedic timing), and what improved — with quotes. The
   critique-repair pipeline verified clean (0 blockers).

## Go/no-go

The gate needs the reader's ear, not mine: read `results/ballot.md` (source +
anonymous rewrites of each corpus chapter, labels shuffled with a recorded
seed), rank each chapter's versions, then check `ballot_key.json`. Recommended
lens: the foxkin chapter's blind ranking is the decision — the plan's bar is
"prose you prefer on your own novels".

Mechanical preconditions all passed (validation, preservation verifier,
holdout, cadence movement, cost). If the ballot prefers any v1.1 rewrite over
its source, the recommendation is **go**: lock rewrite prompt v1.1 as the v1
product asset, treat Gemini 3.1 Pro as the value default and Claude Opus 5
fast as the premium-but-no-ZDR option, and proceed to the plan's Phase 2
(pure planning + storage in the app). If the ballot prefers the source on the
problem chapter, stop here per the plan — no storage/engine/UI is worth
building against a prompt that cannot clear the bar.

## Second matrix: the reader's requested models (2026-08-24, later the same day; corrected 2026-08-25)

Credits were added (key limit $3 → $8) and eight reader-selected models were run
on the problem chapter with rewrite prompt v1.1 and cross-model verification.
An earlier draft of this section reported four models as "slot-shifters" whose
rewrites misplaced content; that conclusion was wrong — the harness's own
verify step was re-parsing `polished.html`, which carries no block ids, so
source block `bNNNN` was compared against *polished paragraph #N*. Once merges
shrink the chapter, every pair past the first merge is misaligned, and the
verifier narrated confident findings around the broken pairs (including
fabricated "blocks b0128–b0194 missing" ranges). The harness now rebuilds
id-aligned pairs from the stored validated reply (`load_id_aligned_blocks`)
and refuses to verify against id-less HTML. Corrected outcomes, all verified
with proper pairing (verifier ≠ rewriter, privacy routing disabled per the
reader's call that privacy is secondary):

| model | outcome | clusters | frag share | triplets | merges | total cost |
|---|---|---|---|---|---|---|
| `moonshotai/kimi-k2-0905` | **verified, 0 blockers** (caveat: one earlier attempt silently dropped trailing blocks with `finish=stop` — contract adherence is nondeterministic) | 10→4 | 51%→39% | 17→25 | 67 | $0.090 |
| `z-ai/glm-5.3` | **verified, 0 blockers** | 10→4 | 51%→36% | 17→23 | 47 | $0.116 |
| `deepseek/deepseek-v4-pro-0813` | **verified, 0 blockers** | 10→4 | 51%→36% | 17→29 | 79 | $0.134 |
| `x-ai/grok-4.6` | **verified, 0 blockers** | 10→4 | 51%→36% | 17→24 | 72 | $0.126 |
| `openai/gpt-5.6-terra` | **verified, 0 blockers** | 10→5 | 51%→42% | 17→13 | 31 | $0.158 |
| `openai/gpt-5.6-sol` | **verified, 0 blockers** | 10→5 | 51%→40% | 17→16 | 32 | $0.288 |
| `stealth/ox-alpha` | rewrite validated (free, $0) but **blocked by verifier**: 1 genuine blocker — dropped the beat "I raise an eyebrow." while merging the final lines | 10→5 | 51%→39% | 17→21 | ~60 | $0.030 |
| `deepseek/deepseek-v4-flash-0731` | rejected at rewrite: `finish=length` at 21k tokens (hidden-reasoning burn, same as its flash sibling) | — | — | — | — | $0.007 |

**What the corrected round actually established:**

- **Merge-run calibration.** Merging a dense fragment cluster (Foxkin's run
  4–8 paragraphs) legitimately produces 4–8 consecutive empty blocks — even
  round-one's verified-clean Gemini and Claude do it. The `merge_slot_shift`
  validation stays as a safety net, calibrated to 12 (above the densest real
  cluster, below pathology), and `spike.py audit <run_dir>` re-judges stored
  replies for free.
- **Verifier reliability is a first-class concern.** Kimi-as-verifier, fed
  misaligned pairs, produced plausible-but-fabricated findings and verbose
  replies that exhausted the token limit (its "0 blockers" on two runs was
  really "no verdict" — now recorded as `verify_failed`). Grok-4.6 and
  GPT-5.6-sol as verifiers, on correctly aligned pairs, returned short clean
  verdicts and one genuine catch (ox-alpha's dropped beat). Product rule:
  verifier ≠ rewriter model, evidence capped by schema, pairing must come from
  validated ids — never from re-parsed output HTML.
- **The product-grade failure mode to watch is silent block-dropping**
  (kimi's `finish=stop` at 192/195 ids) — deterministic validation catches it,
  which is why it stays in the app's validation layer.
- **Cadence:** all verified models land in a tight band (clusters 10→4–5,
  fragment share 51%→36–42%). The heavy mergers convert paragraph-level
  fragment clusters into intra-paragraph sentence triplets (triplets rise to
  23–29 for kimi/glm/ds-pro/grok) while terra and sol reduced triplets
  (17→13/16). Model choice moves that trade-off more than it moves the
  headline cadence numbers.

**Privacy routing tiers** (for the record, though de-prioritized by the
reader): strict `zdr + data_collection:deny + require_parameters` was
satisfied by deepseek-v4-pro-0813, deepseek-v4-flash-0731, and grok-4.6;
kimi, glm-5.3, gpt-5.6-terra, gpt-5.6-sol required the relaxed tier;
ox-alpha requires no routing (and free-tier rate limits: expect 429s).

**State of the gate — RESOLVED (reader ballot, 2026-08-25): NO-GO as specified.**
The reader voted blind through `results/ballot.html` (seed 20260824). On the
problem chapter, **the source itself ranked in the top tier ("better")** — no
rewrite was preferred over it. `gpt-5.6-terra`, `gpt-5.6-sol`, and the timid
round-one `claude v1` run merely matched the source (same tier); every heavy
merger (v1.1 prompt: claude, gemini, grok, kimi, glm, ds-pro) landed at
"same" or below. Control chapter: source "MUCH better" (models left it nearly
untouched, as designed). Synthetic chapter: gemini v1.1 "better", flash v1.1
"worse" (over-aggressive merging).

Verdict per the plan's own gate — "if no candidate model produces prose the
reader prefers on their own chapters, stop here" — the spike **fails the
go/no-go**: Phase 2+ (storage, engine, UI) should not be built against prompt
v1/v1.1. The closest candidates were terra/sol, which also showed the lowest
triplet drift (they merge less and rarely convert paragraph fragments into
in-sentence triplets). If the reader ever wants one more attempt, the
evidence-backed direction is a lighter-touch v1.2 profile run only on
terra/sol-class models — but by default this feature stops here, per plan.

## Notes for the product phase

- The `""`-merge semantics, merge validation rules, and the verifier's
  merged-pair handling from this spike translate directly into
  `AiChapterRewritePlanning.kt` and the verifier prompt.
- Budget per call must include a reasoning allowance; `finish_reason=length`
  must hard-reject (never partially apply).
- The protected-block classifier's conservative bias behaved well: stat
  panels, class-selection lines, tables, dividers, and spacers all pinned
  byte-for-byte; the one prose false positive ("My life is simple:" matched a
  label-colon pattern) is acceptable drift per the plan.
- Key budget was the binding constraint of the day ($3.00 limit): plan future
  matrices against the remaining limit or a fresh key.
