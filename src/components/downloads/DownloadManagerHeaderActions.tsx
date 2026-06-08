import React from "react";
import { StyleSheet, View } from "react-native";
import { IconButton, useTheme } from "react-native-paper";
import type { MD3Theme } from "react-native-paper";
import type { QueueStats } from "../../services/download/types";

interface DownloadManagerHeaderActionsProps {
  stats: QueueStats;
  onPauseAll: () => void;
  onResumeAll: () => void;
  onCancelAll: () => void;
  onRetryAllFailed: () => void;
  onOpenSettings: () => void;
}

export function DownloadManagerHeaderActions({
  stats,
  onPauseAll,
  onResumeAll,
  onCancelAll,
  onRetryAllFailed,
  onOpenSettings,
}: DownloadManagerHeaderActionsProps) {
  const theme = useTheme<MD3Theme>();
  const hasActiveJobs = stats.pending > 0 || stats.active > 0;
  const hasPausedJobs = stats.paused > 0;

  return (
    <View style={styles.headerActions}>
      {hasActiveJobs && (
        <IconButton
          icon="pause-circle"
          size={24}
          onPress={onPauseAll}
          iconColor={theme.colors.onSurface}
        />
      )}
      {hasPausedJobs && (
        <IconButton
          icon="play-circle"
          size={24}
          onPress={onResumeAll}
          iconColor={theme.colors.onSurface}
        />
      )}
      {(hasActiveJobs || hasPausedJobs) && (
        <IconButton
          icon="stop-circle"
          size={24}
          onPress={onCancelAll}
          iconColor={theme.colors.error}
        />
      )}
      {stats.failed > 0 && (
        <IconButton
          icon="refresh"
          size={24}
          onPress={onRetryAllFailed}
          iconColor={theme.colors.primary}
        />
      )}
      <IconButton
        icon="tune"
        size={24}
        onPress={onOpenSettings}
        iconColor={theme.colors.onSurfaceVariant}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  headerActions: {
    flexDirection: "row",
    alignItems: "center",
    marginRight: -8,
  },
});
