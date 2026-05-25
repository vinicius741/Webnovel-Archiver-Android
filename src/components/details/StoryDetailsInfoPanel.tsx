import React from "react";
import { StyleSheet, View } from "react-native";

import { ChapterFilterMode, EpubConfig, Story } from "../../types";
import { StoryHeader } from "./StoryHeader";
import { StoryActions } from "./StoryActions";
import { DownloadRangeDialog } from "./DownloadRangeDialog";
import { EpubConfigDialog } from "./EpubConfigDialog";
import { EpubSelectorDialog } from "./EpubSelectorDialog";
import { StoryTags } from "./StoryTags";
import { StoryDescription } from "./StoryDescription";
import { ChapterFilterMenu } from "./ChapterFilterMenu";

export interface StoryStatus {
  downloading: boolean;
  syncing: boolean;
  generating: boolean;
  opening?: boolean;
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
}

export interface StoryDialogState {
  showDownloadRange: boolean;
  showEpubConfig: boolean;
  showEpubSelector: boolean;
}

export interface StoryEpubState {
  availableEpubs: string[];
  initialConfig: EpubConfig;
}

export interface StoryChapterFilter {
  mode: ChapterFilterMode;
  searchQuery: string;
  selectionMode: boolean;
  chapterCount: number;
  downloadedChapterCount: number;
}

export interface StoryBookmarkInfo {
  isValid: boolean;
  chapterNumber?: number;
}

export interface StoryDetailsHandlers {
  onSync: () => void;
  onGenerate: () => void;
  onRead: () => void;
  onReadEpubAtPath: (path: string) => void;
  onDownloadAll: () => void;
  onViewDownloads: () => void;
  onDownloadRange: (start: number, end: number) => void;
  onSetShowDownloadRange: (visible: boolean) => void;
  onSetShowEpubConfig: (visible: boolean) => void;
  onSaveEpubConfig: (config: EpubConfig) => void | Promise<void>;
  onSetShowEpubSelector: (visible: boolean) => void;
  onFilterSelect: (mode: ChapterFilterMode) => void | Promise<void>;
  onSearchChange: (value: string) => void;
  onToggleSelectionMode: () => void;
}

export interface StoryLayoutConfig {
  isTwoPane?: boolean;
  widthClass?: "compact" | "medium" | "expanded";
}

interface StoryDetailsInfoPanelProps {
  story: Story;
  status: StoryStatus;
  dialogs: StoryDialogState;
  epub: StoryEpubState;
  chapterFilter: StoryChapterFilter;
  bookmark: StoryBookmarkInfo;
  handlers: StoryDetailsHandlers;
  layout?: StoryLayoutConfig;
}

export const StoryDetailsInfoPanel: React.FC<StoryDetailsInfoPanelProps> = ({
  story,
  status,
  dialogs,
  epub,
  chapterFilter,
  bookmark,
  handlers,
  layout = {},
}) => {
  const { isTwoPane = false, widthClass = "compact" } = layout;
  const stackedControls = isTwoPane || widthClass === "compact";
  const contentAlign = "center";

  return (
    <View style={styles.container}>
      <StoryHeader story={story} align={contentAlign} />

      <StoryActions
        story={story}
        downloading={status.downloading}
        syncing={status.syncing}
        generating={status.generating}
        opening={status.opening}
        epubProgress={status.epubProgress}
        syncStatus={status.syncStatus}
        downloadProgress={status.downloadProgress}
        downloadStatus={status.downloadStatus}
        onSync={handlers.onSync}
        onGenerate={handlers.onGenerate}
        onRead={handlers.onRead}
        onDownloadAll={handlers.onDownloadAll}
        onViewDownloads={handlers.onViewDownloads}
        stacked={stackedControls}
      />

      <DownloadRangeDialog
        visible={dialogs.showDownloadRange}
        onDismiss={() => handlers.onSetShowDownloadRange(false)}
        onDownload={handlers.onDownloadRange}
        totalChapters={story.totalChapters}
        hasBookmark={bookmark.isValid}
        bookmarkChapterNumber={bookmark.chapterNumber}
      />

      <EpubConfigDialog
        visible={dialogs.showEpubConfig}
        onDismiss={() => handlers.onSetShowEpubConfig(false)}
        onSave={handlers.onSaveEpubConfig}
        initialConfig={epub.initialConfig}
        totalChapters={chapterFilter.chapterCount}
        downloadedChapterCount={chapterFilter.downloadedChapterCount}
        hasBookmark={bookmark.isValid}
      />

      <EpubSelectorDialog
        visible={dialogs.showEpubSelector}
        onDismiss={() => handlers.onSetShowEpubSelector(false)}
        onSelect={handlers.onReadEpubAtPath}
        epubs={epub.availableEpubs}
      />

      <StoryTags tags={story.tags} align={contentAlign} />
      <StoryDescription description={story.description} align={contentAlign} />

      <View style={[styles.filterContainer, isTwoPane && styles.filterContainerTwoPane]}>
        <ChapterFilterMenu
          filterMode={chapterFilter.mode}
          hasBookmark={!!story.lastReadChapterId}
          onFilterSelect={handlers.onFilterSelect}
          searchQuery={chapterFilter.searchQuery}
          onSearchChange={handlers.onSearchChange}
          selectionMode={chapterFilter.selectionMode}
          onToggleSelectionMode={handlers.onToggleSelectionMode}
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
