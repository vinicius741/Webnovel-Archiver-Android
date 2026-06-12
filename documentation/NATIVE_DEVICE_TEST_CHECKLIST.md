# Native Device Test Checklist

Use this checklist on a physical Android device or emulator with network access.

**Build:** `cd android && ./gradlew :app:assembleDebug`
**APK:** `android/app/build/outputs/apk/debug/app-debug.apk`

## Install
- Install `android/app/build/outputs/apk/debug/app-debug.apk`.
- Launch the app and grant notification permission when prompted.
- Confirm the Library screen opens without a crash.

## Import And Sync
- Add a Royal Road fiction URL from the Add Story screen.
- Add a Scribble Hub series URL from the Add Story screen.
- Confirm chapter metadata, title, author, description, tags, score, and cover render.
- Try importing a chapter URL or unsupported URL and confirm it is rejected.
- Open the in-app browser, navigate to a supported story page, import it, and assign it to a tab.

## Library And Tabs
- Search by title/author.
- Filter by tag and source.
- Sort by recency, title, date added, last updated, total chapters, and score.
- Create, rename, reorder, and delete a tab.
- Move one story and multiple selected stories between tabs.
- Confirm deleting a tab moves stories to Unassigned.

## Downloads
- Queue all chapters for a story.
- Queue a selected chapter range.
- Queue individual selected undownloaded chapters.
- Pause, resume, cancel, retry failed, clear finished, and pause/resume a story group.
- Background the app while downloads run and confirm the foreground notification updates.
- Kill and restart the app during a download and confirm interrupted jobs recover to pending.

## Reader And TTS
- Open a downloaded chapter in the reader.
- Navigate previous/next chapters.
- Copy chapter text and confirm paragraph/line breaks are readable.
- Mark a chapter as read and verify the bookmark indicator updates.
- Start TTS, then test pause, resume, previous chunk, next chunk, and stop from both in-app controls and notification actions.
- Restart the app during a paused/active TTS session and confirm resume behavior.

## Text Cleanup
- Add, edit, delete, and toggle sentence removal entries.
- Add, edit, delete, toggle, and validate regex cleanup rules.
- Confirm duplicate regex rules and duplicate sentence entries are rejected.
- Export cleanup rules JSON.
- Apply cleanup to downloaded chapters and confirm the EPUB stale state is updated.

## EPUB
- Generate an EPUB for all downloaded chapters.
- Configure a chapter range and generate an EPUB.
- Enable start-after-bookmark and verify the generated range advances past the bookmark.
- Generate a split-volume EPUB with a low max-chapter setting.
- Open/share generated EPUB files in an external reader.
- Delete or move an EPUB externally if possible, then confirm the app handles the missing file and asks to regenerate.

## Backup And Restore
- Export JSON metadata backup and inspect that local paths/content are not present.
- Import JSON metadata backup into an app with existing downloaded chapters and confirm local downloads are preserved.
- Export full ZIP backup.
- Clear local data, restore the full ZIP backup, and confirm downloaded chapter files are restored.
- Try restoring an invalid ZIP and a ZIP missing `manifest.json`; confirm non-destructive errors.

## Archive Snapshots
- Sync a story whose source chapter list has removed chapters, if a known fixture/source condition is available.
- Confirm the app creates an archived read-only snapshot and preserves downloaded chapter files.

## Settings
- Change global download concurrency/delay.
- Change per-source download overrides and reset them.
- Change EPUB max chapters.
- Change TTS pitch, rate, chunk size, and voice.
- Change theme and fold layout mode.
- Restart the app and confirm settings persist.

## Final Smoke
- Reopen the app after a force stop.
- Confirm library, tabs, settings, queue, generated EPUB paths, bookmarks, cleanup rules, and TTS settings persist.
