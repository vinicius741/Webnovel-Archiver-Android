# Chapter-polish Phase 1 spike

Offline-first harness for the "Chapter polish" plan's go/no-go gate
(`docs/ai/ai-chapter-rewrite-plan.html`, roadmap phase 1). No Android code: the
spike runs from this directory to answer whether any candidate model produces
prose the reader prefers on their own problem novels before any storage,
engine, or UI is built.

## Layout

- `spike.py` — CLI harness (subcommands below).
- `ballot_ui.py` — template + generator for the self-contained ballot voting page (`spike.py ballot-ui`).
- `blocks.py` — HTML sanitize → block parse → protected-block classification → output validation (pure).
- `cadence.py` — deterministic cadence metrics: fragment share, clusters, triplets, sentence-length variation, em-dash density, template-swap detection (pure).
- `prompts/` — versioned prompt assets (`rewrite_system_v1.txt`, `rewrite_system_v1_1.txt`, `verifier_system_v1.txt`, `critic_system_v1.txt`). The `PROMPT_VERSION:` header is mandatory; `spike.py selftest` checks it.
- `corpus/manifest.json` + chapters — synthetic (committed), public domain and the reader's problem chapters (gitignored under `corpus/local/`).
- `local/` — gitignored: `openrouter_key.txt` (the key extracted from the emulator's app storage), model-catalog cache.
- `results/` — gitignored per-run artifacts: `polished.html`, `source_sanitized.html`, `cadence_before/after.json`, `model_raw_reply.txt`, `receipts.jsonl`, `meta.json`, plus generated `report.md`, `ballot.md`, `ballot_key.json`.

## Commands

```bash
python3 spike.py selftest                 # offline checks, no network, no cost
python3 spike.py catalog --match gemini   # public model catalog with structured-output support
python3 spike.py plan --chapter foxkin_ch1 --model <id>   # block census + token/cost estimate
python3 spike.py run --chapter foxkin_ch1 --model <id> --pipeline one_pass   # paid rewrite
python3 spike.py matrix --chapter foxkin_ch1 --models "id1,id2,..." --verify-with <id>  # batch + verify
python3 spike.py verify <run_dir> --model <other-id>     # independent verifier pass (paid)
python3 spike.py holdout <run_dir> --model <id>          # planted-error verifier test (paid)
python3 spike.py audit <run_dir>          # re-validate a stored reply under current rules (free)
python3 spike.py report                  # aggregate report.md + blind ballot.md (free)
python3 spike.py ballot-ui               # results/ballot.html — self-contained voting page (free)
```

The ballot page is one HTML file with the chapters, versions, and (post-vote) key
embedded: double-click it in any browser. Rate versions (worse/same/better/much
better), compare any two side by side, and export the votes to paste back. Votes
persist in the browser's localStorage, keyed by ballot seed.

Pipelines: `one_pass` (rewrite only), `verify` (rewrite + verifier; also usable
via the `verify` subcommand on an existing run), `critique_repair` (rewrite →
critic → rewrite). Every paid run enforces `--max-cost-usd` (default 6.0) against
receipted spend plus a worst-case projection.

Provider routing defaults to OpenRouter strict privacy
(`zdr + data_collection:deny + require_parameters`). `--relax-privacy` drops
`require_parameters`; `--no-privacy` drops routing entirely and is recorded in
the run meta — Anthropic endpoints currently fail both strict modes on
OpenRouter (no ZDR declaration), so frontier Claude runs need `--no-privacy`.

## Corpus

`corpus/local/` is populated by pulling chapters from the emulator's debug app
(not committed — copyrighted, personal-use only), e.g.:

```bash
adb -s emulator-5554 exec-out run-as com.vinicius741.webnovelarchiver.nativeapp.debug \
  cat files/webnovel_archiver/novels/rr_165465/0000_3342012.html > corpus/local/foxkin_ch1.html
```

## Results (2026-08-24/25 runs)

See `docs/ai/ai-chapter-rewrite-spike.md` for the full findings. Round one
(3 models): prompt v1 too timid, v1.1 merge semantics fixed it, verifier
holdout 3/3, ~$2.99. Round two (reader's 8 models): after fixing the
verify-step pairing bug (never re-parse polished.html for ids — use the stored
validated reply), six of eight verified clean (kimi, glm-5.3, deepseek-v4-pro,
grok-4.6, gpt-5.6-terra, gpt-5.6-sol), ox-alpha blocked by a genuine dropped
beat, flash-0731 rejected for truncation. Blind ballot ready for the reader's
go/no-go vote. Cumulative spend ≈ $4.1 of the $8 key limit.
