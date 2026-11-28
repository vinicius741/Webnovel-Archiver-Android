# Full-backup chapter packs (manifest version 3)

Since manifest version 3, a full backup (`webnovel_full_backup_<millis>.zip`) stores each story's
downloaded chapters as one zstd stream instead of one deflated ZIP entry per chapter. Deflate
compresses every chapter alone, so it cannot reuse the markup and prose that a story's chapters
share. One stream per story can.

## Layout

| Entry | Content |
|---|---|
| `manifest.json` | As in version 1 (`library`, `config`, `chapterFiles`, `metricFiles`, `coverFiles`, `missingContent`), with `version: 3` and a `chapterPacks` index. |
| `chapter_packs/<story>.zst` | One per story with downloaded chapters. `<story>` is the percent-encoded story id, the same segment as `novels/<story>/` (`FullBackupPaths.chapterPackPath`). The ZIP entry is deflate level 0 (stored blocks): zstd output does not deflate further. |
| `metrics/…`, `covers/…` | Unchanged from version 1. |

`chapterPacks` is a list of `{storyId, path}`, at most one per story, and `path` must equal
`chapter_packs/<encoded storyId>.zst`. `chapterFiles` still lists every chapter with its
`novels/<story>/<index>_<chapter>.html` path. Those paths now name files inside the packs.

### Pack stream (`data/backup/ChapterPack`)

One zstd frame (content checksum on). Decompressed, it holds:

1. The 8-byte magic `WNAPACK\x01`.
2. One record per chapter file: `u16` name length (1–1024), the UTF-8 file name, `u32` content
   length, the content. Integers are big-endian. The name is a bare file name (`0001_<chapter>.html`)
   with no `/`, `\`, control characters, `.` or `..`.
3. A `u16` 0 terminator, then the end of the stream.

The format is custom rather than tar so the reader has nothing to skip or interpret: no header
types, links, pax records or padding.

## Restore

Restore reads versions 1 and 3. Version 2 is reserved for the compact manifest (PR #61), so no
number means two things. Builds before version 3 reject a version-3 backup as
"unsupported version 3".

`FullBackupZipExtractor` expands each pack into `novels/<story>/` as if every chapter were its own
ZIP entry. The story directory comes from the pack's entry name, so a pack cannot write into another
story's directory. Every packed file goes through the same checks as a loose entry:

- the entry-name allowlist (`BackupInputLimits.isAllowedFullBackupEntry`) and the safe extraction
  target;
- the duplicate-entry check, shared with loose entries, so a packed chapter cannot shadow a loose one;
- `MAX_ZIP_ENTRIES`, counting packed files;
- `MAX_ZIP_ENTRY_BYTES` per file, checked against the declared length before any content is read;
- the extraction budget, counting decompressed bytes.

On top of these, the zstd window is capped at 2^24 (`ChapterPack.MAX_WINDOW_LOG`), which bounds the
decoder's memory against a hostile frame header. A truncated, malformed or trailing-data pack throws
`ZipException`, which restore reports as a corrupt backup. The extracted file set (loose plus packed
chapters, plus the pack names) is then checked against `chapterFiles` + `chapterPacks` exactly as in
version 1, so the rest of the restore pipeline is unchanged.

## Choice of codec and level (measured 2026-11-27)

**Host JVM, 1,085 real chapters from 10 stories (29.0 MB raw).** Times are for compressing each
story as one stream. The machine was shared with other builds, so treat times as relative.

| Codec | Size | vs per-file deflate-6 | Encode | Decode |
|---|---|---|---|---|
| per-file deflate-6 (format v1) | 12.00 MB | — | 1.27 s | — |
| solid deflate-9 | 10.84 MB | −10% | 1.95 s | 0.10 s |
| zstd-3 | 10.51 MB | −12% | 0.30 s | 0.06 s |
| **zstd-6** | **9.66 MB** | **−20%** | **0.76 s** | **0.06 s** |
| zstd-9 | 9.32 MB | −22% | 1.40 s | 0.08 s |
| zstd-12 | 9.08 MB | −24% | 3.6 s | 0.07 s |
| zstd-19 | 8.34 MB | −31% | 27 s | 0.08 s |
| xz preset 1 (pure Java) | 10.01 MB | −17% | 5.9 s | 0.8 s |
| xz preset 3 | 9.40 MB | −22% | 23 s | 0.8 s |
| xz preset 6 | 8.26 MB | −31% | 38 s | 0.75 s |

For the same size, xz (`org.tukaani:xz`, pure Java) is about 16× slower to encode and 10× slower
to decode than zstd.

**Emulator export, the debug library (305 novels, 24,468 chapters, 646.5 MB chapter HTML).**
Settings → Data & backup → Create backup. Time is from the `full_export_started` to the
`full_export_finished` activity-log events. Runs alternated between builds with a warm page cache,
and each backup was deleted afterwards.

| Exporter | Export time | Backup size | Chapter bytes |
|---|---|---|---|
| Version 1 (per-file deflate-6) | 37.1, 37.2, 38.4 s | 336.0 MB | 260.1 MB |
| Version 3, zstd-9 | 40.1, 40.4, 40.8 s | 273.6 MB | 201.6 MB (−22.5%) |
| **Version 3, zstd-6 (shipped)** | **29.3, 29.6 s** | **280.7 MB** | **≈208.7 MB (−19.8%)** |

Level 6 is both faster than the version-1 export (−21%) and 16.5% smaller overall. Level 9 saves only
another 7 MB and costs 11 s more. Covers (69.5 MB) and the manifest are unchanged here. Other PRs
handle those (#60 WebP covers, #61 compact manifest).

**APK cost of zstd-jni 1.5.7-26.** Native library per ABI, deflated as the APK stores it
(`useLegacyPackaging`): arm64-v8a 235 KB (487 KB installed), armeabi-v7a 207 KB (370 KB installed).
The release APK carries both phone ABIs: about +442 KB download, and +487 KB installed on an arm64
phone. The Java classes are 70 KB of dex (29 KB compressed), all kept because the JNI code reads
fields by name. `x86_64` (252 KB) ships only in `debug` and `instrumentation`. For comparison, xz
1.12 would add about 121 KB of dex before shrinking, but at the speeds above.
