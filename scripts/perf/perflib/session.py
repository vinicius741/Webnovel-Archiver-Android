"""Parsing of app-side perf session exports (session.json + frames.jsonl) into plain dicts.

Everything here tolerates malformed input: a bad session becomes a structured
{"error": ...} result instead of an exception, so one corrupt iteration can never
abort a whole run.
"""

import json

SESSION_FORMAT = "webnovel-perf-session"
SESSION_VERSION = 1
REPORT_FORMAT = "webnovel-perf-report"
REPORT_VERSION = 1


def parse_session_json(text):
    """Parses session.json text. Returns (session_dict, None) or (None, error)."""
    try:
        doc = json.loads(text)
    except (ValueError, TypeError) as exc:
        return None, f"invalid json: {exc}"
    if not isinstance(doc, dict):
        return None, "session root is not an object"
    if doc.get("format") != SESSION_FORMAT:
        return None, f"unexpected format {doc.get('format')!r}"
    if doc.get("version") != SESSION_VERSION:
        return None, f"unsupported session version {doc.get('version')!r}"
    session = doc.get("session")
    if not isinstance(session, dict) or "runId" not in session or "scenario" not in session:
        return None, "missing session identity block"
    for key in ("events", "memory"):
        if not isinstance(doc.get(key), list):
            return None, f"missing {key} list"
    if not isinstance(doc.get("overhead"), dict):
        return None, "missing overhead block"
    return doc, None


def parse_frames_jsonl(text):
    """Parses frames.jsonl text; skips blank/malformed/truncated trailing lines and
    returns (samples, dropped_line_count)."""
    samples = []
    dropped = 0
    for line in (text or "").splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            sample = json.loads(line)
            t = sample.get("t")
            d = sample.get("durationMicros")
            if not isinstance(t, (int, float)) or not isinstance(d, (int, float)):
                dropped += 1
                continue
            samples.append(
                {
                    "t": t,
                    "durationMs": d / 1000.0,
                    "tag": str(sample.get("tag") or "unknown"),
                    "jank": bool(sample.get("jank")),
                }
            )
        except ValueError:
            dropped += 1
    return samples, dropped


def event_by_name(session_doc, name, route=None, detail_key="route"):
    """First event with the given name (optionally route-filtered) or None."""
    for event in session_doc.get("events", []):
        if event.get("name") != name:
            continue
        if route is not None and (event.get("detail") or {}).get(detail_key) != route:
            continue
        return event
    return None


def events_named(session_doc, name):
    return [e for e in session_doc.get("events", []) if e.get("name") == name]


def process_start_nanos(session_doc):
    return (session_doc.get("session") or {}).get("processStartElapsedNanos") or 0


def elapsed_ms_since_process_start(session_doc, event):
    start = process_start_nanos(session_doc)
    t = event.get("t") or 0
    if start <= 0 or t < start:
        return None
    return (t - start) / 1_000_000.0


def session_identity(session_doc):
    s = session_doc.get("session") or {}
    return {"runId": s.get("runId"), "scenario": s.get("scenario"), "iteration": s.get("iteration")}


def iteration_status(session_doc, expected_identity, complete_required=True):
    """Classifies a session as ok / incomplete / stale / failed(details)."""
    identity = session_identity(session_doc)
    if expected_identity and (
        identity.get("runId") != expected_identity.get("runId")
        or identity.get("iteration") != expected_identity.get("iteration")
    ):
        return "stale", f"session identity {identity} does not match launch {expected_identity}"
    meta = session_doc.get("session") or {}
    events = session_doc.get("events", [])
    names = [e.get("name") for e in events]
    has_crash_marker = "session_end" in names and meta.get("ended", False)
    if "scenario_deadline" in names:
        return "incomplete", "scenario deadline fired before completion"
    if complete_required and "scenario_complete" not in names:
        if has_crash_marker:
            return "incomplete", "session ended without completion"
        return "incomplete", "no scenario_complete event"
    return "ok", None
