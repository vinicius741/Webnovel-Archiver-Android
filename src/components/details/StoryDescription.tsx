import React, { useRef, useState } from "react";
import { View, StyleSheet, TouchableOpacity } from "react-native";
import { Text, useTheme } from "react-native-paper";
import * as Clipboard from "expo-clipboard";

interface StoryDescriptionProps {
  description?: string;
  maxLength?: number;
  align?: "center" | "start";
}

const DEFAULT_MAX_LENGTH = 200;

export const StoryDescription: React.FC<StoryDescriptionProps> = ({
  description,
  maxLength = DEFAULT_MAX_LENGTH,
  align = "center",
}) => {
  const [isExpanded, setIsExpanded] = useState(false);
  const lastTap = useRef(0);
  const theme = useTheme();

  if (!description) {
    return null;
  }

  const isStartAligned = align === "start";

  const shouldTruncate = description.length > maxLength;
  
  const getTruncatedText = (): string => {
    if (!shouldTruncate) {
      return description;
    }
    const truncated = description.slice(0, maxLength);
    const lastSpaceIndex = truncated.lastIndexOf(" ");
    if (lastSpaceIndex > 0) {
      return `${truncated.slice(0, lastSpaceIndex)}...`;
    }
    return `${truncated}...`;
  };

  const displayText = isExpanded ? description : getTruncatedText();

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
        style={[styles.description, isStartAligned && styles.descriptionStart]}
        onPress={() => void handlePress()}
      >
        {displayText}
      </Text>
      {shouldTruncate && (
        <TouchableOpacity
          onPress={handleExpandToggle}
          style={[styles.readMoreButton, isStartAligned && styles.readMoreButtonStart]}
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
  descriptionStart: {
    textAlign: "left",
  },
  readMoreButton: {
    marginTop: 8,
    alignSelf: "center",
  },
  readMoreButtonStart: {
    alignSelf: "flex-start",
  },
  readMoreText: {
    fontWeight: "600",
  },
});
