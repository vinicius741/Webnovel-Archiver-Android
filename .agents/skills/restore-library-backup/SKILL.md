# Skill: restore-library-backup

---
name: restore-library-backup
description: Replaces the debug app's ENTIRE library on the webnovel_api36 emulator from a backup — no SAF picker, no app UI, all via `adb run-as`. Handles BOTH formats: `webnovel_backup_*.json` (metadata-only; host-side conversion + seed) and `webnovel_full_backup_*.zip` (complete: chapters, settings, metrics; restored through the app's own production pipeline via the debug-only `dev_restore_full_backup` launch extra). Use whenever the user mentions replacing/restoring/importing a backup onto the emulator or simulator, swapping the test novels for their real library, seeding the debug app from a backup file, or handing over any `webnovel_backup_*.json` / `webnovel_full_backup_*.zip` to install. Trigger phrases include "replace the app backup", "import this backup into the simulator", "load my library onto the emulator", "install the full backup", "swap the test novels for my real ones". This is the REPLACE path; it does NOT cover the in-app merge import (Settings picker) or ANY physical-phone use — see "When not to use this skill".
---

## What this skill does

The app's in-app "Import Backup" (Settings → Data & Backup) requires the system file
picker and only **merges** into the existing library. There is no programmatic import
hook. This skill replaces the emulator's library wholesale, in one of two ways
depending on the file the user hands over:

- **`webnovel_backup_*.json`** (metadata-only) — convert on the host and write the
  app's on-disk storage directly (workflow A below):

1. Converts the backup on the host into the app's file layout (`scripts/backup_to_storage.py`):
   per-story `stories/<safeName(id)>.json`, `library_index.json`, `tabs.json` — each
   wrapped in the DurableJson envelope `{"schemaVersion":1,"appVersion":...,"payload":...}`.
   A raw backup body placed on disk directly is quarantined as corrupt, so the
   conversion step is mandatory.
2. Applies the same scrub the app's own importer applies to brand-new stories
   (`BackupMergePlanning.scrubPortableIncomingStory`): no downloaded chapters, no
   epub paths, `downloadedChapters=0`, `totalChapters=len(chapters)`.
3. Force-stops the app, wipes `files/webnovel_archiver/` (equivalent to Settings →
   Clear Local Storage), extracts the staged tree via `run-as`, and cold-starts the
   app back onto the library screen.
4. Verifies the restore through the app's built-in `dev_library_report` hook:
   the app itself reports what it hydrated — library size, an id-list hash, per-tab counts,
   downloaded-chapter total, and any storage documents quarantined as corrupt — so verification
   is a deterministic comparison, not screenshot/UI-dump guesswork.

- **`webnovel_full_backup_*.zip`** (complete: downloaded chapter files, settings, TTS,
  cleanup rules, trend metrics) — workflow B below. The ZIP is staged inside the app's
  own cache and the app restores it **through its production pipeline** via the
  debug-only `dev_restore_full_backup` launch extra: same extraction, staging,
  verification, atomic root swap, and cache refresh as the in-app Settings picker.
  The emulator ends up with the user's real library *with readable chapters offline*
  — no re-downloading.

Both paths share the same report-based verification.

## When not to use this skill

- **Physical phone** — never. This writes app data via `run-as`; it only works on the
  debuggable debug variant and must target an emulator serial (per repo AGENTS.md).
- **User wants a merge** (keep current emulator novels + add backup ones) — that is the
  in-app Settings → Data & Backup → Import Backup picker; don't script it.
- **Just launching/QA-ing the app** — use the `dev-launch-screen` skill instead.

## Irreversibility check

Both workflows REPLACE the emulator's current library wholesale (workflow A wipes the
data root first; workflow B's restore swaps a new root into place). Workflow A also
resets app settings to defaults; workflow B overwrites them with the backup's. Either
way the previous emulator library is gone — normally disposable test data, but if there
is any doubt, export the current library first (Settings → Data & Backup → Export) or
ask the user before proceeding. A failed workflow-B restore rolls back and leaves the
live library untouched.

## Workflow A — JSON backup (`webnovel_backup_*.json`, metadata-only)

All `adb` commands use an explicit emulator serial. Resolve it first — the only
unqualified adb call allowed is discovery:

```bash
adb devices -l                 # pick the emulator-* serial (webnovel_api36)
SERIAL=emulator-5554
PKG=com.vinicius741.webnovelarchiver.nativeapp.debug
ACT=com.vinicius741.webnovelarchiver.app.MainActivity
```

### 1. Convert the backup

```bash
VERSION=$(adb -s $SERIAL shell dumpsys package $PKG | grep -m1 versionName | tr -d ' \r' | cut -d= -f2)
rm -rf /tmp/webnovel_seed_staging
python3 scripts/backup_to_storage.py <backup.json> /tmp/webnovel_seed_staging --app-version "$VERSION"
```

The script fails closed on duplicate ids, blank ids, or safeName collisions. Its
summary must match the backup (story count, tab names) and prints a `storyIdsSha256`
line — capture it; step 4 compares it against the app's own report. `--app-version` is
recorded in the envelopes for fidelity but is informational — only `schemaVersion: 1`
is checked on read.

### 2. Stage, wipe, extract

```bash
tar --format ustar -C /tmp/webnovel_seed_staging -cf /tmp/webnovel_seed.tar .
adb -s $SERIAL push /tmp/webnovel_seed.tar /data/local/tmp/webnovel_seed.tar
adb -s $SERIAL shell chmod 644 /data/local/tmp/webnovel_seed.tar
adb -s $SERIAL shell am force-stop $PKG
adb -s $SERIAL shell "run-as $PKG sh -c 'rm -rf files/webnovel_archiver'"
adb -s $SERIAL shell "run-as $PKG sh -c 'mkdir -p files/webnovel_archiver && cd files/webnovel_archiver && tar xf /data/local/tmp/webnovel_seed.tar'"
adb -s $SERIAL shell "rm -f /data/local/tmp/webnovel_seed.tar"
```

**Quoting is load-bearing.** The whole remote command must reach adb as ONE argument —
wrap it in double quotes with the `sh -c` payload in single quotes. If the compound
command is passed unquoted, adb mangles it, `rm` fails with "Needs 1 argument", and
NOTHING runs (the first failure mode looks like success: old data still present).

`ustar` format because the device's toybox tar reads it reliably. `force-stop` MUST
come before the wipe — a live process can rewrite state over the seeded files.

### 3. Verify on disk before launching

```bash
adb -s $SERIAL shell "run-as $PKG ls files/webnovel_archiver/stories/" | wc -l   # expect story count
adb -s $SERIAL shell "run-as $PKG cat files/webnovel_archiver/library_index.json" \
  | python3 -c "import json,sys; print(len(json.load(sys.stdin)['payload']), 'ids')"
adb -s $SERIAL shell "run-as $PKG cat files/webnovel_archiver/tabs.json" \
  | python3 -c "import json,sys; print([t['name'] for t in json.load(sys.stdin)['payload']])"
```

Counts must match the converter summary exactly. If a story count mismatches, stop and
re-extract (the tar is still at /tmp/webnovel_seed.tar) — do not launch the app on a
partial tree.

### 4. Launch and verify via the app's dev library report

The app has a debug-only verification hook (`dev_library_report` intent extra, no-op in
release): on a cold start carrying it, the app writes what it actually hydrated to
`cache/dev_library_report.json` — including any story files the storage layer quarantined
as corrupt, which would otherwise fail silently as a smaller library.

```bash
adb -s $SERIAL shell "run-as $PKG sh -c 'rm -f cache/dev_library_report.json'"   # stale report from an earlier launch reads as success
adb -s $SERIAL shell am force-stop $PKG
adb -s $SERIAL shell am start -n "$PKG/$ACT" --es dev_start_screen library --es dev_library_report 1

# POLL for the report — a large library can take >4s to hydrate before the file appears.
for i in $(seq 1 15); do
  sleep 2
  adb -s $SERIAL shell "run-as $PKG cat cache/dev_library_report.json" > /tmp/dev_report.json 2>/dev/null \
    && python3 -c "import json; json.load(open('/tmp/dev_report.json'))" 2>/dev/null && break
done
cat /tmp/dev_report.json
```

Then compare against the converter's step-1 summary. All of these must hold:

| Report field | Expected |
|---|---|
| `librarySize` | story count from the converter summary |
| `storyIdsSha256` | the `storyIdsSha256:` line printed by `backup_to_storage.py` |
| `totalChapterEntries` | chapters total from the converter summary |
| `downloadedChapterEntries` | `0` (JSON backups carry no chapter content) |
| `storageIssues` | `[]` — any entry means a seeded document was quarantined |
| `tabs[].storyCount` | per-tab counts sum to `librarySize` (cross-check `dev_library.sh list --tab <id>`) |

A `storyIdsSha256` match proves the app loaded exactly the intended stories in exactly
the intended order — stronger than a count, which cannot catch a swap/drop that keeps
the total. A mismatch or a non-empty `storageIssues` means a seeded file was malformed:
do not paper over it; re-run the converter/extract and investigate.

The report proves the app *loaded* the library. If you also need to prove the UI
*rendered* it, dump the screen and look for `Library` + `<N> novels` + the tab row
(settle rules per `dev-launch-screen`; screenshots taken too early show a loading
frame). Optionally open one novel (`scripts/dev_library.sh open "<title substring>"`)
to confirm a details screen hydrates.

## Workflow B — Full backup ZIP (`webnovel_full_backup_*.zip`, complete)

No host-side conversion and no manual wipe: the app's own `FullBackupRestorer` does the
work (extract → validate manifest + ZIP index → stage → verify → atomic root swap →
refresh). The hook is the debug-only `dev_restore_full_backup` launch extra, which
restores a ZIP staged inside the app's own cache and then writes the
`dev_library_report` automatically. The staged zip is deleted after the attempt, so a
relaunch never re-runs the restore.

### B1. Compute the expected values

```bash
python3 scripts/full_backup_expectations.py <webnovel_full_backup_*.zip>
```

Prints `librarySize`, `storyIdsSha256`, `downloadedChapters` (the chapterFiles index
count — NOT zero for full backups), tabs, and which settings the ZIP carries.

### B2. Stage the ZIP inside the app's cache

```bash
adb -s $SERIAL push <webnovel_full_backup_*.zip> /data/local/tmp/dev_restore_source.zip
adb -s $SERIAL shell chmod 644 /data/local/tmp/dev_restore_source.zip
adb -s $SERIAL shell am force-stop $PKG
adb -s $SERIAL shell "run-as $PKG sh -c 'cp /data/local/tmp/dev_restore_source.zip cache/dev_restore_source.zip'"
adb -s $SERIAL shell "rm -f /data/local/tmp/dev_restore_source.zip"
adb -s $SERIAL shell "run-as $PKG sh -c 'rm -f cache/dev_library_report.json'"   # stale report reads as success
```

The extra names a path relative to the app's cacheDir; absolute or `..` paths are
rejected by the app (`DevRestorePlanning.resolveSandboxZipPath`), so stage the ZIP
exactly at `cache/dev_restore_source.zip`.

### B3. Launch the restore and wait — it is SLOW

```bash
adb -s $SERIAL shell am start -n "$PKG/$ACT" --es dev_start_screen library --es dev_restore_full_backup dev_restore_source.zip
```

The app shows its startup loading screen while restoring; the log line
`Dev full-backup restore: Restored N novels and M downloaded chapters` (tag
`MainActivity`) marks completion. Measured: a 113 MB ZIP with 10,808 chapter files took
~7-8 minutes on the emulator — extraction, staging, and verification are I/O and Gson
heavy. Poll for the report (written automatically after the restore attempt) with a
generous window:

```bash
for i in $(seq 1 60); do
  sleep 10
  adb -s $SERIAL shell "run-as $PKG cat cache/dev_library_report.json" > /tmp/dev_report.json 2>/dev/null \
    && python3 -c "import json; json.load(open('/tmp/dev_report.json'))" 2>/dev/null && break
done
adb -s $SERIAL logcat -d | grep "Dev full-backup restore" | tail -2
```

### B4. Verify

Same report comparison as workflow A, except `downloadedChapterEntries` must equal the
`downloadedChapters` line from `full_backup_expectations.py` (chapters came along).
Also check the result string in logcat says `Restored <librarySize> novels`.

Because chapter files are restored, prove offline reading end-to-end: open a downloaded
chapter and confirm body text renders (settle rules per `dev-launch-screen` — the
reader must show the chapter title and body, not `Preparing chapter`):

```bash
scripts/dev_library.sh open "<title substring>" --chapter 1
```

A restore failure never touches the live library (atomic swap + rollback); the logcat
line and the unchanged report say so explicitly.

## What the user should expect afterwards

**JSON backup (workflow A):**

- **Chapters show 0 / N everywhere.** JSON backups carry library metadata only — no
  chapter text or EPUBs. Reading requires re-downloading from sources (Sync Chapters /
  Download All), same as any backup restore onto a fresh install.
- **Preserved:** tabs, dateAdded ordering, reading positions (lastReadChapterId),
  scores, tags, Patreon stats, archived copies, epub configs.
- **Reset to defaults:** theme, TTS voice/settings, text-cleanup rules, download queue,
  follow-updates selection (none of these live in a JSON backup).

**Full backup ZIP (workflow B):** everything above is preserved AND chapters are
readable offline immediately (downloaded flags + chapter files restored), plus app
settings (theme, TTS voice, text-cleanup rules), per-novel trend metrics, and the
follow-updates selection. EPUBs are not carried (the export drops epub artifacts), so
generated EPUBs need regenerating.
