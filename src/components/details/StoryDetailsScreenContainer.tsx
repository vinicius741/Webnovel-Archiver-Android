import React from "react";
import { StyleSheet, View } from "react-native";
import { Stack, useRouter } from "expo-router";
import {
  ActivityIndicator,
  FAB,
  Text,
  useTheme,
} from "react-native-paper";

import { useAppAlert } from "../../context/AlertContext";
import { useScreenLayout } from "../../hooks/common/useScreenLayout";
import { useStoryDetails } from "../../hooks/details/useStoryDetails";
import { useStoryDetailsViewState } from "../../hooks/details/useStoryDetailsViewState";
import { EpubConfig } from "../../types";
import { ScreenContainer } from "../common/ScreenContainer";
import { AppButton } from "../theme/AppButton";
import { StoryMenu } from "./StoryMenu";
import { StoryDetailsLayout } from "./StoryDetailsLayout";
import {
  StoryDetailsInfoPanel,
  StoryStatus,
  StoryDialogState,
  StoryEpubState,
  StoryChapterFilter,
  StoryBookmarkInfo,
  StoryDetailsHandlers,
} from "./StoryDetailsInfoPanel";

interface StoryDetailsScreenContainerProps {
  id: string | string[] | undefined;
}

type AdaptiveLayout = ReturnType<typeof useScreenLayout> & {
  widthClass?: "compact" | "medium" | "expanded";
  heightClass?: "compact" | "medium" | "expanded";
  isTwoPane?: boolean;
  isCompactHeight?: boolean;
};

export const StoryDetailsScreenContainer: React.FC<
  StoryDetailsScreenContainerProps
> = ({ id }) => {
  const router = useRouter();
  const theme = useTheme();
  const screenLayout = useScreenLayout() as AdaptiveLayout;
  const { screenWidth, screenHeight } = screenLayout;
  const widthClass =
    screenLayout.widthClass ??
    (screenWidth >= 840 ? "expanded" : screenWidth >= 600 ? "medium" : "compact");
  const heightClass =
    screenLayout.heightClass ??
    (screenHeight >= 900 ? "expanded" : screenHeight >= 480 ? "medium" : "compact");
  const isTwoPane = screenLayout.isTwoPane ?? widthClass !== "compact";
  const isCompactHeight =
    screenLayout.isCompactHeight ?? heightClass === "compact";
  const isNarrowTwoPane = isTwoPane && screenWidth < 520;
  const { showAlert } = useAppAlert();

  const {
    story,
    loading,
    downloading,
    syncing,
    generating,
    opening,
    epubProgress,
    syncStatus,
    downloadProgress,
    downloadStatus,
    deleteStory,
    markChapterAsRead,
    syncChapters,
    generateEpub,
    readEpub,
    readEpubAtPath,
    downloadAll,
    downloadRange,
    applySentenceRemoval,
    updateStory,
    showEpubSelector,
    setShowEpubSelector,
    availableEpubs,
    downloadChaptersByIds,
  } = useStoryDetails(id);

  const {
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
    bookmarkChapterNumber,
    initialEpubConfig,
    handleFilterSelect,
    handleToggleSelectionMode,
    handleToggleChapter,
    handleDownloadSelected,
  } = useStoryDetailsViewState({
    story,
    showAlert,
    downloadChaptersByIds,
  });

  if (loading) {
    return (
      <ScreenContainer>
        <View style={styles.center}>
          <ActivityIndicator size="large" />
        </View>
      </ScreenContainer>
    );
  }

  if (!story) {
    return (
      <ScreenContainer>
        <View style={styles.center}>
          <Text variant="headlineSmall">Story not found</Text>
          <AppButton onPress={() => router.back()}>Go Back</AppButton>
        </View>
      </ScreenContainer>
    );
  }

  if (!initialEpubConfig) {
    return null;
  }

  const handleSaveEpubConfig = async (config: EpubConfig) => {
    const updatedStory = {
      ...story,
      epubConfig: config,
    };
    await updateStory(updatedStory);
  };

  const handleOpenChapter = (chapterId: string) => {
    router.push(`/reader/${story.id}/${encodeURIComponent(chapterId)}`);
  };

  const infoPanel = (
    <StoryDetailsInfoPanel
      story={story}
      status={{
        downloading,
        syncing,
        generating,
        opening,
        epubProgress,
        syncStatus,
        downloadProgress,
        downloadStatus,
      } satisfies StoryStatus}
      dialogs={{
        showDownloadRange,
        showEpubConfig,
        showEpubSelector,
      } satisfies StoryDialogState}
      epub={{
        availableEpubs,
        initialConfig: initialEpubConfig,
      } satisfies StoryEpubState}
      chapterFilter={{
        mode: filterMode,
        searchQuery,
        selectionMode,
        chapterCount,
        downloadedChapterCount,
      } satisfies StoryChapterFilter}
      bookmark={{
        isValid: hasValidBookmark,
        chapterNumber: bookmarkChapterNumber,
      } satisfies StoryBookmarkInfo}
      handlers={{
        onSync: syncChapters,
        onGenerate: generateEpub,
        onRead: readEpub,
        onReadEpubAtPath: readEpubAtPath,
        onDownloadAll: downloadAll,
        onViewDownloads: () => router.push("/download-manager"),
        onDownloadRange: downloadRange,
        onSetShowDownloadRange: setShowDownloadRange,
        onSetShowEpubConfig: setShowEpubConfig,
        onSaveEpubConfig: handleSaveEpubConfig,
        onSetShowEpubSelector: setShowEpubSelector,
        onFilterSelect: handleFilterSelect,
        onSearchChange: setSearchQuery,
        onToggleSelectionMode: handleToggleSelectionMode,
      } satisfies StoryDetailsHandlers}
      layout={{
        isTwoPane,
        widthClass: isNarrowTwoPane ? "compact" : widthClass,
      }}
    />
  );

  return (
    <ScreenContainer edges={["bottom", "left", "right"]}>
      <Stack.Screen
        options={{
          title: story.title || "Details",
          headerRight: () => (
            <StoryMenu
              onDownloadRange={() => setShowDownloadRange(true)}
              onConfigureEpub={() => setShowEpubConfig(true)}
              onApplySentenceRemoval={applySentenceRemoval}
              onDelete={deleteStory}
              disabled={loading}
              isArchived={story.isArchived}
            />
          ),
        }}
      />

      <StoryDetailsLayout
        story={story}
        chapters={filteredChapters}
        selectionMode={selectionMode}
        selectedChapterIds={selectedChapterIds}
        infoPanel={infoPanel}
        isTwoPane={isTwoPane}
        isCompactHeight={isCompactHeight}
        isNarrowTwoPane={isNarrowTwoPane}
        onOpenChapter={handleOpenChapter}
        onMarkChapterAsRead={markChapterAsRead}
        onToggleChapter={handleToggleChapter}
      />

      {selectionMode && selectedChapterIds.size > 0 && !story.isArchived && (
        <FAB
          icon="download"
          label={`Download (${selectedChapterIds.size})`}
          onPress={handleDownloadSelected}
          style={[styles.fab, { backgroundColor: theme.colors.primary }]}
          color={theme.colors.onPrimary}
        />
      )}
    </ScreenContainer>
  );
};

const styles = StyleSheet.create({
  center: {
    flex: 1,
    justifyContent: "center",
    alignItems: "center",
  },
  fab: {
    position: "absolute",
    margin: 24,
    right: 8,
    bottom: 24,
  },
});
