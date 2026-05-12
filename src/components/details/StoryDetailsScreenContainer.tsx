import React from "react";
import { ScrollView, StyleSheet, View } from "react-native";
import { Stack, useRouter } from "expo-router";
import {
  ActivityIndicator,
  FAB,
  List,
  Text,
  useTheme,
} from "react-native-paper";

import { useAppAlert } from "../../context/AlertContext";
import { useScreenLayout } from "../../hooks/useScreenLayout";
import { useStoryDetails } from "../../hooks/useStoryDetails";
import { useStoryDetailsViewState } from "../../hooks/details/useStoryDetailsViewState";
import { EpubConfig } from "../../types";
import { ScreenContainer } from "../ScreenContainer";
import { AppButton } from "../theme/AppButton";
import { StoryMenu } from "./StoryMenu";
import { StoryDetailsChaptersList } from "./StoryDetailsChaptersList";
import { StoryDetailsInfoPanel } from "./StoryDetailsInfoPanel";

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
      downloading={downloading}
      syncing={syncing}
      generating={generating}
      epubProgress={epubProgress}
      syncStatus={syncStatus}
      downloadProgress={downloadProgress}
      downloadStatus={downloadStatus}
      showDownloadRange={showDownloadRange}
      showEpubConfig={showEpubConfig}
      showEpubSelector={showEpubSelector}
      availableEpubs={availableEpubs}
      filterMode={filterMode}
      searchQuery={searchQuery}
      selectionMode={selectionMode}
      chapterCount={chapterCount}
      downloadedChapterCount={downloadedChapterCount}
      hasValidBookmark={hasValidBookmark}
      bookmarkChapterNumber={bookmarkChapterNumber}
      initialEpubConfig={initialEpubConfig}
      onSync={syncChapters}
      onGenerate={generateEpub}
      onRead={readEpub}
      onReadEpubAtPath={readEpubAtPath}
      onDownloadAll={downloadAll}
      onViewDownloads={() => router.push("/download-manager")}
      onDownloadRange={downloadRange}
      onSetShowDownloadRange={setShowDownloadRange}
      onSetShowEpubConfig={setShowEpubConfig}
      onSaveEpubConfig={handleSaveEpubConfig}
      onSetShowEpubSelector={setShowEpubSelector}
      onFilterSelect={handleFilterSelect}
      onSearchChange={setSearchQuery}
      onToggleSelectionMode={handleToggleSelectionMode}
      isTwoPane={isTwoPane}
      widthClass={isNarrowTwoPane ? "compact" : widthClass}
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

      {isTwoPane ? (
        <View style={styles.twoPaneShell}>
          <View
            style={[
              styles.twoPaneContainer,
              isCompactHeight && styles.twoPaneContainerCompactHeight,
              isNarrowTwoPane && styles.twoPaneContainerNarrow,
            ]}
          >
            <View style={[styles.leftColumn, isNarrowTwoPane && styles.leftColumnNarrow]}>
              <ScrollView contentContainerStyle={styles.leftColumnContent}>
                {infoPanel}
              </ScrollView>
            </View>

            <View style={styles.rightColumn}>
              <List.Section style={styles.chapterSection} title="Chapters">
                <StoryDetailsChaptersList
                  story={story}
                  chapters={filteredChapters}
                  selectionMode={selectionMode}
                  selectedChapterIds={selectedChapterIds}
                  onOpenChapter={handleOpenChapter}
                  onMarkChapterAsRead={markChapterAsRead}
                  onToggleChapter={handleToggleChapter}
                  contentContainerStyle={styles.chapterListContent}
                />
              </List.Section>
            </View>
          </View>
        </View>
      ) : (
        <StoryDetailsChaptersList
          story={story}
          chapters={filteredChapters}
          selectionMode={selectionMode}
          selectedChapterIds={selectedChapterIds}
          onOpenChapter={handleOpenChapter}
          onMarkChapterAsRead={markChapterAsRead}
          onToggleChapter={handleToggleChapter}
          contentContainerStyle={styles.content}
          listHeaderComponent={
            <View style={styles.compactInfoPanel}>
              {infoPanel}
            </View>
          }
          listHeaderComponentStyle={styles.listHeader}
        />
      )}

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
  content: {
    padding: 16,
    paddingTop: 20,
    flexGrow: 1,
    width: "100%",
    maxWidth: 840,
    alignSelf: "center",
    paddingBottom: 104,
  },
  compactInfoPanel: {
    width: "100%",
  },
  listHeader: {
    marginBottom: 16,
  },
  twoPaneShell: {
    flex: 1,
    width: "100%",
    maxWidth: 1280,
    alignSelf: "center",
    paddingHorizontal: 16,
    paddingBottom: 16,
  },
  twoPaneContainer: {
    flex: 1,
    flexDirection: "row",
    gap: 32,
  },
  twoPaneContainerCompactHeight: {
    gap: 24,
  },
  twoPaneContainerNarrow: {
    gap: 12,
  },
  leftColumn: {
    flexBasis: 360,
    flexGrow: 0,
    flexShrink: 1,
    minWidth: 280,
    maxWidth: 440,
    alignItems: "stretch",
  },
  leftColumnNarrow: {
    flexBasis: 168,
    minWidth: 150,
    maxWidth: 190,
  },
  leftColumnContent: {
    paddingTop: 16,
    paddingBottom: 120,
  },
  rightColumn: {
    flex: 1,
    minWidth: 0,
  },
  chapterSection: {
    flex: 1,
    marginTop: 0,
  },
  chapterListContent: {
    paddingBottom: 120,
  },
  fab: {
    position: "absolute",
    margin: 24,
    right: 8,
    bottom: 24,
  },
});
