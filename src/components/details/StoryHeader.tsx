import React, { useRef, useState } from "react";
import { View, StyleSheet, Image, Linking, Pressable } from "react-native";
import { Text, useTheme, Chip, IconButton } from "react-native-paper";
import ImageView from "../ImageViewer";
import { Story } from "../../types";
import { sourceRegistry } from "../../services/source/SourceRegistry";

interface StoryHeaderProps {
  story: Story;
  align?: "center" | "start";
}

export const StoryHeader: React.FC<StoryHeaderProps> = ({
  story,
  align = "center",
}) => {
  const theme = useTheme();
  const sourceName = sourceRegistry.getProvider(story.sourceUrl)?.name;
  const lastTap = useRef<number | null>(null);
  const [viewerVisible, setViewerVisible] = useState(false);

  const handleTitlePress = () => {
    const now = Date.now();
    const DOUBLE_PRESS_DELAY = 300;
    if (lastTap.current && now - lastTap.current < DOUBLE_PRESS_DELAY) {
      Linking.openURL(story.sourceUrl).catch((err) =>
        console.error("Couldn't load page", err),
      );
      lastTap.current = null;
    } else {
      lastTap.current = now;
    }
  };

  const images = story.coverUrl ? [{ uri: story.coverUrl }] : [];
  const isStartAligned = align === "start";

  return (
    <View
      testID="story-header"
      style={[styles.container, isStartAligned && styles.containerStart]}
    >
      {story.coverUrl ? (
        <>
          <Pressable onPress={() => setViewerVisible(true)}>
            <Image
              testID="image"
              source={{ uri: story.coverUrl }}
              style={styles.coverImage}
            />
          </Pressable>
          <ImageView
            images={images}
            imageIndex={0}
            visible={viewerVisible}
            onRequestClose={() => setViewerVisible(false)}
          />
        </>
      ) : (
        <View
          style={[
            styles.coverImage,
            styles.placeholderCover,
            { backgroundColor: theme.colors.surfaceVariant },
          ]}
        >
          <IconButton
            icon="book-open-variant"
            size={48}
            iconColor={theme.colors.onSurfaceVariant}
            style={styles.placeholderIcon}
          />
        </View>
      )}
      <Pressable onPress={handleTitlePress}>
        <Text
          variant="headlineMedium"
          style={[styles.title, isStartAligned && styles.titleStart]}
        >
          {story.title}
        </Text>
      </Pressable>
      <Text
        variant="titleMedium"
        style={[
          styles.author,
          isStartAligned && styles.authorStart,
          { color: theme.colors.secondary },
        ]}
      >
        {story.author}
      </Text>

      <View
        style={[
          styles.chipRow,
          isStartAligned && styles.chipRowStart,
        ]}
      >
        {sourceName && (
          <Chip icon="web" style={styles.sourceChip} textStyle={{ fontSize: 12 }}>
            {sourceName}
          </Chip>
        )}

        {story.isArchived ? (
          <Chip
            icon="archive"
            style={styles.archiveChip}
            textStyle={{ fontSize: 12 }}
          >
            Archived
          </Chip>
        ) : null}
      </View>

      <View style={[styles.stats, isStartAligned && styles.statsStart]}>
        {story.score && (
          <View style={[styles.statItem, { backgroundColor: theme.colors.surfaceVariant }]}>
            <IconButton
              icon="star"
              iconColor={theme.colors.tertiary}
              size={16}
              style={styles.statIcon}
            />
            <Text variant="titleSmall" style={styles.scoreText}>
              {story.score}
            </Text>
          </View>
        )}
        <View style={[styles.statItem, { backgroundColor: theme.colors.surfaceVariant }]}>
          <IconButton
            icon="book-open-variant"
            size={16}
            style={styles.statIcon}
          />
          <Text variant="bodyMedium">{story.totalChapters} Chs</Text>
        </View>
        <View style={[styles.statItem, { backgroundColor: theme.colors.surfaceVariant }]}>
          <IconButton icon="download" size={16} style={styles.statIcon} />
          <Text variant="bodyMedium">{story.downloadedChapters} Saved</Text>
        </View>
      </View>
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    width: "100%",
    alignItems: "center",
  },
  containerStart: {
    alignItems: "flex-start",
  },
  coverImage: {
    width: 150,
    height: 225,
    borderRadius: 8,
    marginBottom: 20,
  },
  placeholderCover: {
    justifyContent: "center",
    alignItems: "center",
  },
  placeholderIcon: {
    margin: 0,
  },
  title: {
    marginBottom: 8,
    textAlign: "center",
  },
  titleStart: {
    textAlign: "left",
  },
  author: {
    textAlign: "center",
    marginBottom: 8,
  },
  authorStart: {
    textAlign: "left",
    width: "100%",
  },
  chipRow: {
    flexDirection: "row",
    flexWrap: "wrap",
    gap: 8,
    justifyContent: "center",
    marginBottom: 16,
  },
  chipRowStart: {
    justifyContent: "flex-start",
  },
  sourceChip: {
  },
  archiveChip: {
  },
  stats: {
    flexDirection: "row",
    flexWrap: "wrap",
    gap: 8,
    marginTop: 8,
    marginBottom: 16,
    justifyContent: "center",
  },
  statsStart: {
    justifyContent: "flex-start",
  },
  statItem: {
    flexDirection: "row",
    alignItems: "center",
    borderRadius: 20,
    paddingRight: 12,
    height: 32,
  },
  statIcon: {
    margin: 0,
    marginRight: -4,
  },
  scoreText: {
  },
});
