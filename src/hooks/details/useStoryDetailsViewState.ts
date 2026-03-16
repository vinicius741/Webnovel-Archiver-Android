import { useCallback, useEffect, useMemo, useState } from "react";

import { storageService } from "../../services/StorageService";
import type { ChapterFilterMode, EpubConfig, Story } from "../../types";

interface UseStoryDetailsViewStateParams {
  story: Story | null;
  showAlert: (title: string, message?: string) => void;
  downloadChaptersByIds: (chapterIds: string[]) => Promise<void>;
}

export const useStoryDetailsViewState = ({
  story,
  showAlert,
  downloadChaptersByIds,
}: UseStoryDetailsViewStateParams) => {
  const [searchQuery, setSearchQuery] = useState("");
  const [showDownloadRange, setShowDownloadRange] = useState(false);
  const [showEpubConfig, setShowEpubConfig] = useState(false);
  const [filterMode, setFilterMode] = useState<ChapterFilterMode>("all");
  const [defaultMaxChapters, setDefaultMaxChapters] = useState(150);
  const [selectionMode, setSelectionMode] = useState(false);
  const [selectedChapterIds, setSelectedChapterIds] = useState<Set<string>>(
    new Set(),
  );

  useEffect(() => {
    let mounted = true;

    const loadSettings = async () => {
      try {
        const [filterSettings, appSettings] = await Promise.all([
          storageService.getChapterFilterSettings(),
          storageService.getSettings(),
        ]);

        if (!mounted) return;
        setFilterMode(filterSettings.filterMode);
        setDefaultMaxChapters(appSettings.maxChaptersPerEpub || 150);
      } catch (error) {
        console.error("Failed to load details settings", error);
      }
    };

    void loadSettings();
    return () => {
      mounted = false;
    };
  }, []);

  const filteredChapters = useMemo(() => {
    if (!story) return [];

    const normalizedSearch = searchQuery.toLowerCase();
    const bookmarkIndex =
      story.lastReadChapterId && filterMode === "hideAboveBookmark"
        ? story.chapters.findIndex((ch) => ch.id === story.lastReadChapterId)
        : -1;

    return story.chapters.filter((chapter, index) => {
      const matchesSearch = chapter.title
        .toLowerCase()
        .includes(normalizedSearch);
      if (!matchesSearch) return false;

      if (selectionMode && chapter.downloaded) {
        return false;
      }

      switch (filterMode) {
        case "hideNonDownloaded":
          return chapter.downloaded === true;
        case "hideAboveBookmark":
          return bookmarkIndex !== -1 ? index >= bookmarkIndex : true;
        default:
          return true;
      }
    });
  }, [story, searchQuery, filterMode, selectionMode]);

  const chapterCount = story?.chapters.length ?? 0;
  const downloadedChapterCount =
    story?.chapters.filter((chapter) => chapter.downloaded === true).length ??
    0;
  const hasValidBookmark = Boolean(
    story?.lastReadChapterId &&
      story.chapters.some((chapter) => chapter.id === story.lastReadChapterId),
  );

  const initialEpubConfig = useMemo<EpubConfig | null>(() => {
    if (!story) return null;

    return {
      maxChaptersPerEpub:
        story.epubConfig?.maxChaptersPerEpub ?? defaultMaxChapters,
      rangeStart: story.epubConfig?.rangeStart ?? 1,
      rangeEnd: story.epubConfig?.rangeEnd ?? chapterCount,
      startAfterBookmark: story.epubConfig?.startAfterBookmark ?? false,
    };
  }, [story, defaultMaxChapters, chapterCount]);

  const handleFilterSelect = useCallback(async (mode: ChapterFilterMode) => {
    setFilterMode(mode);
    await storageService.saveChapterFilterSettings({ filterMode: mode });
  }, []);

  const handleToggleSelectionMode = useCallback(() => {
    setSelectionMode((prev) => !prev);
    setSelectedChapterIds(new Set());
  }, []);

  const handleToggleChapter = useCallback((chapterId: string) => {
    setSelectedChapterIds((prev) => {
      const next = new Set(prev);
      if (next.has(chapterId)) {
        next.delete(chapterId);
      } else {
        next.add(chapterId);
      }
      return next;
    });
  }, []);

  const handleDownloadSelected = useCallback(async () => {
    if (!story) return;

    const idsToDownload = Array.from(selectedChapterIds).filter((id) => {
      const chapter = story.chapters.find((ch) => ch.id === id);
      return chapter && !chapter.downloaded;
    });

    if (idsToDownload.length === 0) {
      showAlert(
        "No Chapters to Download",
        "All selected chapters are already downloaded.",
      );
      return;
    }

    await downloadChaptersByIds(idsToDownload);
    setSelectionMode(false);
    setSelectedChapterIds(new Set());
  }, [story, selectedChapterIds, showAlert, downloadChaptersByIds]);

  return {
    searchQuery,
    setSearchQuery,
    showDownloadRange,
    setShowDownloadRange,
    showEpubConfig,
    setShowEpubConfig,
    filterMode,
    selectionMode,
    selectedChapterIds,
    filteredChapters,
    chapterCount,
    downloadedChapterCount,
    hasValidBookmark,
    initialEpubConfig,
    handleFilterSelect,
    handleToggleSelectionMode,
    handleToggleChapter,
    handleDownloadSelected,
  };
};
