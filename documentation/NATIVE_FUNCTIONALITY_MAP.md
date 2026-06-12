# Webnovel Archiver Functionality Map

This map documents the feature set of the native Kotlin implementation. Compatibility references note where the native app maintains data-format parity with the legacy React Native implementation for backup/restore and cross-version interoperability.

## Library
- Story list with title, author, tags, status, downloaded/total chapter counts, archive marker, search, tab filter, tag filter, sort controls, sync, open, move, and delete actions.
- Story list renders native cover thumbnails when a cover URL is available.
- Library filters support content tags and source-name filters, including source+tag intersections.
- Library sort controls support default recency, title, date added, last updated, total chapters, and score ordering.
- Bulk story selection with move-to-tab and delete actions.
- Per-story metadata: source URL, cover URL, description, tags, score, timestamps, EPUB paths, stale EPUB flag, tab assignment, bookmark/last-read chapter, pending new chapter IDs, archive fields.
- Custom tabs: create, rename, reorder, delete; create/rename trim names and reject blank values, ordering is normalized after moves, and deleting a tab moves novels to unassigned.
- Archive snapshots: when source sync detects removed chapters, the full previous story and downloaded chapter files are copied into an archived story with an `__archive_` suffix and source-removal reason.
- Archived snapshots are read-only for source sync and download queue actions, while still allowing reading/export workflows.
- Story sync preserves downloaded chapters across stable chapter ID/slug changes, remaps bookmarks to stable IDs, preserves pending-new chapter IDs, extends EPUB configured range ends when the previous range reached the old chapter count, marks generated EPUBs stale when chapter lists change, and queues newly discovered chapters for existing stories.

## Sources
- Network requests use a mobile user agent, HTML/image accept headers, English accept-language, Scribble Hub URL-encoded AJAX form posts, per-host Scribble Hub rate limiting, and retry handling for Scribble Hub 403/429 responses.
- Source chapter titles are normalized with cleanup for trailing overflow markers, relative "time ago" suffixes, and absolute date suffixes.
- Story import accepts only supported story detail URLs: Royal Road fiction pages and Scribble Hub series pages; chapter/non-story URLs are rejected before sync to avoid unstable fallback story IDs.
- Royal Road support:
  - Story ID from `/fiction/{id}`.
  - Chapter ID from `/chapter/{id}`.
  - Metadata parsing for title, author, cover, description, tags, score, canonical URL.
  - Author metadata fallback includes visible author links/text plus `author`, `article:author`, and `twitter:creator` meta tags.
  - Chapter list parsing from `.chapter-row`.
  - Chapter content extraction from `.chapter-inner`, removing scripts and Royal Road clutter.
- Scribble Hub support:
  - Story ID from `/series/{id}` with URL fallback.
  - URL fallback story IDs use `encodeURIComponent`-compatible percent encoding.
  - Chapter ID from `/chapter/{id}`.
  - Metadata parsing for title, author, cover, synopsis, tags, score, canonical URL.
  - Chapter list parsing from `.toc_ol`.
  - Paginated TOC loading through Scribble Hub AJAX.
  - Chapter content extraction from `#chp_raw`, removing author notes/share/code/script/style clutter.

## Downloads
- Persistent queue stored on device.
- Queue all chapters, selected explicit ranges, bookmark-forward ranges, count-based ranges, or individual chapters.
- Select multiple undownloaded chapters from story details and queue them together.
- Concurrency and delay settings.
- Per-source concurrency and delay overrides for each registered provider.
- Queue screen with pending/active/failed/completed state.
- Queue screen shows total, queued, active, paused, completed, failed, and cancelled counts.
- Queue jobs are grouped by story with per-story completed/queued/active/paused/failed/cancelled summaries.
- Pause all, resume all, pause/resume/cancel individual jobs, pause/resume a story group, retry individual failed jobs, retry failed jobs for a story, retry all failed jobs, remove individual terminal jobs, and clear finished.
- Queue controls preserve consistent status transitions: pause clears scheduled retry timers, resume keeps existing error metadata while clearing retry timers, cancel records cancelled error metadata, and manual retry resets only failed jobs.
- Re-queueing a chapter with an existing failed, completed, or cancelled job resets that job to pending while preserving retry count; active/pending duplicates are not duplicated.
- Foreground download service with progress notification, notification channel, and pause/resume/stop notification actions.
- Interrupted `downloading` jobs are recovered to `pending` on app/service startup.
- Pending or active jobs with no matching source provider are failed before consuming a worker slot, and stuck downloading story statuses recover to idle/partial when no active jobs remain.
- Download errors are classified, retryable transient errors are automatically rescheduled with exponential backoff, and failed/cancelled jobs retain error category/code details.
- Per-chapter HTML saved under app-private `novels/{storyId}` storage.
- Downloaded chapters immediately update story status, downloaded count, pending new IDs, and EPUB stale flag.

## Text Cleanup
- Sentence removal list.
- Sentence removal management trims entries, rejects blanks and duplicates, inserts new entries at the beginning, edits by index, and confirms deletes.
- Default sentence-removal list matches the legacy `default_sentence_removal.json` anti-piracy cleanup phrases for backward compatibility.
- Regex cleanup rules with enabled flag and target (`download`, `tts`, `both`).
- Add, edit, delete, toggle, validate, and export cleanup rules as JSON.
- Regex cleanup validation supports `/pattern/flags` literals, flag normalization, unsupported-flag rejection, invalid-regex rejection, and nested-quantifier risk rejection.
- Regex cleanup creation/editing rejects duplicate rules with the same normalized pattern, flags, and target.
- Regex cleanup storage sanitizes persisted rules on read/write, dropping invalid entries, requiring IDs, normalizing fields, and keeping the last rule for duplicate IDs.
- Quick separator rule builder generates repeated-character cleanup patterns.
- Cleanup applied during download.
- Offline re-apply cleanup to downloaded chapters.

## Reader And TTS
- Native reader screen for downloaded chapter text.
- Reader renders downloaded chapter HTML in a native WebView with responsive reader styling and image scaling.
- Reader shows an undownloaded chapter fallback message when chapter content is not available.
- Reader shows current chapter position, supports previous/next navigation, copying formatted chapter text to clipboard, explicit mark-read, and return-to-details actions.
- Reader copy text preserves paragraph breaks, headings, line breaks, and table cell separators while removing script/style/iframe/noscript content.
- Story details render the native cover image when available and open a larger cover viewer on tap.
- Story details show provider/source information and can open the original source URL externally.
- Story descriptions are truncated in details with full-description dialog and clipboard copy actions.
- Last-read chapter persistence.
- Mark-read/bookmark actions toggle the current bookmark from story details, set the bookmark from the reader, and advance the configured EPUB start range when `startAfterBookmark` is enabled.
- On app startup, an eligible persisted TTS session navigates back to the saved reader story/chapter when that story and chapter still exist.
- Story details chapter search, downloaded-only filter, bookmark-forward filter, and mark-read/bookmark action.
- Persisted chapter filter preference with `all`, `hideNonDownloaded`, and `hideAboveBookmark` modes.
- Android `TextToSpeech` playback with paragraph-oriented chunking, TTS-target regex cleanup, pitch/rate/voice settings model, pause/stop, previous/next chunk controls, persisted session data, and persisted-session resume when the saved session has a story/chapter plus active or paused playback state.
- Persisted TTS resume rebuilds chunks using current TTS settings and clamps the saved chunk index to the available chapter chunks before playback starts.
- TTS marks the completed chapter as last-read and continues into the next available chapter when a chapter finishes.
- Foreground TTS service with notification channel and previous/play-or-pause/next/stop notification actions.

## EPUB
- EPUB 2 ZIP generation with a spec-compliant stored/uncompressed first `mimetype` entry.
- `META-INF/container.xml`, `content.opf`, `toc.ncx`, cover/details/table-of-contents XHTML, CSS, and chapter XHTML files.
- EPUB cover, details, table-of-contents, chapter XHTML, and CSS follow a consistent structure including cover placeholders, description paragraphs, tag chips, and reader-friendly styles.
- Chapter XHTML generation strips full saved HTML document wrappers down to body contents before embedding, while preserving regular content fragments.
- OPF metadata includes title, author, language, stable identifier, description, tags as subjects, cover metadata, generator metadata, spine, and cover/table-of-contents guide references.
- NCX metadata includes EPUB 2 doctype, stable identifier, depth, page-count metadata, front matter navigation, and chapter navigation.
- NCX navigation includes cover, details, table of contents, and chapter entries with chapter play order after front matter.
- Optional cover embedding with fetched image media type and extension preserved for JPEG, PNG, GIF, SVG, and WebP.
- Volume splitting by max chapters per EPUB.
- EPUB split size is clamped to 10-1000 chapters, and generated EPUB filenames use bounded sanitized story-title bases with chapter range suffixes preserved.
- Per-story EPUB settings for max chapters per EPUB, chapter range, and start-after-bookmark generation.
- Per-story EPUB path persistence and stale flag clearing after generation.
- Intent/share helpers for generated EPUB files.
- Multiple generated EPUB volumes can be selected before opening, with readable decoded filenames and missing-file cleanup that updates story EPUB paths.

## Backup And Data
- JSON metadata backup export/import with local file paths, EPUB paths, chapter content, and downloaded flags scrubbed from exported story metadata.
- JSON metadata backup export rejects empty libraries and oversized JSON payloads above 50 MB.
- JSON metadata import merges incoming story metadata while preserving existing local downloaded chapter state, chapter file paths/content, last-read chapter, date-added/last-updated values, EPUB paths/stale flags, and pending-new IDs.
- JSON metadata import validates backup version, library shape, and story IDs before merging, reports invalid JSON/shape errors without mutating the library, and does not trust imported stale file paths/download flags for newly added chapters or stories.
- Full ZIP backup export/restore using a structured manifest with `config`, scrubbed story file paths, a `chapterFiles` index, settings, tabs, cleanup rules, display preferences, chapter filter preference, TTS settings/session, library, and chapter files.
- Full ZIP backup export rejects empty libraries with the same user-facing guard as JSON backup export.
- Full ZIP restore reports both restored novel count and restored downloaded chapter count.
- Full ZIP backup chapter file entries include `storyId`, `chapterId`, `chapterIndex`, `title`, and portable `novels/{encodedStoryId}/{0000}_{encodedChapterId}.html` paths.
- Full ZIP backup export/restore includes per-source download settings.
- Full ZIP restore rejects unsafe ZIP entries that would extract outside the restore directory.
- Full ZIP restore reports a missing-manifest error before destructive restore.
- Full ZIP restore validates manifest format, version, library shape, chapter file index, configuration, and story IDs before clearing local data.
- Full ZIP restore defensively clears transient EPUB/download pointers from imported story metadata, then restores only chapter files present in the backup and recomputes downloaded counts.
- Clear local storage.
- App-private local-first storage with no backend.
- FileProvider exposes app-private files/cache for Android share/open intents without exposing raw file paths.

## Browser
- Native WebView source browser.
- Address/search bar.
- Browser address resolution preserves explicit HTTP(S) URLs, adds HTTPS to URL-like input, and converts plain text to Google search.
- URL normalization for direct URLs and web search fallback for non-URL search terms.
- Go, back, forward, refresh, home, import current URL, tab assignment during import, and open external browser actions.
- Import is gated to supported Royal Road fiction and Scribble Hub series pages, and Google auth URLs are opened externally.

## Settings
- Download concurrency and delay.
- Download settings validation clamps concurrency to 1-10, delay to 0+, EPUB split size to 10-1000, TTS pitch/rate to 0.5-2.0, and TTS chunk size to 100+.
- Persisted preference loading normalizes invalid, high out-of-range, or legacy partial settings back to bounded defaults for global downloads, per-source downloads, TTS, chapter filter mode, active theme, and fold layout mode.
- Provider-specific download concurrency and delay with optional per-source overrides and reset-to-global behavior.
- Max chapters per EPUB.
- TTS pitch, rate, and chunk size.
- TTS local voice selection with system-default fallback.
- Tabs management entry.
- Text cleanup management entry.
- JSON backup/import.
- Full backup/restore.
- Share/export intents use specific MIME types for JSON backups, ZIP full backups, and EPUB files.
- Android package-visibility queries are declared for browser links, EPUB open targets, and JSON/ZIP/EPUB share targets.
- Clear local storage.
- Theme selection for Obsidian, Midnight, Forest, and Classic Light, mapped to native dark/light rendering.
- Persisted fold layout mode (`auto`, `cover`, `inner`).

## Verification Evidence
- Native unit tests:
  - `TextCleanupTest` verifies nested text cleanup, regex cleanup, and script removal.
  - `TextCleanupTest` also verifies TTS-target cleanup and chunk splitting.
  - `TextCleanupTest` also verifies plain and formatted reader text extraction for copy/TTS, including headings, line breaks, and tables.
  - `TextCleanupTest` also verifies regex literal normalization, unsafe regex rejection, duplicate-rule detection, persisted rule sanitization, quick separator generation, and DOT_MATCHES_ALL flag cleanup.
  - `SentenceRemovalPlanningTest` verifies sentence trimming, blank/duplicate rejection, insertion order, index editing, and deletion.
  - `DefaultCleanupTest` verifies the default sentence-removal list matches the expected asset shape.
  - `ReaderContentRendererTest` verifies reader HTML wrapping, escaped titles, viewport metadata, font sizing, responsive image styling, and the undownloaded chapter fallback message.
  - `SourceProviderTest` verifies Royal Road and Scribble Hub metadata, chapter list, chapter ID, and chapter body parsing.
  - `SourceProviderTest` also verifies Royal Road `article:author` and `twitter:creator` metadata fallbacks.
  - `SourceProviderTest` also verifies Scribble Hub fallback story ID encoding.
  - `SourceProviderTest` also verifies chapter title cleanup for overflow markers, relative timestamps, and absolute dates.
  - `SourceUrlValidationTest` verifies import gating for supported story URLs and rejection of chapter/non-story URLs.
  - `BrowserUrlPlanningTest` verifies browser URL resolution and Google auth external-routing detection.
  - `TabPlanningTest` verifies tab creation, rename validation, delete reordering, and move ordering behavior.
  - `NetworkRequestsTest` verifies fetch/form request headers and Scribble Hub AJAX form body shape.
  - `ArchiveUtilsTest` verifies ZIP traversal protection and stored EPUB `mimetype` entry creation.
  - `ArchiveSnapshotPlanningTest` verifies full-story archive snapshots, copied downloaded chapter file paths, EPUB/pending-new cleanup, and source-removal archive metadata.
  - `EpubSelectionTest` verifies EPUB range selection, downloaded-only inclusion, original chapter numbering, and start-after-bookmark behavior.
  - `EpubSelectionTest` also verifies readable EPUB selector labels for regular paths and encoded Android-style URIs.
  - `EpubFilenameTest` verifies safe bounded EPUB filenames and chapter range suffix generation.
  - `DownloadSchedulerTest` verifies global concurrency, per-source concurrency, and per-source delay scheduling.
  - `DownloadQueuePlanningTest` verifies valid chapter queueing, terminal duplicate reset, active duplicate suppression, and no-op downloaded/invalid selections.
  - `DownloadQueueControlPlanningTest` verifies pause/resume/cancel/manual retry status transitions and failed-only manual retry behavior.
  - `DownloadQueueMaintenanceTest` verifies unsupported-source queue cleanup and stuck story status recovery.
  - `DownloadErrorClassifierTest` verifies download error classification and retry-delay policy.
  - `DownloadRangeSelectionTest` verifies explicit range, bookmark-forward range, count-based range, and invalid range handling with validation messages.
  - `StorySyncPlanningTest` verifies stable-ID chapter merge behavior, downloaded chapter preservation across slug changes, bookmark remapping/removal, pending-new chapter tracking, EPUB config range extension, and EPUB stale detection during sync.
  - `StoryActionGuardsTest` verifies archived snapshots cannot sync or queue downloads with appropriate guard messages.
  - `BackupMergePlanningTest` verifies JSON backup imports preserve local downloaded chapter files, reader progress, EPUB state, and pending-new chapter metadata.
  - `BackupMergePlanningTest` also verifies JSON backup imports scrub stale downloaded/file-path/EPUB state from new stories and newly imported chapters.
  - `BackupExportPlanningTest` verifies JSON/full-backup empty-library guards and the JSON backup 50 MB size limit.
  - `JsonBackupValidationTest` verifies JSON backup version, library, and story-ID validation.
  - `FullBackupPathsTest` verifies full-backup chapter path encoding and filename generation.
  - `FullBackupManifestValidationTest` verifies full-backup manifest validation errors and missing-manifest messaging before destructive restore.
  - `FullBackupRestorePlanningTest` verifies full-backup restore metadata scrubbing, chapter file re-linking, downloaded-count recomputation, and restore summary counts.
  - `EpubMetadataTest` verifies OPF description/subject/guide metadata, cover manifest metadata, NCX doctype, dtb metadata, and front matter navigation.
  - `EpubContentTest` verifies cover/details XHTML placeholders, escaped tags/descriptions, EPUB CSS structure, and full-HTML chapter content sanitization before XHTML embedding.
  - `StoryBookmarkPlanningTest` verifies bookmark toggling and EPUB range advancement when `startAfterBookmark` is enabled.
  - `LibraryQueryTest` verifies source-name filtering, source+tag intersections, default recency sorting, numeric score sorting, and source/tag filter label ordering.
  - `TtsSessionPlanningTest` verifies persisted TTS resume eligibility, current-settings chunk size restore, chunk-index bounds, and next-chapter continuation planning.
  - `TtsSessionPlanningTest` also verifies startup reader resume targets are only produced for eligible sessions whose story/chapter still exist.
  - `TtsNotificationActionsTest` verifies foreground TTS notifications show Pause during playback and Play mapped to session resume while paused.
  - `SettingsValidationTest` verifies settings bounds for download concurrency, delay, EPUB size, TTS pitch/rate, and TTS chunk size.
  - `PreferenceNormalizationTest` verifies invalid, high out-of-range, or legacy persisted preferences are normalized to bounded defaults on native read/write boundaries.
  - `FileMimeTypesTest` verifies MIME types used for JSON, ZIP, EPUB, and fallback file sharing.
  - `AndroidManifestIntegrationTest` verifies package-visibility queries for browser, EPUB open, and JSON/ZIP/EPUB share intents plus FileProvider files/cache coverage.
- Build command verified:
  - `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`
- Debug APK:
  - `android/app/build/outputs/apk/debug/app-debug.apk`

## Known Remaining Runtime Validation
- Full device/emulator flow validation is still required for long-running downloads, WebView navigation/import, Android file sharing targets, TTS audio behavior, and EPUB opening in third-party readers.
- This environment currently has no connected `adb` device, no configured AVD, no installed Android system images, and no `emulator` binary on PATH.
- The native implementation is local-first and feature-complete at the code/build level, but final completion should be based on emulator or physical-device QA across the mapped workflows.
