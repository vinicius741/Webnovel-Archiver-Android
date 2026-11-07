# Diagnostic Reports

Users can send two shareable JSON exports when something goes wrong. Both read a **recent time
window** that the user picks (last hour, 6 hours, 24 hours, or 3 days), not the app's whole history.

| Export | Where | Format | Contents |
|--------|-------|--------|----------|
| Share Diagnostic Report | Settings (main screen) | One JSON document, `format: wna-diagnostic-report` | Everything: crashes, Android process-exit reasons, warnings/errors with stack traces, operation timings, source-access events, plus a snapshot of app, device, library, queue, settings, and source state. |
| Share Source Access Logs | Settings → Data & Backup | JSON Lines, `format: wna-source-access-log` (v2) | Only source-access / Cloudflare-bypass events. See `docs/cloudflare/source-reliability-implementation.md`. |

Both start with an `agentInstructions` field so an AI agent can read the file without other context.

## Persistent journal

All producers write to `data/diagnostics/DiagnosticJournal`: append-only JSON Lines segments in
`filesDir/diagnostics/` (outside `webnovel_archiver/`, so backups never include it and restores
never replace it).

- **Why persistent:** the old in-memory rings were lost whenever the process died, which is
  exactly when a user needs to report a problem. They also covered "since this process started",
  which could be minutes or days.
- **Retention:** 72 hours and 12 MiB in total, in 1 MiB segments. Each process starts a new segment.
  Pruning runs when a session starts and on each rotation.
- **Threading:** callers serialize a record and queue it. One low-priority daemon thread writes
  and flushes the queue. The crash path (`appendBlocking`) drains the queue, writes, and fsyncs on
  the crashing thread.
- **No Timber inside the journal:** Timber's diagnostic tree writes to the journal, so logging a
  journal failure there would recurse. The journal counts write failures and dropped records, and
  reports them under `journal` in the report.
- In unit tests `DiagnosticJournal.active` is null, so recording does nothing. Tests that need
  records create a `DiagnosticJournal(dir, background = false)`.

Record kinds (`k`): `session` (process start), `log` (WARN+ Timber), `op` (timed operation),
`src` (source-access event from `BypassEventLog`), `crash` (uncaught exception). Every record has
`ts` (epoch ms) and `sid` (per-process id).

## Producers

- `WebnovelArchiverApp.installDiagnostics()` runs first in `onCreate`. It installs the journal,
  chains a crash recorder in front of the default uncaught-exception handler, and writes a
  `session` record.
- `LocalDiagnosticTree` (Timber) records WARN+ logs: a redacted message, the throwable as
  structured data (type, redacted message, up to 24 frames, up to 3 causes, and a fingerprint
  `fp`), and an `origin`, which is the first stack frame outside Timber.
- `LocalDiagnostics.recordOperation` / `measuring` record named timings.
- `BypassEventLog.record` records source-access events. These are already sanitized to hosts and
  fixed vocabulary.

## Redaction

`DiagnosticRedactor` scrubs free text (log and exception messages, exit descriptions):

- URLs are reduced to `scheme://host/…`, and `content://` URIs to `content://…`.
- Absolute file paths are reduced to `…/last-segment`.
- Emails, `sk-…` API keys, bearer tokens, `cookie=` / `token=` / `key=`-style assignments, and
  long opaque tokens become placeholders.
- Text is truncated to 400 characters.

Stack frames are code locations and are kept. A story title that a call site puts into a log
message cannot be detected and may survive. Aggregates (library and queue summaries, storage issues)
use allowlisted categories and counts only.

## Report layout and trimming

`DiagnosticReportPlanning` streams the window through a `Collector`. Detail lists are bounded:
newest 25 crashes, 600 logs, 500 operations, and 6000 source events. The final document is limited
to 768 KiB. When trimming, the planner keeps the header, state, summary, crashes, and exit reasons
first, then fills the remaining space newest-first with logs, then operations, then source events.
`summary`, `operationStats`, and `sourceSummary` always count the whole window. `summary.included`
says how many records each detail list kept. Repeated stack traces are stored once under
`stackTraces[fp]`.

`exitReasons` comes from `ActivityManager.getHistoricalProcessExitReasons` (API 30+) and is
limited to the window. It records crashes, native crashes, ANRs (with the `main` thread block of
the ANR trace), low-memory kills, and user stops. These are deaths the in-process crash handler
cannot see.

## Release builds

Release builds are R8-minified. `proguard-rules.pro` keeps `SourceFile,LineNumberTable`. The
release workflow publishes `<apk-name>-mapping.txt` next to the APK. To read a release report's
stack traces, retrace them with the mapping file for that `app.versionName` / `versionCode`
(`retrace mapping.txt stacktrace.txt`).
