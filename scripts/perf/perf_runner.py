#!/usr/bin/env python3
"""One-command local performance tracking for the Webnovel Archiver debug app.

Subcommands:
  run        Drive emulator scenarios and write report.json / report.md (+ raw artifacts).
  baseline   save|list|show — explicit baseline management (runs never auto-replace one).
  compare    Compare a finished report against a baseline (or two reports) without a device.
  selftest   Run the pure-logic unit tests (stats/aggregation/baseline/session parsing).

Examples:
  python3 scripts/perf/perf_runner.py run --suite short
  python3 scripts/perf/perf_runner.py run --suite short --baseline main-0918
  python3 scripts/perf/perf_runner.py baseline save perf-runs/<run-dir> --name main-0918
  python3 scripts/perf/perf_runner.py compare perf-runs/<run-dir>/report.json --baseline main-0918

Emulator-only by construction: the serial resolver refuses anything that is not
emulator-*, mirroring scripts/redeploy.sh (AGENTS.md device safety).
"""

import argparse
import glob
import json
import os
import subprocess
import sys
import time
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from perflib import adb, aggregate, baseline as baseline_lib, device, report as report_lib, scenarios  # noqa: E402

REPO_ROOT = device.REPO_ROOT
DEFAULT_OUT_ROOT = os.path.join(REPO_ROOT, "perf-runs")


def build_arg_parser():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)

    run = sub.add_parser("run", help="drive scenarios on the emulator and write reports")
    run.add_argument("--suite", choices=["short", "full", "custom"], default="short")
    run.add_argument("--scenarios", help="comma-separated subset (cold_library,cold_reader,...)", default=None)
    run.add_argument("--iterations", type=int, default=None, help="cold-start repeats (default: suite)")
    run.add_argument("--cycles", type=int, default=None, help="warmed navigation cycles (default: suite)")
    run.add_argument("--swipes", type=int, default=None, help="scroll swipes per direction (default: suite)")
    run.add_argument("--serial", default=None)
    run.add_argument("--variant", choices=["debug", "instrumentation"], default="debug")
    run.add_argument("--apk", default=None)
    run.add_argument("--build", action="store_true", help="assemble the variant APK first")
    run.add_argument("--baseline", default=None, help="baseline name under perf-runs/baselines or a report.json path")
    run.add_argument("--thresholds", default=None, help="JSON file overriding advisory thresholds")
    run.add_argument("--out", default=DEFAULT_OUT_ROOT)
    run.add_argument("--perfetto", action="store_true", help="also record a Perfetto trace (run is then non-comparable)")
    run.add_argument("--fail-on-regression", action="store_true", help="exit 2 when the advisory comparison flags a regression")
    run.add_argument("--skip-install", action="store_true", help="assume the APK is already installed")

    base = sub.add_parser("baseline", help="manage baselines")
    base.add_argument("action", choices=["save", "list", "show", "remove"])
    base.add_argument("source", nargs="?", help="run directory or report.json (save) or baseline name (show/remove)")
    base.add_argument("--name", default=None)
    base.add_argument("--out", default=DEFAULT_OUT_ROOT)

    compare = sub.add_parser("compare", help="compare a report against a baseline")
    compare.add_argument("report", help="path to a report.json")
    compare.add_argument("--baseline", required=True, help="baseline name or report.json path")
    compare.add_argument("--out", default=DEFAULT_OUT_ROOT)
    compare.add_argument("--thresholds", default=None)

    sub.add_parser("selftest", help="run the pure-logic unit tests")
    return parser


def suite_settings(args):
    defaults = {"short": (2, 2, 5), "full": (5, 4, 10)}[args.suite if args.suite != "custom" else "short"]
    settings = {
        "iterations": args.iterations or defaults[0],
        "cycles": args.cycles or defaults[1],
        "swipes": args.swipes or defaults[2],
        "suites": scenarios.DEFAULT_SUITES if args.suite != "custom" else [],
    }
    if args.scenarios:
        wanted = [s.strip() for s in args.scenarios.split(",") if s.strip()]
        unknown = [s for s in wanted if s not in scenarios.COLD_SCENARIOS and s not in ("warmed_navigation", "reader_scroll", "library_scroll")]
        if unknown:
            raise SystemExit(f"unknown scenarios: {unknown}")
        settings["suites"] = wanted
    elif args.suite == "custom":
        raise SystemExit("--suite custom requires --scenarios")
    return settings


def build_if_requested(args):
    if not args.build:
        return
    task = ":app:assembleDebug" if args.variant == "debug" else ":app:assembleInstrumentation"
    print(f"[perf] building {task} …", flush=True)
    proc = subprocess.run(
        [os.path.join(REPO_ROOT, "android", "gradlew"), "-p", os.path.join(REPO_ROOT, "android"), task,
         "-x", "copyDebugApkToProjectRoot", "--console=plain"],
        cwd=REPO_ROOT, capture_output=True, text=True,
    )
    if proc.returncode != 0:
        sys.stderr.write(proc.stdout[-4000:] + proc.stderr[-4000:])
        raise SystemExit("gradle build failed")


def maybe_start_perfetto(serial, total_estimate_s):
    """Best-effort background Perfetto trace; returns the pulled local path or None."""
    config = (
        "buffers { size_kb 65536 }\n"
        "data_sources { config { name: 'linux.ftrace' ftrace_config { ftrace_events: 'sched/sched_switch' "
        "ftrace_events: 'sched/sched_wakeup' ftrace_events: 'power/cpu_frequency' } } }\n"
        "data_sources { config { name: 'android.surfaceflinger.layers' } }\n"
        "duration_ms: %d\n" % max(30, int(total_estimate_s * 1000))
    )
    remote = "/data/local/tmp/webnovel_perf_trace.pftrace"
    try:
        adb.shell(serial, "rm -f " + remote, check=False, timeout=10)
        proc = subprocess.run(
            ["adb", "-s", serial, "shell", "perfetto", "--background", "--txt", "-c", "-", "-o", remote],
            input=config, capture_output=True, text=True, timeout=30,
        )
        if proc.returncode != 0:
            return None
        return remote
    except Exception:
        return None


def pull_perfetto(serial, remote_path, out_dir):
    if not remote_path:
        return None
    time.sleep(3)
    local = os.path.join(out_dir, "trace.pftrace")
    try:
        adb.run(serial, "pull", remote_path, local, timeout=120)
        return local
    except Exception:
        return None


def cmd_run(args):
    settings = suite_settings(args)
    if args.perfetto and args.baseline:
        raise SystemExit("refusing to compare a perfetto-traced run against a baseline (tracing overhead skews timings)")
    serial = adb.resolve_serial(args.serial, env=os.environ)
    adb.wait_boot_complete(serial)
    app_id = device.VARIANTS[args.variant]
    build_if_requested(args)
    apk = device.apk_identity(args.variant, args.apk)
    if not device.app_installed(serial, app_id):
        raise SystemExit(f"{app_id} is not installed on {serial}; run without --skip-install or install the APK")
    if not args.skip_install:
        print(f"[perf] installing {apk['path']} …", flush=True)
        adb.run(serial, "install", "-r", apk["path"], timeout=300)

    run_id = time.strftime("perf-%Y%m%d-%H%M%S")
    out_dir = os.path.join(args.out, run_id)
    artifacts_dir = os.path.join(out_dir, "sessions")
    os.makedirs(artifacts_dir, exist_ok=True)
    print(f"[perf] run {run_id} on {serial} ({app_id}), artifacts → {out_dir}", flush=True)

    probe = device.probe_dataset(serial, app_id)
    dataset = device.dataset_from_probe(probe, app_id)
    print(f"[perf] dataset: {dataset['storyCount']} stories, fingerprint {str(dataset['storyIdsSha256'])[:16]}…", flush=True)
    reader_target = device.pick_reader_target(serial, app_id)
    fingerprint_before = device.storage_fingerprint(serial, app_id)

    environment = {
        "runId": run_id,
        "git": device.git_metadata(),
        "apk": apk,
        "device": device.device_info(serial) | {"appId": app_id},
        "dataset": dataset,
        "readerTarget": reader_target,
        "baselinePath": None,
    }

    thresholds = baseline_lib.DEFAULT_THRESHOLDS
    if args.thresholds:
        with open(args.thresholds, "r", encoding="utf-8") as handle:
            thresholds = dict(baseline_lib.DEFAULT_THRESHOLDS, **json.load(handle))
    collector_settings = {
        "flushDebounceMs": 400,
        "maxEvents": 2000,
        "maxFrameSamples": 30000,
        "jankMultiplier": 2.0,
        "minFrameSamples": aggregate.DEFAULT_MIN_FRAME_SAMPLES,
        "minSamplesPerMetric": baseline_lib.DEFAULT_MIN_SAMPLES,
        "thresholds": thresholds,
        "completeOnDefaults": {k: v["completeOn"] for k, v in scenarios.COLD_SCENARIOS.items()},
    }

    estimate = 60 * (1 + settings["iterations"] * 0.4) + settings["cycles"] * 20
    perfetto_remote = maybe_start_perfetto(serial, estimate) if args.perfetto else None
    if args.perfetto:
        print("[perf] perfetto tracing enabled — this run is marked non-comparable", flush=True)

    scenario_results = {}
    for name in settings["suites"]:
        print(f"[perf] scenario {name} …", flush=True)
        if name in scenarios.COLD_SCENARIOS:
            scenario_results[name] = scenarios.run_cold_scenario(
                serial, app_id, run_id, name, scenarios.COLD_SCENARIOS[name],
                settings["iterations"], reader_target=reader_target, artifacts_dir=artifacts_dir,
            )
        elif name == "warmed_navigation":
            scenario_results[name] = scenarios.run_warmed_navigation(
                serial, app_id, run_id, reader_target, settings["cycles"], artifacts_dir=artifacts_dir,
            )
        elif name == "reader_scroll":
            scenario_results[name] = scenarios.run_scroll_scenario(
                serial, app_id, run_id, name, "reader", settings["swipes"],
                reader_target=reader_target, artifacts_dir=artifacts_dir,
            )
        elif name == "library_scroll":
            scenario_results[name] = scenarios.run_scroll_scenario(
                serial, app_id, run_id, name, "library", settings["swipes"],
                reader_target=reader_target, artifacts_dir=artifacts_dir, story_count=dataset.get("storyCount"),
            )
        status = scenario_results[name].get("status")
        print(f"[perf]   → {status}" + (f" ({scenario_results[name].get('skipReason')})" if scenario_results[name].get("skipReason") else ""), flush=True)

    adb.force_stop(serial, app_id)
    fingerprint_after = device.storage_fingerprint(serial, app_id)
    incidental = device.diff_fingerprints(fingerprint_before, fingerprint_after)

    perfetto_local = pull_perfetto(serial, perfetto_remote, out_dir) if perfetto_remote else None

    comparison = None
    if args.baseline:
        baseline_report, baseline_path = baseline_lib.load_baseline(args.out, args.baseline)
        environment["baselinePath"] = baseline_path
        comparison = baseline_lib.compare_reports(
            {"version": report_lib.REPORT_VERSION, "environment": environment, "scenarios": scenario_results},
            baseline_report, thresholds,
        )

    report = report_lib.build_report(
        run_id, args.suite, environment, scenario_results, comparison, incidental, collector_settings,
        perfetto=perfetto_local,
    )
    json_path, md_path = report_lib.write_report(report, out_dir)
    print(f"[perf] report: {json_path}")
    print(f"[perf] summary: {md_path}")

    if comparison and comparison.get("status") == "ok":
        regressions = [
            f"{scenario}:{metric}"
            for scenario, block in (comparison.get("scenarios") or {}).items()
            for metric, verdict in (block.get("metrics") or {}).items()
            if verdict.get("verdict") == "regression"
        ]
        if regressions:
            print(f"[perf] ADVISORY REGRESSIONS: {', '.join(regressions)}")
            if args.fail_on_regression:
                return 2
    failures = [n for n, s in scenario_results.items() if s.get("status") in ("failed", "incomplete")]
    if failures:
        print(f"[perf] execution problems in: {', '.join(failures)}")
        return 1
    return 0


def cmd_baseline(args):
    if args.action == "list":
        directory = os.path.join(args.out, "baselines")
        if not os.path.isdir(directory):
            print("(no baselines)")
            return 0
        for name in sorted(os.listdir(directory)):
            manifest = os.path.join(directory, name, "manifest.json")
            if os.path.isfile(manifest):
                with open(manifest, "r", encoding="utf-8") as handle:
                    meta = json.load(handle)
                print(f"{name:24s} created {meta.get('createdAt')} from {meta.get('source')}")
        return 0
    if args.action == "save":
        source = args.source
        if os.path.isdir(source):
            source = os.path.join(source, "report.json")
        if not os.path.isfile(source):
            raise SystemExit(f"report not found: {args.source}")
        with open(source, "r", encoding="utf-8") as handle:
            report_doc = json.load(handle)
        if report_doc.get("format") != "webnovel-perf-report":
            raise SystemExit("not a perf report.json")
        if report_doc.get("perfetto"):
            raise SystemExit("refusing to baseline a perfetto-traced run (overhead-affected numbers)")
        if (report_doc.get("environment") or {}).get("dataset", {}).get("storyIdsSha256") is None:
            raise SystemExit("report has no dataset fingerprint; not baseline-safe")
        name = args.name or os.path.basename(os.path.dirname(os.path.abspath(source)))
        directory = baseline_lib.save_baseline(report_doc, args.out, name, source_path=source)
        print(f"saved baseline '{name}' → {directory}")
        return 0
    if args.action == "remove":
        import shutil

        directory = baseline_lib.baseline_dir(args.out, args.source)
        if not os.path.isdir(directory):
            raise SystemExit(f"no such baseline: {args.source}")
        shutil.rmtree(directory)
        print(f"removed baseline '{args.source}'")
        return 0
    if args.action == "show":
        report_doc, path = baseline_lib.load_baseline(args.out, args.source)
        env = report_doc.get("environment") or {}
        print(f"baseline {args.source} ({path})")
        print(f"  created from {env.get('runId')} at {report_doc.get('generatedAt')}")
        print(f"  revision {env.get('git', {}).get('revision', '?')[:12]} dirty={env.get('git', {}).get('dirty')}")
        print(f"  device {env.get('device', {}).get('model')} API {env.get('device', {}).get('sdkInt')}")
        print(f"  dataset {env.get('dataset', {}).get('storyCount')} stories fingerprint {str(env.get('dataset', {}).get('storyIdsSha256'))[:16]}…")
        return 0
    return 1


def cmd_compare(args):
    with open(args.report, "r", encoding="utf-8") as handle:
        current = json.load(handle)
    thresholds = baseline_lib.DEFAULT_THRESHOLDS
    if args.thresholds:
        with open(args.thresholds, "r", encoding="utf-8") as handle:
            thresholds = dict(baseline_lib.DEFAULT_THRESHOLDS, **json.load(handle))
    baseline_report, baseline_path = baseline_lib.load_baseline(args.out, args.baseline)
    comparison = baseline_lib.compare_reports(current, baseline_report, thresholds)
    print(json.dumps(comparison, indent=2))
    return 0 if comparison.get("status") == "ok" else 3


def cmd_selftest(_args):
    tests = unittest.defaultTestLoader.discover(os.path.join(os.path.dirname(os.path.abspath(__file__)), "tests"))
    runner = unittest.TextTestRunner(verbosity=2)
    result = runner.run(tests)
    return 0 if result.wasSuccessful() else 1


def main(argv=None):
    parser = build_arg_parser()
    args = parser.parse_args(argv)
    if args.command == "run":
        return cmd_run(args)
    if args.command == "baseline":
        return cmd_baseline(args)
    if args.command == "compare":
        return cmd_compare(args)
    if args.command == "selftest":
        return cmd_selftest(args)
    parser.error("unknown command")


if __name__ == "__main__":
    sys.exit(main())
