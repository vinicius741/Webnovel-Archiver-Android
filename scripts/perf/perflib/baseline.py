"""Baseline storage, compatibility rules, and advisory threshold verdicts.

Baselines are created only by an explicit `baseline save` command — a run never
replaces a baseline automatically. Comparisons must be rejected (not_comparable)
when the environment that produced the numbers differs in a way that invalidates
them: device model/sdk, app variant, or the library dataset fingerprint.
"""

import json
import os
import time

# Advisory thresholds: a metric is flagged only when BOTH the absolute and the
# relative change exceed the configured values (lower-is-better). Diagnostics
# (memory) never produce verdicts.
DEFAULT_THRESHOLDS = {
    "proc_start_to_ui_ready_ms": {"abs": 150.0, "rel": 0.15},
    "proc_start_to_screen_built_ms": {"abs": 150.0, "rel": 0.15},
    "startup_total_ms": {"abs": 150.0, "rel": 0.15},
    "startup_load_ms": {"abs": 100.0, "rel": 0.15},
    "startup_hydrate_ms": {"abs": 100.0, "rel": 0.15},
    "screen_build_ms": {"abs": 80.0, "rel": 0.20},
    "proc_start_to_reader_presented_ms": {"abs": 300.0, "rel": 0.20},
    "proc_start_to_reader_painted_ms": {"abs": 300.0, "rel": 0.20},
    "reader_prepare_ms": {"abs": 200.0, "rel": 0.25},
    "tap_to_details_ms": {"abs": 200.0, "rel": 0.25},
    "tap_to_library_ms": {"abs": 200.0, "rel": 0.25},
    "tap_to_reader_presented_ms": {"abs": 300.0, "rel": 0.25},
    "tap_to_reader_painted_ms": {"abs": 400.0, "rel": 0.25},
    "frame_p95_ms": {"abs": 6.0, "rel": 0.15},
    "frame_jank_pct": {"abs": 4.0, "rel": 0.30},
}
DIAGNOSTIC_METRICS = {"pss_at_ready_kb", "memory_trend_kb_per_cycle", "library_pss_per_cycle_kb"}
DEFAULT_MIN_SAMPLES = 2


def threshold_for(metric, thresholds):
    if metric in DIAGNOSTIC_METRICS:
        return {"diagnostic": True}
    custom = (thresholds or {}).get(metric)
    if custom:
        return {"abs": float(custom.get("abs", 0.0)), "rel": float(custom.get("rel", 0.0))}
    # Metrics without a configured entry still get compared with a permissive default.
    return {"abs": 50.0, "rel": 0.25}


def judge_metric(baseline_stats, current_stats, threshold, min_samples=DEFAULT_MIN_SAMPLES):
    """Advisory verdict for one metric: regression | improved | stable | inconclusive."""
    if not isinstance(baseline_stats, dict) or not isinstance(current_stats, dict):
        return {"verdict": "inconclusive", "reason": "missing stats"}
    if threshold.get("diagnostic"):
        return {
            "verdict": "diagnostic",
            "reason": "memory metrics are diagnostic-only",
            "baseline_median": baseline_stats.get("median"),
            "current_median": current_stats.get("median"),
        }
    b = baseline_stats.get("median")
    c = current_stats.get("median")
    nb = baseline_stats.get("count") or 0
    nc = current_stats.get("count") or 0
    if b is None or c is None:
        return {"verdict": "inconclusive", "reason": "missing median"}
    if nb < min_samples or nc < min_samples:
        return {"verdict": "inconclusive", "reason": f"insufficient samples (baseline n={nb}, current n={nc})"}
    delta = c - b
    rel = (delta / abs(b)) if b != 0 else None
    result = {
        "baseline_median": round(b, 3),
        "current_median": round(c, 3),
        "delta_abs": round(delta, 3),
        "delta_rel": round(rel, 4) if rel is not None else None,
    }
    if rel is None:
        result["verdict"] = "inconclusive"
        result["reason"] = "baseline median is zero; relative change undefined"
        return result
    if delta >= threshold["abs"] and rel >= threshold["rel"]:
        result["verdict"] = "regression"
    elif delta <= -threshold["abs"] and rel <= -threshold["rel"]:
        result["verdict"] = "improved"
    else:
        result["verdict"] = "stable"
    return result


def compatibility_reasons(current_env, baseline_env):
    """Why a comparison is invalid; empty list means comparable."""
    reasons = []
    if current_env.get("reportVersion") != baseline_env.get("reportVersion"):
        reasons.append(f"report format version differs ({current_env.get('reportVersion')} vs {baseline_env.get('reportVersion')})")
    for device_field in ("model", "sdkInt"):
        cur = (current_env.get("device") or {}).get(device_field)
        base = (baseline_env.get("device") or {}).get(device_field)
        if cur != base:
            reasons.append(f"device {device_field} differs ({base!r} vs {cur!r})")
    cur_ds = current_env.get("dataset") or {}
    base_ds = baseline_env.get("dataset") or {}
    if cur_ds.get("storyIdsSha256") != base_ds.get("storyIdsSha256"):
        reasons.append("library dataset fingerprint differs (stories added/removed/reordered)")
    for field in ("downloadedChapterEntries", "totalChapterEntries", "storageIssues"):
        if cur_ds.get(field) != base_ds.get(field):
            reasons.append(f"library dataset {field} differs ({base_ds.get(field)!r} vs {cur_ds.get(field)!r})")
    cur_target = current_env.get("readerTarget") or {}
    base_target = baseline_env.get("readerTarget") or {}
    for field in ("storyId", "chapterId"):
        if cur_target.get(field) != base_target.get(field):
            reasons.append(f"reader target {field} differs")
    if (cur_ds.get("appId") or (current_env.get("device") or {}).get("appId")) != (
        base_ds.get("appId") or (baseline_env.get("device") or {}).get("appId")
    ):
        reasons.append("app id/variant differs — instrumentation and debug baselines are never comparable")
    return reasons


def scenario_metric_views(scenario):
    """Flatten a scenario aggregate into comparable metric views (incl. frame stats)."""
    views = dict(scenario.get("metrics") or {})
    frames = scenario.get("frames") or {}
    if frames and not frames.get("insufficient_samples"):
        views["frame_p95_ms"] = {
            "median": frames.get("p95_ms"),
            "count": frames.get("count"),
        }
        views["frame_jank_pct"] = {
            "median": frames.get("jank_pct"),
            "count": frames.get("count"),
        }
    return views


def compare_reports(current_report, baseline_report, thresholds=None, min_samples=DEFAULT_MIN_SAMPLES):
    """Full comparison block. Status: ok | not_comparable."""
    thresholds = thresholds or DEFAULT_THRESHOLDS
    current_env = current_report.get("environment") or {}
    baseline_env = baseline_report.get("environment") or {}
    current_env = dict(current_env, reportVersion=current_report.get("version"))
    baseline_env = dict(baseline_env, reportVersion=baseline_report.get("version"))
    reasons = compatibility_reasons(current_env, baseline_env)
    comparison = {
        "baseline": {
            "path": (baseline_report.get("environment") or {}).get("baselinePath"),
            "runId": (baseline_report.get("environment") or {}).get("runId"),
            "generatedAt": baseline_report.get("generatedAt"),
        },
        "compatibilityReasons": reasons,
        "scenarios": {},
        "advisory": True,
    }
    if reasons:
        comparison["status"] = "not_comparable"
        return comparison
    comparison["status"] = "ok"
    base_scenarios = baseline_report.get("scenarios") or {}
    cur_scenarios = current_report.get("scenarios") or {}
    for name, cur in cur_scenarios.items():
        base = base_scenarios.get(name)
        if base is None:
            comparison["scenarios"][name] = {"status": "inconclusive", "reason": "scenario missing in baseline"}
            continue
        if cur.get("status") != "ok" or base.get("status") != "ok":
            comparison["scenarios"][name] = {
                "status": "inconclusive",
                "reason": f"non-ok execution (baseline={base.get('status')}, current={cur.get('status')})",
            }
            continue
        base_views = scenario_metric_views(base)
        cur_views = scenario_metric_views(cur)
        metrics = {}
        for metric, cur_stats in cur_views.items():
            base_stats = base_views.get(metric)
            verdict = judge_metric(base_stats if isinstance(base_stats, dict) else None, cur_stats, threshold_for(metric, thresholds), min_samples)
            metrics[metric] = verdict
        verdicts = {m["verdict"] for m in metrics.values()}
        if "regression" in verdicts:
            status = "regression"
        elif "improved" in verdicts:
            status = "improved"
        else:
            status = "stable"
        comparison["scenarios"][name] = {"status": status, "metrics": metrics}
    return comparison


def baseline_dir(out_root, name):
    return os.path.join(out_root, "baselines", name)


def save_baseline(report, out_root, name, source_path=None):
    """Explicit-only baseline creation; refuses to silently overwrite an existing name."""
    directory = baseline_dir(out_root, name)
    manifest_path = os.path.join(directory, "manifest.json")
    if os.path.exists(manifest_path):
        raise FileExistsError(f"baseline '{name}' already exists at {directory}; remove it first")
    os.makedirs(directory, exist_ok=True)
    with open(os.path.join(directory, "report.json"), "w", encoding="utf-8") as handle:
        json.dump(report, handle, indent=2)
    manifest = {
        "name": name,
        "createdAt": time.strftime("%Y-%m-%dT%H:%M:%S"),
        "source": source_path,
        "runId": (report.get("environment") or {}).get("runId"),
    }
    with open(manifest_path, "w", encoding="utf-8") as handle:
        json.dump(manifest, handle, indent=2)
    return directory


def load_baseline(out_root, name_or_path):
    """Loads a baseline by stored name or direct report path."""
    if os.path.isfile(name_or_path):
        with open(name_or_path, "r", encoding="utf-8") as handle:
            return json.load(handle), name_or_path
    path = os.path.join(baseline_dir(out_root, name_or_path), "report.json")
    if not os.path.isfile(path):
        raise FileNotFoundError(f"baseline '{name_or_path}' not found (looked at {path})")
    with open(path, "r", encoding="utf-8") as handle:
        return json.load(handle), path
