---
name: perf-suite
description: >
  Runs the local performance tracking suite for the Webnovel Archiver Android debug app on the
  webnovel_api36 emulator via one command (scripts/perf/perf_runner.py), producing a JSON report,
  a Markdown summary, and raw per-iteration artifacts (sessions, frames, logcat, meminfo), plus
  optional advisory comparison against an explicitly saved baseline. USE THIS when the user asks
  to measure performance, check for perf regressions, benchmark the app locally, create a perf
  baseline, compare against a baseline, or "run the perf suite". Also use for the optional short
  perf pass inside emulator QA. Do NOT use for phone/release measurements, CI benchmarks, or live
  network work. Debug/emulator numbers are development regression signals only — never present
  them as release or device performance.
---

# Skill: perf-suite

One-command local performance tracking: cold starts (Library, Settings, Queue, Details,
Reader), warmed navigation cycles, and scroll interactions on the emulator, with in-app
monotonic timing, frame/jank stats (FrameMetrics), memory snapshots, and crash/ANR/StrictMode
evidence. Full design, metric definitions, and methodology:
`docs/architecture/performance-tracking.md`.

## Hard rules

- Debug app only: `com.vinicius741.webnovelarchiver.nativeapp.debug` on `webnovel_api36`.
  The runner itself fails closed on any non-`emulator-*` serial.
- Never clear/seed/replace the emulator library. Scenarios are read-only navigation; the
  runner reports incidental persisted-state changes itself.
- No phone, no release builds, no paid AI, no live scraping.
- Numbers are advisory regression signals (debug + emulator), not release claims.

## Commands

```bash
# Short pass (~5–8 min), fresh install of the current debug APK:
python3 scripts/perf/perf_runner.py run --suite short

# No reinstall (QA already installed it):
python3 scripts/perf/perf_runner.py run --suite short --skip-install

# Longer pass with tighter statistics (5 iterations, 4 cycles, 10 swipes):
python3 scripts/perf/perf_runner.py run --suite full

# Scenario subset / repeats:
python3 scripts/perf/perf_runner.py run --suite custom --scenarios cold_reader,warmed_navigation --iterations 5

# Baseline lifecycle (explicit only; runs never auto-create/replace one):
python3 scripts/perf/perf_runner.py baseline save perf-runs/<run-dir> --name <label>
python3 scripts/perf/perf_runner.py baseline list
python3 scripts/perf/perf_runner.py run --suite short --baseline <label>   # compare while running
python3 scripts/perf/perf_runner.py compare perf-runs/<run>/report.json --baseline <label>

# Pure-logic tests (no device):
python3 scripts/perf/perf_runner.py selftest

# Optional Perfetto trace (run becomes non-comparable; never baseline it):
python3 scripts/perf/perf_runner.py run --suite short --perfetto
```

## Reading the output

- `perf-runs/<runId>/report.md` — human summary: per-scenario tables with baseline deltas and
  advisory verdicts, frame percentiles (only with ≥100 samples), statuses, incidental state
  changes, disclaimer.
- `perf-runs/<runId>/report.json` — machine-readable (`webnovel-perf-report` v1): environment
  (revision, dirty state, APK sha256, device, dataset fingerprint), collector settings,
  per-iteration metrics/evidence, comparison block.
- `perf-runs/<runId>/sessions/<scenario>/<n>/` — raw artifacts: app session.json, frames.jsonl,
  logcat slice, meminfo, verification UI dump.

Scenario statuses: `ok` · `partial` (some iterations failed) · `failed` (execution problem /
crash lines) · `incomplete` (deadline, missing terminal event) · `skipped` (prerequisite
missing, e.g. no downloaded chapter for reader scenarios) · `inconclusive` (insufficient
samples or non-ok execution in a comparison). Comparison status `not_comparable` means the
device/sdk/variant/dataset fingerprint differs from the baseline — fix the environment, don't
read numbers.

## What to check in report.md

1. All scenarios `ok` (or `skipped` with an expected reason like "no downloaded chapter").
2. `screen_build_ms`, `proc_start_to_ui_ready_ms`, reader present/paint times vs baseline.
3. Frames: jank % and p95 only where sample counts are adequate.
4. Memory trend in `warmed_navigation` — diagnostic only, never a verdict.
5. `incidentalStateChanges` — should be empty or explainable (navigation-stack persistence).

## Traps

- The runner needs the debug APK built (`--build` or build it first); it refuses to run
  against a missing APK rather than measuring nothing.
- Baselines are fingerprint-locked: adding/removing a library story makes the old baseline
  not-comparable on purpose. Save a new baseline after intentional dataset changes.
- `tap_to_*` metrics include adb input-injection latency (~100–300 ms); use the
  `build_*_ms` metrics for app-internal cost.
- Multiple emulators running → pass `--serial emulator-XXXX` or set `EMULATOR_SERIAL`.
- If the emulator network wedges mid-run (hangs past timeouts), force-stop the app and retry
  once before concluding a failure — see memory note on emulator network wedge.
