import React, { useState, useEffect, useMemo } from "react";
import { View, StyleSheet, ScrollView, FlatList } from "react-native";
import { Text, Button, List, ActivityIndicator, FAB, useTheme } from "react-native-paper";
import { useLocalSearchParams, useRouter, Stack } from "expo-router";

import { StoryHeader } from "../../src/components/details/StoryHeader";
import { StoryActions } from "../../src/components/details/StoryActions";
import { StoryMenu } from "../../src/components/details/StoryMenu";
import { StoryDescription } from "../../src/components/details/StoryDescription";
import { StoryTags } from "../../src/components/details/StoryTags";
import { ChapterListItem } from "../../src/components/details/ChapterListItem";
import { ChapterFilterMenu } from "../../src/components/details/ChapterFilterMenu";
import { DownloadRangeDialog } from "../../src/components/details/DownloadRangeDialog";
import { EpubConfigDialog } from "../../src/components/details/EpubConfigDialog";
import { EpubSelectorDialog } from "../../src/components/details/EpubSelectorDialog";
import { ScreenContainer } from "../../src/components/ScreenContainer";
import { useScreenLayout } from "../../src/hooks/useScreenLayout";
import { useStoryDetails } from "../../src/hooks/useStoryDetails";
import { useAppAlert } from "../../src/context/AlertContext";
import {
  storageService,
  ChapterFilterMode,
} from "../../src/services/StorageService";
import { Chapter, EpubConfig } from "../../src/types";

export default function StoryDetailsScreen() {
  const { id } = useLocalSearchParams();
  const router = useRouter();
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
    downloadRange,
    applySentenceRemoval,
    updateStory,
    showEpubSelector,
    setShowEpubSelector,
    availableEpubs,
    downloadChaptersByIds,
  } = useStoryDetails(id);

  const theme = useTheme();
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
    const loadSettings = async () => {
      const [filterSettings, appSettings] = await Promise.all([
        storageService.getChapterFilterSettings(),
        storageService.getSettings(),
      ]);
      setFilterMode(filterSettings.filterMode);
      setDefaultMaxChapters(appSettings.maxChaptersPerEpub || 150);
    };
    loadSettings();
  }, []);

  const filteredChapters = useMemo(() => {
    if (!story) return [];

    const bookmarkIndex =
      story.lastReadChapterId && filterMode === "hideAboveBookmark"
        ? story.chapters.findIndex((ch) => ch.id === story.lastReadChapterId)
        : -1;

    return story.chapters.filter((c, index) => {
      const matchesSearch = c.title
        .toLowerCase()
        .includes(searchQuery.toLowerCase());
      if (!matchesSearch) return false;

      if (selectionMode && c.downloaded) {
        return false;
      }

      switch (filterMode) {
        case "hideNonDownloaded":
          return c.downloaded === true;
        case "hideAboveBookmark":
          return bookmarkIndex !== -1 ? index >= bookmarkIndex : true;
        default:
          return true;
      }
    });
  }, [story, searchQuery, filterMode, selectionMode]);

  const handleFilterSelect = async (mode: ChapterFilterMode) => {
    setFilterMode(mode);
    await storageService.saveChapterFilterSettings({ filterMode: mode });
  };

  const handleToggleSelectionMode = () => {
    setSelectionMode((prev) => !prev);
    setSelectedChapterIds(new Set());
  };

  const handleToggleChapter = (chapterId: string) => {
    setSelectedChapterIds((prev) => {
      const newSet = new Set(prev);
      if (newSet.has(chapterId)) {
        newSet.delete(chapterId);
      } else {
        newSet.add(chapterId);
      }
      return newSet;
    });
  };

  const handleDownloadSelected = async () => {
    if (!story) return;

    // Filter out already downloaded chapters
    const idsToDownload = Array.from(selectedChapterIds).filter((id) => {
      const chapter = story.chapters.find((ch) => ch.id === id);
      return chapter && !chapter.downloaded;
    });

    if (idsToDownload.length === 0) {
      showAlert("No Chapters to Download", "All selected chapters are already downloaded.");
      return;
    }

    await downloadChaptersByIds(idsToDownload);
    setSelectionMode(false);
    setSelectedChapterIds(new Set());
  };

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

  const chapterCount = story.chapters.length;
  const downloadedChapterCount = story.chapters.filter(
    (chapter) => chapter.downloaded === true,
  ).length;
  const hasValidBookmark =
    !!story.lastReadChapterId &&
    story.chapters.some((chapter) => chapter.id === story.lastReadChapterId);
  const initialEpubConfig: EpubConfig = {
    maxChaptersPerEpub:
      story.epubConfig?.maxChaptersPerEpub ?? defaultMaxChapters,
    rangeStart: story.epubConfig?.rangeStart ?? 1,
    rangeEnd: story.epubConfig?.rangeEnd ?? chapterCount,
    startAfterBookmark: story.epubConfig?.startAfterBookmark ?? false,
  };

  const handleSaveEpubConfig = async (config: EpubConfig) => {
    const updatedStory = {
      ...story,
      epubConfig: config,
    };
    await updateStory(updatedStory);
  };

  const renderStoryInfo = () => (
    <View style={styles.fullWidth}>
      <StoryHeader story={story} />
      <StoryActions
        story={story}
        downloading={downloading}
        syncing={syncing}
        generating={generating}
        epubProgress={epubProgress}
        syncStatus={syncStatus}
        downloadProgress={downloadProgress}
        downloadStatus={downloadStatus}
        onSync={syncChapters}
        onGenerate={generateEpub}
        onRead={readEpub}
        onDownloadAll={() =>
          story.totalChapters > 0 && downloadRange(1, story.totalChapters)
        }
        onViewDownloads={() => router.push("/download-manager")}
      />
      <DownloadRangeDialog
        visible={showDownloadRange}
        onDismiss={() => setShowDownloadRange(false)}
        onDownload={downloadRange}
        totalChapters={story.totalChapters}
      />
      <EpubConfigDialog
        visible={showEpubConfig}
        onDismiss={() => setShowEpubConfig(false)}
        onSave={handleSaveEpubConfig}
        initialConfig={initialEpubConfig}
        totalChapters={chapterCount}
        downloadedChapterCount={downloadedChapterCount}
        hasBookmark={hasValidBookmark}
      />
      <EpubSelectorDialog
        visible={showEpubSelector}
        onDismiss={() => setShowEpubSelector(false)}
        onSelect={readEpubAtPath}
        epubs={availableEpubs}
      />
      <StoryTags tags={story.tags} />
      <StoryDescription description={story.description} />

      <View style={styles.filterContainer}>
        <ChapterFilterMenu
          filterMode={filterMode}
          hasBookmark={!!story.lastReadChapterId}
          onFilterSelect={handleFilterSelect}
          searchQuery={searchQuery}
          onSearchChange={setSearchQuery}
          selectionMode={selectionMode}
          onToggleSelectionMode={handleToggleSelectionMode}
          selectionDisabled={!!story.isArchived}
        />
      </View>
    </View>
  );

  return (
    <ScreenContainer edges={["bottom", "left", "right"]}>
      <Stack.Screen
        options={{
          title: story ? story.title : "Details",
          headerRight: () => (
            <StoryMenu
              onDownloadRange={() => setShowDownloadRange(true)}
              onConfigureEpub={() => setShowEpubConfig(true)}
              onApplySentenceRemoval={applySentenceRemoval}
              onDelete={deleteStory}
              disabled={loading}
              isArchived={story?.isArchived}
            />
          ),
        }}
      />

      {isLargeScreen ? (
        <View style={styles.largeScreenContainer}>
          <View style={styles.leftColumn}>
            <ScrollView contentContainerStyle={{ padding: 16 }}>
              {renderStoryInfo()}
            </ScrollView>
          </View>
          <View style={styles.rightColumn}>
            <List.Section title="Chapters">
              <FlatList
                data={filteredChapters}
                renderItem={({ item }) => (
                  <ChapterListItem
                    item={item}
                    isLastRead={story.lastReadChapterId === item.id}
                    onPress={() =>
                      router.push(
                        `/reader/${story.id}/${encodeURIComponent(item.id)}`,
                      )
                    }
                    onLongPress={() => markChapterAsRead(item)}
                    selectionMode={selectionMode}
                    selected={selectedChapterIds.has(item.id)}
                    onToggleSelection={() => handleToggleChapter(item.id)}
                  />
                )}
                keyExtractor={(item: Chapter) => item.id}
              />
            </List.Section>
          </View>
        </View>
      ) : (
        <FlatList
          contentContainerStyle={styles.content}
          data={filteredChapters}
          renderItem={({ item }) => (
            <ChapterListItem
              item={item}
              isLastRead={story.lastReadChapterId === item.id}
              onPress={() =>
                router.push(
                  `/reader/${story.id}/${encodeURIComponent(item.id)}`,
                )
              }
              onLongPress={() => markChapterAsRead(item)}
              selectionMode={selectionMode}
              selected={selectedChapterIds.has(item.id)}
              onToggleSelection={() => handleToggleChapter(item.id)}
            />
          )}
          keyExtractor={(item: Chapter) => item.id}
          ListHeaderComponent={renderStoryInfo()}
          ListHeaderComponentStyle={{ marginBottom: 16 }}
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
}

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
  largeScreenContainer: {
    flexDirection: "row",
    gap: 32,
  },
  normalContainer: {
    flexDirection: "column",
  },
  leftColumn: {
    flex: 1,
    minWidth: 200,
    maxWidth: 500,
    alignItems: "stretch",
  },
  rightColumn: {
    flex: 2,
  },
  fullWidth: {
    width: "100%",
  },
  filterContainer: {
    margin: 16,
    marginBottom: 8,
  },
  fab: {
    position: "absolute",
    margin: 24,
    right: 8,
    bottom: 24,
  },
});
