import React from "react";
import { View, StyleSheet } from "react-native";
import { Appbar, Text, useTheme } from "react-native-paper";

interface ReaderNavigationProps {
  currentChapterIndex: number;
  totalChapters: number;
  hasPrevious: boolean;
  hasNext: boolean;
  maxWidth?: number;
  horizontalPadding?: number;
  compactHeight?: boolean;
  onPrevious: () => void;
  onNext: () => void;
  onCopy?: () => void;
}

export const ReaderNavigation: React.FC<ReaderNavigationProps> = ({
  currentChapterIndex,
  totalChapters,
  hasPrevious,
  hasNext,
  maxWidth,
  horizontalPadding = 16,
  compactHeight = false,
  onPrevious,
  onNext,
  onCopy,
}) => {
  const theme = useTheme();

  return (
    <View
      style={[
        styles.container,
        {
          paddingHorizontal: horizontalPadding,
          paddingBottom: compactHeight ? 8 : 12,
        },
      ]}
    >
      <Appbar
        style={[
          styles.bottomBar,
          {
            backgroundColor: theme.colors.elevation.level2,
            maxWidth,
          },
        ]}
      >
        <Appbar.Action
          icon="chevron-left"
          disabled={!hasPrevious}
          onPress={onPrevious}
        />
        <View style={styles.spacer} />

        <Appbar.Action
          icon="content-copy"
          onPress={onCopy}
          accessibilityLabel="Copy chapter text"
        />

        <View style={styles.spacer} />
        <Text
          variant="labelLarge"
          style={{ color: theme.colors.onSurfaceVariant }}
        >
          {currentChapterIndex + 1} / {totalChapters}
        </Text>
        <View style={styles.spacer} />
        <Appbar.Action
          icon="chevron-right"
          disabled={!hasNext}
          onPress={onNext}
        />
      </Appbar>
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    width: "100%",
  },
  bottomBar: {
    width: "100%",
    alignSelf: "center",
    justifyContent: "space-between",
    paddingHorizontal: 8,
    borderRadius: 24,
  },
  spacer: {
    flex: 1,
  },
});
