# Chapters removed from the source

Authors stub novels for publication, delete author notes, or pull side stories. The app keeps any
downloaded chapter a source stops listing. It stays inside the novel as an ordinary chapter, so
nothing appears as a separate library entry.

## Data

`Chapter.removedFromSourceAt` holds the time of the sync that first found the chapter missing.
`null` means the source still lists it. The chapter keeps its `filePath`, download state, and
bookmark.

## Sync

`StorySyncPlanning.mergeChapters` (full chapter list) handles three cases:

- **A downloaded chapter the source no longer lists:** it stays and gets the flag. An existing flag
  keeps its first time.
- **A missing chapter that was never downloaded:** it's dropped, because there is no text to keep.
- **A flagged chapter the source lists again:** the flag is cleared (`mergeLatestChapters` also
  clears it).

`RemovedChapterPlanning.placeKept` puts each kept chapter right after the nearest chapter that
preceded it and is still listed. Kept chapters with no surviving predecessor go first. Patreon
early-access copies always stay last. For a stubbed novel, the result is: surviving samples, the
stubbed run, then new chapters.

A table of contents that failed to load completely (for example, a blocked page) can make chapters
look removed. When that happens, the chapters are flagged instead of lost, and the next complete
sync clears the flags.

The concurrent-sync fold (`StorySyncMergePlanning`) does not bring back a kept chapter the user
discarded while the sync was running. A JSON backup merge (`BackupMergePlanning`) keeps local
flagged chapters that the backup lacks.

## UI and output

- **Chapter rows:** the subtitle includes "Removed from source".
- **Details:** a notice with the count and a **Discard** action
  (`AppRepository.discardRemovedChapters`). Discarding deletes the files, moves a bookmark on a
  discarded chapter back to the nearest earlier chapter, and remaps EPUB ranges and AI chapter
  picks by chapter identity (`RemovedChapterPlanning.remapPositions`).
- **Chapter filter:** a "Removed from source (N)" chip appears only for novels that have kept
  chapters.
- **Downloads:** kept chapters are always downloaded, so nothing queues them.
- **Reader, TTS, and EPUB:** they read straight through, and EPUBs include kept chapters.

## Legacy archive snapshots

Before this model, a sync that found missing chapters copied the whole novel into a read-only
`<id>__archive_<time>_<suffix>` story (`isArchived`, `archiveOfStoryId`, `archivedAt`,
`archiveReason`). The "Archive Local Copy" button did the same for unavailable sources. Neither
path exists anymore; the fields stay readable.

`LegacyArchiveFoldPlanning` with `data/storage/LegacyArchiveFold.kt` runs at startup (in the
one-pass load in `AppContainer`) and after every JSON import or full-backup restore. Snapshots are
processed newest first, per live story:

- **Missing from the live story:** each downloaded, readable chapter becomes a kept chapter in its
  old position, flagged with the snapshot's `archivedAt`.
- **Listed by the live story but never downloaded there:** the chapter takes the snapshot's copy.
- **Already downloaded in the live story:** it's covered, so nothing changes.
- **Bookmarks:** a bookmark the removal cleared is restored when it points at a kept chapter.
- **No live story:** the newest snapshot becomes the live story under the original id, and older
  snapshots fold into it.
- **Patreon copies inside snapshots:** skipped when the live story already has that copy or the
  downloaded public chapter it copies. Otherwise the snapshot holds the only text, so the copy is
  kept as an ordinary removed-from-source chapter.

Adopted files are copied into the live story's folder as `kept_<chapterId>.html`. A snapshot is
deleted only after the live story is written and every downloaded, readable chapter of every
snapshot is verified as readable in it. A failure keeps that story's snapshots for the next run.
The fold is idempotent.
