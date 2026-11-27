# Quick backup

A quick backup is a small ZIP (1.6 MB and 1.4 s on the emulator for the 305-novel, 24k-chapter test
library, against ~355 MB for a full backup) with everything about the library except the chapter text. It is meant to be made
often; the full backup stays the occasional, complete copy.

Code: `data/backup/QuickBackupFormat.kt` (format and validation),
`data/backup/QuickBackupMergePlanning.kt` (restore rules), `data/storage/QuickBackupExporter.kt`,
`data/storage/QuickBackupRestorer.kt`, `data/storage/QuickBackupZip.kt`.

## What it holds

| Included | Not included |
|---|---|
| Every story's metadata and full chapter list, with which chapters were downloaded, removed-from-source flags, bookmarks (`lastReadChapterId`), tabs, EPUB settings, AI descriptions and Patreon links | Chapter text (`novels/`) |
| Settings, per-site download settings, chapter filters, display preferences, tabs | EPUB files |
| Text cleanup rules (sentence list and regex rules) | Generated chapter audio |
| Listening settings, the saved listening session, per-story listening positions | AI cover drafts and cover history, characters |
| Trend history (`metrics/`) | The OpenRouter key and the AI spend ledger (device-local, like the full backup) |
| Optional: the applied AI covers (`covers/`; 67.9 MB in all on the test library) | The download queue |

The settings set is exactly the full backup's (`BackupExporter.fullConfig`), so a new setting added
to the full backup is in the quick backup too.

## Format

- File name `webnovel_quick_backup_<millis>.zip`, in the same `backups/` folder as full backups, shown
  as "Quick backup" in Backup files.
- First ZIP entry `quick_backup.json`: `{format: "webnovel-archiver-quick-backup", version: 1,
  exportDate, library, config, metricFiles, coverFiles}`. Then `metrics/<id>.json` and, when chosen,
  `covers/<name>.<image>`. Nothing else is accepted.
- It is a separate format with its own version line, not a full-backup version. Restore tells the two
  apart by the first entry. An app without quick backups sees a full backup without a manifest and
  refuses it without changing anything.

## Create

Data & backup → **Create backup** opens a sheet: **Full backup**, **Quick backup**, or **Quick backup
with AI covers**. All three run in the activity-level `BackupExports`, so they outlive the page, and
end with the same "Backup saved · size" share sheet.

## Restore: metadata from the backup, files stay on the phone

**Restore backup** is still one row. A quick backup is merged into the library instead of replacing
it (`QuickBackupMergePlanning`):

- **Stories in the backup** take the backup's details, chapter list, bookmark, tab and EPUB range.
- **Chapter text is never deleted or replaced.** A chapter keeps its file when its text is on the
  phone, even if the backup is older than the download. A chapter the backup lists as downloaded but
  whose text is not on the phone becomes *not downloaded*, so it can be downloaded again; the outcome
  message counts these.
- **Downloaded chapters the backup does not list** (removed from the source, or downloaded after the
  backup) stay, in their reading position, with their removed-from-source flag. An older backup never
  clears a removed-from-source flag the phone already has. Removed-from-source chapters in the
  backup are kept even when their text is not on the phone.
- **Stories only on the phone** stay untouched. A quick restore never removes a story.
- **Tabs**: the backup's tabs, then the phone's tabs the backup lacks. **Listening positions**: the
  backup's for its stories, the phone's for the rest. Other settings: the backup's.
- **Files**: EPUBs and the download state stay the phone's. An AI cover file on the phone is never
  replaced; a backup cover is written only where the phone has none. Trend history is replaced only
  when the backup's newest snapshot is newer.

There is no root swap: `novels/` is never touched. Before each write the restorer copies the file
aside (`webnovel_quick_restore_previous_<millis>` in app files) and puts every file back if a later
write fails. Validation happens before any write, so a rejected backup changes nothing.

## Restore mode or disaster-recovery companion?

Both, but it is built as the **companion to the full backup**:

- After losing the phone: restore the last full backup (text as of then), then the latest quick
  backup (bookmarks, chapter lists, settings as of yesterday). Chapters downloaded in between show as
  not downloaded and can be fetched again.
- On the same phone, it is also a safe undo for metadata mistakes (a deleted novel, scrambled tabs or
  settings), because it cannot delete text.

It is not a replacement for the full backup: on a phone without the text it only gives back a
library to download again.

## Why this is not the removed JSON export

JSON export/import was removed in November 2026 because Data & backup had five actions with three
different behaviours (import merged, restore replaced, recover only added) and the JSON export said
it held settings when it held only the library and tabs. The quick backup keeps the page at two rows
(Create, Restore), puts the choice inside Create, carries the real settings set, and has one merge
rule that is stated here and in the code.
