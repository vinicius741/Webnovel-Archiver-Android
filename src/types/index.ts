export interface Chapter {
  id: string;
  title: string;
  url: string;
  content?: string; // HTML content, loaded only when needed? Or maybe path to file?
  // Ideally content is stored in file, not here. Here we track metadata.
  filePath?: string; // Path to local HTML file
  downloaded?: boolean;
}

export const DownloadStatus = {
  Idle: "idle",
  Downloading: "downloading",
  Completed: "completed",
  Failed: "failed",
  Paused: "paused",
  Partial: "partial",
} as const;

export type DownloadStatus =
  (typeof DownloadStatus)[keyof typeof DownloadStatus];

export interface EpubConfig {
  maxChaptersPerEpub: number;
  rangeStart: number; // 1-based inclusive
  rangeEnd: number; // 1-based inclusive
  startAfterBookmark: boolean;
}

export type ArchiveReason = "source_chapters_removed";

export interface Story {
  id: string;
  title: string;
  author: string;
  coverUrl?: string;
  description?: string;
  sourceUrl: string;
  status: DownloadStatus;
  totalChapters: number;
  downloadedChapters: number;
  chapters: Chapter[];
  lastUpdated?: number; // timestamp
  dateAdded?: number; // timestamp for initial addition
  epubPath?: string; // Local URI to generated EPUB (deprecated, use epubPaths)
  epubPaths?: string[]; // Local URIs to generated EPUBs (supports split files)
  epubStale?: boolean; // True when EPUB needs regeneration
  epubConfig?: EpubConfig; // Per-story EPUB generation configuration
  pendingNewChapterIds?: string[]; // Chapter IDs discovered on sync but not downloaded yet
  tags?: string[];
  lastReadChapterId?: string;
  score?: string;
  tabId?: string | null; // ID of the tab this story belongs to, or null for unassigned
  isArchived?: boolean;
  archiveOfStoryId?: string;
  archivedAt?: number;
  archiveReason?: ArchiveReason;
}

export type RegexCleanupAppliesTo = "download" | "tts" | "both";

export interface RegexCleanupRule {
  id: string;
  name: string;
  pattern: string;
  flags: string;
  enabled: boolean;
  appliesTo: RegexCleanupAppliesTo;
}

export interface AppSettings {
  downloadConcurrency: number;
  downloadDelay: number;
  maxChaptersPerEpub: number;
}

export interface SourceDownloadSettings {
  concurrency: number;
  delay: number;
}

export type SourceDownloadSettingsMap = Record<string, SourceDownloadSettings>;

export type FoldLayoutMode = "auto" | "cover" | "inner";

export interface TTSSettings {
  pitch: number;
  rate: number;
  voiceIdentifier?: string;
  chunkSize: number;
}

export interface TTSSession {
  storyId: string;
  chapterId: string;
  chapterTitle: string;
  currentChunkIndex: number;
  isPaused: boolean;
  wasPlaying: boolean;
  chunkSize: number;
  voiceIdentifier?: string;
  rate: number;
  pitch: number;
  updatedAt: number;
  sessionVersion: number;
}

export type DownloadRangeMode = "range" | "bookmark" | "count";

export type ChapterFilterMode =
  | "all"
  | "hideNonDownloaded"
  | "hideAboveBookmark";

export interface ChapterFilterSettings {
  filterMode: ChapterFilterMode;
}
