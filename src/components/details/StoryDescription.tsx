import React, { useRef, useState } from "react";
import { View, StyleSheet, TouchableOpacity } from "react-native";
import { Text, useTheme } from "react-native-paper";
import * as Clipboard from "expo-clipboard";

interface StoryDescriptionProps {
  description?: string;
  maxLength?: number;
}

const DEFAULT_MAX_LENGTH = 200;

export const StoryDescription: React.FC<StoryDescriptionProps> = ({
  description,
  maxLength = DEFAULT_MAX_LENGTH,
}) => {
  const [isExpanded, setIsExpanded] = useState(false);
  const lastTap = useRef(0);
  const theme = useTheme();

  if (!description) {
    return null;
  }

  const shouldTruncate = description.length > maxLength;
  const displayText =
    shouldTruncate && !isExpanded
      ? `${description.slice(0, maxLength).trim()}...`
      : description;

  const handlePress = async () => {
    const now = Date.now();
    const DOUBLE_PRESS_DELAY = 300;
    if (now - lastTap.current < DOUBLE_PRESS_DELAY) {
      await Clipboard.setStringAsync(description || "");
    }
    lastTap.current = now;
  };

  const handleExpandToggle = () => {
    setIsExpanded(!isExpanded);
  };

  return (
    <View style={styles.descriptionContainer}>
      <Text
        variant="bodyMedium"
        style={styles.description}
        onPress={() => void handlePress()}
      >
        {displayText}
      </Text>
      {shouldTruncate && (
        <TouchableOpacity
          onPress={handleExpandToggle}
          style={styles.readMoreButton}
          testID="read-more-button"
        >
          <Text
            style={[styles.readMoreText, { color: theme.colors.primary }]}
            testID="read-more-text"
          >
            {isExpanded ? "Show less" : "Read more"}
          </Text>
        </TouchableOpacity>
      )}
    </View>
  );
};

const styles = StyleSheet.create({
  descriptionContainer: {
    marginBottom: 20,
    paddingHorizontal: 16,
  },
  description: {
    textAlign: "center",
  },
  readMoreButton: {
    marginTop: 8,
    alignSelf: "center",
  },
  readMoreText: {
    fontWeight: "600",
  },
});
