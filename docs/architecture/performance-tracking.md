# Local Performance Tracking (debug/emulator)

Local, repeatable performance measurement for the native app: one host command drives
emulator scenarios, collects in-app timing + frame + memory evidence, and writes a
machine-readable JSON report, a human-readable Markdown summary, and raw artifacts.
Comparisons against an explicitly created baseline are advisory signals for development.

**What this is not:** release-build or physical-device performance claims, a Macrobenchmark
replacement, or a CI gate. Debug builds run unminified with debug overheads, and the
emulator's CPU/GPU/timing profile differs from any phone. The numbers are useful as
*regression signals relative to a baseline on the same machine, build variant, and dataset*.

## Why not Macrobenchmark

Macrobenchmark requires a benchmark module with its own `compileSdk`/Gradle plumbing and —
critically — its startup benchmarking model assumes control over app data (it clears or
seeds the target app). The hard constraint here is the opposite: the debug emulator holds
the user's working library and must never be cleared, replaced, or seeded. The dev-only
`dev_start_screen` launch path already lands on any screen deterministically without
touching data, so this system reuses it instead. No Macrobenchmark safety check is
suppressed anywhere; Macrobenchmark is simply not used. A library-free Macrobenchmark
module could be added later for `baseline startup`-style numbers if a release-approximate
measurement is ever needed (it would need its own target app id, e.g. the
`instrumentation` variant, and its baselines would stay separate from these).

## One-command usage

```bash
# Short pass (~5–8 min): cold library/settings/queue/details/reader, warmed navigation,
# reader + library scroll. Installs the current debug APK, runs, writes reports.
python3 scripts/perf/perf_runner.py run --suite short

# Full pass: more iterations/cycles/swipes for tighter statistics
python3 scripts/perf/perf_runner.py run --suite full

# Baseline flow (explicit; a run never creates or replaces a baseline on its own)
python3 scripts/perf/perf_runner.py baseline save perf-runs/<run-dir> --name main-0918
python3 scripts/perf/perf_runner.py run --suite short --baseline main-0918   # compare while running
python3 scripts/perf/perf_runner.py compare perf-runs/<other-run>/report.json --baseline main-0918

# Pure-logic tests (no device)
python3 scripts/perf/perf_runner.py selftest
```

Output lands in `perf-runs/<runId>/` (gitignored):

```
perf-runs/perf-20260918-101500/
  report.json                  # machine-readable, versioned (webnovel-perf-report v1)
  report.md                    # human summary with baseline comparison table
  sessions/<scenario>/<n>/     # raw app session.json, frames.jsonl, logcat, meminfo, verify.xml
  trace.pftrace                # only with --perfetto (run is then non-comparable)
perf-runs/baselines/<name>/    # explicit baselines: report.json + manifest.json
```

Safety properties (enforced in code, mirroring AGENTS.md): the serial resolver refuses
anything that is not `emulator-*`; scenarios never clear/seed/replace the debug library;
every run reports incidental persisted-state changes by diffing the library file listing
before/after; the `instrumentation` variant is supported via `--variant instrumentation`
and its baselines are never comparable with debug ones (appId is part of compatibility).

## Architecture

Two halves, one file contract:

1. **App side** (`android/app/src/main/java/.../perf/`) — a debug-gated recorder started
   from the launch intent (`--es perf_session 1`). Every hook call site is behind
   `BuildConfig.DEBUG`, so release compiles the calls out entirely; behavior is unchanged.
2. **Host side** (`scripts/perf/`) — `perf_runner.py` + `perflib/` (adb, scenarios,
   session parsing, stats, aggregation, baselines, report writers) with a stdlib-only
   Python 3 implementation, mirroring the `backup_to_storage.py` style.

Contract files (app cache, read via `run-as`):

- `cache/perf/session.json` — identity + events + memory samples + recorder overhead.
  Rewritten atomically (tmp + rename) with a 400 ms debounce; written immediately on
  scenario completion and activity destroy. Staleness-proof: the host verifies the
  embedded runId/iteration match what it launched.
- `cache/perf/frames.jsonl` — append-only one-JSON-object-per-line frame samples,
  flushed periodically and at session end. The host tolerates a truncated trailing line.

### App-side components

| File | Role |
|---|---|
| `PerfSessionContract.kt` | Intent extras, event names, caps, complete-on parsing (pure) |
| `PerfSessionState.kt` | Bounded buffers, complete-on matching, deadline evaluation (pure, JVM-tested) |
| `PerfSessionModels.kt` | Export data classes + overhead accumulator + jank math (pure) |
| `PerfRecorder.kt` | Session lifecycle facade; pre-session buffering for hydration events |
| `PerfSessionRunner.kt` | Off-main executor: memory snapshots, session.json rewrites, deadline timer |
| `PerfFrameCollector.kt` | HandlerThread + FrameMetrics listener; appends frames.jsonl |
| `PerfInstrumentation.kt` | Event-recording surface used by hooks |
| `PerfEnvironment.kt` | Display refresh rate / process start / version reads |

Design constraints honored: monotonic timing (`SystemClock.elapsedRealtimeNanos`, anchored
to `Process.getStartElapsedRealtime()`), bounded buffers everywhere (drops are counted in
the exported overhead block, never blocking callers), all file I/O off the main thread,
lifecycle cleanup in `MainActivity.onDestroy` (final flush + frame listener removal +
thread shutdown), and no new dependencies (zero Gradle lockfile changes).

## What each metric measures

### Timing events (session.json `events`)

All in-app timestamps are `elapsedRealtimeNanos` (monotonic, survives across the run);
"process start" anchors come from `Process.getStartElapsedRealtime()`.

| Event | Emitted at | Measures |
|---|---|---|
| `startup_pass` | AppContainer hydration transaction | R30 phase split: `loadMs` (index + story files), `migrateMs` (identity migration + write-backs), `hydrateMs` (repository refresh), duration = total. Recorded even before the session starts (buffered, merged on start) |
| `ui_init` | `initializeUiAfterRepositoryReady` | Main-thread UI init between hydration-ready and theme/first-render dispatch |
| `ui_ready` | `uiReady = true` in MainActivity | End of the splash-hold gate; first content permitted to draw |
| `screen_built` | `Scaffold.screen()` completion | Main-thread construction of a screen (app bar + body block). Detail carries `route` (AppRoute.name), title (omitted for `reader` so chapter text never leaves the device), subtitle |
| `reader_preparing` | `showReader` transient state | Reader opened; "Preparing chapter…" state on screen |
| `reader_prepare_done` | `ReaderDocumentPreparer.prepare` returns | **Loading completion**: chapter resolved (source or polished variant), sanitized, TTS-annotated HTML rendered — no WebView exists yet. `outcome`: ready/missing/failed |
| `reader_content_presented` | after `loadDataWithBaseURL` | **Content presentation**: the prepared reader screen is built and the WebView has been handed the document |
| `reader_content_painted` | debug-only `WebViewClient.onPageFinished` | The WebView finished loading the chapter document (closest in-app signal to "content actually shown"; not a pixel-readback) |
| `interaction` | `onUserInteraction` | Host-driven tap/swipe markers; correlate frames to interaction windows |
| `scenario_complete` | first complete-on match | Terminal marker the host waits for (e.g. `screen_built:library`, `reader_content_presented`) |
| `scenario_deadline` | deadline timer | No completion within `perf_max_ms` — the host reports the iteration incomplete |

The loading-vs-presentation split matters: `reader_prepare_done` can be fast while
presentation lags (huge HTML, WebView warm-up), and vice versa. Cold-reader metrics report
all three (`proc_start_to_prepare_done_ms`, `proc_start_to_reader_presented_ms`,
`proc_start_to_reader_painted_ms`) plus the isolated `reader_prepare_ms`.

Host-side wall-clock `am start -W TotalTime` is reported separately (`am_total_ms`) — it
is a different measurement (activity-manager view incl. process start + first draw) and
inherently noisier on an emulator.

### Frames (frames.jsonl)

Each sample: `{t, durationMicros, tag, jank}` where `durationMicros` is
`FrameMetrics.TOTAL_DURATION` — the full frame time from the input callback (earliest of
vsync/input/animation start) through presentation (signal vsync SEM/TE), i.e. the
platform's own end-to-end frame latency for the app's main window. `tag` is the current
screen route (set on each `screen_built`), so frame stats can be grouped by screen;
interaction events let the host restrict scroll analysis to swipe windows.

**Jank definition:** `duration > 2 × display frame interval` (interval from
`DisplayManager`; the API 37 SDK removed the old `DisplayMetrics.refreshRate` field).
This mirrors JankStats' default heuristic (2× expected frame time).

**Platform alternative justification (instead of JankStats):** frame collection uses
`Window.OnFrameMetricsAvailableListener` rather than the `androidx.metrics` JankStats
library because (a) JankStats is still 1.0.0-beta and would add a locked dependency for
debug-only value; (b) FrameMetrics exposes the same total-duration signal JankStats
aggregates plus phase breakdowns, on every API level we support (minSdk 26 ≥ API 24);
(c) raw-sample export keeps percentile aggregation host-side where it is unit-tested.
Known trade-off: FrameMetrics delivery is asynchronous and can drop callbacks if the
listener is slow — the listener only copies one long onto a private thread, and drops are
visible as lower-than-expected sample counts.

Percentiles (p90/p95/p99) are reported **only when a window has ≥ 100 samples**
(`minFrameSamples`, in collectorSettings); otherwise the report says
`insufficient_samples` and withholds them.

### Memory (session.json `memory` + host meminfo)

App-side snapshots at session start, every `screen_built` (taken off-main immediately
after), and session end: `totalPssKb/dalvikPssKb/nativePssKb/otherPssKb`
(`Debug.MemoryInfo`), `javaHeapUsedKb/javaHeapMaxKb` (Runtime). The host additionally
captures `dumpsys meminfo` per iteration as an artifact.

Repeated navigation (`warmed_navigation`) records the PSS at each cycle's library screen;
the report includes the per-cycle slope (`memory_trend_kb_per_cycle`) explicitly as a
**diagnostic signal, not proof of a leak** — PSS grows legitimately (caches, WebView
warm-up, fragmentation). Verdicts are never produced for memory metrics.

### Crash / ANR / StrictMode evidence

Per iteration the host clears logcat, runs the scenario, then captures the window's
logcat: `FATAL EXCEPTION` / `ANR in` lines mark the iteration `execution_failure`;
`StrictMode policy violation` lines (the existing debug `penaltyLog()` output — reused,
not duplicated; `penaltyListener` is not in the public SDK surface) are counted and saved.
Raw logcat + meminfo + the verification UI dump are artifacts per iteration.

## Methodology

- **Cold vs warmed are separate scenarios.** Cold scenarios force-stop and relaunch per
  iteration (each its own process and session). `warmed_navigation` keeps one process and
  repeats library→details→reader→details→library tap cycles; per-leg timings and the
  memory trend come from that single process.
- **Repeats and statistics.** Every metric reports sample count, median, mean, stdev,
  min/max, and the coefficient of variation; short suite = 2 iterations per cold scenario,
  full = 5. Frame stats pool windowed samples across iterations.
- **Captures never pollute timing windows.** In-app event timestamps drive all metrics.
  The host polls only `cache/perf/session.json` (a `run-as cat` of a small file — no UI
  thread involvement) while a window is open. `uiautomator dump` verification and any
  screenshots happen *after* the terminal event; frame windows are capped at
  `scenario_complete`/interaction timestamps so verification-driven frames cannot count.
  Exception by design: scroll scenarios measure the swipes themselves.
- **Fallback navigation is not success.** The session's first `screen_built` route must
  equal the expected route (e.g. `details`); a dev-target fallback to `library` (empty
  library, bad ids) fails the iteration. Screen content is additionally verified from one
  post-window dump (marker text present, `Preparing chapter` absent for reader).
- **Missing prerequisites are skips, not failures.** `cold_details`/`cold_reader`/
  navigation/scroll scenarios skip with a reason when the library has no downloaded
  chapter (or fewer than 4 stories for `library_scroll`).
- **Deterministic targeting.** Details/reader scenarios pass explicit
  `dev_start_story`/`dev_start_chapter` ids chosen from the on-disk library (first story
  with a downloaded chapter, preferring first-chapter-downloaded) — recorded in the
  report's `environment.readerTarget`.
- **Dataset fingerprint.** Each run starts with the app's own `dev_library_report` probe;
  its `storyIdsSha256` (hash over the ordered story-id list) is the compatibility token
  for baselines.
- **Incidental state.** The run diffs the persisted library file listing before/after and
  reports changes (expected: none beyond app-managed indexes; navigation via dev tokens
  may persist the navigation stack, which the next dev-target launch overrides).

## Baselines and comparison

- Baselines are created only by `baseline save` (refuses overwrite; refuses
  perfetto-traced or fingerprint-less runs). A run compares only when `--baseline` names
  one — there is no implicit "latest baseline".
- **Compatibility is mandatory.** A comparison is rejected (`not_comparable`, no metric
  verdicts) unless: report format version, device model + SDK, app id/variant, and the
  dataset fingerprint all match. Debug and instrumentation runs are therefore never
  comparable, by construction.
- **Advisory thresholds** (both absolute AND relative must be exceeded; lower is better;
  `--thresholds file.json` overrides):

| Metric | abs | rel |
|---|---|---|
| proc_start_to_ui_ready / screen_built | 150 ms | 15% |
| startup total/load/hydrate | 100–150 ms | 15% |
| screen_build (main thread) | 80 ms | 20% |
| reader presented/painted (process start) | 300 ms | 20% |
| reader_prepare | 200 ms | 25% |
| tap_to_* legs | 200–400 ms | 25% |
| frame p95 | 6 ms | 15% |
| jank % | 4 pp | 30% |

- **Statuses are distinct:** `regression` / `improved` / `stable` (comparison verdicts,
  advisory), `execution_failure` (crash/ANR/launch failure), `incomplete` (deadline,
  missing terminal event, missed interactions), `skipped` (prerequisite missing),
  `not_comparable` (environment mismatch), `inconclusive` (insufficient samples or
  non-ok execution — an incomplete run is never a regression).
- Exit codes: `run` exits 1 on execution failures/incomplete scenarios, 2 with
  `--fail-on-regression` and any advisory regression, 0 otherwise. `compare` exits 3 on
  not-comparable.
- Synthetic-data verification: `selftest` proves regression/improved/stable/inconclusive
  decisions, compatibility rejection, and malformed-input handling on synthetic report
  data — production code is never slowed down to "test" detection.

## Recorder overhead and uncertainty

The recorder self-measures: every event-recording call is timed
(`overhead.eventRecordMeanNanos/MaxNanos`, call count, flush count, drop counts are in
every session.json). On the validation runs this showed low-single-digit-microsecond mean
call cost (see report block for the exact numbers). Remaining uncertainty, documented
rather than hidden: the frame listener itself adds per-frame work on a dedicated thread;
debounced session.json rewrites perform small I/O bursts on a background thread; the
debug build's StrictMode/Timber logging and non-minified code shift absolute numbers vs
release. These are constant-across-baseline effects for regression detection, not
absolute-performance truth.

## Validation performed (2026-09-18)

- Kotlin: `:app:testInstrumentationUnitTest` for `perf.*` (15 tests: caps, complete-on,
  deadline, truncation, jank math, overhead) — green. Full local gate
  `:app:lintKotlin :app:ci` (kotlinter, file-size budgets, detekt ratchet, lintDebug,
  assembleDebug) — green.
- Python: `perf_runner.py selftest` (51 tests: percentiles, aggregation incl. reader
  milestones/warmed legs/scroll windows, baseline compat + thresholds + synthetic regression
  detection, malformed session/frame parsing, timeout statuses) — green.
- Emulator (`webnovel_api36`, API 36, 169-story/10,779-downloaded-chapter library): short
  suite run twice, both fully `ok` across all 8 scenarios, exit 0; run 1 saved as the
  `short-2026-09-18a` baseline; run 2 compared → all scenarios `stable`, zero incidental
  persisted-state changes.
- Headline run-1 numbers (medians, n=2): process start → UI ready ≈ 1.85–1.91 s across cold
  scenarios (hydration load ≈ 1.34–1.40 s of it); library screen build 146 ms, details 97 ms,
  reader prepare 220 ms, process start → reader presented 2.17 s, painted +75 ms after;
  warmed nav details build 21 ms, library build 48 ms; scroll frames p95 18.6 ms (reader) /
  24.5 ms (library), jank 0–1.6 %.
- Synthetic regression detection via the real `compare` CLI on a mutated report:
  `reader_prepare_ms` (+173 %, +380 ms) and `proc_start_to_reader_presented_ms` (+61 %,
  +1.3 s) flagged as regressions; a +8 %-only change correctly stayed `stable`
  (both-dimensions rule). A dataset-fingerprint mismatch correctly returns `not_comparable`
  (exit 3). No production code was slowed to test detection.
- Recorder overhead (self-measured, exported per session): mean ~92 µs per instrumented-event
  call on the main thread (max 0.52 ms, includes scheduling the per-screen memory snapshot),
  0 dropped events/frames on the short suite.

## QA workflow integration

The `emulator-qa` skill's pipeline now has an optional short perf pass (build+install
already done there): `python3 scripts/perf/perf_runner.py run --suite short
--skip-install` and read `report.md`. A longer repeated pass (`--suite full`, more
iterations) is the pre-merge ritual for startup/reader/navigation-affecting changes.
See `.agents/skills/perf-suite/SKILL.md` for the agent workflow.

## Limitations

- Emulator numbers only; debug variant only by default. No release-build, phone, or
  multi-device claims. (The `instrumentation` variant exists for fixture-driven work and
  keeps its own baselines.)
- No network scenarios by design (no live scraping, no paid AI): everything measured is
  local UI/hydration/reader work on already-downloaded content.
- `reader_content_painted` is `onPageFinished`, not a pixel readback; first-paint after
  that callback is unmeasured in-app.
- Host-driven taps carry adb input-injection latency (~100–300 ms); `tap_to_*` metrics
  are interaction latency, not pure app cost (the `build_*_ms` metrics are app-internal).
- Warmed-navigation cycles re-reveal the chapter row with dumps and swipes between the
  details and reader anchors; the event-based timing metrics are unaffected, but that
  scenario's frame samples include the reveal interaction noise.
- After long heavy emulator use, the accessibility snapshot behind `uiautomator dump` can
  lag the rendered screen by tens of seconds while returning structurally valid shallow
  dumps (observed up to ~47 s after ~40 min of continuous QA). The runner waits up to 90 s
  for library visibility; if capture health is suspect, reboot the emulator before a
  baseline-quality run.
- Foreign apps left running on the emulator (e.g. the Patreon probe app) can resurface
  after force-stops; launches verify the resumed activity and retry, and dumps from a
  foreign package fail verification instead of feeding wrong coordinates into a run.
- Frame p95/p99 on an emulator are coarse (host GPU emulation, background daemons);
  they are most useful for before/after deltas on large regressions.
