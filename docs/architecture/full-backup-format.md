# Full-backup ZIP format

A full backup (`webnovel_full_backup_<millis>.zip`, Settings → Data & backup) holds `manifest.json`
plus one entry per file it restores:

| Entry | Content |
|---|---|
| `manifest.json` | Library, configuration, and the file indexes (below). Written first. |
| `novels/<story>/<index>_<chapter>.html` | One downloaded chapter. `<index>` is the chapter's 4-digit position in `story.chapters`; ids are percent-encoded (`FullBackupPaths.chapterPath`). |
| `metrics/<story>.json` | Trend history, listed in `metricFiles`. |
| `covers/<name>.<png\|jpg\|jpeg\|webp>` | Generated AI cover at the story's own `aiCoverPath`, listed in `coverFiles`. |
| `chapter_rewrites/…` | Only in backups from the retired Chapter polish feature, listed in `rewriteFiles`; validated, then dropped. |

Restore rejects a ZIP whose entries differ from the manifest's indexes in either direction
("unindexed ZIP entry" / "missing ZIP entry", `FullBackupRestorer.verifyZipIndex`).

## Manifest versions

Both versions share `format: "webnovel-archiver-full-backup"`, `library` (stories without
`filePath`, `content` or EPUB paths), `config` (must hold a `tabs` array), and the optional
`metricFiles`, `coverFiles`, `rewriteFiles` and `missingContent`.

- **Version 1** (written until 1.2.12): pretty-printed, with a `chapterFiles` array of
  `{storyId, chapterId, chapterIndex, title, path}` for every chapter in the ZIP. Restore still
  reads it and uses that list as the chapter index.
- **Version 2** (current): compact JSON without HTML escaping, and no `chapterFiles`. The chapter
  index is derived: every chapter with `downloaded: true`, at its position in its story, except the
  chapters listed in `missingContent` with `kind: "chapter"` (downloaded chapters whose content
  was absent at export, so the ZIP has no entry for them). This is the same rule the exporter
  uses to choose which chapters to zip (`FullBackupContentPlanning`). A version-2 manifest that
  carries `chapterFiles` is rejected.

The derived index passes the same checks as a listed one: entry-name allowlist, at most
`MAX_CHAPTER_FILES` entries, no duplicate paths, no duplicate (story, chapter) pairs. Then it
goes through the same ZIP comparison. Builds before version 2 reject version-2 backups
("unsupported version 2").

## Reading and writing

- `FullBackupManifestWriter` streams the manifest into the ZIP entry one story at a time.
- `FullBackupManifestReader` streams `manifest.json` under the `MAX_MANIFEST_BYTES` limit. It
  decodes each story typed through a one-story JSON tree, keeps only the string fields of index
  entries, and keeps `config` as a JSON tree that `RestoreStagingWriter` decodes per key.
  No manifest-sized string or untyped `Map` copy is built.
- `FullBackupManifestValidation` validates the streamed manifest. It keeps the error messages and
  their order from the former `Map` validation, then derives or converts the chapter index.

## Measurements (2026-11-27)

`FullBackupManifestBenchmarkTest` (opt-in; see its KDoc) on a generated 305-story,
33,000-chapter library and on the owner's real library pulled read-only from the emulator:

| | Generated (305 stories, 33,000 chapters) | Real library (305 stories, 33,329 chapters) |
|---|---|---|
| Manifest v1, raw / deflated | 15.6 MB / 983 KB | 16.4 MB / 2.27 MB |
| Manifest v2, raw / deflated | 7.8 MB / 377 KB | 8.5 MB / 1.54 MB |
| Export: manifest write v1 → v2 | 57 → 27 ms; 79 → 4 MB allocated | 105 → 35 ms; 221 → 4 MB allocated |
| Restore parse, former Map path (v1) | 147 ms; 185 MB allocated; min heap 103 MB | 217 ms; 320 MB allocated; min heap 167 MB |
| Restore parse, streaming (v1) | 65 ms; 87 MB; min heap 23 MB | 110 ms; 89 MB; min heap 27 MB |
| Restore parse, streaming (v2) | 50 ms; 69 MB; min heap 19 MB | 82 ms; 71 MB; min heap 23 MB |

Times are host-JVM medians of 5 runs; "min heap" is the smallest `-Xmx` (4 MB steps, serial GC)
at which a fresh JVM completes the parse. Phones are slower in absolute terms; the ratios are
what carry over.
