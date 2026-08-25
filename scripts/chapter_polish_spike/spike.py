#!/usr/bin/env python3
"""Chapter-polish Phase 1 spike harness (docs/ai/ai-chapter-rewrite-plan.html, roadmap P1).

No Android code: the plan's go/no-go gate runs entirely from this script. It
builds block-structured prompts, calls OpenRouter with structured outputs and
strict provider routing, validates the result deterministically, runs one
bounded repair and an independent verifier, computes the cadence report, and
writes per-run artifacts plus an aggregate report and blind-preference ballot.

Subcommands:
  selftest   Deterministic offline checks (no network, no cost).
  catalog    Print candidate models from the public OpenRouter catalog.
  plan       Build the prompt for a corpus chapter and print token/cost estimates.
  run        Execute one rewrite run (paid).
  holdout    Plant errors into a polished run and check the verifier catches them (paid).
  report     Aggregate results/ into report.md + ballot.md (free).
"""

from __future__ import annotations

import argparse
import dataclasses
import datetime as dt
import json
import math
import os
import random
import re
import sys
import time
import urllib.error
import urllib.request
import zlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import blocks as B
import cadence as C

HERE = os.path.dirname(os.path.abspath(__file__))
CORPUS_DIR = os.path.join(HERE, "corpus")
LOCAL_DIR = os.path.join(HERE, "local")
RESULTS_DIR = os.path.join(HERE, "results")
PROMPTS_DIR = os.path.join(HERE, "prompts")
BASE_URL = os.environ.get("OPENROUTER_BASE_URL", "https://openrouter.ai")
PROMPT_VERSIONS = {"rewrite": "v1", "verifier": "v1", "critic": "v1"}
# v1.1 adds merge semantics ("" html = absorbed into the previous addressable block) and a
# braver fragment-reduction mandate; v1 proved too timid on the problem chapter.
REWRITE_PROMPTS = {"v1": "rewrite_system_v1", "v1.1": "rewrite_system_v1_1"}
DEFAULT_REWRITE_PROMPT = "v1.1"
# Long runs of consecutive empty merges are usually legitimate: merging a dense fragment
# cluster (Foxkin's are 4-8 paragraphs) into one carrier produces exactly that, and the
# round-one verified-clean models (gemini, claude) do it too. The threshold is a safety
# net against pathological dumping, calibrated above the densest observed real cluster.
MAX_CONSECUTIVE_EMPTY_MERGES = 12
# Hybrid-reasoning models (deepseek v4, etc.) spend hidden reasoning tokens inside the
# completion limit; without an allowance the JSON itself gets truncated (finish=length).
REASONING_TOKEN_ALLOWANCE = 6000

REWRITE_SCHEMA = {
    "type": "object",
    "properties": {
        "blocks": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "id": {"type": "string"},
                    "html": {"type": "string"},
                },
                "required": ["id", "html"],
                "additionalProperties": False,
            },
        },
        "self_audit": {
            "type": "object",
            "properties": {
                "protected_blocks_unchanged": {"type": "boolean"},
                "possible_drift": {"type": "array", "items": {"type": "string"}},
            },
            "required": ["protected_blocks_unchanged", "possible_drift"],
            "additionalProperties": False,
        },
    },
    "required": ["blocks", "self_audit"],
    "additionalProperties": False,
}

VERIFIER_SCHEMA = {
    "type": "object",
    "properties": {
        "findings": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "severity": {"type": "string", "enum": ["blocker", "warning"]},
                    "type": {"type": "string"},
                    "block_ids": {"type": "array", "items": {"type": "string"}},
                    "evidence": {"type": "string"},
                },
                "required": ["severity", "type", "block_ids", "evidence"],
                "additionalProperties": False,
            },
        }
    },
    "required": ["findings"],
    "additionalProperties": False,
}


# --------------------------------------------------------------------- prompts


def load_prompt(name: str) -> tuple[str, str]:
    """Load prompts/<name>.txt, returning (version, text) from its PROMPT_VERSION header."""
    path = os.path.join(PROMPTS_DIR, f"{name}.txt")
    raw = open(path, encoding="utf-8").read()
    m = re.match(r"PROMPT_VERSION:\s*([\w.-]+)\s*\n+", raw)
    if not m:
        raise SystemExit(f"{path} is missing its PROMPT_VERSION header")
    return m.group(1), raw[m.end():].strip() + "\n"


def est_tokens(text: str) -> int:
    return math.ceil(len(text) / 3.8)


# ------------------------------------------------------------------- openrouter


class OpenRouterError(Exception):
    pass


def _http_json(method: str, path: str, api_key: str | None, body: dict | None = None, timeout: int = 300) -> tuple[int, dict]:
    url = f"{BASE_URL}{path}"
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if api_key:
        req.add_header("Authorization", f"Bearer {api_key}")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        payload = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(payload)
        except json.JSONDecodeError:
            return e.code, {"error": {"message": payload[:500]}}


def load_api_key() -> str:
    key_file = os.path.join(LOCAL_DIR, "openrouter_key.txt")
    if not os.path.exists(key_file):
        raise SystemExit("No API key. Put an OpenRouter key in scripts/chapter_polish_spike/local/openrouter_key.txt")
    key = open(key_file).read().strip()
    if not key.startswith("sk-"):
        raise SystemExit("Key file does not look like an OpenRouter key (sk-...).")
    return key


def fetch_models(cache_hours: float = 12.0) -> dict[str, dict]:
    cache = os.path.join(LOCAL_DIR, "models_cache.json")
    if os.path.exists(cache) and time.time() - os.path.getmtime(cache) < cache_hours * 3600:
        return json.load(open(cache))
    code, payload = _http_json("GET", "/api/v1/models", api_key=None)
    if code != 200:
        raise OpenRouterError(f"model catalog failed: HTTP {code}")
    out: dict[str, dict] = {}
    for m in payload.get("data", []):
        pricing = m.get("pricing") or {}
        out[m["id"]] = {
            "name": m.get("name", m["id"]),
            "prompt_price_per_token": float(pricing.get("prompt") or 0),
            "completion_price_per_token": float(pricing.get("completion") or 0),
            "context_length": m.get("context_length"),
            "max_completion_tokens": m.get("top_provider", {}).get("max_completion_tokens"),
            "supported_parameters": m.get("supported_parameters") or [],
        }
    os.makedirs(LOCAL_DIR, exist_ok=True)
    json.dump(out, open(cache, "w"))
    return out


def chat(
    api_key: str,
    model: str,
    messages: list[dict],
    max_tokens: int,
    temperature: float,
    response_format: dict | None = None,
    provider: dict | None = None,
) -> dict:
    body: dict = {
        "model": model,
        "messages": messages,
        "max_tokens": max_tokens,
        "temperature": temperature,
        "reasoning": {"effort": "low", "exclude": True},
    }
    if response_format is not None:
        body["response_format"] = response_format
    if provider is not None:
        body["provider"] = provider
    code, payload = _http_json("POST", "/api/v1/chat/completions", api_key=api_key, body=body, timeout=600)
    if code == 429:
        print("  rate-limited; waiting 30s for one retry...", file=sys.stderr)
        time.sleep(30)
        code, payload = _http_json("POST", "/api/v1/chat/completions", api_key=api_key, body=body, timeout=600)
    if code != 200:
        msg = (payload.get("error") or {}).get("message", json.dumps(payload)[:300])
        raise OpenRouterError(f"chat failed: HTTP {code}: {msg}")
    usage = payload.get("usage") or {}
    choice = (payload.get("choices") or [{}])[0]
    content = ((choice.get("message") or {}).get("content") or "").strip()
    details = usage.get("completion_tokens_details") or {}
    receipt = {
        "generation_id": payload.get("id"),
        "model": payload.get("model", model),
        "prompt_tokens": usage.get("prompt_tokens"),
        "completion_tokens": usage.get("completion_tokens"),
        "reasoning_tokens": details.get("reasoning_tokens"),
        "cost_usd": usage.get("cost"),
        "finish_reason": choice.get("finish_reason"),
    }
    return {"content": content, "receipt": receipt}


def strip_json_fences(text: str) -> str:
    t = text.strip()
    if t.startswith("```"):
        t = re.sub(r"^```(?:json)?\s*", "", t)
        t = re.sub(r"\s*```$", "", t)
    return t.strip()


def parse_model_json(text: str) -> dict:
    """Parse a model reply. strict=False tolerates raw control characters (literal newlines)
    that some providers emit inside JSON string values even under structured outputs."""
    return json.loads(strip_json_fences(text), strict=False)


# ------------------------------------------------------------------- corpus


def load_corpus() -> list[dict]:
    manifest = json.load(open(os.path.join(CORPUS_DIR, "manifest.json"), encoding="utf-8"))
    out = []
    for e in manifest["entries"]:
        path = os.path.join(CORPUS_DIR, e["file"])
        if os.path.exists(path):
            out.append({**e, "path": path})
    return out


def corpus_entry(entry_id: str) -> dict:
    for e in load_corpus():
        if e["id"] == entry_id:
            return e
    known = ", ".join(e["id"] for e in load_corpus())
    raise SystemExit(f"unknown chapter id {entry_id!r}; available: {known}")


# ------------------------------------------------------------------- prompt building


REWRITE_PROFILE = {
    "strength": "balanced",
    "fragments": "rare, only when earned",
    "repetition": "preserve deliberate motifs; cut redundant beats",
    "humor": "preserve; do not add jokes",
    "dialogue": "wording may be tightened; speaker, intent, and information may not change",
    "metaphor_density": "restrained",
    "pov_tense": "unchanged",
}


def source_data_story(entry: dict) -> dict:
    return {
        "title": entry.get("title", ""),
        "author": entry.get("author", ""),
        "chapter_title": entry.get("title", ""),
        "license_note": "downloaded for personal reading; rewrite is for the reader's private use only",
    }


def build_rewrite_user_message(entry: dict, parsed: B.ParsedChapter) -> str:
    data = {
        "story": source_data_story(entry),
        "rewrite_profile": REWRITE_PROFILE,
        "blocks": [
            {"id": b.id, "protected": b.protected, "html": b.html}
            for b in parsed.blocks
        ],
    }
    payload = json.dumps(data, ensure_ascii=False)
    payload = re.sub(r"SOURCE_DATA_(START|END)", "[source boundary marker removed]", payload, flags=re.I)
    return (
        "Rewrite this chapter's addressable blocks under the contract. Protected blocks are "
        "returned byte-for-byte. Return every block id exactly once, in order.\n\n"
        f"SOURCE_DATA_START\n{payload}\nSOURCE_DATA_END"
    )


def build_verifier_user_message(entry: dict, source_blocks: list[B.Block], rewritten_blocks: list[B.Block]) -> str:
    by_id = {b.id: b for b in rewritten_blocks}
    data = {
        "story": source_data_story(entry),
        "blocks": [
            {
                "id": s.id,
                "protected": s.protected,
                "source_html": s.html,
                "rewritten_html": by_id[s.id].html if s.id in by_id else "",
            }
            for s in source_blocks
        ],
    }
    payload = json.dumps(data, ensure_ascii=False)
    payload = re.sub(r"SOURCE_DATA_(START|END)", "[source boundary marker removed]", payload, flags=re.I)
    return (
        "Verify preservation of the rewritten chapter against the source block pairs. "
        'A rewritten_html of "" means the block was merged into the block above; that is not a '
        "finding by itself — check the carrier block for the absorbed content and flag it only "
        "if the absorbed content changed meaning.\n\n"
        f"SOURCE_DATA_START\n{payload}\nSOURCE_DATA_END"
    )


def build_critique_user_message(entry: dict, source_blocks: list[B.Block], rewritten_blocks: list[B.Block]) -> str:
    source_html = "\n".join(b.html for b in source_blocks)
    rewritten_html = "\n".join(b.html for b in rewritten_blocks)
    payload = json.dumps(
        {"story": source_data_story(entry), "source_html": source_html, "rewritten_html": rewritten_html},
        ensure_ascii=False,
    )
    payload = re.sub(r"SOURCE_DATA_(START|END)", "[source boundary marker removed]", payload, flags=re.I)
    return (
        "Critique the rewrite as prose.\n\n"
        f"SOURCE_DATA_START\n{payload}\nSOURCE_DATA_END"
    )


# ------------------------------------------------------------------- validation


@dataclasses.dataclass
class ValidationResult:
    ok: bool
    issues: list[dict] = dataclasses.field(default_factory=list)
    warnings: list[dict] = dataclasses.field(default_factory=list)
    blocks: list[B.Block] = dataclasses.field(default_factory=list)
    sanitization_notes: list[str] = dataclasses.field(default_factory=list)
    merged_count: int = 0
    max_empty_run: int = 0


def validate_rewrite(parsed_reply: dict, chapter: B.ParsedChapter, max_output_factor: float = 3.0) -> ValidationResult:
    issues: list[dict] = []
    warnings: list[dict] = []
    notes: list[str] = []
    merged_count = 0

    reply_blocks = parsed_reply.get("blocks")
    if not isinstance(reply_blocks, list):
        return ValidationResult(False, issues=[{"code": "schema", "detail": "missing blocks array"}])

    reply_ids = [b.get("id") for b in reply_blocks if isinstance(b, dict)]
    input_ids = [b.id for b in chapter.blocks]
    if reply_ids != input_ids:
        missing = [i for i in input_ids if i not in reply_ids]
        extra = [i for i in reply_ids if i not in input_ids]
        dupes = [i for i in set(reply_ids) if reply_ids.count(i) > 1]
        order_bad = reply_ids != input_ids and not missing and not extra and not dupes
        detail = []
        if missing:
            detail.append(f"missing ids: {missing[:8]}")
        if extra:
            detail.append(f"unknown ids: {extra[:8]}")
        if dupes:
            detail.append(f"duplicate ids: {dupes[:8]}")
        if order_bad:
            detail.append("ids out of order")
        return ValidationResult(False, issues=[{"code": "ids", "detail": "; ".join(detail) or "id sequence mismatch"}])

    out_blocks: list[B.Block] = []
    src_words_total = 0
    out_words_total = 0
    consecutive_empties = 0
    max_empty_run = 0
    for idx, (src, rb) in enumerate(zip(chapter.blocks, reply_blocks)):
        html = rb.get("html")
        if not isinstance(html, str):
            return ValidationResult(False, issues=[{"code": "schema", "detail": f"{src.id} html is not a string"}])
        if src.protected:
            consecutive_empties = 0
            if html == "":
                issues.append({"code": "protected_merged", "detail": f"{src.id} ({src.reason}) returned empty but is protected"})
                out_blocks.append(src)
                continue
            if B.normalize_for_compare(html) != B.normalize_for_compare(src.html):
                issues.append({"code": "protected_changed", "detail": f"{src.id} ({src.reason}) differs from source"})
                out_blocks.append(src)
                continue
            out_blocks.append(B.Block(id=src.id, tag=src.tag, html=src.html, protected=True, reason=src.reason, protected_hash=src.protected_hash))
            continue
        if html == "":
            # Merge semantics (prompt v1.1): content absorbed into the previous addressable
            # block. Chained merges (A3 into the carrier of A2+A1) are allowed; a protected
            # block between them blocks the merge entirely.
            consecutive_empties += 1
            max_empty_run = max(max_empty_run, consecutive_empties)
            target = None
            for prev in reversed(out_blocks):
                if prev.protected:
                    break
                if prev.html != "":
                    target = prev
                    break
            if target is None:
                issues.append({"code": "merge_without_target", "detail": f"{src.id} merged with no addressable carrier above"})
            else:
                merged_count += 1
            out_blocks.append(B.Block(id=src.id, tag=src.tag, html="", protected=False, reason="merged"))
            src_words_total += len(C.words(src.text))
            continue
        consecutive_empties = 0
        clean, block_notes = B.sanitize_output_block(html)
        for n in block_notes:
            notes.append(f"{src.id}: {n}")
        out_words = len(C.words(B.text_of(clean)))
        src_words = len(C.words(src.text))
        src_words_total += src_words
        out_words_total += out_words
        if out_words == 0:
            issues.append({"code": "empty_prose", "detail": f"{src.id} sanitized to nothing"})
        if src_words >= 20 and out_words < src_words * 0.4:
            warnings.append({"code": "severe_shrink", "detail": f"{src.id}: {src_words}->{out_words} words"})
        if out_words > src_words * max_output_factor + 30:
            warnings.append({"code": "severe_growth", "detail": f"{src.id}: {src_words}->{out_words} words"})
        out_blocks.append(B.Block(id=src.id, tag=src.tag, html=clean, protected=False, reason="rewritten"))
    if out_words_total < src_words_total * 0.4:
        issues.append({"code": "chapter_shrink", "detail": f"total prose words {src_words_total}->{out_words_total}"})
    if max_empty_run > MAX_CONSECUTIVE_EMPTY_MERGES:
        issues.append({"code": "merge_slot_shift",
                       "detail": f"{max_empty_run} consecutive empty merges — far above the densest real "
                                 f"fragment cluster; likely pathological merge dumping"})

    audit = parsed_reply.get("self_audit") or {}
    if audit.get("protected_blocks_unchanged") is not True:
        warnings.append({"code": "self_audit_flag", "detail": "model reports protected blocks may have changed"})
    for d in audit.get("possible_drift") or []:
        warnings.append({"code": "self_audit_drift", "detail": str(d)})

    return ValidationResult(not issues, issues, warnings, out_blocks, notes, merged_count, max_empty_run)


# ------------------------------------------------------------------- run engine


class CostBudgetExceeded(Exception):
    pass


class SpikeRun:
    def __init__(self, args) -> None:
        self.args = args
        self.api_key = load_api_key()
        self.entry = corpus_entry(args.chapter)
        self.raw_html = open(self.entry["path"], encoding="utf-8").read()
        self.chapter = B.parse_chapter(self.raw_html)
        self.receipts: list[dict] = []
        self.spent = 0.0
        self.last_raw: str | None = None
        self.prompt_versions: dict = {"rewrite": getattr(args, "prompt_version", None) or DEFAULT_REWRITE_PROMPT,
                                      "verifier": "v1", "critic": "v1"}
        self.catalog: dict[str, dict] = {}
        self.provider = {"zdr": True, "data_collection": "deny", "require_parameters": True}
        if getattr(args, "no_privacy", False):
            self.provider = None
        elif getattr(args, "relax_privacy", False):
            self.provider = {"zdr": True, "data_collection": "deny"}
        ts = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
        self.run_id = f"{ts}_{re.sub(r'[^a-z0-9]+', '-', args.model.split('/')[-1])}_{args.chapter}_{args.pipeline}"
        self.run_dir = os.path.join(RESULTS_DIR, self.run_id)
        os.makedirs(self.run_dir, exist_ok=True)
        self.operation_id = f"spike-{self.run_id}"

    # -- low-level call with budget enforcement + receipt recording
    def call(self, phase: str, messages: list[dict], max_tokens: int, temperature: float, schema: dict | None) -> dict:
        est_prompt = sum(est_tokens(m["content"]) for m in messages)
        est_cost = est_prompt * self.price("prompt") + max_tokens * self.price("completion")
        if self.spent + est_cost > self.args.max_cost_usd:
            raise CostBudgetExceeded(
                f"projected ${self.spent + est_cost:.2f} exceeds cap ${self.args.max_cost_usd:.2f} "
                f"(already spent ${self.spent:.2f})")
        response_format = None
        if schema is not None:
            response_format = {"type": "json_schema", "json_schema": {"name": phase, "strict": True, "schema": schema}}
        result = chat(
            self.api_key, self.args.model, messages, max_tokens=max_tokens,
            temperature=temperature, response_format=response_format, provider=self.provider)
        receipt = {**result["receipt"], "phase": phase, "operation_id": self.operation_id,
                   "ts": dt.datetime.now().isoformat(timespec="seconds")}
        self.receipts.append(receipt)
        cost = receipt.get("cost_usd")
        if cost is not None:
            self.spent += float(cost)
        print(f"  [{phase}] finish={receipt.get('finish_reason')} "
              f"tokens={receipt.get('prompt_tokens')}->{receipt.get('completion_tokens')} "
              f"cost=${float(cost or 0):.4f}")
        return result

    def price(self, kind: str) -> float:
        info = self.model_info()
        return info[f"{kind}_price_per_token"] if info else 0.0

    def max_completion_cap(self) -> int | None:
        info = self.model_info()
        return (info or {}).get("max_completion_tokens")

    def model_info(self) -> dict | None:
        if not self.catalog:
            try:
                self.catalog = fetch_models()
            except Exception as e:  # catalog is an optimization; run without estimates
                print(f"  (no catalog for estimates: {e})", file=sys.stderr)
                self.catalog = {}
        return self.catalog.get(self.args.model)

    # -- rewrite with one bounded repair
    def rewrite(self, critique: str | None = None) -> tuple[ValidationResult, str]:
        prompt_version = getattr(self.args, "prompt_version", None) or DEFAULT_REWRITE_PROMPT
        prompt_file = REWRITE_PROMPTS[prompt_version]
        self.prompt_versions = {"rewrite": prompt_version, "verifier": "v1", "critic": "v1"}
        version, system = load_prompt(prompt_file)
        user = build_rewrite_user_message(self.entry, self.chapter)
        if critique:
            user += ("\n\nA critic reviewed your previous rewrite of this chapter:\n" + critique +
                     "\nProduce a better rewrite that addresses the critique without violating the contract.")
        # The reply mirrors the serialized user payload (every block id + html) plus JSON
        # escaping overhead (dialogue quotes double in size), and hybrid-reasoning models
        # spend hidden reasoning tokens inside the same completion limit, so budget
        # generously: billed tokens are what is generated, the ceiling itself is free.
        max_tokens = max(4000, int(est_tokens(user) * 2.2) + 1000 + REASONING_TOKEN_ALLOWANCE)
        cap = self.max_completion_cap()
        if cap:
            max_tokens = min(max_tokens, cap)
        result = self.call("rewrite" if critique is None else "rewrite_after_critique",
                           [{"role": "system", "content": system}, {"role": "user", "content": user}],
                           max_tokens, temperature=0.6, schema=REWRITE_SCHEMA)
        self.last_raw = result["content"]
        if result["receipt"].get("finish_reason") == "length":
            return ValidationResult(False, issues=[{"code": "truncated", "detail": f"finish_reason=length at max_tokens={max_tokens}"}]), result["content"]
        validation = self._parse_or_validate(result["content"])
        if validation.ok:
            return validation, result["content"]
        print(f"  validation failed: {[i['code'] for i in validation.issues]} -> one repair call")
        repair_user = user + ("\n\nYour previous reply failed validation:\n" +
                              "\n".join(f"- {i['code']}: {i['detail']}" for i in validation.issues) +
                              "\nReturn the corrected complete JSON: every input block id exactly once, in order; "
                              "protected blocks byte-for-byte.")
        result2 = self.call("repair",
                            [{"role": "system", "content": system}, {"role": "user", "content": repair_user}],
                            max_tokens, temperature=0.3, schema=REWRITE_SCHEMA)
        self.last_raw = result2["content"]
        if result2["receipt"].get("finish_reason") == "length":
            return ValidationResult(False, issues=validation.issues + [{"code": "truncated", "detail": f"repair finish_reason=length at max_tokens={max_tokens}"}]), result2["content"]
        return self._parse_or_validate(result2["content"]), result2["content"]

    def _parse_or_validate(self, content: str) -> ValidationResult:
        """Parse a rewrite reply; an unparseable body is a validation failure (repair-able),
        never a crash."""
        try:
            parsed = parse_model_json(content)
        except json.JSONDecodeError as e:
            return ValidationResult(False, issues=[{"code": "unparseable", "detail": str(e)}])
        if not isinstance(parsed, dict):
            return ValidationResult(False, issues=[{"code": "unparseable", "detail": "reply is not a JSON object"}])
        return validate_rewrite(parsed, self.chapter)

    def verify(self, rewritten: list[B.Block]) -> dict:
        version, system = load_prompt("verifier_system_v1")
        user = build_verifier_user_message(self.entry, self.chapter.blocks, rewritten)
        parsed = None
        for attempt in range(2):
            result = self.call("verify",
                               [{"role": "system", "content": system}, {"role": "user", "content": user}],
                               max_tokens=4000 + 2000 * attempt, temperature=0.1 if attempt == 0 else 0.0,
                               schema=VERIFIER_SCHEMA)
            try:
                parsed = parse_model_json(result["content"])
                break
            except json.JSONDecodeError as e:
                print(f"  verifier JSON parse failed ({e}); {'one retry' if attempt == 0 else 'giving up'}")
        if parsed is None:
            return {"prompt_version": version, "model": self.args.model, "findings": [],
                    "parse_error": "verifier reply unparseable after one retry"}
        return {"prompt_version": version, "model": self.args.model, "findings": parsed.get("findings", [])}

    def critique(self, rewritten: list[B.Block]) -> str:
        version, system = load_prompt("critic_system_v1")
        user = build_critique_user_message(self.entry, self.chapter.blocks, rewritten)
        result = self.call("critique",
                           [{"role": "system", "content": system}, {"role": "user", "content": user}],
                           max_tokens=2000, temperature=0.3, schema=None)
        return result["content"]

    def execute(self) -> dict:
        print(f"run {self.run_id} (rewrite prompt {self.prompt_versions['rewrite']})")
        print(f"  blocks={len(self.chapter.blocks)} addressable={len(self.chapter.addressable)} "
              f"protected={len(self.chapter.blocks) - len(self.chapter.addressable)}")
        before = C.cadence_of(self.chapter.blocks)
        validation, raw = self.rewrite()
        meta: dict = {
            "run_id": self.run_id, "operation_id": self.operation_id,
            "chapter": self.args.chapter, "model": self.args.model, "pipeline": self.args.pipeline,
            "prompt_versions": self.prompt_versions, "provider_routing": self.provider,
            "issues": validation.issues, "warnings": validation.warnings,
            "sanitization_notes": validation.sanitization_notes,
            "merged_blocks": validation.merged_count,
            "max_empty_run": validation.max_empty_run,
            "cost_total_usd": round(self.spent, 4),
        }
        if not validation.ok:
            meta["status"] = "rejected"
            self.finish(meta, before=before, after=None, raw=self.last_raw)
            return meta

        polished_blocks = [b for b in validation.blocks if b.html != ""]
        after = C.cadence_of(polished_blocks)
        meta["cadence_comparison"] = C.compare(before, after).to_dict()
        # Checkpoint after the first billed, validated rewrite so a later budget or
        # network failure cannot lose paid work (plan: persist after every billed chunk).
        self.checkpoint(meta, before, after, polished_blocks)

        if self.args.pipeline == "critique_repair":
            critique_text = self.critique(polished_blocks)
            open(os.path.join(self.run_dir, "critique.txt"), "w").write(critique_text)
            validation2, raw2 = self.rewrite(critique=critique_text)
            meta["issues_after_critique"] = validation2.issues
            meta["warnings_after_critique"] = validation2.warnings
            if validation2.ok:
                polished_blocks = [b for b in validation2.blocks if b.html != ""]
                after = C.cadence_of(polished_blocks)
                meta["cadence_comparison"] = C.compare(before, after).to_dict()
            else:
                meta["critique_repair_rejected"] = True

        verification = None
        if self.args.pipeline in ("verify", "critique_repair"):
            # Verify against the unfiltered block list so merged blocks appear as pairs
            # with empty rewritten_html instead of vanishing from the comparison.
            verification = self.verify(validation.blocks)
            blockers = [f for f in verification["findings"] if f.get("severity") == "blocker"]
            meta["verification"] = verification
            meta["verification_blockers"] = len(blockers)

        meta["status"] = "validated" if self.args.pipeline == "one_pass" else "verified"
        if verification and meta.get("verification_blockers", 0) > 0:
            meta["status"] = "blocked_by_verifier"
        self.finish(meta, before=before, after=after, polished=polished_blocks, raw=raw)
        return meta

    def checkpoint(self, meta: dict, before: C.CadenceReport, after: C.CadenceReport, polished: list[B.Block]) -> None:
        """Write current artifacts without finalizing receipts (mid-run durability)."""
        json.dump(before.to_dict(), open(os.path.join(self.run_dir, "cadence_before.json"), "w"), indent=1)
        json.dump(after.to_dict(), open(os.path.join(self.run_dir, "cadence_after.json"), "w"), indent=1)
        open(os.path.join(self.run_dir, "polished.html"), "w").write(B.assemble_chapter_html(polished) + "\n")
        open(os.path.join(self.run_dir, "source_sanitized.html"), "w").write(self.chapter.html_of() + "\n")
        json.dump({**meta, "receipts": self.receipts, "checkpointed": True},
                  open(os.path.join(self.run_dir, "meta.json"), "w"), indent=1)

    def finish(self, meta: dict, before: C.CadenceReport, after: C.CadenceReport | None = None, polished: list[B.Block] | None = None, raw: str | None = None) -> None:
        json.dump(before.to_dict(), open(os.path.join(self.run_dir, "cadence_before.json"), "w"), indent=1)
        if after is not None:
            json.dump(after.to_dict(), open(os.path.join(self.run_dir, "cadence_after.json"), "w"), indent=1)
        if polished is not None:
            open(os.path.join(self.run_dir, "polished.html"), "w").write(B.assemble_chapter_html(polished) + "\n")
        if raw:
            open(os.path.join(self.run_dir, "model_raw_reply.txt"), "w").write(raw)
        open(os.path.join(self.run_dir, "source_sanitized.html"), "w").write(self.chapter.html_of() + "\n")
        with open(os.path.join(self.run_dir, "receipts.jsonl"), "w") as f:
            for r in self.receipts:
                f.write(json.dumps(r) + "\n")
        meta["receipts"] = self.receipts
        meta["cost_total_usd"] = round(self.spent, 4)
        json.dump(meta, open(os.path.join(self.run_dir, "meta.json"), "w"), indent=1)
        print(f"  status={meta['status']} spent=${self.spent:.4f} -> {self.run_dir}")


# ------------------------------------------------------------------- subcommands


def cmd_catalog(args) -> None:
    models = fetch_models()
    def show(pred, label, limit=12):
        rows = [(mid, m) for mid, m in models.items() if pred(mid, m)]
        rows.sort(key=lambda kv: (kv[1]["completion_price_per_token"] or 0), reverse=True)
        print(f"\n{label}:")
        for mid, m in rows[:limit]:
            print(f"  {mid}")
            print(f"    ${m['prompt_price_per_token'] * 1e6:.2f}/M in, ${m['completion_price_per_token'] * 1e6:.2f}/M out, "
                  f"ctx={m['context_length']}, structured={'response_format' in m['supported_parameters']}")
    pat = args.match.lower() if args.match else ""
    def match(mid: str) -> bool:
        return pat in mid.lower() or pat in models[mid]["name"].lower()
    show(lambda mid, m: match(mid) and m["completion_price_per_token"] >= 5e-6, "frontier candidates (>= $5/M out)", limit=args.limit)
    show(lambda mid, m: match(mid) and 5e-6 > m["completion_price_per_token"] >= 5e-7, "mid tier", limit=args.limit)
    show(lambda mid, m: match(mid) and m["completion_price_per_token"] < 5e-7, "flash tier", limit=args.limit)


def cmd_plan(args) -> None:
    entry = corpus_entry(args.chapter)
    parsed = B.parse_chapter(open(entry["path"], encoding="utf-8").read())
    version, system = load_prompt("rewrite_system_v1")
    user = build_rewrite_user_message(entry, parsed)
    est_in = est_tokens(system) + est_tokens(user)
    max_tokens = max(4000, int(est_tokens(user) * 2.2) + 1000)
    print(f"chapter: {entry['id']} ({entry['kind']})")
    print(f"blocks: {len(parsed.blocks)} total, {len(parsed.addressable)} addressable, "
          f"{len(parsed.blocks) - len(parsed.addressable)} protected")
    reasons: dict[str, int] = {}
    for b in parsed.blocks:
        if b.protected:
            reasons[b.reason] = reasons.get(b.reason, 0) + 1
    print(f"protected census: {reasons}")
    print(f"source words: {sum(len(C.words(b.text)) for b in parsed.addressable)}")
    print(f"est input tokens: {est_in}, max output tokens: {max_tokens}")
    try:
        models = fetch_models()
        m = models.get(args.model)
        if m:
            cost = est_in * m["prompt_price_per_token"] + max_tokens * m["completion_price_per_token"]
            print(f"model {args.model}: ctx={m['context_length']} "
                  f"structured={'response_format' in m['supported_parameters']} est rewrite cost <= ${cost:.3f}")
            vcost = (est_in * 1.5) * m["prompt_price_per_token"] + 4000 * m["completion_price_per_token"]
            print(f"est rewrite+verify <= ${cost + vcost:.3f}")
        else:
            print(f"model {args.model} not in catalog")
    except Exception as e:
        print(f"(catalog unavailable: {e})")
    if args.dump_prompt:
        print("\n----- SYSTEM -----\n" + system)
        print("\n----- USER (first 3000 chars) -----\n" + user[:3000])


def _ns(**kw):
    """argparse.Namespace shim for programmatic SpikeRun construction."""
    defaults = dict(chapter="foxkin_ch1", model="", pipeline="one_pass", max_cost_usd=6.0,
                    relax_privacy=False, no_privacy=False, prompt_version=DEFAULT_REWRITE_PROMPT)
    defaults.update(kw)
    import argparse
    return argparse.Namespace(**defaults)


def _routing_error(e: OpenRouterError) -> bool:
    return "404" in str(e) and ("endpoint" in str(e).lower() or "data policy" in str(e).lower())


def run_one(chapter: str, model: str, pipeline: str = "one_pass", max_cost: float = 6.0,
            prompt_version: str = DEFAULT_REWRITE_PROMPT) -> tuple[str | None, str]:
    """One rewrite run, escalating provider routing strictness only when routing 404s.

    Returns (run_dir_or_None, tier) where tier records which privacy level was used.
    """
    tiers = ["strict", "relaxed", "none"]
    for tier in tiers:
        args = _ns(chapter=chapter, model=model, pipeline=pipeline, max_cost_usd=max_cost,
                   prompt_version=prompt_version,
                   relax_privacy=(tier == "relaxed"), no_privacy=(tier == "none"))
        run = SpikeRun(args)
        try:
            meta = run.execute()
            if meta.get("status") != "error":
                return run.run_dir, tier
            issues = "; ".join(i["detail"] for i in meta.get("issues", []))
            if not _routing_error_str(issues):
                return run.run_dir, tier
        except OpenRouterError as e:
            if not _routing_error(e):
                print(f"  {model}: aborting ({e})", file=sys.stderr)
                return None, tier
        print(f"  {model}: routing failed at {tier} privacy, stepping down...")
    return None, "none"


def _routing_error_str(s: str) -> bool:
    return "404" in s and ("endpoint" in s.lower() or "data policy" in s.lower())


def cmd_matrix(args) -> None:
    """Run the same chapter through a list of models, then verify each validated rewrite."""
    models = [m.strip() for m in args.models.split(",") if m.strip()]
    results: list[dict] = []
    for model in models:
        print(f"\n=== {model} ===")
        try:
            run_dir, tier = run_one(args.chapter, model, pipeline=args.pipeline,
                                    max_cost=args.max_cost_per_model, prompt_version=args.prompt_version)
        except Exception as e:  # one broken model must not kill the batch
            print(f"  {model}: crashed: {type(e).__name__}: {e}", file=sys.stderr)
            run_dir, tier = None, "error"
        results.append({"model": model, "run_dir": run_dir, "privacy_tier": tier})
    print("\n=== verification pass (verifier:", args.verify_with, ") ===")
    for r in results:
        if not r["run_dir"]:
            continue
        meta = json.load(open(os.path.join(r["run_dir"], "meta.json")))
        if meta.get("status") not in ("validated", "verified"):
            continue
        v_tier = "strict"
        for tier in ("strict", "relaxed", "none"):
            try:
                v_args = _ns(chapter=args.chapter, model=args.verify_with, pipeline="verify",
                             max_cost_usd=args.max_cost_per_model,
                             relax_privacy=(tier == "relaxed"), no_privacy=(tier == "none"))
                verify_run = SpikeRun(v_args)
                verify_run.run_dir = r["run_dir"]
                source = B.parse_chapter(open(os.path.join(r["run_dir"], "source_sanitized.html")).read())
                polished = load_id_aligned_blocks(r["run_dir"], source)
                if polished is None:
                    print(f"  {r['model']}: cannot rebuild id-aligned blocks; skipping verify", file=sys.stderr)
                    break
                verification = verify_run.verify(polished)
                blockers = [f for f in verification["findings"] if f.get("severity") == "blocker"]
                meta["verification"] = verification
                meta["verification_blockers"] = len(blockers)
                meta["pipeline"] = "verify"
                if verification.get("parse_error"):
                    meta["status"] = "verify_failed"
                    print(f"  {r['model']}: verifier could not be parsed (recorded verify_failed)")
                    break
                meta["status"] = "verified" if not blockers else "blocked_by_verifier"
                meta["verify_receipts"] = verify_run.receipts
                meta["verify_cost_usd"] = round(verify_run.spent, 4)
                meta["cost_total_usd"] = round(meta.get("cost_total_usd", 0) + verify_run.spent, 4)
                json.dump(meta, open(os.path.join(r["run_dir"], "meta.json"), "w"), indent=1)
                v_tier = tier
                print(f"  {r['model']}: verified, {len(blockers)} blockers (${verify_run.spent:.4f})")
                break
            except OpenRouterError as e:
                if not _routing_error(e):
                    print(f"  {r['model']}: verify failed: {e}", file=sys.stderr)
                    break
                v_tier = tier + "->stepping down"
            except Exception as e:  # keep the batch alive through verifier crashes too
                print(f"  {r['model']}: verify crashed: {type(e).__name__}: {e}", file=sys.stderr)
                break
        r["verify_tier"] = v_tier
    print("\n=== summary ===")
    for r in results:
        status = "no-run" if not r["run_dir"] else json.load(open(os.path.join(r["run_dir"], "meta.json"))).get("status")
        print(f"  {r['model']}: {status} (rewrite privacy: {r['privacy_tier']})")
    report_args = _ns()
    import types
    report_args.ballot_seed = 20260824
    cmd_report(report_args)


def cmd_run(args) -> None:
    run = SpikeRun(args)
    try:
        meta = run.execute()
    except OpenRouterError as e:
        print(f"ABORTED: {e}", file=sys.stderr)
        run.finish({"run_id": run.run_id, "chapter": args.chapter, "model": args.model,
                    "pipeline": args.pipeline, "status": "error", "prompt_versions": PROMPT_VERSIONS,
                    "provider_routing": run.provider, "operation_id": run.operation_id,
                    "issues": [{"code": "openrouter_error", "detail": str(e)}],
                    "warnings": [], "sanitization_notes": [], "receipts": run.receipts,
                    "cost_total_usd": round(run.spent, 4)},
                   before=C.cadence_of(run.chapter.blocks))
        sys.exit(3)
    except CostBudgetExceeded as e:
        print(f"ABORTED: {e}", file=sys.stderr)
        run.finish({"run_id": run.run_id, "chapter": args.chapter, "model": args.model,
                    "pipeline": args.pipeline, "status": "budget_aborted", "issues": [{"code": "budget", "detail": str(e)}],
                    "warnings": [], "sanitization_notes": [], "receipts": run.receipts,
                    "cost_total_usd": round(run.spent, 4), "prompt_versions": PROMPT_VERSIONS,
                    "provider_routing": run.provider, "operation_id": run.operation_id},
                   before=C.cadence_of(run.chapter.blocks))
        sys.exit(2)


def _planted_run_dir(path: str) -> str:
    if not os.path.isdir(path):
        raise SystemExit(f"{path} is not a run directory")
    return path


def load_id_aligned_blocks(run_dir: str, chapter: B.ParsedChapter) -> list[B.Block] | None:
    """Rebuild id-aligned rewritten blocks for a stored run from the validated model reply.

    polished.html carries no block ids — re-parsing it assigns fresh sequential ids that
    silently mis-pair against source ids once merges shrink the chapter (the round-two
    verification bug). The stored reply keeps the real ids including "" merge markers.
    """
    raw_path = os.path.join(run_dir, "model_raw_reply.txt")
    if not os.path.exists(raw_path):
        return None
    try:
        parsed = parse_model_json(open(raw_path).read())
    except json.JSONDecodeError:
        return None
    result = validate_rewrite(parsed, chapter)
    if result.ok and result.blocks:
        return result.blocks
    return None


def cmd_verify(args) -> None:
    """Run the independent verifier over an existing validated run's polished output (paid)."""
    run_dir = _planted_run_dir(args.run_dir)
    meta = json.load(open(os.path.join(run_dir, "meta.json")))
    source = B.parse_chapter(open(os.path.join(run_dir, "source_sanitized.html")).read())
    polished = load_id_aligned_blocks(run_dir, source)
    if polished is None:
        raise SystemExit("cannot rebuild id-aligned blocks from model_raw_reply.txt; "
                         "refusing to verify against id-less polished.html")

    class A:
        chapter = meta["chapter"]
        model = args.model or meta["model"]
        relax_privacy = args.relax_privacy
        no_privacy = args.no_privacy
        max_cost_usd = args.max_cost_usd
        pipeline = "verify"

    run = SpikeRun(A())
    verification = run.verify(polished)
    blockers = [f for f in verification["findings"] if f.get("severity") == "blocker"]
    meta["verification"] = verification
    meta["verification_blockers"] = len(blockers)
    meta["pipeline"] = "verify"
    if verification.get("parse_error"):
        meta["status"] = "verify_failed"
    else:
        meta["status"] = "verified" if not blockers else "blocked_by_verifier"
    meta["verify_receipts"] = run.receipts
    meta["verify_cost_usd"] = round(run.spent, 4)
    meta["cost_total_usd"] = round(meta.get("cost_total_usd", 0) + run.spent, 4)
    json.dump(meta, open(os.path.join(run_dir, "meta.json"), "w"), indent=1)
    print(f"  findings: {len(verification['findings'])} ({len(blockers)} blockers), verifier cost ${run.spent:.4f}")
    for f in verification["findings"]:
        print(f"  [{f.get('severity')}] {f.get('type')} {f.get('block_ids')}: {f.get('evidence', '')[:110]}")


def cmd_holdout(args) -> None:
    """Plant subtle errors in a validated run's polished output and check the verifier catches them."""
    run_dir = _planted_run_dir(args.run_dir)
    meta = json.load(open(os.path.join(run_dir, "meta.json")))
    source = B.parse_chapter(open(os.path.join(run_dir, "source_sanitized.html")).read())
    polished = [b for b in (load_id_aligned_blocks(run_dir, source) or []) if b.html.strip()]
    if not polished:
        raise SystemExit("cannot rebuild id-aligned blocks from model_raw_reply.txt")

    rng = random.Random(20260824)
    addressable_idx = [i for i, b in enumerate(polished) if not b.protected]
    planted: list[dict] = []

    def find_block(pred) -> int:
        cands = [i for i in addressable_idx if pred(polished[i])]
        return rng.choice(cands) if cands else -1

    # 1) changed_number: bump the first number in a prose block
    i = find_block(lambda b: re.search(r"\d+", b.html))
    if i >= 0:
        polished[i].html = re.sub(r"\d+", lambda m: str(int(m.group()) + 1), polished[i].html, count=1)
        planted.append({"type": "changed_number", "block": polished[i].id})
    # 2) missing_content: delete one addressable block entirely
    if len(addressable_idx) > 6:
        victim = addressable_idx[len(addressable_idx) // 2]
        planted.append({"type": "missing_content", "block": polished[victim].id})
        polished[victim].html = "<p>…</p>"
    # 3) invented_detail: append an unsupported detail
    i = find_block(lambda b: len(C.words(b.text)) > 15)
    if i >= 0:
        polished[i].html = polished[i].html.replace("</p>", " The room smelled of copper and old rain.</p>")
        planted.append({"type": "invented_detail", "block": polished[i].id})

    class A:  # minimal args shim for SpikeRun.verify
        chapter = meta["chapter"]
        model = args.model or meta["model"]
        relax_privacy = getattr(args, "relax_privacy", False)
        max_cost_usd = args.max_cost_usd
        pipeline = "holdout"

    run = SpikeRun(A())
    verification = run.verify(polished)
    blockers = [f for f in verification["findings"] if f.get("severity") == "blocker"]
    warnings = [f for f in verification["findings"] if f.get("severity") == "warning"]
    hit_types = {f.get("type") for f in blockers}
    expected = {p["type"] for p in planted}
    caught = len(expected & hit_types)
    verdict = "PASS" if caught >= 2 else "FAIL"
    out = {
        "planted": planted, "blockers": blockers, "warnings": warnings,
        "expected_types": sorted(expected), "caught_types": sorted(hit_types),
        "caught_of_expected": f"{caught}/{len(expected)}", "verdict": verdict,
        "verifier_model": A.model, "receipts": run.receipts,
    }
    out_dir = os.path.join(run.run_dir, "holdout.json")
    json.dump(out, open(out_dir, "w"), indent=1)
    print(json.dumps({k: v for k, v in out.items() if k not in ("blockers", "warnings", "receipts")}, indent=1))
    for b in blockers:
        print(f"  blocker {b.get('type')} {b.get('block_ids')}: {b.get('evidence', '')[:100]}")
    print(f"holdout verdict: {verdict} ({out_dir}); verifier cost ${run.spent:.4f}")


def cmd_audit(args) -> None:
    """Re-validate a run's stored model reply under the CURRENT validation rules (offline).

    Used after tightening validation (e.g. the merge_slot_shift rule) to re-judge existing
    runs without paying for anything again. Updates meta.json with audit_* fields.
    """
    run_dir = _planted_run_dir(args.run_dir)
    meta = json.load(open(os.path.join(run_dir, "meta.json")))
    raw_path = os.path.join(run_dir, "model_raw_reply.txt")
    if not os.path.exists(raw_path):
        raise SystemExit("no model_raw_reply.txt in run dir")
    chapter = B.parse_chapter(open(os.path.join(run_dir, "source_sanitized.html")).read())
    try:
        parsed = parse_model_json(open(raw_path).read())
    except json.JSONDecodeError as e:
        print(f"audit: reply unparseable ({e})")
        return
    result = validate_rewrite(parsed, chapter)
    meta["audit"] = {
        "revalidated_with": "merge_slot_shift rules",
        "ok": result.ok,
        "issues": result.issues,
        "merged_count": result.merged_count,
        "max_empty_run": result.max_empty_run,
    }
    json.dump(meta, open(os.path.join(run_dir, "meta.json"), "w"), indent=1)
    print(f"{meta['model']}: ok={result.ok} merged={result.merged_count} max_empty_run={result.max_empty_run} "
          f"issues={[i['code'] for i in result.issues]}")
    if result.max_empty_run > MAX_CONSECUTIVE_EMPTY_MERGES:
        print("  SLOT-SHIFT signature: content was shifted into earlier ids instead of merging in place.")


def cmd_report(args) -> None:
    runs = collect_runs()
    if not runs:
        raise SystemExit("no runs under results/ yet")

    lines = ["# Chapter-polish spike — evaluation report", ""]
    lines.append(f"Generated {dt.datetime.now().isoformat(timespec='seconds')}; {len(runs)} runs.")
    lines.append("")
    lines.append("| chapter | model | prompt | pipeline | status | blockers | warnings | clusters | fragment share | triplets | sentence CV | template-swap | cost |")
    lines.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|")
    for m in runs:
        cc = m.get("cadence_comparison") or {}
        b, a = cc.get("before") or {}, cc.get("after") or {}

        def fmt(before_v, after_v, pct=False):
            if before_v is None or after_v is None:
                return "—"
            if pct:
                return f"{before_v:.0%}→{after_v:.0%}"
            return f"{before_v}→{after_v}"

        prompt_v = (m.get("prompt_versions") or {}).get("rewrite", "?")
        lines.append(
            f"| {m.get('chapter')} | `{m.get('model')}` | {prompt_v} | {m.get('pipeline')} | {m.get('status')} "
            f"| {m.get('verification_blockers', '—')} | {len(m.get('warnings', []))} "
            f"| {fmt(b.get('cluster_count'), a.get('cluster_count'))} "
            f"| {fmt(b.get('fragment_share'), a.get('fragment_share'), pct=True)} "
            f"| {fmt(b.get('triplet_count'), a.get('triplet_count'))} "
            f"| {fmt(b.get('sentence_length_cv'), a.get('sentence_length_cv'))} "
            f"| {'YES' if cc.get('template_swap_warning') else 'no'} "
            f"| ${m.get('cost_total_usd', 0):.3f} |")
        if cc.get("template_swap_detail"):
            lines.append(f"  - template-swap: {cc['template_swap_detail']}")
    report_path = os.path.join(RESULTS_DIR, "report.md")
    open(report_path, "w").write("\n".join(lines) + "\n")
    print(f"wrote {report_path}")

    # Blind ballot over validated/verified rewrites per chapter. verify runs are the same
    # rewrite as their one_pass origin (verification only adds findings), so both qualify.
    ballot_data, key = build_ballot(args.ballot_seed or 20260824, runs=runs)
    if not ballot_data["chapters"]:
        print("no validated one_pass runs; skipping ballot")
        return
    ballot_lines = ["# Blind preference ballot", "",
                    "Below are the source chapter and anonymous rewrites. Read each version of a chapter",
                    "and rank them (e.g. 'A > C > source'). Do not open ballot_key.json until after voting.",
                    "The go/no-go gate: does any model produce prose you *prefer* on your own novels?", ""]
    for ch in ballot_data["chapters"]:
        ballot_lines += [f"## {ch['id']} ({ch['kind']})", ""]
        for v in ch["versions"]:
            text = "\n\n".join(v["paragraphs"])
            ballot_lines += [f"### Version {v['label']}", "", "```", text.strip(), "```", ""]
        ballot_lines += [f"Ranking for {ch['id']}: ____", ""]
    ballot_path = os.path.join(RESULTS_DIR, "ballot.md")
    open(ballot_path, "w").write("\n".join(ballot_lines) + "\n")
    key_path = os.path.join(RESULTS_DIR, "ballot_key.json")
    json.dump({"seed": ballot_data["seed"], "labels": key}, open(key_path, "w"), indent=1)
    print(f"wrote {ballot_path} (key: {key_path} — open only after voting)")


def collect_runs() -> list[dict]:
    os.makedirs(RESULTS_DIR, exist_ok=True)
    runs = []
    for name in sorted(os.listdir(RESULTS_DIR)):
        meta_path = os.path.join(RESULTS_DIR, name, "meta.json")
        if os.path.exists(meta_path):
            runs.append(json.load(open(meta_path)))
    return runs


def _chapter_paragraphs(html: str) -> list[str]:
    """Paragraph texts (protected panels keep their internal line structure)."""
    out = []
    for b in B.parse_chapter(html).blocks:
        t = b.text.strip()
        if t:
            out.append(t)
    return out


def build_ballot(seed: int, runs: list[dict] | None = None) -> tuple[dict, dict]:
    """One source of truth for the blind ballot: (ballot_data, key).

    ballot_data = {"seed", "chapters": [{"id", "kind", "versions": [{"label",
    "paragraphs"}]}]} with labels shuffled deterministically per chapter; key maps
    label -> "model [run_id]" (or "source"). The markdown ballot (cmd_report) and
    the ballot web page (cmd_ballot_ui) both consume this so their labels agree.
    """
    runs = runs if runs is not None else collect_runs()
    by_chapter: dict[str, list[dict]] = {}
    for m in runs:
        if m.get("status") in ("validated", "verified") and m.get("pipeline") in ("one_pass", "verify"):
            by_chapter.setdefault(m["chapter"], []).append(m)
    chapters: list[dict] = []
    key: dict[str, dict[str, str]] = {}
    for chapter, ms in by_chapter.items():
        entry = corpus_entry(chapter)
        entries: list[tuple[str, list[str]]] = [
            ("source", _chapter_paragraphs(open(entry["path"], encoding="utf-8").read()))
        ]
        for m in ms:
            polished_path = os.path.join(RESULTS_DIR, m["run_id"], "polished.html")
            if os.path.exists(polished_path):
                entries.append((f"{m['model']} [{m['run_id']}]",
                                _chapter_paragraphs(open(polished_path).read())))
        rng = random.Random(seed + zlib.crc32(chapter.encode("utf-8")))
        rng.shuffle(entries)
        labels = [chr(ord("A") + i) for i in range(len(entries))]
        key[chapter] = dict(zip(labels, [e[0] for e in entries]))
        chapters.append({
            "id": chapter, "kind": entry.get("kind", ""),
            "versions": [{"label": label, "paragraphs": paragraphs}
                         for label, (_, paragraphs) in zip(labels, entries)],
        })
    return {"seed": seed, "chapters": chapters}, key


def cmd_ballot_ui(args) -> None:
    """Render the blind ballot as a self-contained web page (results/ballot.html)."""
    import ballot_ui

    ballot_data, key = build_ballot(args.ballot_seed or 20260824)
    if not ballot_data["chapters"]:
        raise SystemExit("no validated runs to ballot")
    out = os.path.join(RESULTS_DIR, "ballot.html")
    ballot_ui.write_ballot_page(out, ballot_data, key)


def cmd_selftest(args) -> None:
    failures: list[str] = []

    def check(name: str, cond: bool) -> None:
        print(f"  {'ok  ' if cond else 'FAIL'} {name}")
        if not cond:
            failures.append(name)

    print("prompts")
    for p in ("rewrite_system_v1", "verifier_system_v1", "critic_system_v1"):
        v, text = load_prompt(p)
        check(f"{p} loads with version header", v == "v1" and len(text) > 400)

    print("sanitizer")
    dirty = '<div style="x:1" onclick="evil()"><script>bad()</script><p class="junk" style="m:1">keep <b>bold</b> &amp; <i>it</i></p><iframe src="x"></iframe><span style="font-weight:400">tail</span></div>'
    clean = B.sanitize_chapter_html(dirty)
    check("scripts/styles/attrs stripped, text kept", "script" not in clean and "onclick" not in clean and "keep" in clean and "<b>bold</b>" in clean)

    print("sentence splitting")
    s = C.split_sentences("Mr. Bennet was so odd. “One out of ten.” A pause. A calculation. A prediction.")
    check("Mr. abbreviation not split", any(x.startswith("Mr. Bennet") for x in s))
    check("fragment triplet counts as 3 sentences", sum(1 for x in s if x.strip() in ("A pause.", "A calculation.", "A prediction.")) == 3)

    print("classification")
    syn = B.parse_chapter(open(os.path.join(CORPUS_DIR, "synthetic_litrpg_ch1.html")).read())
    prot = [b for b in syn.blocks if b.protected]
    kinds = {b.tag for b in prot}
    check("synthetic: tables/blockquotes/hr/headings protected", {"table", "blockquote", "hr", "h3"} <= kinds)
    check("synthetic: spacers protected", any(b.reason == "spacer" for b in prot))
    check("synthetic: dialogue paragraphs addressable", any(not b.protected and ('"' in b.html or "“" in b.html) for b in syn.blocks))
    check("ids sequential", [b.id for b in syn.blocks] == [f"b{i:04d}" for i in range(1, len(syn.blocks) + 1)])

    foxkin_path = os.path.join(CORPUS_DIR, "local", "foxkin_ch1.html")
    if os.path.exists(foxkin_path):
        print("foxkin reference numbers (plan: 187/91/11)")
        fox = B.parse_chapter(open(foxkin_path).read())
        rep = C.cadence_of(fox.blocks)
        print(f"    measured: paragraphs={rep.paragraph_count} fragments={rep.fragment_paragraphs} clusters={rep.cluster_count}")
        check("foxkin paragraphs within 180-195", 180 <= rep.paragraph_count <= 195)
        check("foxkin fragments within 84-98", 84 <= rep.fragment_paragraphs <= 98)
        check("foxkin clusters within 7-15", 7 <= rep.cluster_count <= 15)

    print("validation")
    src = B.parse_chapter("<p>Alpha sentence one.</p><blockquote><strong>[SYSTEM] Level 4</strong></blockquote><p>Beta line here now.</p>")
    good = {"blocks": [{"id": "b0001", "html": "<p>Alpha sentence one, unchanged.</p>"},
                       {"id": "b0002", "html": "<blockquote><strong>[SYSTEM] Level 4</strong></blockquote>"},
                       {"id": "b0003", "html": "<p>Beta line here now.</p>"}],
            "self_audit": {"protected_blocks_unchanged": True, "possible_drift": []}}
    v = validate_rewrite(good, src)
    check("valid reply passes", v.ok)
    bad_ids = {"blocks": [{"id": "b0001", "html": "<p>x</p>"}], "self_audit": {"protected_blocks_unchanged": True, "possible_drift": []}}
    check("missing ids rejected", not validate_rewrite(bad_ids, src).ok)
    mutated = json.loads(json.dumps(good))
    mutated["blocks"][1]["html"] = "<blockquote><strong>[SYSTEM] Level 5</strong></blockquote>"
    r = validate_rewrite(mutated, src)
    check("protected mutation rejected", not r.ok and any(i["code"] == "protected_changed" for i in r.issues))
    scripted = json.loads(json.dumps(good))
    scripted["blocks"][0]["html"] = "<p onclick=\"x()\">Alpha <img src=\"http://x\">sentence one.</p>"
    r = validate_rewrite(scripted, src)
    check("output sanitizer strips script/img attrs", r.ok and not any("onclick" in b.html for b in r.blocks) and "img" not in r.blocks[0].html)

    print("merge semantics (v1.1)")
    src_pair = B.parse_chapter("<p>Alpha sentence one.</p><p>Beta line here now.</p>")
    merged = {"blocks": [{"id": "b0001", "html": "<p>Alpha sentence one that absorbs Beta.</p>"},
                         {"id": "b0002", "html": ""}],
              "self_audit": {"protected_blocks_unchanged": True, "possible_drift": []}}
    r = validate_rewrite(merged, src_pair)
    check("valid merge accepted and counted", r.ok and r.merged_count == 1 and r.blocks[1].html == "")
    src_m = B.parse_chapter("<p>First beat.</p><blockquote><strong>[PANEL]</strong></blockquote><p>Second beat.</p>")
    badm = {"blocks": [{"id": "b0001", "html": "<p>First beat.</p>"},
                       {"id": "b0002", "html": "<blockquote><strong>[PANEL]</strong></blockquote>"},
                       {"id": "b0003", "html": ""}],
            "self_audit": {"protected_blocks_unchanged": True, "possible_drift": []}}
    r = validate_rewrite(badm, src_m)
    check("merge across protected rejected", not r.ok and any(i["code"] == "merge_without_target" for i in r.issues))
    firstm = {"blocks": [{"id": "b0001", "html": ""}, {"id": "b0002", "html": "<p>x</p>"}],
              "self_audit": {"protected_blocks_unchanged": True, "possible_drift": []}}
    r = validate_rewrite(firstm, B.parse_chapter("<p>One.</p><p>Two.</p>"))
    check("merge without carrier rejected", not r.ok and any(i["code"] == "merge_without_target" for i in r.issues))

    print("merge run sanity")
    src8 = B.parse_chapter("".join(f"<p>Beat {i}.</p>" for i in range(1, 9)))
    dense = {"blocks": [{"id": "b0001", "html": "<p>Beat one two three four five six seven eight.</p>"}] +
                       [{"id": f"b{i:04d}", "html": ""} for i in range(2, 9)],
             "self_audit": {"protected_blocks_unchanged": True, "possible_drift": []}}
    r = validate_rewrite(dense, src8)
    check("dense cluster merge (run of 7) accepted",
          r.ok and r.merged_count == 7 and r.max_empty_run == 7)
    pathological = {"blocks": [{"id": f"b{i:04d}", "html": "<p>All beats.</p>" if i == 1 else ""}
                               for i in range(1, 26)],
                    "self_audit": {"protected_blocks_unchanged": True, "possible_drift": []}}
    r = validate_rewrite(pathological, B.parse_chapter("".join(f"<p>Beat {i}.</p>" for i in range(1, 26))))
    check("pathological empty dump (run of 24) rejected",
          not r.ok and any(i["code"] == "merge_slot_shift" for i in r.issues))

    print("cadence comparison")
    before_blocks = [B.Block(id=f"b{i:04d}", tag="p", html=f"<p>{t}</p>", protected=False, reason="x")
                     for i, t in enumerate(["A pause.", "A calculation.", "A prediction.", "The long sentence that carries the scene forward with detail and movement goes here.", "Short again.", "Another one.", "Third short.", "End."])]
    after_blocks = [B.Block(id=f"b{i:04d}", tag="p", html="<p>The sentences here are all of one medium uniform length throughout.</p>", protected=False, reason="x")
                    for i in range(8)]
    cmp = C.compare(C.cadence_of(before_blocks), C.cadence_of(after_blocks))
    check("template-swap detected when one shape dominates", cmp.template_swap_warning)

    if failures:
        raise SystemExit(f"selftest failures: {failures}")
    print("\nselftest OK")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)

    sub.add_parser("selftest")

    p = sub.add_parser("catalog")
    p.add_argument("--match", default="", help="substring filter on model id/name")
    p.add_argument("--limit", type=int, default=12)

    p = sub.add_parser("plan")
    p.add_argument("--chapter", required=True)
    p.add_argument("--model", default="")
    p.add_argument("--dump-prompt", action="store_true")

    p = sub.add_parser("run")
    p.add_argument("--chapter", required=True)
    p.add_argument("--model", required=True)
    p.add_argument("--pipeline", choices=["one_pass", "verify", "critique_repair"], default="verify")
    p.add_argument("--max-cost-usd", type=float, default=6.0)
    p.add_argument("--relax-privacy", action="store_true",
                   help="drop require_parameters routing (keeps zdr + deny) for models without parameter support")
    p.add_argument("--no-privacy", action="store_true",
                   help="drop provider routing entirely (recorded in the run meta and report)")
    p.add_argument("--prompt-version", choices=sorted(REWRITE_PROMPTS), default=DEFAULT_REWRITE_PROMPT)

    p = sub.add_parser("verify")
    p.add_argument("run_dir", help="existing run directory with polished.html + source_sanitized.html")
    p.add_argument("--model", default="", help="verifier model (defaults to the run's rewrite model)")
    p.add_argument("--max-cost-usd", type=float, default=2.0)
    p.add_argument("--relax-privacy", action="store_true")
    p.add_argument("--no-privacy", action="store_true")

    p = sub.add_parser("holdout")
    p.add_argument("run_dir")
    p.add_argument("--model", default="")
    p.add_argument("--max-cost-usd", type=float, default=2.0)

    p = sub.add_parser("matrix")
    p.add_argument("--chapter", required=True)
    p.add_argument("--models", required=True, help="comma-separated OpenRouter model ids")
    p.add_argument("--verify-with", default="moonshotai/kimi-k2-0905")
    p.add_argument("--pipeline", choices=["one_pass"], default="one_pass")
    p.add_argument("--max-cost-per-model", type=float, default=4.0)
    p.add_argument("--prompt-version", choices=sorted(REWRITE_PROMPTS), default=DEFAULT_REWRITE_PROMPT)

    p = sub.add_parser("audit")
    p.add_argument("run_dir")

    p = sub.add_parser("report")
    p.add_argument("--ballot-seed", type=int, default=20260824)

    p = sub.add_parser("ballot-ui")
    p.add_argument("--ballot-seed", type=int, default=20260824)

    args = ap.parse_args()
    {"selftest": cmd_selftest, "catalog": cmd_catalog, "plan": cmd_plan,
     "run": cmd_run, "verify": cmd_verify, "holdout": cmd_holdout, "report": cmd_report,
     "matrix": cmd_matrix, "audit": cmd_audit, "ballot-ui": cmd_ballot_ui}[args.cmd](args)


if __name__ == "__main__":
    main()
