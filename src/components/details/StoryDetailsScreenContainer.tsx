import React from "react";
import { ScrollView, StyleSheet, View } from "react-native";
import { Stack, useRouter } from "expo-router";
import {
  ActivityIndicator,
  Button,
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
import { StoryMenu } from "./StoryMenu";
import { StoryDetailsChaptersList } from "./StoryDetailsChaptersList";
import { StoryDetailsInfoPanel } from "./StoryDetailsInfoPanel";

interface StoryDetailsScreenContainerProps {
  id: string | string[] | undefined;
}

export const StoryDetailsScreenContainer: React.FC<
  StoryDetailsScreenContainerProps
> = ({ id }) => {
  const router = useRouter();
  const theme = useTheme();
  const { isLargeScreen } = useScreenLayout();
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
          <Button onPress={() => router.back()}>Go Back</Button>
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

      {isLargeScreen ? (
        <View style={styles.largeScreenContainer}>
          <View style={styles.leftColumn}>
            <ScrollView contentContainerStyle={styles.leftColumnContent}>
              {infoPanel}
            </ScrollView>
          </View>

          <View style={styles.rightColumn}>
            <List.Section title="Chapters">
              <StoryDetailsChaptersList
                story={story}
                chapters={filteredChapters}
                selectionMode={selectionMode}
                selectedChapterIds={selectedChapterIds}
                onOpenChapter={handleOpenChapter}
                onMarkChapterAsRead={markChapterAsRead}
                onToggleChapter={handleToggleChapter}
              />
            </List.Section>
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
          listHeaderComponent={infoPanel}
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
    flexGrow: 1,
  },
  listHeader: {
    marginBottom: 16,
  },
  largeScreenContainer: {
    flexDirection: "row",
    gap: 32,
  },
  leftColumn: {
    flex: 1,
    minWidth: 200,
    maxWidth: 500,
    alignItems: "stretch",
  },
  leftColumnContent: {
    padding: 16,
  },
  rightColumn: {
    flex: 2,
  },
  fab: {
    position: "absolute",
    margin: 24,
    right: 8,
    bottom: 24,
  },
});
