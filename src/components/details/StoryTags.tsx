import React from "react";
import { View, StyleSheet } from "react-native";
import { Chip } from "react-native-paper";

interface StoryTagsProps {
  tags?: string[];
  align?: "center" | "start";
}

export const StoryTags: React.FC<StoryTagsProps> = ({
  tags,
  align = "center",
}) => {
  if (!tags || tags.length === 0) {
    return null;
  }

  return (
    <View
      testID="tags-container"
      style={[styles.container, align === "start" && styles.containerStart]}
    >
      {tags.map((tag, index) => (
        <Chip
          testID={`chip-${index}`}
          key={`${tag}-${index}`}
          style={styles.chip}
          textStyle={styles.chipText}
          compact
        >
          {tag}
        </Chip>
      ))}
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    flexDirection: "row",
    flexWrap: "wrap",
    justifyContent: "center",
    gap: 8,
    paddingHorizontal: 16,
    marginBottom: 16,
  },
  containerStart: {
    justifyContent: "flex-start",
  },
  chip: {
    height: 32,
  },
  chipText: {
    fontSize: 12,
    lineHeight: 20,
  },
});
