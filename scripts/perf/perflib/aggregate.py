"""Pure aggregation: app session docs -> per-iteration metrics -> scenario aggregates.

All functions take plain dicts/lists (already parsed) and return plain dicts, so the
whole aggregation pipeline is unit-testable without a device.
"""

from . import stats
from .session import event_by_name, events_named, elapsed_ms_since_process_start

# Frame percentiles are only reported when at least this many samples exist in the window.
DEFAULT_MIN_FRAME_SAMPLES = 100


def extract_cold_metrics(session, expected_route):
    """Per-iteration metrics for a cold-start scenario; missing signals stay absent."""
    metrics = {}
    startup = event_by_name(session, "startup_pass")
    if startup:
        detail = startup.get("detail") or {}
        for key in ("loadMs", "migrateMs", "hydrateMs"):
            if key in detail:
                metrics[f"startup_{key[:-2].lower()}_ms"] = float(detail[key])
        if startup.get("durationMillis") is not None:
            metrics["startup_total_ms"] = float(startup["durationMillis"])
    ui_ready = event_by_name(session, "ui_ready")
    if ui_ready:
        value = elapsed_ms_since_process_start(session, ui_ready)
        if value is not None:
            metrics["proc_start_to_ui_ready_ms"] = value
    built = event_by_name(session, "screen_built", route=expected_route)
    if built:
        value = elapsed_ms_since_process_start(session, built)
        if value is not None:
            metrics["proc_start_to_screen_built_ms"] = value
        if built.get("durationMillis") is not None:
            metrics["screen_build_ms"] = float(built["durationMillis"])
    if expected_route == "reader":
        prep = event_by_name(session, "reader_prepare_done")
        if prep:
            if prep.get("durationMillis") is not None:
                metrics["reader_prepare_ms"] = float(prep["durationMillis"])
            value = elapsed_ms_since_process_start(session, prep)
            if value is not None:
                metrics["proc_start_to_prepare_done_ms"] = value
        for name, metric in (
            ("reader_content_presented", "proc_start_to_reader_presented_ms"),
            ("reader_content_painted", "proc_start_to_reader_painted_ms"),
        ):
            event = event_by_name(session, name)
            if event:
                value = elapsed_ms_since_process_start(session, event)
                if value is not None:
                    metrics[metric] = value
    if built:
        pss = memory_pss_near(session, built.get("t"))
        if pss is not None:
            metrics["pss_at_ready_kb"] = float(pss)
    return metrics


def memory_pss_near(session, t_nanos):
    """The total-Pss sample closest to (not after) the reference timestamp."""
    if t_nanos is None:
        return None
    best = None
    for sample in session.get("memory", []):
        if sample.get("t", 0) <= t_nanos:
            best = sample
    if best is None and session.get("memory"):
        best = session["memory"][0]
    return (best or {}).get("totalPssKb")


def extract_warmed_navigation(session):
    """Legs pair each screen_built / reader presentation with the most recent interaction tap.

    Includes adb input-injection latency, documented as such; the app-internal build cost is
    reported alongside as `build_ms`.
    """
    legs = []
    last_tap_t = None
    pending = None
    for event in session.get("events", []):
        name = event.get("name")
        t = event.get("t")
        if t is None:
            continue
        if name == "interaction":
            last_tap_t = t
        elif name == "reader_content_painted" and last_tap_t is not None and pending == "reader":
            legs.append(
                {
                    "kind": "tap_to_reader_painted_ms",
                    "ms": (t - last_tap_t) / 1_000_000.0,
                    "build_ms": None,
                }
            )
            pending = None
        elif name == "reader_content_presented" and last_tap_t is not None:
            legs.append(
                {
                    "kind": "tap_to_reader_presented_ms",
                    "ms": (t - last_tap_t) / 1_000_000.0,
                    "build_ms": None,
                }
            )
            pending = "reader"
        elif name == "screen_built" and last_tap_t is not None:
            route = (event.get("detail") or {}).get("route")
            if route in ("details", "library"):
                legs.append(
                    {
                        "kind": f"tap_to_{route}_ms",
                        "ms": (t - last_tap_t) / 1_000_000.0,
                        "build_ms": event.get("durationMillis"),
                    }
                )
                last_tap_t = None
    metrics = {}
    for leg in legs:
        metrics.setdefault(leg["kind"], []).append(leg["ms"])
        if leg["build_ms"] is not None:
            metrics.setdefault(f"{leg['kind'].replace('tap_to_', 'build_')}", []).append(float(leg["build_ms"]))
    library_pss = [
        s.get("totalPssKb")
        for s in session.get("memory", [])
        if s.get("label") == "screen:library" and s.get("totalPssKb") is not None
    ]
    trend = stats.slope(list(enumerate(library_pss)))
    flat = {key: [round(v, 3) for v in values] for key, values in metrics.items()}
    flat["memory_trend_kb_per_cycle"] = trend
    flat["library_pss_per_cycle_kb"] = library_pss
    return flat, legs


def extract_scroll_metrics(session, frame_samples, window_ms=800.0):
    """Frames inside [interaction, interaction+window] windows; plus swipe count."""
    interactions = [e.get("t") for e in events_named(session, "interaction") if e.get("t") is not None]
    samples = [
        s for s in frame_samples if any(s["t"] >= t0 and s["t"] <= t0 + window_ms * 1_000_000.0 for t0 in interactions)
    ]
    return {
        "interaction_count": len(interactions),
        "window_ms": window_ms,
        "frame_stats": frame_stats(samples),
        "tag_breakdown": {
            tag: frame_stats([s for s in samples if s["tag"] == tag])
            for tag in sorted({s["tag"] for s in samples})
        },
    }


def frame_stats(samples, min_samples=DEFAULT_MIN_FRAME_SAMPLES):
    """Percentiles only with adequate samples; jank share over the same window."""
    count = len(samples)
    result = {"count": count, "insufficient_samples": count < min_samples}
    if result["insufficient_samples"]:
        return result
    durations = sorted(s["durationMs"] for s in samples)
    summary = stats.summarize(durations)
    result.update(
        {
            "median_ms": round(summary["median"], 3),
            "mean_ms": round(summary["mean"], 3),
            "p90_ms": round(stats.percentile(durations, 90), 3),
            "p95_ms": round(stats.percentile(durations, 95), 3),
            "p99_ms": round(stats.percentile(durations, 99), 3),
            "max_ms": round(summary["max"], 3),
            "jank_pct": round(100.0 * sum(1 for s in samples if s["jank"]) / count, 2),
        }
    )
    return result


def cold_frame_window(session):
    """Cold-start frame window: first ui_ready .. scenario_complete (or last event)."""
    ui_ready = event_by_name(session, "ui_ready")
    complete = event_by_name(session, "scenario_complete")
    events = session.get("events", [])
    last_t = events[-1].get("t") if events else None
    t0 = ui_ready.get("t") if ui_ready else None
    t1 = complete.get("t") if complete else last_t
    if t0 is None or t1 is None or t1 <= t0:
        return None
    return (t0, t1)


def aggregate_cold_scenario(iterations, expected_route):
    """iterations: [{status, error, session, metrics, frame_samples}]."""
    ok = [i for i in iterations if i["status"] == "ok" and i.get("metrics")]
    metric_series = {}
    for iteration in ok:
        for key, value in iteration["metrics"].items():
            if value is not None:
                metric_series.setdefault(key, []).append(float(value))
    aggregated = {
        key: stats.summarize(values) | {"samples": [round(v, 2) for v in values]}
        for key, values in metric_series.items()
    }
    windowed_frames = []
    for iteration in [i for i in iterations if i["status"] == "ok" and i.get("frame_samples") is not None]:
        window = cold_frame_window(iteration["session"])
        if window:
            windowed_frames.extend([s for s in iteration["frame_samples"] if window[0] <= s["t"] <= window[1]])
    return {
        "status": scenario_status(iterations),
        "ok_iterations": len(ok),
        "total_iterations": len(iterations),
        "metrics": aggregated,
        "frames": frame_stats(windowed_frames) | {"tags": sorted({s["tag"] for s in windowed_frames})},
        "variability_cv": {
            key: round(stats.coefficient_of_variation(values), 4)
            for key, values in metric_series.items()
            if stats.coefficient_of_variation(values) is not None
        },
    }


def scenario_status(iterations):
    if not iterations:
        return "skipped"
    statuses = {i["status"] for i in iterations}
    if statuses == {"ok"}:
        return "ok"
    if "ok" in statuses:
        return "partial"
    if statuses == {"skipped"}:
        return "skipped"
    return "failed"


def aggregate_warmed_scenario(session_results):
    """session_results: [{status, metrics_flat, legs}]."""
    ok = [r for r in session_results if r["status"] == "ok" and r.get("metrics_flat")]
    aggregated = {}
    for result in ok:
        for key, value in result["metrics_flat"].items():
            if isinstance(value, list):
                aggregated.setdefault(key, []).extend(value)
    summary = {}
    for key, values in aggregated.items():
        if key in ("library_pss_per_cycle_kb",):
            continue
        if key == "memory_trend_kb_per_cycle":
            trends = [v for v in values if v is not None]
            if trends:
                summary[key] = stats.summarize(trends) | {"diagnostic_only": True}
        else:
            summary[key] = stats.summarize(values) | {"samples": [round(v, 1) for v in values]}
    return {
        "status": scenario_status(session_results),
        "ok_sessions": len(ok),
        "total_sessions": len(session_results),
        "metrics": summary,
        "legs_count": sum(len(r.get("legs") or []) for r in ok),
        "memory_note": "Memory growth is a diagnostic signal only, not proof of a leak.",
    }
