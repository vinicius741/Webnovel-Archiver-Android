import React from "react";
import { View, StyleSheet, Animated } from "react-native";
import { Surface, IconButton, Text, useTheme } from "react-native-paper";
import { useAppTheme } from "../theme/useAppTheme";

interface TTSControllerProps {
  onPlayPause: () => void;
  onStop: () => void;
  onNext: () => void;
  onPrevious: () => void;
  _isSpeaking: boolean;
  isPaused: boolean;
  currentChunk: number;
  totalChunks: number;
  visible: boolean;
  maxWidth?: number;
  horizontalPadding?: number;
  bottomOffset?: number;
  compactHeight?: boolean;
}

export const TTSController: React.FC<TTSControllerProps> = ({
  onPlayPause,
  onStop,
  onNext,
  onPrevious,
  _isSpeaking,
  isPaused,
  currentChunk,
  totalChunks,
  visible,
  maxWidth,
  horizontalPadding = 16,
  bottomOffset = 96,
  compactHeight = false,
}) => {
  const theme = useTheme();
  const appTheme = useAppTheme();
  const [fadeAnim] = React.useState(new Animated.Value(0));

  const [isRendered, setIsRendered] = React.useState(visible);
  const isNarrowLayout = !!maxWidth && maxWidth < 420;

  React.useEffect(() => {
    if (visible) {
      setIsRendered(true);
      Animated.timing(fadeAnim, {
        toValue: 1,
        duration: 300,
        useNativeDriver: true,
      }).start();
    } else {
      Animated.timing(fadeAnim, {
        toValue: 0,
        duration: 300,
        useNativeDriver: true,
      }).start(({ finished }) => {
        if (finished) setIsRendered(false);
      });
    }
  }, [visible, fadeAnim]);

  if (!isRendered) return null;

  return (
    <Animated.View
      testID="animated-view"
      pointerEvents="box-none"
      style={[
        styles.container,
        {
          opacity: fadeAnim,
          bottom: bottomOffset,
          paddingHorizontal: horizontalPadding,
        },
      ]}
    >
      <Surface
        style={[
          styles.surface,
          {
            borderRadius: appTheme.shapes.fabRadius,
            backgroundColor: theme.colors.elevation.level3,
            maxWidth,
            paddingVertical: compactHeight ? 6 : 10,
          },
        ]}
        elevation={4}
      >
        <View
          style={[
            styles.content,
            isNarrowLayout ? styles.contentStacked : null,
          ]}
        >
          <View
            style={[
              styles.info,
              isNarrowLayout ? styles.infoStacked : null,
              isNarrowLayout ? styles.centered : null,
            ]}
          >
            <Text variant="labelSmall" style={{ color: theme.colors.primary }}>
              TEXT-TO-SPEECH
            </Text>
            <Text variant="bodySmall">
              {totalChunks > 0
                ? `Chunk ${currentChunk + 1} / ${totalChunks}`
                : "Initializing..."}
            </Text>
          </View>

          <View
            style={[
              styles.controls,
              isNarrowLayout ? styles.controlsStacked : null,
            ]}
          >
            <IconButton
              icon="skip-previous"
              size={24}
              disabled={currentChunk === 0}
              onPress={onPrevious}
              testID="previous-button"
            />
            <IconButton
              icon={isPaused ? "play" : "pause"}
              size={32}
              mode="contained"
              containerColor={theme.colors.primary}
              iconColor={theme.colors.onPrimary}
              onPress={onPlayPause}
              testID="play-pause-button"
            />
            <IconButton
              icon="skip-next"
              size={24}
              disabled={currentChunk >= totalChunks - 1}
              onPress={onNext}
              testID="next-button"
            />
            <IconButton
              icon="stop"
              size={24}
              onPress={onStop}
              testID="stop-button"
            />
          </View>
        </View>
      </Surface>
    </Animated.View>
  );
};

const styles = StyleSheet.create({
  container: {
    position: "absolute",
    left: 0,
    right: 0,
    zIndex: 1000,
  },
  surface: {
    width: "100%",
    alignSelf: "center",
    paddingHorizontal: 16,
  },
  content: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
  },
  contentStacked: {
    alignItems: "stretch",
  },
  info: {
    flex: 1,
  },
  infoStacked: {
    marginBottom: 4,
  },
  centered: {
    alignItems: "center",
  },
  controls: {
    flexDirection: "row",
    alignItems: "center",
  },
  controlsStacked: {
    justifyContent: "center",
  },
});
