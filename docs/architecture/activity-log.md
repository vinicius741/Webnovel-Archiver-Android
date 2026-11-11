# Activity log

The activity log is a persistent record of what the app did over the last 7 days. It covers syncs, downloads, library changes, backups, network access, warnings, and crashes. When something goes wrong on the phone, the owner exports it from **Settings → Share Activity Logs** and sends the file. The problem can then be diagnosed from the file alone.

It complements two older diagnostics, which are both in-memory and lost when the app restarts:

- **Source access log** (`BypassEventLog`, **Share Source Access Logs**): privacy-stripped Cloudflare and network events.
- **Local diagnostics** (`LocalDiagnostics`): small rings of warning types and operation timings.

## Recording

`data/diagnostics/ActivityLog.kt`:

```kotlin
ActivityLog.record(ActivityCategory.SYNC, "sync_started", "storyId" to id, "title" to title)
ActivityLog.recordAll(category, type, fields) // list form, for assembled field lists
```

- **Caller safety:** `record` never throws and never touches disk on the caller's thread. It serializes the event to one JSON line and queues it.
- **Writer:** a single daemon thread (`ActivityLogWriter`) appends queued lines in batches.
- **Before startup:** events recorded before `ActivityLog.init`, and every event in JVM unit tests (which never call it), wait in a 5,000-line buffer. When the buffer is full, the oldest line is dropped and counted.
- **Startup:** `WebnovelArchiverApp.onCreate` calls `init`, then plants `ActivityLogTree`.

Line format (JSON Lines):

```json
{"ts":1794416598076,"seq":42,"sid":"3f2a91c0","cat":"sync","type":"sync_merged","storyId":"rr_118891","newlyRemovedKept":88}
```

- **Envelope:** `ts` (epoch ms, device clock), `seq` (monotonic within one process), `sid` (random per process, so a new `sid` means the app restarted), `cat`, `type`.
- **Field names:** a field named after an envelope key is renamed `f_<key>`.
- **Value limits:** strings are capped at 300 characters, and fields ending in `stack` at 2,000. Lists and maps keep 25 items plus a count of the rest. A throwable becomes `Type: message`.

## What is recorded

| Category | Events |
|---|---|
| `app` | `process_start` (version, build type, SDK, device, pid), `app_upgraded` (from/to), `crash` (thread, error, 30-line stack, flushed before the process dies), `startup_library_loaded` (story/chapter/downloaded/removed counts, legacy archives loaded and left), `activity_log_exported` |
| `sync` | `sync_started`, `sync_merged`, `sync_patreon`, `sync_committed`, `sync_failed`, `sync_commit_rejected` (details below) |
| `dl` | `enqueued`, `chapter_committed`, `chapter_skipped`, `chapter_blocked`, `chapter_failed` (story, chapter, index, attempt, error), `run_finished` |
| `library` | `story_deleted` (counts before deletion), plus the sync safety-net event. Logs from 1.0.13 may also contain `removed_chapters_discarded` (the Discard action, removed in 1.0.14) |
| `backup` | `json_export`, `full_export`, `json_import`, and `full_restore`, each with `_started`, `_finished` (result, library counts before and after), or `_failed` |
| `recovery` | Chapters recovered from a full backup |
| `reader`, `tts` | `chapter_opened`, `play`, `stop` |
| `net` | Every `BypassEventLog` event, mirrored with its fields; `src` is `net`, `cf`, or `dl` |
| `log` | Every Timber WARN/ERROR line: tag, first message line, error, short stack |

The `sync` events in detail:

- **`sync_started`:** the novel's chapter, downloaded, removed-flag and Patreon-copy counts.
- **`sync_merged`:** list kind (`latest_only` or `full`), incoming, new and merged counts, and newly removed chapters split into `newlyRemovedKept` (flagged) and `droppedNeverDownloaded` (with titles).
- **`sync_patreon`:** copies, stored, locked, error.
- **`sync_committed`:** final counts plus `lostDownloadedVsBefore`, which must be 0.

## Storage and retention

- **Location:** `files/activity_logs/activity_<yyyy-MM-dd>.ndjson`. A day's file rolls into `_1`, `_2`, … parts past 1 MB.
- **Why outside the storage root:** the directory sits outside `files/webnovel_archiver/` on purpose. Clear Local Storage and a full-backup restore replace that root, and the log must survive the action it explains.
- **Dev simulation:** the log always uses the real files directory, never the simulation's storage context.
- **Retention:** `ActivityLogFiles.filesToPrune` keeps the last 7 days (today included) and at most 8 MB in total, dropping the oldest files first and always keeping the newest. It runs at startup and when the day changes.
- **Upgrade detection:** `last_version.txt` in the same directory records the running version, so the next start can log `app_upgraded`.

## Export

`ActivityLogExporter.export` flushes the writer and writes `webnovel_activity_log_<epochMillis>.json` (JSON Lines content) into the backups folder, which `FileProvider` already shares. It keeps the newest 3 exports. Line 1 is a header:

- **Identity:** `format: "wna-activity-log"`, `version`, `generatedAt`, and app version, build type, SDK, device, and session id.
- **Library:** `libraryStats` and a per-novel `library` summary: id, title, source, chapter, downloaded, removed-flag and Patreon-copy counts, Patreon link, last chapter sync, status, archive flag.
- **Queue and network:** the download queue summary and source reliability snapshots.
- **Log files:** their names and sizes.
- **Completeness:** `droppedOldestForSize`, `droppedBeforeWrite`, and `writeFailures`, so a reader knows when history is incomplete.
- **`agentInstructions`:** reading instructions for an AI agent.

Event lines follow, oldest first. If the document exceeds 12 MB, the oldest events are dropped and counted (`ActivityLogExportPlanning.documentLines`).

## Privacy

This log is for the owner's own troubleshooting, so it includes story and chapter ids and titles, which the source access log deliberately omits.

It never records:

- cookies or auth headers;
- the Patreon `session_id`;
- the OpenRouter key;
- chapter text.

`ActivityLogPlanning` adds a backstop for anything that slips into a field:

- **Secret-sounding field names** (cookie, token, authorization, apikey, password, secret) are replaced with `[redacted]`.
- **Secret-looking text** in values (`sk-or-…` keys, `Bearer …`, `Cookie:`/`Authorization:` values, `session_id=`/`cf_clearance=` pairs) is redacted too.

## Adding events

1. Call `ActivityLog.record` at the decision point, with the story id and title plus the counts someone would need to explain the outcome. Never pass a body, cookie, or key.
2. Add the event to the table above and, when a reader needs its vocabulary, to `ActivityLogExportPlanning.AGENT_INSTRUCTIONS`.
3. Pure rules live in `ActivityLogPlanning`, `ActivityLogFiles`, and `ActivityLogExportPlanning`, tested in `app/src/test/.../data/diagnostics/`.
