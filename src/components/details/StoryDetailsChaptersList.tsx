import React from "react";
import {
  FlatList,
  ListRenderItem,
  StyleProp,
  ViewStyle,
} from "react-native";

import { Chapter, Story } from "../../types";
import { ChapterListItem } from "./ChapterListItem";

interface StoryDetailsChaptersListProps {
  story: Story;
  chapters: Chapter[];
  selectionMode: boolean;
  selectedChapterIds: Set<string>;
  onOpenChapter: (chapterId: string) => void;
  onMarkChapterAsRead: (chapter: Chapter) => void;
  onToggleChapter: (chapterId: string) => void;
  contentContainerStyle?: StyleProp<ViewStyle>;
  listHeaderComponent?: React.ReactElement | null;
  listHeaderComponentStyle?: StyleProp<ViewStyle>;
}

export const StoryDetailsChaptersList: React.FC<StoryDetailsChaptersListProps> = ({
  story,
  chapters,
  selectionMode,
  selectedChapterIds,
  onOpenChapter,
  onMarkChapterAsRead,
  onToggleChapter,
  contentContainerStyle,
  listHeaderComponent,
  listHeaderComponentStyle,
}) => {
  const renderChapterItem: ListRenderItem<Chapter> = ({ item }) => (
    <ChapterListItem
      item={item}
      isLastRead={story.lastReadChapterId === item.id}
      onPress={() => onOpenChapter(item.id)}
      onLongPress={() => onMarkChapterAsRead(item)}
      selectionMode={selectionMode}
      selected={selectedChapterIds.has(item.id)}
      onToggleSelection={() => onToggleChapter(item.id)}
    />
  );

  return (
    <FlatList
      data={chapters}
      renderItem={renderChapterItem}
      keyExtractor={(item: Chapter) => item.id}
      contentContainerStyle={contentContainerStyle}
      ListHeaderComponent={listHeaderComponent}
      ListHeaderComponentStyle={listHeaderComponentStyle}
    />
  );
};
