import React from "react";
import { ScrollView, StyleSheet, View } from "react-native";
import { List } from "react-native-paper";

import { Chapter, Story } from "../../types";
import { StoryDetailsChaptersList } from "./StoryDetailsChaptersList";

interface StoryDetailsLayoutProps {
  story: Story;
  chapters: Chapter[];
  selectionMode: boolean;
  selectedChapterIds: Set<string>;
  infoPanel: React.ReactNode;
  isTwoPane: boolean;
  isCompactHeight: boolean;
  isNarrowTwoPane: boolean;
  onOpenChapter: (chapterId: string) => void;
  onMarkChapterAsRead: (chapter: Chapter) => void;
  onToggleChapter: (chapterId: string) => void;
}

export const StoryDetailsLayout: React.FC<StoryDetailsLayoutProps> = ({
  story,
  chapters,
  selectionMode,
  selectedChapterIds,
  infoPanel,
  isTwoPane,
  isCompactHeight,
  isNarrowTwoPane,
  onOpenChapter,
  onMarkChapterAsRead,
  onToggleChapter,
}) => {
  if (isTwoPane) {
    return (
      <View style={styles.twoPaneShell}>
        <View
          style={[
            styles.twoPaneContainer,
            isCompactHeight && styles.twoPaneContainerCompactHeight,
            isNarrowTwoPane && styles.twoPaneContainerNarrow,
          ]}
        >
          <View
            style={[
              styles.leftColumn,
              isNarrowTwoPane && styles.leftColumnNarrow,
            ]}
          >
            <ScrollView contentContainerStyle={styles.leftColumnContent}>
              {infoPanel}
            </ScrollView>
          </View>

          <View style={styles.rightColumn}>
            <List.Section style={styles.chapterSection} title="Chapters">
              <StoryDetailsChaptersList
                story={story}
                chapters={chapters}
                selectionMode={selectionMode}
                selectedChapterIds={selectedChapterIds}
                onOpenChapter={onOpenChapter}
                onMarkChapterAsRead={onMarkChapterAsRead}
                onToggleChapter={onToggleChapter}
                contentContainerStyle={styles.chapterListContent}
              />
            </List.Section>
          </View>
        </View>
      </View>
    );
  }

  return (
    <StoryDetailsChaptersList
      story={story}
      chapters={chapters}
      selectionMode={selectionMode}
      selectedChapterIds={selectedChapterIds}
      onOpenChapter={onOpenChapter}
      onMarkChapterAsRead={onMarkChapterAsRead}
      onToggleChapter={onToggleChapter}
      contentContainerStyle={styles.content}
      listHeaderComponent={
        <View style={styles.compactInfoPanel}>{infoPanel}</View>
      }
      listHeaderComponentStyle={styles.listHeader}
    />
  );
};

const styles = StyleSheet.create({
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
});
