import { useState, useCallback, useMemo, useEffect } from "react";
import { useFocusEffect } from "expo-router";
import { storageService } from "../services/StorageService";
import { DownloadStatus, Story } from "../types";
import { sourceRegistry } from "../services/source/SourceRegistry";
import { downloadManager } from "../services/download/DownloadManager";
import { downloadQueue } from "../services/download/DownloadQueue";
import type { DownloadJob } from "../services/download/types";

export type SortOption =
  | "default"
  | "title"
  | "dateAdded"
  | "lastUpdated"
  | "totalChapters"
  | "score";
export type SortDirection = "asc" | "desc";

interface UseLibraryOptions {
  activeTabId?: string | null;
  hasCustomTabs?: boolean;
}

// Helper to check if a story belongs to the active tab
const matchesTab = (
  story: Story,
  activeTabId?: string | null,
  hasCustomTabs?: boolean,
): boolean => {
  if (hasCustomTabs) {
    if (activeTabId === "unassigned") {
      return !story.tabId;
    } else if (activeTabId) {
      return story.tabId === activeTabId;
    }
  }
  return true;
};

const withLiveDownloadProgress = (stories: Story[]): Story[] => {
  const completedJobs = downloadQueue
    .getAllJobs()
    .filter((job) => job.status === "completed");

  if (completedJobs.length === 0) {
    return stories;
  }

  const completedJobsByStoryId = completedJobs.reduce(
    (map, job) => {
      const jobs = map.get(job.storyId);
      if (jobs) {
        jobs.push(job);
      } else {
        map.set(job.storyId, [job]);
      }
      return map;
    },
    new Map<string, DownloadJob[]>(),
  );

  return stories.map((story) => {
    const completedStoryJobs = completedJobsByStoryId.get(story.id);
    if (!completedStoryJobs) {
      return story;
    }

    const chapters = [...story.chapters];
    completedStoryJobs.forEach((job) => {
      const chapterIndex = chapters.findIndex(
        (chapter) => chapter.id === job.chapter.id,
      );
      const chapter = chapters[chapterIndex];
      if (chapter) {
        chapters[chapterIndex] = {
          ...chapter,
          downloaded: true,
        };
      }
    });

    const downloadedChapters = chapters.filter((chapter) => chapter.downloaded)
      .length;

    if (downloadedChapters === story.downloadedChapters) {
      return story;
    }

    return {
      ...story,
      chapters,
      downloadedChapters,
      status:
        downloadedChapters === chapters.length
          ? DownloadStatus.Completed
          : DownloadStatus.Partial,
    };
  });
};

export const useLibrary = (options: UseLibraryOptions = {}) => {
  const { activeTabId, hasCustomTabs } = options;

  const [stories, setStories] = useState<Story[]>([]);
  const [searchQuery, setSearchQuery] = useState("");
  const [selectedTags, setSelectedTags] = useState<string[]>([]);
  const [refreshing, setRefreshing] = useState(false);

  // Sorting state
  const [sortOption, setSortOption] = useState<SortOption>("default");
  const [sortDirection, setSortDirection] = useState<SortDirection>("desc");

  const loadLibrary = useCallback(async (showRefreshing = true) => {
    try {
      if (showRefreshing) {
        setRefreshing(true);
      }
      const library = await storageService.getLibrary();
      // We'll handle sorting in the useMemo below, so we just set raw data here
      // But to keep initial state consistent or if useMemo is expensive, we could sort here.
      // However, dynamic sorting is requested.
      setStories(withLiveDownloadProgress(library));
    } catch (e) {
      console.error(e);
    } finally {
      if (showRefreshing) {
        setRefreshing(false);
      }
    }
  }, []);

  useFocusEffect(
    useCallback(() => {
      void loadLibrary();
    }, [loadLibrary]),
  );

  useEffect(() => {
    const updateLiveProgress = () => {
      setStories((currentStories) => withLiveDownloadProgress(currentStories));
    };
    const reloadAfterFlush = () => {
      void loadLibrary(false);
    };

    downloadManager.on("queue-updated", updateLiveProgress);
    downloadManager.on("job-completed", updateLiveProgress);
    downloadManager.on("all-complete", reloadAfterFlush);

    return () => {
      downloadManager.off("queue-updated", updateLiveProgress);
      downloadManager.off("job-completed", updateLiveProgress);
      downloadManager.off("all-complete", reloadAfterFlush);
    };
  }, [loadLibrary]);

  const onRefresh = useCallback(() => {
    void loadLibrary();
  }, [loadLibrary]);

  const { allTags, sourceNames, tagCounts } = useMemo(() => {
    const sourceCounts = new Map<string, number>();

    // Count sources from tab-filtered stories
    stories.forEach((story) => {
      if (!matchesTab(story, activeTabId, hasCustomTabs)) return;

      const providerName = sourceRegistry.getProvider(story.sourceUrl)?.name;
      if (providerName) {
        sourceCounts.set(
          providerName,
          (sourceCounts.get(providerName) ?? 0) + 1,
        );
      }
    });

    const sortedSources = Array.from(sourceCounts.entries())
      .sort((a, b) => {
        if (b[1] !== a[1]) return b[1] - a[1];
        return a[0].localeCompare(b[0]);
      })
      .map(([name]) => name);

    // Determine if a source is currently selected
    const activeSource = selectedTags.find((tag) =>
      sortedSources.includes(tag),
    );

    const tagCountsMap = new Map<string, number>();

    // Count tags from tab-filtered stories (respecting source filter)
    stories.forEach((story) => {
      if (!matchesTab(story, activeTabId, hasCustomTabs)) return;

      const providerName = sourceRegistry.getProvider(story.sourceUrl)?.name;
      if (activeSource && providerName !== activeSource) return;

      story.tags?.forEach((tag) => {
        tagCountsMap.set(tag, (tagCountsMap.get(tag) ?? 0) + 1);
      });
    });

    const sortedTags = Array.from(tagCountsMap.entries())
      .sort((a, b) => {
        if (b[1] !== a[1]) return b[1] - a[1];
        return a[0].localeCompare(b[0]);
      })
      .map(([name]) => name);

    // Merge source counts so UI can display them too
    sourceCounts.forEach((count, name) => {
      tagCountsMap.set(name, count);
    });

    return {
      allTags: [...sortedSources, ...sortedTags],
      sourceNames: sortedSources,
      tagCounts: tagCountsMap,
    };
  }, [stories, selectedTags, activeTabId, hasCustomTabs]);

  const toggleTag = (tag: string) => {
    setSelectedTags((prev) => {
      const isSourceTag = sourceNames.includes(tag);

      if (isSourceTag) {
        // If it's already selected, toggle it off
        if (prev.includes(tag)) {
          return prev.filter((t) => t !== tag);
        }

        // If adding a source tag, remove any other source tags from selection first
        const nonSourceTags = prev.filter((t) => !sourceNames.includes(t));
        return [...nonSourceTags, tag];
      }

      // Standard toggle for regular tags
      return prev.includes(tag)
        ? prev.filter((t) => t !== tag)
        : [...prev, tag];
    });
  };

  const filteredAndSortedStories = useMemo(() => {
    // First filter
    const filtered = stories.filter((story) => {
      // Tab filtering
      if (!matchesTab(story, activeTabId, hasCustomTabs)) return false;

      const matchesSearch =
        story.title.toLowerCase().includes(searchQuery.toLowerCase()) ||
        (story.author &&
          story.author.toLowerCase().includes(searchQuery.toLowerCase()));

      if (!matchesSearch) return false;

      if (selectedTags.length > 0) {
        const storyTags = story.tags || [];
        const storySourceName = sourceRegistry.getProvider(
          story.sourceUrl,
        )?.name;

        const hasAllTags = selectedTags.every((tag) => {
          // Check if the selected tag is actually a source name
          if (sourceNames.includes(tag)) {
            return storySourceName === tag;
          }
          return storyTags.includes(tag);
        });
        return hasAllTags;
      }

      return true;
    });

    // Then sort
    return filtered.sort((a, b) => {
      let comparison = 0;

      switch (sortOption) {
        case "title":
          comparison = a.title.localeCompare(b.title);
          break;
        case "dateAdded":
          comparison = (a.dateAdded || 0) - (b.dateAdded || 0);
          break;
        case "lastUpdated":
          comparison = (a.lastUpdated || 0) - (b.lastUpdated || 0);
          break;
        case "totalChapters":
          comparison = a.totalChapters - b.totalChapters;
          break;
        case "score":
          const parseScore = (s?: string) => {
            if (!s) return 0;
            const match = s.match(/(\d+\.?\d*)/);
            return match ? parseFloat(match[1]) : 0;
          };
          comparison = parseScore(a.score) - parseScore(b.score);
          break;
        case "default":
        default:
          // Default logic: Smart sort by recent activity (max of lastUpdated or dateAdded)
          const dateA = Math.max(a.lastUpdated || 0, a.dateAdded || 0);
          const dateB = Math.max(b.lastUpdated || 0, b.dateAdded || 0);
          comparison = dateA - dateB;
          break;
      }

      // Apply direction (Ascending is default for numbers in subtraction a-b, but we want Descending usually for dates)
      // Wait, standard sort:
      // a - b is Ascending (Small to Large)
      // b - a is Descending (Large to Small)

      // For strings (localeCompare): 'a'.localeCompare('b') is -1 (Ascending)

      // So 'comparison' above is calculated as Ascending (except default which we might want to verify).
      // Actually, for 'default', I calculated dateA - dateB.
      // If dateA (today) > dateB (yesterday), dateA - dateB > 0.
      // In Ascending sort, positive means b comes first? No.
      // sort((a,b) => a-b):
      // 2 - 1 = 1 (>0), so b (1) comes before a (2). Sorted: 1, 2. ASC.

      // So my calculations above are all ASCENDING.

      // If user wants ASC, we return comparison.
      // If user wants DESC, we return -comparison.

      return sortDirection === "asc" ? comparison : -comparison;
    });
  }, [
    stories,
    searchQuery,
    selectedTags,
    sortOption,
    sortDirection,
    sourceNames,
    activeTabId,
    hasCustomTabs,
  ]);

  const storiesByTabId = useMemo(() => {
    const map = new Map<string, Story[]>();
    if (!hasCustomTabs) return map;

    const applySearchAndTags = (story: Story) => {
      const matchesSearch =
        story.title.toLowerCase().includes(searchQuery.toLowerCase()) ||
        (story.author &&
          story.author.toLowerCase().includes(searchQuery.toLowerCase()));
      if (!matchesSearch) return false;

      if (selectedTags.length > 0) {
        const storyTags = story.tags || [];
        const storySourceName = sourceRegistry.getProvider(
          story.sourceUrl,
        )?.name;

        const hasAllTags = selectedTags.every((tag) => {
          if (sourceNames.includes(tag)) {
            return storySourceName === tag;
          }
          return storyTags.includes(tag);
        });
        return hasAllTags;
      }
      return true;
    };

    const filtered = stories.filter(applySearchAndTags);

    const applySort = (a: Story, b: Story) => {
      let comparison = 0;
      switch (sortOption) {
        case "title":
          comparison = a.title.localeCompare(b.title);
          break;
        case "dateAdded":
          comparison = (a.dateAdded || 0) - (b.dateAdded || 0);
          break;
        case "lastUpdated":
          comparison = (a.lastUpdated || 0) - (b.lastUpdated || 0);
          break;
        case "totalChapters":
          comparison = a.totalChapters - b.totalChapters;
          break;
        case "score": {
          const parseScore = (s?: string) => {
            if (!s) return 0;
            const match = s.match(/(\d+\.?\d*)/);
            return match ? parseFloat(match[1]) : 0;
          };
          comparison = parseScore(a.score) - parseScore(b.score);
          break;
        }
        case "default":
        default: {
          const dateA = Math.max(a.lastUpdated || 0, a.dateAdded || 0);
          const dateB = Math.max(b.lastUpdated || 0, b.dateAdded || 0);
          comparison = dateA - dateB;
          break;
        }
      }
      return sortDirection === "asc" ? comparison : -comparison;
    };

    const sorted = filtered.sort(applySort);

    for (const story of sorted) {
      const key = story.tabId || "unassigned";
      const arr = map.get(key);
      if (arr) {
        arr.push(story);
      } else {
        map.set(key, [story]);
      }
    }
    return map;
  }, [stories, hasCustomTabs, searchQuery, selectedTags, sortOption, sortDirection, sourceNames]);

  return {
    stories: filteredAndSortedStories,
    storiesByTabId,
    allStories: stories,
    loading: refreshing,
    refreshing,
    onRefresh,
    searchQuery,
    setSearchQuery,
    selectedTags,
    toggleTag,
    allTags,
    tagCounts,
    sortOption,
    setSortOption,
    sortDirection,
    setSortDirection,
  };
};
