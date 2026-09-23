"""Machine (JSON) and human (Markdown) report writers."""

import json
import os
import time

from .session import REPORT_FORMAT, REPORT_VERSION

DISCLAIMER = (
    "Advisory development signals from a debug build on an emulator. Not release-build "
    "performance, not physical-device performance, and not a Macrobenchmark replacement."
)

METRIC_LABELS = {
    "proc_start_to_ui_ready_ms": "process start -> UI ready",
    "proc_start_to_screen_built_ms": "process start -> screen built",
    "startup_total_ms": "startup pass total",
    "startup_load_ms": "hydration: load",
    "startup_migrate_ms": "hydration: migrate",
    "startup_hydrate_ms": "hydration: refresh",
    "screen_build_ms": "screen build (main thread)",
    "am_total_ms": "am start -W TotalTime",
    "reader_prepare_ms": "reader document preparation",
    "proc_start_to_reader_presented_ms": "process start -> reader content presented",
    "proc_start_to_reader_painted_ms": "process start -> reader WebView painted",
    "proc_start_to_prepare_done_ms": "process start -> reader prepare done",
    "tap_to_details_ms": "tap -> details (incl. input latency)",
    "tap_to_library_ms": "tap -> library (incl. input latency)",
    "tap_to_reader_presented_ms": "tap -> reader presented (incl. input latency)",
    "tap_to_reader_painted_ms": "tap -> reader painted (incl. input latency)",
    "build_details_ms": "details build (main thread)",
    "build_library_ms": "library build (main thread)",
    "frame_p95_ms": "frame duration p95",
    "frame_jank_pct": "jank frames %",
    "pss_at_ready_kb": "PSS at screen ready (KB)",
    "memory_trend_kb_per_cycle": "library PSS slope per cycle (KB)",
}


def build_report(run_id, suite_name, environment, scenarios, comparison, incidental_changes, collector_settings, perfetto=None):
    return {
        "format": REPORT_FORMAT,
        "version": REPORT_VERSION,
        "generatedAt": time.strftime("%Y-%m-%dT%H:%M:%S"),
        "runId": run_id,
        "suite": suite_name,
        "environment": environment,
        "collectorSettings": collector_settings,
        "scenarios": scenarios,
        "comparison": comparison,
        "incidentalStateChanges": incidental_changes,
        "perfetto": perfetto,
        "disclaimer": DISCLAIMER,
    }


def write_report(report, out_dir):
    os.makedirs(out_dir, exist_ok=True)
    json_path = os.path.join(out_dir, "report.json")
    with open(json_path, "w", encoding="utf-8") as handle:
        json.dump(report, handle, indent=2)
    md_path = os.path.join(out_dir, "report.md")
    with open(md_path, "w", encoding="utf-8") as handle:
        handle.write(render_markdown(report))
    return json_path, md_path


def fmt(value, digits=1):
    if value is None:
        return "—"
    if isinstance(value, float):
        return f"{value:.{digits}f}"
    return str(value)


def verdict_symbol(verdict):
    return {
        "regression": "⚠️ REGRESSION",
        "improved": "✅ improved",
        "stable": "ok",
        "diagnostic": "· diagnostic",
        "inconclusive": "? inconclusive",
    }.get(verdict, verdict or "—")


def render_markdown(report):
    env = report.get("environment") or {}
    lines = []
    lines.append(f"# Performance run `{report.get('runId')}`")
    lines.append("")
    lines.append(f"*Generated {report.get('generatedAt')} · suite `{report.get('suite')}`*")
    lines.append("")
    lines.append(f"> {report.get('disclaimer')}")
    lines.append("")
    apk = env.get("apk") or {}
    git = env.get("git") or {}
    device = env.get("device") or {}
    dataset = env.get("dataset") or {}
    lines.append("## Environment")
    lines.append("")
    lines.append(f"- revision `{git.get('revision', '?')[:12]}`" + (" (dirty worktree)" if git.get("dirty") else " (clean)"))
    lines.append(f"- APK {apk.get('variant')} sha256 `{str(apk.get('sha256', '?'))[:16]}…` ({apk.get('mtime')})")
    lines.append(f"- device {device.get('model')} API {device.get('sdkInt')} serial {device.get('serial')} ({device.get('screenSize')}, {device.get('density')})")
    lines.append(
        f"- dataset {dataset.get('storyCount')} stories, {dataset.get('downloadedChapterEntries')} downloaded chapters, fingerprint `{str(dataset.get('storyIdsSha256', '?'))[:16]}…`"
    )
    if env.get("readerTarget"):
        target = env["readerTarget"]
        lines.append(f"- reader target story `{target.get('storyId')}` chapter `{target.get('chapterId')}` ({target.get('downloadedChapters')}/{target.get('totalChapters')} downloaded)")
    lines.append("")
    comparison = report.get("comparison")
    if comparison and comparison.get("status") == "not_comparable":
        lines.append("## Baseline comparison: NOT COMPARABLE")
        lines.append("")
        for reason in comparison.get("compatibilityReasons") or []:
            lines.append(f"- {reason}")
        lines.append("")
    elif comparison and comparison.get("status") == "ok":
        base = comparison.get("baseline") or {}
        lines.append(f"## Baseline comparison (advisory) vs `{base.get('runId')}`")
        lines.append("")
    for name, scenario in (report.get("scenarios") or {}).items():
        lines.extend(render_scenario(name, scenario, comparison))
    incidental = report.get("incidentalStateChanges") or []
    lines.append("## Incidental persisted-state changes")
    if incidental:
        for change in incidental:
            lines.append(f"- {change}")
    else:
        lines.append("- none observed (library root file listing unchanged)")
    lines.append("")
    if report.get("perfetto"):
        lines.append(f"Perfetto trace: `{report['perfetto']}` (overhead-affected run; excluded from baselines)")
        lines.append("")
    lines.append("## Metric definitions")
    lines.append("")
    lines.append("- Cold metrics use in-app monotonic timestamps anchored at process start; `am start -W TotalTime` is the host-side wall clock.")
    lines.append("- `screen_build_ms` is main-thread screen construction (Scaffold build incl. body).")
    lines.append("- Reader distinguishes prepare done (document resolved), presented (WebView + load issued), painted (onPageFinished).")
    lines.append("- `tap_to_*` legs include adb input-injection latency (~100–300 ms); treat as interaction latency, not app-only cost.")
    lines.append("- Frame durations are Window FrameMetrics TOTAL_DURATION; jank = duration > 2× display frame interval.")
    return "\n".join(lines) + "\n"


def render_scenario(name, scenario, comparison):
    lines = []
    status = scenario.get("status")
    lines.append(f"## {name} — {status}")
    if scenario.get("skipReason"):
        lines.append("")
        lines.append(f"Reason: {scenario.get('skipReason')}")
    lines.append("")
    metrics = scenario.get("metrics") or {}
    comp = None
    if comparison and comparison.get("status") == "ok":
        comp = ((comparison.get("scenarios") or {}).get(name) or {}).get("metrics")
    if metrics:
        header = "| metric | baseline | current | Δ | Δ% | verdict |"
        lines.append(header)
        lines.append("|---|---|---|---|---|---|")
        for metric, summary in sorted(metrics.items()):
            label = METRIC_LABELS.get(metric, metric)
            current = summary.get("median")
            row_comp = (comp or {}).get(metric) or {}
            base = row_comp.get("baseline_median")
            delta = row_comp.get("delta_abs")
            rel = row_comp.get("delta_rel")
            verdict = row_comp.get("verdict", "" if comp else None)
            if summary.get("diagnostic_only"):
                verdict = "diagnostic"
            count = summary.get("count")
            lines.append(
                f"| {label} | {fmt(base)} | {fmt(current)} (n={count}) | {fmt(delta)} | "
                f"{fmt(rel * 100 if rel is not None else None)}% | {verdict_symbol(verdict) if verdict else ''} |"
            )
    frames = scenario.get("frames") or {}
    if frames and frames.get("count"):
        if frames.get("insufficient_samples"):
            lines.append("")
            lines.append(f"Frames: {frames.get('count')} samples — below the percentile minimum; percentiles withheld.")
        else:
            lines.append("")
            lines.append(
                f"Frames (n={frames.get('count')}): median {fmt(frames.get('median_ms'))} ms · p90 {fmt(frames.get('p90_ms'))} ms · "
                f"p95 {fmt(frames.get('p95_ms'))} ms · p99 {fmt(frames.get('p99_ms'))} ms · jank {fmt(frames.get('jank_pct'))}%"
            )
    variable = [
        key for key, cv in (scenario.get("variability_cv") or {}).items()
        if key.endswith("_ms") and cv >= 0.3 and (metrics.get(key) or {}).get("median", 0) >= 50
    ]
    if variable:
        lines.append(f"- High iteration variability ({', '.join(variable)}); rerun before interpreting these medians.")
    for key in ("interactions_recorded", "legs_observed", "cycles_requested"):
        if scenario.get(key) is not None:
            lines.append(f"- {key}: {scenario[key]}")
    for iteration in scenario.get("iterations") or []:
        if iteration.get("status") != "ok":
            lines.append(f"- iteration {iteration.get('iteration')}: **{iteration.get('status')}** — {iteration.get('error') or iteration.get('verify')}")
    evidence = scenario.get("evidence") or {}
    if evidence.get("strictModeCount"):
        lines.append(f"- StrictMode violations in window: {evidence['strictModeCount']} (see artifacts logcat)")
    if evidence.get("crashCount"):
        lines.append(f"- CRASH/ANR lines: {evidence['crashCount']} (see artifacts logcat)")
    lines.append("")
    return lines
