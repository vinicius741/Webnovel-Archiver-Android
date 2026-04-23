import React from "react";
import { StyleSheet, View } from "react-native";

import { ChapterFilterMode, Story } from "../../types";
import { StoryHeader } from "./StoryHeader";
import { StoryActions } from "./StoryActions";
import { DownloadRangeDialog } from "./DownloadRangeDialog";
import { EpubConfigDialog } from "./EpubConfigDialog";
import { EpubSelectorDialog } from "./EpubSelectorDialog";
import { StoryTags } from "./StoryTags";
import { StoryDescription } from "./StoryDescription";
import { ChapterFilterMenu } from "./ChapterFilterMenu";

interface StoryDetailsInfoPanelProps {
  story: Story;
  downloading: boolean;
  syncing: boolean;
  generating: boolean;
  epubProgress: {
    current: number;
    total: number;
    percentage: number;
    stage: string;
    status: string;
  } | null;
  syncStatus?: string;
  downloadProgress: number;
  downloadStatus: string;
  showDownloadRange: boolean;
  showEpubConfig: boolean;
  showEpubSelector: boolean;
  availableEpubs: string[];
  filterMode: ChapterFilterMode;
  searchQuery: string;
  selectionMode: boolean;
  chapterCount: number;
  downloadedChapterCount: number;
  hasValidBookmark: boolean;
  bookmarkChapterNumber?: number;
  initialEpubConfig: {
    maxChaptersPerEpub: number;
    rangeStart: number;
    rangeEnd: number;
    startAfterBookmark: boolean;
  };
  onSync: () => void;
  onGenerate: () => void;
  onRead: () => void;
  onReadEpubAtPath: (path: string) => void;
  onDownloadAll: () => void;
  onViewDownloads: () => void;
  onDownloadRange: (start: number, end: number) => void;
  onSetShowDownloadRange: (visible: boolean) => void;
  onSetShowEpubConfig: (visible: boolean) => void;
  onSaveEpubConfig: (config: {
    maxChaptersPerEpub: number;
    rangeStart: number;
    rangeEnd: number;
    startAfterBookmark: boolean;
  }) => void | Promise<void>;
  onSetShowEpubSelector: (visible: boolean) => void;
  onFilterSelect: (mode: ChapterFilterMode) => void | Promise<void>;
  onSearchChange: (value: string) => void;
  onToggleSelectionMode: () => void;
  isTwoPane?: boolean;
  widthClass?: "compact" | "medium" | "expanded";
}

export const StoryDetailsInfoPanel: React.FC<StoryDetailsInfoPanelProps> = ({
  story,
  downloading,
  syncing,
  generating,
  epubProgress,
  syncStatus,
  downloadProgress,
  downloadStatus,
  showDownloadRange,
  showEpubConfig,
  showEpubSelector,
  availableEpubs,
  filterMode,
  searchQuery,
  selectionMode,
  chapterCount,
  downloadedChapterCount,
  hasValidBookmark,
  bookmarkChapterNumber,
  initialEpubConfig,
  onSync,
  onGenerate,
  onRead,
  onReadEpubAtPath,
  onDownloadAll,
  onViewDownloads,
  onDownloadRange,
  onSetShowDownloadRange,
  onSetShowEpubConfig,
  onSaveEpubConfig,
  onSetShowEpubSelector,
  onFilterSelect,
  onSearchChange,
  onToggleSelectionMode,
  isTwoPane = false,
  widthClass = "compact",
}) => {
  const stackedControls = widthClass === "compact";
  const contentAlign = isTwoPane ? "start" : "center";

  return (
    <View style={styles.container}>
      <StoryHeader story={story} align={contentAlign} />

      <StoryActions
        story={story}
        downloading={downloading}
        syncing={syncing}
        generating={generating}
        epubProgress={epubProgress}
        syncStatus={syncStatus}
        downloadProgress={downloadProgress}
        downloadStatus={downloadStatus}
        onSync={onSync}
        onGenerate={onGenerate}
        onRead={onRead}
        onDownloadAll={onDownloadAll}
        onViewDownloads={onViewDownloads}
        stacked={stackedControls}
      />

      <DownloadRangeDialog
        visible={showDownloadRange}
        onDismiss={() => onSetShowDownloadRange(false)}
        onDownload={onDownloadRange}
        totalChapters={story.totalChapters}
        hasBookmark={hasValidBookmark}
        bookmarkChapterNumber={bookmarkChapterNumber}
      />

      <EpubConfigDialog
        visible={showEpubConfig}
        onDismiss={() => onSetShowEpubConfig(false)}
        onSave={onSaveEpubConfig}
        initialConfig={initialEpubConfig}
        totalChapters={chapterCount}
        downloadedChapterCount={downloadedChapterCount}
        hasBookmark={hasValidBookmark}
      />

      <EpubSelectorDialog
        visible={showEpubSelector}
        onDismiss={() => onSetShowEpubSelector(false)}
        onSelect={onReadEpubAtPath}
        epubs={availableEpubs}
      />

      <StoryTags tags={story.tags} align={contentAlign} />
      <StoryDescription description={story.description} align={contentAlign} />

      <View style={[styles.filterContainer, isTwoPane && styles.filterContainerTwoPane]}>
        <ChapterFilterMenu
          filterMode={filterMode}
          hasBookmark={!!story.lastReadChapterId}
          onFilterSelect={onFilterSelect}
          searchQuery={searchQuery}
          onSearchChange={onSearchChange}
          selectionMode={selectionMode}
          onToggleSelectionMode={onToggleSelectionMode}
          selectionDisabled={!!story.isArchived}
          stacked={stackedControls}
        />
      </View>
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    width: "100%",
  },
  filterContainer: {
    margin: 16,
    marginBottom: 8,
  },
  filterContainerTwoPane: {
    marginHorizontal: 0,
  },
});
