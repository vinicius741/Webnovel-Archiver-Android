import React from "react";
import { StyleSheet, View } from "react-native";
import { IconButton, useTheme } from "react-native-paper";

interface ReaderHeaderRightProps {
  isSpeaking: boolean;
  isLastRead: boolean;
  cleanupRulesLoaded: boolean;
  onToggleSpeech: () => void;
  onOpenSettings: () => void;
  onMarkAsRead: () => void;
}

export const ReaderHeaderRight: React.FC<ReaderHeaderRightProps> = ({
  isSpeaking,
  isLastRead,
  cleanupRulesLoaded,
  onToggleSpeech,
  onOpenSettings,
  onMarkAsRead,
}) => {
  const theme = useTheme();

  return (
    <View style={styles.container}>
      <IconButton
        icon={isSpeaking ? "stop" : "volume-high"}
        iconColor={isSpeaking ? theme.colors.error : undefined}
        onPress={onToggleSpeech}
        disabled={!cleanupRulesLoaded}
      />

      <IconButton icon="cog-outline" onPress={onOpenSettings} />

      <IconButton
        icon={isLastRead ? "bookmark" : "bookmark-outline"}
        iconColor={isLastRead ? theme.colors.primary : undefined}
        onPress={onMarkAsRead}
      />
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    flexDirection: "row",
  },
});
