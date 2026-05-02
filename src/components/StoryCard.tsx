import React from "react";
import { Image, StyleSheet, View } from "react-native";
import {
  Card,
  Text,
  useTheme,
  ProgressBar,
  IconButton,
} from "react-native-paper";

interface Props {
  title: string;
  author: string;
  coverUrl?: string;
  sourceName?: string;
  score?: string;
  progress?: number; // 0 to 1
  lastReadChapterName?: string;
  onPress: () => void;
  onLongPress?: () => void;
  selectionMode?: boolean;
  selected?: boolean;
  isArchived?: boolean;
  compact?: boolean;
}

export const StoryCard = ({
  title,
  author,
  coverUrl,
  sourceName,
  score,
  progress,
  lastReadChapterName,
  onPress,
  onLongPress,
  selectionMode,
  selected,
  isArchived,
  compact = false,
}: Props) => {
  const theme = useTheme();

  const handlePress = () => {
    if (selectionMode && onLongPress) {
      onLongPress();
    } else {
      onPress();
    }
  };

  return (
    <Card
      style={[
        styles.card,
        compact && styles.cardCompact,
        selected && { borderColor: theme.colors.primary, borderWidth: 2 },
      ]}
      onPress={handlePress}
      onLongPress={onLongPress}
      testID="story-card"
    >
      <Card.Content style={[styles.content, compact && styles.contentCompact]}>
        {selectionMode && (
          <View
            style={[
              styles.selectionIndicator,
              {
                backgroundColor: selected
                  ? theme.colors.primary
                  : theme.colors.surfaceVariant,
                borderColor: selected
                  ? theme.colors.primary
                  : theme.colors.outline,
              },
            ]}
          >
            {selected && (
              <IconButton icon="check" size={16} iconColor={theme.colors.onPrimary} style={styles.checkIcon} />
            )}
          </View>
        )}
        {isArchived && (
          <View
            style={[
              styles.archiveBadge,
              { backgroundColor: theme.colors.primary },
              selectionMode && styles.archiveBadgeWithSelection,
            ]}
          >
            <IconButton
              icon="archive"
              size={14}
              iconColor={theme.colors.onPrimary}
              style={styles.archiveIcon}
              testID="story-card-archive-icon"
            />
          </View>
        )}
        {coverUrl ? (
          <Image
            source={{ uri: coverUrl }}
            style={[styles.coverImage, compact && styles.coverImageCompact]}
            testID="story-card-cover"
            accessibilityLabel={`${title} cover`}
          />
        ) : (
          <View
            style={[
              styles.coverImage,
              compact && styles.coverImageCompact,
              styles.placeholderCover,
              { backgroundColor: theme.colors.surfaceVariant },
            ]}
          >
            <IconButton
              icon="book-open-variant"
              size={28}
              iconColor={theme.colors.onSurfaceVariant}
              style={styles.placeholderIcon}
            />
          </View>
        )}
        <View style={styles.textContainer}>
          <Text
            variant={compact ? "titleSmall" : "titleMedium"}
            numberOfLines={2}
            style={{ marginBottom: 4 }}
          >
            {title}
          </Text>
          <Text
            variant="bodySmall"
            style={{ color: theme.colors.onSurfaceVariant, marginBottom: 4 }}
          >
            {author}
          </Text>
          {sourceName && (
            <Text
              variant="labelSmall"
              style={{ color: theme.colors.primary, marginBottom: 4 }}
            >
              {sourceName}
            </Text>
          )}
          {score && (
            <View style={styles.scoreContainer}>
              <IconButton
                icon="star"
                iconColor="#FFD700"
                size={12}
                style={styles.scoreIcon}
                testID="story-card-score-icon"
              />
              <Text variant="labelSmall" style={styles.scoreText}>
                {score}
              </Text>
            </View>
          )}
          {lastReadChapterName && (
            <Text
              variant="labelSmall"
              style={{ color: theme.colors.onSurfaceVariant }}
              numberOfLines={1}
            >
              Last read: {lastReadChapterName}
            </Text>
          )}
        </View>
      </Card.Content>
      {progress !== undefined && (
        <ProgressBar
          progress={progress}
          style={styles.progress}
          testID="story-card-progress"
        />
      )}
    </Card>
  );
};

const styles = StyleSheet.create({
  card: {
    flex: 1,
    marginBottom: 0, // Handled by parent container now for grid gaps
    borderRadius: 12,
    marginTop: 8,
    flexDirection: "column",
    justifyContent: "space-between",
  },
  cardCompact: {
    minHeight: 0,
  },
  content: {
    flex: 1,
    flexDirection: "row",
    justifyContent: "space-between",
    padding: 16,
  },
  contentCompact: {
    padding: 12,
  },
  coverImage: {
    width: 80,
    height: 120,
    borderRadius: 8,
    marginRight: 16,
  },
  coverImageCompact: {
    width: 64,
    height: 96,
    marginRight: 12,
  },
  placeholderCover: {
    justifyContent: "center",
    alignItems: "center",
  },
  placeholderIcon: {
    margin: 0,
  },
  textContainer: {
    flex: 1,
    justifyContent: "center",
  },
  progress: {
    height: 4,
    borderBottomLeftRadius: 12,
    borderBottomRightRadius: 12,
  },
  scoreContainer: {
    flexDirection: "row",
    alignItems: "center",
    marginBottom: 4,
    marginLeft: -8, // Adjust for IconButton margin
  },
  scoreIcon: {
    margin: 0,
  },
  scoreText: {
    fontWeight: "bold",
    marginLeft: -4,
  },
  selectionIndicator: {
    position: "absolute",
    top: 8,
    right: 8,
    width: 24,
    height: 24,
    borderRadius: 12,
    borderWidth: 2,
    justifyContent: "center",
    alignItems: "center",
    zIndex: 1,
  },
  checkIcon: {
    margin: 0,
    padding: 0,
    width: 20,
    height: 20,
  },
  archiveBadge: {
    position: "absolute",
    top: 8,
    right: 8,
    width: 24,
    height: 24,
    borderRadius: 12,
    justifyContent: "center",
    alignItems: "center",
    zIndex: 1,
  },
  archiveBadgeWithSelection: {
    top: 36,
  },
  archiveIcon: {
    margin: 0,
    padding: 0,
    width: 20,
    height: 20,
  },
});
