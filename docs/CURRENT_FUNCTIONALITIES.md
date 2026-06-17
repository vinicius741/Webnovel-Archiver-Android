# Current Functionalities

> **Note:** This document originally described the Python CLI application that preceded the React Native app. Both have been superseded by the native Kotlin implementation. See `documentation/NATIVE_FUNCTIONALITY_MAP.md` for the current feature map.

---

The Webnovel Archiver Android app is a **native Kotlin** application that downloads, processes, and archives webnovels from supported source sites.

## 1. Core Archiving Engine

### Current Functionality
- **Source Support:** RoyalRoad and Scribble Hub via `SourceProvider` interface and `SourceRegistry`.
- **Download Process:**
  - Fetches story metadata (title, author, cover image, synopsis, tags, score).
  - Fetches the list of chapters with pagination support.
  - Downloads chapter content via `DownloadEngine` with concurrency control.
  - **Resumable:** Tracks progress in download queue. Skips already downloaded chapters.
- **Content Processing:**
  - **HTML Cleaning:** Removes scripts, ads, and platform-specific clutter using Jsoup.
  - **Sentence Removal:** Optionally removes specific sentences based on configurable rules.
  - **Regex Cleanup:** Configurable regex rules targeting download, TTS, or both.
- **Data Organization:**
  - **Storage:** File-based JSON via Gson under `context.filesDir`.
  - **Story metadata:** `stories/{storyId}.json`
  - **Chapter files:** `novels/{storyId}/{0000}_{chapterId}.html`

## 2. EPUB Generation

### Current Functionality
- **Engine:** `EpubEngine` using `java.util.zip` with custom XML generation.
- **Features:**
  - EPUB 2.0 compliant output.
  - Volume splitting by configurable max chapters (10–1000).
  - Configurable chapter range and start-after-bookmark.
  - Cover image embedding with media type detection.
  - OPF metadata with title, author, description, tags, cover.
  - NCX navigation with front matter and chapter entries.

## 3. Backup & Restore

### Current Functionality
- **JSON Metadata Backup:** Exports library metadata (scrubbed of local paths/content). Imports merge with existing data, preserving local downloads and reader progress.
- **Full ZIP Backup:** Includes chapter files, settings, tabs, cleanup rules, TTS session, and library. Restore validates manifest before clearing local data.
- **Validation:** Backup version, library shape, story IDs, and chapter file index are validated before any destructive operations.

## 4. Library & Organization

### Current Functionality
- **Story List:** Title, author, tags, status, downloaded/total counts, archive marker, cover thumbnails.
- **Tabs:** Create, rename, reorder, delete. Stories move to Unassigned on tab deletion. With two or more tabs, swipe horizontally between them (ViewPager2) with two-way tab-bar sync; selection persists and survives restarts.
- **Search & Filter:** Search by title/author, filter by tag and source, sort by recency/title/date/updated/chapters/score.
- **Archive Snapshots:** Read-only snapshots created automatically when source sync detects removed chapters.
- **Bulk Operations:** Multi-select with move-to-tab and delete actions.

## 5. Reader & TTS

### Current Functionality
- **Reader:** WebView-based chapter reader with responsive styling, image scaling, previous/next navigation, copy text, and mark-read.
- **TTS:** Android `TextToSpeech` with paragraph chunking, pitch/rate/voice settings, pause/resume/skip controls, session persistence, auto-resume on restart, and foreground service with notification controls.
- **Last-Read Tracking:** Persists and displays the last-read chapter position.

## 6. Settings

### Current Functionality
- Download concurrency (1–10) and delay (0+).
- Per-source concurrency and delay overrides.
- Max chapters per EPUB (10–1000).
- TTS pitch, rate, chunk size, and voice selection.
- Theme selection (Obsidian, Midnight, Forest, Classic Light).
- Text cleanup management (sentence removal + regex rules).
- Backup export/import.
- Clear local storage.
