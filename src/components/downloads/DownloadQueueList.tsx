import React, { useMemo, useState } from "react";
import { View, FlatList, StyleSheet, RefreshControl } from "react-native";
import { Text, IconButton, useTheme } from "react-native-paper";
import { DownloadStoryCard, StoryGroup } from "./DownloadStoryCard";

interface DownloadQueueListProps {
  jobsByStory: StoryGroup[];
  onPause: (jobId: string) => void;
  onResume: (jobId: string) => void;
  onCancel: (jobId: string) => void;
  onRetry: (jobId: string) => void;
  refreshing: boolean;
  onRefresh: () => void;
  contentMaxWidth?: number;
  horizontalPadding?: number;
}

export const DownloadQueueList: React.FC<DownloadQueueListProps> = ({
  jobsByStory,
  onPause,
  onResume,
  onCancel,
  onRetry,
  refreshing,
  onRefresh,
  contentMaxWidth,
  horizontalPadding = 16,
}) => {
  const theme = useTheme();
  const [expandedStories, setExpandedStories] = useState<Set<string>>(
    new Set(),
  );

  const validStoryIds = useMemo(
    () => new Set(jobsByStory.map((group) => group.storyId)),
    [jobsByStory],
  );

  const toggleStoryExpanded = (storyId: string) => {
    setExpandedStories((previous) => {
      const next = new Set(
        Array.from(previous).filter((id) => validStoryIds.has(id)),
      );
      if (next.has(storyId)) {
        next.delete(storyId);
      } else {
        next.add(storyId);
      }
      return next;
    });
  };

  if (jobsByStory.length === 0) {
    return (
      <View
        style={[
          styles.emptyContainer,
          {
            paddingHorizontal: horizontalPadding,
          },
        ]}
      >
        <View
          style={[
            styles.emptyContent,
            contentMaxWidth ? { maxWidth: contentMaxWidth } : null,
          ]}
        >
          <IconButton
            icon="download-box-outline"
            size={64}
            iconColor={theme.colors.surfaceVariant}
            style={styles.emptyIcon}
          />
          <Text variant="titleLarge" style={styles.emptyTitle}>
            No Active Downloads
          </Text>
          <Text variant="bodyMedium" style={styles.emptyText}>
            Downloaded chapters will appear here
          </Text>
        </View>
      </View>
    );
  }

  const renderItem = ({ item }: { item: StoryGroup }) => {
    const isExpanded =
      validStoryIds.has(item.storyId) && expandedStories.has(item.storyId);

    return (
      <View
        style={[
          styles.itemShell,
          contentMaxWidth ? { maxWidth: contentMaxWidth } : null,
        ]}
      >
        <DownloadStoryCard
          item={item}
          isExpanded={isExpanded}
          onToggleExpanded={toggleStoryExpanded}
          onPause={onPause}
          onResume={onResume}
          onCancel={onCancel}
          onRetry={onRetry}
        />
      </View>
    );
  };

  return (
    <FlatList
      data={jobsByStory}
      keyExtractor={(item) => item.storyId}
      renderItem={renderItem}
      refreshControl={
        <RefreshControl
          refreshing={refreshing}
          onRefresh={onRefresh}
          colors={[theme.colors.primary]}
        />
      }
      contentContainerStyle={[
        styles.listContent,
        { paddingHorizontal: horizontalPadding },
      ]}
      ItemSeparatorComponent={() => <View style={styles.cardSpacing} />}
    />
  );
};

const styles = StyleSheet.create({
  listContent: {
    flexGrow: 1,
    paddingTop: 8,
    paddingBottom: 16,
  },
  cardSpacing: {
    height: 8,
  },
  itemShell: {
    width: "100%",
    alignSelf: "center",
  },
  emptyContainer: {
    flex: 1,
    justifyContent: "center",
    alignItems: "center",
    padding: 32,
  },
  emptyContent: {
    width: "100%",
    alignSelf: "center",
  },
  emptyIcon: {
    marginBottom: 16,
  },
  emptyTitle: {
    marginBottom: 8,
    opacity: 0.7,
  },
  emptyText: {
    textAlign: "center",
    opacity: 0.5,
  },
});
